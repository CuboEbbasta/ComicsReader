package com.cubo.lumetto.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Xml
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicFormat
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.NaturalOrder
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.URLDecoder

/** Decodifica le immagini direttamente alla dimensione necessaria (molta meno memoria). */
object ImageDecoder {
    const val COVER_PIXEL = 640

    fun size(data: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return o.outWidth to o.outHeight
    }

    fun decode(data: ByteArray, maxPixel: Int): Bitmap? {
        val (w, h) = size(data) ?: return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxPixel) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, o) ?: return null
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide <= maxPixel * 1.25) return bmp
        val scale = maxPixel.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}

private fun localName(name: String?): String = name?.substringAfterLast(':') ?: ""

private fun newParser(data: ByteArray): XmlPullParser {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(ByteArrayInputStream(data), null)
    return parser
}

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == name) return getAttributeValue(i)
    }
    return null
}

// ---------------------------------------------------------------------------------------------
// CBZ / CBR

object CbzParser {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp")

    fun pagePaths(archive: ComicArchive): List<String> = archive.filePaths
        .filter { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            val name = path.substringAfterLast('/')
            ext in imageExtensions && !path.startsWith("__MACOSX/") && !name.startsWith(".")
        }
        .sortedWith(NaturalOrder)

    fun cover(uri: Uri): Bitmap? = ArchiveOpener.open(uri).use { archive ->
        // Nei CBR la prima pagina può non essere leggibile: si prova con le successive.
        for (path in pagePaths(archive).take(3)) {
            val bmp = runCatching { ImageDecoder.decode(archive.data(path), ImageDecoder.COVER_PIXEL) }.getOrNull()
            if (bmp != null) return@use bmp
        }
        null
    }

    fun metadata(uri: Uri): ComicMetadata = ArchiveOpener.open(uri).use { archive ->
        var m = ComicMetadata()
        val info = archive.filePaths.firstOrNull { it.substringAfterLast('/').lowercase() == "comicinfo.xml" }
        if (info != null) runCatching { m = ComicInfoReader.parse(archive.data(info)) }
        if (m.pageCount == null || m.pageCount == 0) m = m.copy(pageCount = pagePaths(archive).size)
        m
    }
}

/** ComicInfo.xml (standard di ComicRack). */
object ComicInfoReader {
    fun parse(data: ByteArray): ComicMetadata {
        var m = ComicMetadata()
        val p = newParser(data)
        var text = StringBuilder()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> text = StringBuilder()
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val v = text.toString().trim()
                    if (v.isNotEmpty()) {
                        m = when (localName(p.name)) {
                            "Title" -> m.copy(title = v)
                            "Series" -> m.copy(series = v)
                            "Number" -> m.copy(number = v)
                            "Volume" -> m.copy(volume = v)
                            "Writer" -> m.copy(writer = v)
                            "Penciller" -> m.copy(penciller = v)
                            "Summary" -> m.copy(summary = v)
                            "Year" -> m.copy(year = v.toIntOrNull())
                            "PageCount" -> m.copy(pageCount = v.toIntOrNull())
                            "LanguageISO" -> m.copy(languageISO = v)
                            "Manga" -> m.copy(manga = v.lowercase().startsWith("yes"))
                            else -> m
                        }
                    }
                    text = StringBuilder()
                }
                else -> {}
            }
            event = p.next()
        }
        return m
    }
}

// ---------------------------------------------------------------------------------------------
// EPUB

class EpubPackage(
    val opfPath: String,
    val manifest: Map<String, Item>,
    val spine: List<String>,
    val title: String?,
    val creator: String?,
    val language: String?,
    val coverMetaId: String?,
    val fixedLayoutDeclared: Boolean?,
    val pageProgressionRtl: Boolean,
) {
    class Item(val href: String, val mediaType: String, val properties: String?)

    val opfDirectory: String get() = EpubParser.directory(opfPath)

    val spinePaths: List<String> get() = spine.mapNotNull { manifest[it] }.map { EpubParser.resolve(it.href, opfDirectory) }

    val coverPath: String?
        get() {
            manifest.values.firstOrNull { it.properties?.contains("cover-image") == true }?.let { return EpubParser.resolve(it.href, opfDirectory) }
            coverMetaId?.let { id -> manifest[id]?.let { return EpubParser.resolve(it.href, opfDirectory) } }
            return null
        }
}

