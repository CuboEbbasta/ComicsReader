package com.cubo.lumetto.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.archive.ComicParsing
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicFolder
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.NaturalOrder
import com.cubo.lumetto.model.ReadingProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Ambito delle attività in background dell'app. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Dispatcher limitato per le estrazioni pesanti (copertine, metadati). */
@Suppress("OPT_IN_USAGE")
val ioLimited = Dispatchers.IO.limitedParallelism(3)

/** Copertine: cache in memoria + su disco (JPEG già ridotti); la chiave include data e dimensione. */
object CoverCache {
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashMap<String, CompletableDeferred<Bitmap?>>()
    private val directory: File by lazy { File(AppContext.context.cacheDir, "covers.v1").apply { mkdirs() } }

    private fun key(c: ComicFile) = "${fnv1a(c.relativePath)}_${c.modificationStamp}_${c.fileSizeBytes}.jpg"

    fun cached(c: ComicFile): Bitmap? = memory.get(key(c))

    suspend fun cover(c: ComicFile): Bitmap? {
        val k = key(c)
        memory.get(k)?.let { return it }
        val (deferred, owner) = synchronized(inFlight) {
            val existing = inFlight[k]
            if (existing != null) existing to false else CompletableDeferred<Bitmap?>().also { inFlight[k] = it } to true
        }
        if (!owner) return deferred.await()
        val image = withContext(ioLimited) {
            val f = File(directory, k)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return@withContext it }
            val bmp = ComicParsing.cover(c) ?: return@withContext null
            runCatching { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
            bmp
        }
        if (image != null) memory.put(k, image)
        synchronized(inFlight) { inFlight.remove(k) }
        deferred.complete(image)
        return image
    }

    fun clear() {
        memory.evictAll()
        directory.listFiles()?.forEach { it.delete() }
    }
}

/** Albero della libreria (completo e filtrato per Incognito), indice, ricerca, metadati in background. */
object LibraryStore {
    var root by mutableStateOf<ComicFolder?>(null)
        private set
    var isScanning by mutableStateOf(false)
        private set
    var isReadingMetadata by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
    /** Cambia a ogni nuova scansione (per ridisegnare). */
    var version by mutableStateOf(0)
        private set

    private var fullRoot: ComicFolder? = null
    private var index: Map<String, ComicFile> = emptyMap()
    private var fullIndex: Map<String, ComicFile> = emptyMap()
    private var metadataJob: Job? = null
    private var lastHiddenKey: String? = null

    fun refresh() {
        val uri = FileAccess.rootUri ?: return
        if (isScanning) return
        isScanning = true
        errorMessage = null
        appScope.launch {
            try {
                val tree = withContext(Dispatchers.IO) { LibraryScanner.scan(uri, AppSettings.maxFolderDepth) }
                fullRoot = tree
                rebuildVisible()
                loadMissingMetadata()
            } catch (e: Exception) {
                errorMessage = "Scansione non riuscita: ${e.message ?: "errore sconosciuto"}"
            }
            isScanning = false
        }
    }

    fun reset() {
        metadataJob?.cancel()
        fullRoot = null
        root = null
        index = emptyMap()
        fullIndex = emptyMap()
    }

    /** Da chiamare quando cambiano gli elementi nascosti o lo stato di Incognito. */
    fun incognitoChanged() {
        val key = "${IncognitoStore.isActive}|${IncognitoStore.hiddenPaths.sorted().joinToString("\u0000")}"
        if (key == lastHiddenKey) return
        rebuildVisible()
    }

    private fun rebuildVisible() {
        val full = fullRoot ?: run { root = null; return }
        fullIndex = full.allComicsRecursive().associateBy { it.relativePath }
        val hidden = IncognitoStore.hiddenPaths
        lastHiddenKey = "${IncognitoStore.isActive}|${hidden.sorted().joinToString("\u0000")}"
        val visible = if (IncognitoStore.isActive || hidden.isEmpty()) full else (full.pruned(hidden) ?: full)
        index = visible.allComicsRecursive().associateBy { it.relativePath }
        root = visible
        version++
    }

