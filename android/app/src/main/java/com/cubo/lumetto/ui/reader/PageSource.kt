package com.cubo.lumetto.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.cubo.lumetto.archive.ArchiveException
import com.cubo.lumetto.archive.ArchiveOpener
import com.cubo.lumetto.archive.CbzParser
import com.cubo.lumetto.archive.ComicArchive
import com.cubo.lumetto.archive.EpubParser
import com.cubo.lumetto.archive.ImageDecoder
import com.cubo.lumetto.archive.PdfParser
import com.cubo.lumetto.archive.StoredRarArchive
import com.cubo.lumetto.archive.ZipArchive
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Un fumetto aperto, indipendente dal formato: quante pagine ha e come ottenerne le immagini. */
interface PageSource : Closeable {
    val pageCount: Int
    fun image(index: Int, maxPixel: Int): Bitmap
    /** true se la pagina è più larga che alta (doppia pagina già unita nella scansione). */
    fun isWide(index: Int): Boolean
}

/** CBZ, CBR ed EPUB a layout fisso: immagini dentro un archivio. */
class ArchivePageSource(private val archive: ComicArchive, private val paths: List<String>) : PageSource {
    private val wide = HashMap<Int, Boolean>()

    init {
        if (paths.isEmpty()) throw ArchiveException("Il file non contiene pagine leggibili.")
    }

    override val pageCount: Int get() = paths.size

    override fun image(index: Int, maxPixel: Int): Bitmap {
        val path = paths.getOrNull(index) ?: throw ArchiveException("Pagina non valida.")
        val data = archive.data(path)
        ImageDecoder.size(data)?.let { (w, h) -> synchronized(wide) { wide[index] = w > h * 1.15 } }
        return ImageDecoder.decode(data, maxPixel) ?: throw ArchiveException("Impossibile decodificare la pagina.")
    }

    override fun isWide(index: Int): Boolean {
        synchronized(wide) { wide[index] }?.let { return it }
        val path = paths.getOrNull(index) ?: return false
        val size = runCatching { ImageDecoder.size(archive.data(path)) }.getOrNull() ?: return false
        val result = size.first > size.second * 1.15
        synchronized(wide) { wide[index] = result }
        return result
    }

    override fun close() = archive.close()
}

/** PDF con PdfRenderer; un solo disegno alla volta sullo stesso documento. */
class PdfPageSource(private val pfd: ParcelFileDescriptor) : PageSource {
    private val renderer = PdfRenderer(pfd)

    init {
        if (renderer.pageCount == 0) throw ArchiveException("Il file non contiene pagine leggibili.")
    }

    override val pageCount: Int = renderer.pageCount

    override fun image(index: Int, maxPixel: Int): Bitmap = synchronized(renderer) {
        renderer.openPage(index).use { PdfParser.render(it, maxPixel) }
    }

    override fun isWide(index: Int): Boolean = synchronized(renderer) {
        renderer.openPage(index).use { it.width > it.height * 1.15 }
    }

    override fun close() {
        synchronized(renderer) { runCatching { renderer.close() } }
        runCatching { pfd.close() }
    }
}

object PageSourceFactory {
    fun open(comic: ComicFile): PageSource = when (comic.format) {
        ComicFormat.CBZ, ComicFormat.CBR -> {
            val archive = ArchiveOpener.open(comic.uri)
            try {
                if (archive is StoredRarArchive && !archive.hasStoredFiles) throw ArchiveException(StoredRarArchive.COMPRESSED_MESSAGE)
                ArchivePageSource(archive, CbzParser.pagePaths(archive))
            } catch (e: Exception) {
                archive.close()
                throw e
            }
        }
        ComicFormat.EPUB -> {
            val archive = ZipArchive.open(comic.uri)
            try {
                ArchivePageSource(archive, EpubParser.pageImagePaths(EpubParser.packageOf(archive), archive))
            } catch (e: Exception) {
                archive.close()
                throw e
            }
        }
        ComicFormat.PDF -> PdfPageSource(FileAccess.openFd(comic.uri))
    }
}

/** Carica le pagine fuori dal thread principale, con cache per pagine intere e miniature. */
class PageLoader(val source: PageSource, screenPixel: Int) {
    enum class Kind { FULL, THUMB }

    val fullPixel = screenPixel.coerceIn(1400, 3200)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Suppress("OPT_IN_USAGE")
    private val fullDispatcher = Dispatchers.IO.limitedParallelism(2)
    @Suppress("OPT_IN_USAGE")
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val maxBytes = (Runtime.getRuntime().maxMemory() / 5).toInt()
    private val full = object : LruCache<Int, Bitmap>(maxBytes) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val thumbs = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashMap<String, CompletableDeferred<Bitmap?>>()

    fun cached(index: Int, kind: Kind): Bitmap? = (if (kind == Kind.FULL) full else thumbs).get(index)

    suspend fun load(index: Int, kind: Kind): Bitmap? {
        cached(index, kind)?.let { return it }
        val key = "$kind-$index"
        val existing = synchronized(inFlight) { inFlight[key] }
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<Bitmap?>()
        synchronized(inFlight) { inFlight[key] = deferred }
        val bmp = withContext(if (kind == Kind.FULL) fullDispatcher else thumbDispatcher) {
            runCatching { source.image(index, if (kind == Kind.FULL) fullPixel else 220) }.getOrNull()
        }
        if (bmp != null) (if (kind == Kind.FULL) full else thumbs).put(index, bmp)
        synchronized(inFlight) { inFlight.remove(key) }
        deferred.complete(bmp)
        return bmp
    }

    fun prefetch(indices: List<Int>) {
        for (i in indices) {
            if (i < 0 || i >= source.pageCount || cached(i, Kind.FULL) != null) continue
            scope.launch { load(i, Kind.FULL) }
        }
    }

    fun close() {
        scope.cancel()
        // Il documento si chiude dopo che le letture in corso sono finite.
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(1500)
            runCatching { source.close() }
        }
    }
}