object EpubParser {
    fun packageOf(archive: ZipArchive): EpubPackage {
        var opfPath: String? = null
        run {
            val p = newParser(archive.data("META-INF/container.xml"))
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && localName(p.name) == "rootfile" && opfPath == null) opfPath = p.attr("full-path")
                e = p.next()
            }
        }
        val opf = opfPath ?: throw ArchiveException("Elemento non trovato nell'archivio.")

        val manifest = HashMap<String, EpubPackage.Item>()
        val spine = ArrayList<String>()
        var title: String? = null
        var creator: String? = null
        var language: String? = null
        var coverMetaId: String? = null
        var fixed: Boolean? = null
        var rtl = false
        val p = newParser(archive.data(opf))
        var text = StringBuilder()
        var metaProperty: String? = null
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    metaProperty = p.attr("property")
                    when (localName(p.name)) {
                        "item" -> {
                            val id = p.attr("id")
                            val href = p.attr("href")
                            if (id != null && href != null) manifest[id] = EpubPackage.Item(href, p.attr("media-type") ?: "", p.attr("properties"))
                        }
                        "itemref" -> p.attr("idref")?.let { spine.add(it) }
                        "spine" -> rtl = p.attr("page-progression-direction") == "rtl"
                        "meta" -> {
                            if (p.attr("name") == "cover") coverMetaId = p.attr("content")
                            if (p.attr("name") == "fixed-layout") fixed = p.attr("content") == "true"
                        }
                        else -> {}
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val v = text.toString().trim()
                    val name = localName(p.name)
                    if (name == "meta" && metaProperty == "rendition:layout") fixed = v == "pre-paginated"
                    if (v.isNotEmpty()) {
                        when (name) {
                            "title" -> if (title == null) title = v
                            "creator" -> if (creator == null) creator = v
                            "language" -> if (language == null) language = v
                            else -> {}
                        }
                    }
                    text = StringBuilder()
                }
                else -> {}
            }
            e = p.next()
        }
        return EpubPackage(opf, manifest, spine, title, creator, language, coverMetaId, fixed, rtl)
    }

    /** EPUB a layout fisso: l'immagine a piena pagina di ogni documento della spine. */
    fun pageImagePaths(pkg: EpubPackage, archive: ZipArchive): List<String> = pkg.spinePaths.mapNotNull { path ->
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) path
        else runCatching { imagePath(path, archive, false) }.getOrNull()
    }

    /** Layout fisso se dichiarato, altrimenti se almeno il 60% dei documenti contiene solo un'immagine. */
    fun isFixedLayout(uri: Uri): Boolean = ZipArchive.open(uri).use { archive ->
        val pkg = packageOf(archive)
        pkg.fixedLayoutDeclared?.let { return@use it }
        val sample = pkg.spinePaths.take(30)
        if (sample.isEmpty()) return@use false
        val withImage = sample.count { runCatching { imagePath(it, archive, true) }.getOrNull() != null }
        withImage.toDouble() / sample.size >= 0.6
    }

    fun cover(uri: Uri): Bitmap? = ZipArchive.open(uri).use { archive ->
        val pkg = packageOf(archive)
        val candidates = listOfNotNull(
            pkg.coverPath,
            runCatching { pageImagePaths(pkg, archive).firstOrNull() }.getOrNull(),
            pkg.manifest.values.firstOrNull { it.mediaType.startsWith("image/") }?.let { resolve(it.href, pkg.opfDirectory) },
        )
        for (path in candidates) {
            val bmp = runCatching { ImageDecoder.decode(archive.data(path), ImageDecoder.COVER_PIXEL) }.getOrNull()
            if (bmp != null) return@use bmp
        }
        null
    }

    fun metadata(uri: Uri): ComicMetadata = ZipArchive.open(uri).use { archive ->
        val pkg = packageOf(archive)
        ComicMetadata(
            title = pkg.title, writer = pkg.creator, languageISO = pkg.language,
            pageCount = pkg.spine.size, manga = if (pkg.pageProgressionRtl) true else null,
        )
    }

    /** Titoli dei capitoli dall'indice (nav.xhtml di EPUB 3 o toc.ncx di EPUB 2), per percorso. */
    fun chapterTitles(pkg: EpubPackage, archive: ZipArchive): Map<String, String> {
        val toc = pkg.manifest.values.firstOrNull { it.properties?.contains("nav") == true }
            ?: pkg.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?: return emptyMap()
        val tocPath = resolve(toc.href, pkg.opfDirectory)
        val data = runCatching { archive.data(tocPath) }.getOrNull() ?: return emptyMap()
        val entries = ArrayList<Pair<String, String>>()
        runCatching {
            val p = newParser(data)
            var linkHref: String? = null
            var linkText = StringBuilder()
            var labelText = StringBuilder()
            var inText = false
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (localName(p.name)) {
                        "a" -> { linkHref = p.attr("href"); linkText = StringBuilder() }
                        "text" -> { inText = true; labelText = StringBuilder() }
                        "content" -> p.attr("src")?.let { entries.add(it to labelText.toString()) }
                        else -> {}
                    }
                    XmlPullParser.TEXT -> {
                        if (linkHref != null) linkText.append(p.text)
                        if (inText) labelText.append(p.text)
                    }
                    XmlPullParser.END_TAG -> when (localName(p.name)) {
                        "a" -> { linkHref?.let { entries.add(it to linkText.toString()) }; linkHref = null }
                        "text" -> inText = false
                        else -> {}
                    }
                    else -> {}
                }
                e = p.next()
            }
        }
        val titles = HashMap<String, String>()
        for ((href, title) in entries) {
            val path = resolve(href, directory(tocPath))
            val clean = title.trim().replace(Regex("\\s+"), " ")
            if (!titles.containsKey(path) && clean.isNotEmpty()) titles[path] = clean
        }
        return titles
    }

    fun directory(path: String): String = path.substringBeforeLast('/', "")

    fun resolve(href: String, directory: String): String {
        var clean = href.substringBefore('#')
        clean = runCatching { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") }.getOrDefault(clean)
        if (clean.startsWith("/")) return clean.drop(1)
        val parts: MutableList<String> = if (directory.isEmpty()) ArrayList<String>() else directory.split('/').toMutableList()
        for (component in clean.split('/')) {
            when (component) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(component)
            }
        }
        return parts.joinToString("/")
    }

    private fun imagePath(path: String, archive: ZipArchive, requireImageOnly: Boolean): String {
        val p = newParser(archive.data(path))
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        var href: String? = null
        var textLength = 0
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> if (href == null) {
                    when (localName(p.name)) {
                        "img" -> href = p.attr("src")
                        "image" -> href = p.attr("xlink:href") ?: p.attr("href")
                        else -> {}
                    }
                }
                XmlPullParser.TEXT -> textLength += p.text.trim().length
                else -> {}
            }
            e = p.next()
        }
        val found = href ?: throw ArchiveException("Nessuna immagine.")
        if (requireImageOnly && textLength > 200) throw ArchiveException("Troppo testo.")
        return resolve(found, directory(path))
    }
}