    fun comic(path: String): ComicFile? = index[path]

    /** Tutti i fumetti, anche quelli nascosti in Incognito (per la sincronizzazione tra dispositivi). */
    fun allComicsIncludingHidden(): Collection<ComicFile> = fullIndex.values

    fun comicIncludingHidden(path: String): ComicFile? = fullIndex[path]

    /** Il fumetto che segue nella stessa cartella. */
    fun nextComic(after: ComicFile): ComicFile? {
        val folder = root?.find(after.folderPath) ?: return null
        val i = folder.comics.indexOfFirst { it.id == after.id }
        return if (i >= 0) folder.comics.getOrNull(i + 1) else null
    }

    private fun loadMissingMetadata() {
        metadataJob?.cancel()
        val pending = (fullRoot?.allComicsRecursive() ?: emptyList()).filter { MetadataStore.needsParsing(it) }
        if (pending.isEmpty()) return
        isReadingMetadata = true
        metadataJob = appScope.launch {
            val batch = ArrayList<Pair<ComicFile, ComicMetadata>>()
            for (comic in pending) {
                if (!isActive) break
                val m = withContext(ioLimited) { ComicParsing.metadata(comic) }
                batch.add(comic to m)
                if (batch.size >= 25) {
                    MetadataStore.store(batch.toList())
                    batch.clear()
                }
            }
            MetadataStore.store(batch.toList())
            isReadingMetadata = false
        }
    }

    fun title(c: ComicFile): String = MetadataStore.metadata(c).title ?: c.fileTitle

    fun subtitle(c: ComicFile): String {
        val m = MetadataStore.metadata(c)
        m.volume?.takeIf { it.isNotEmpty() }?.let { return "Volume $it" }
        m.number?.takeIf { it.isNotEmpty() }?.let { return "Numero $it" }
        return c.format.displayName
    }

    /** Iniziati e non finiti, dal più recente (massimo 15). */
    fun continueReading(progress: Map<String, ReadingProgress>): List<Pair<ComicFile, ReadingProgress>> =
        progress.values
            .filter { !it.isCompleted && it.lastReadAt != null }
            .sortedByDescending { it.lastReadAt ?: 0.0 }
            .mapNotNull { p -> index[p.relativePath]?.let { it to p } }
            .take(15)

    class SearchResults(val folders: List<ComicFolder>, val comics: List<ComicFile>) {
        val isEmpty get() = folders.isEmpty() && comics.isEmpty()
    }

    /** Ricerca su tutta la libreria: nome del file, titolo, serie, autori. */
    fun search(query: String): SearchResults {
        val terms = query.lowercase().split(' ').filter { it.isNotEmpty() }
        val r = root
        if (terms.isEmpty() || r == null) return SearchResults(emptyList(), emptyList())
        fun matches(text: String): Boolean {
            val t = text.lowercase()
            return terms.all { t.contains(it) }
        }
        val folders = r.allFoldersRecursive().filter { matches(it.name) }.take(30)
        val comics = index.values.filter { c ->
            val m = MetadataStore.metadata(c)
            matches(listOfNotNull(c.fileName, m.title, m.series, m.writer, m.penciller, m.number?.let { "#$it" }).joinToString(" "))
        }.sortedWith { a, b -> NaturalOrder.compare(a.relativePath, b.relativePath) }.take(200)
        return SearchResults(folders, comics)
    }
}

/** Presentazioni a schermo intero e fogli. */
object AppRouter {
    var openedComic by mutableStateOf<ComicFile?>(null)
    var showSearch by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var editingComic by mutableStateOf<ComicFile?>(null)

    fun open(c: ComicFile) {
        openedComic = c
    }
}