// ---------------------------------------------------------------------------------------------
// PDF

object PdfParser {
    fun render(page: PdfRenderer.Page, maxPixel: Int): Bitmap {
        val scale = maxPixel.toFloat() / maxOf(page.width, page.height, 1)
        val w = maxOf(1, (page.width * scale).toInt())
        val h = maxOf(1, (page.height * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val m = Matrix().apply { setScale(scale, scale) }
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    fun cover(uri: Uri): Bitmap? = FileAccess.openFd(uri).use { pfd ->
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) null else r.openPage(0).use { render(it, ImageDecoder.COVER_PIXEL) }
        }
    }

    fun metadata(uri: Uri): ComicMetadata = FileAccess.openFd(uri).use { pfd ->
        PdfRenderer(pfd).use { r -> ComicMetadata(pageCount = r.pageCount) }
    }
}

/** Punto unico per copertine e metadati. */
object ComicParsing {
    fun cover(comic: ComicFile): Bitmap? = runCatching {
        when (comic.format) {
            ComicFormat.CBZ, ComicFormat.CBR -> CbzParser.cover(comic.uri)
            ComicFormat.EPUB -> EpubParser.cover(comic.uri)
            ComicFormat.PDF -> PdfParser.cover(comic.uri)
        }
    }.getOrNull()

    fun metadata(comic: ComicFile): ComicMetadata = runCatching {
        when (comic.format) {
            ComicFormat.CBZ, ComicFormat.CBR -> CbzParser.metadata(comic.uri)
            ComicFormat.EPUB -> EpubParser.metadata(comic.uri)
            ComicFormat.PDF -> PdfParser.metadata(comic.uri)
        }
    }.getOrDefault(ComicMetadata.EMPTY)
}
