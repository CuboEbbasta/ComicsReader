package com.cubo.lumetto.ai

import android.graphics.Bitmap
import android.text.Html
import android.util.Base64
import com.cubo.lumetto.archive.EpubParser
import com.cubo.lumetto.archive.ZipArchive
import com.cubo.lumetto.data.AppSettings
import com.cubo.lumetto.data.AppRouter
import com.cubo.lumetto.data.HighlightStore
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicFolder
import com.cubo.lumetto.model.ComicFormat
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.EpubFont
import com.cubo.lumetto.model.HighlightColor
import com.cubo.lumetto.model.LibraryBackground
import com.cubo.lumetto.model.PageLayoutMode
import com.cubo.lumetto.model.PageTransitionStyle
import com.cubo.lumetto.model.ReadingDirection
import com.cubo.lumetto.model.ReadingProgress
import com.cubo.lumetto.model.readingDuration
import com.cubo.lumetto.ui.reader.PageSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Gli strumenti che Lumetto offre alle AI. Le pagine si contano da 0. I percorsi sono quelli relativi
 * alla cartella principale della libreria (gli stessi usati per progressi e sincronizzazione).
 * Rispetta Incognito: i fumetti nascosti non sono raggiungibili finché Incognito è spento.
 */
object AiTools : ToolHost {
    const val INSTRUCTIONS =
        "Lumetto è un lettore di fumetti, manga ed EPUB. Usa library_overview per iniziare, poi list_folder o search per trovare " +
            "i file; i percorsi sono relativi alla cartella principale. Le pagine si contano da 0. Per vedere una pagina usa " +
            "get_page_image, per leggere un EPUB epub_chapters ed epub_text. Gli strumenti che modificano dati o aprono schermate " +
            "funzionano solo se l'utente ha attivato «Consenti modifiche» nelle impostazioni."

    private const val MAX_TEXT = 30_000

    // ----------------------------------------------------------------------------------------
    // Costruzione degli schemi

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)

    private fun obj(props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
    }

    private val pathProp = str("Percorso relativo del file, come restituito da list_folder o search")

    override val specs: List<ToolSpec> = listOf(
        ToolSpec("library_overview", "Panoramica della libreria: quanti file, cartelle di primo livello, stato della scansione.", obj(), true),
        ToolSpec("list_folder", "Elenca sottocartelle e file di una cartella (path vuoto = cartella principale).",
            obj(mapOf("path" to str("Percorso relativo della cartella; vuoto per la radice"))), true),
        ToolSpec("search", "Cerca in tutta la libreria per nome file, titolo, serie, autori.",
            obj(mapOf("query" to str("Parole da cercare")), listOf("query")), true),
        ToolSpec("get_comic", "Dettagli di un file: formato, dimensione, metadati, progresso di lettura, segnalibri.",
            obj(mapOf("path" to pathProp), listOf("path")), true),
        ToolSpec("continue_reading", "I fumetti iniziati e non finiti, dal più recente.", obj(), true),
        ToolSpec("reading_stats", "Statistiche di lettura: completati, tempo totale, pagine lette.", obj(), true),
        ToolSpec("get_page_image", "Restituisce una pagina come immagine JPEG (CBZ, CBR, PDF, EPUB a layout fisso).",
            obj(mapOf("path" to pathProp, "page" to int("Numero pagina, da 0"), "max_pixel" to int("Lato lungo massimo in pixel (400-1600, default 1000)")), listOf("path", "page")), true),
        ToolSpec("epub_chapters", "Elenco dei capitoli (spine) di un EPUB.", obj(mapOf("path" to pathProp), listOf("path")), true),
        ToolSpec("epub_text", "Testo di un capitolo di un EPUB (massimo 30000 caratteri per chiamata).",
            obj(mapOf("path" to pathProp, "chapter" to int("Indice del capitolo, da 0"), "offset" to int("Da quale carattere iniziare (default 0)")), listOf("path", "chapter")), true),
        ToolSpec("list_bookmarks", "Segnalibri di un file (o di tutta la libreria se path è omesso).",
            obj(mapOf("path" to pathProp)), true),
        ToolSpec("list_highlights", "Evidenziazioni di un EPUB.", obj(mapOf("path" to pathProp), listOf("path")), true),
        ToolSpec("get_settings", "Impostazioni di lettura e libreria.", obj(), true),

        ToolSpec("set_metadata", "Modifica i metadati di un file (si sincronizzano con l'iPad). Passa solo i campi da cambiare; i campi vuoti non cancellano.",
            obj(mapOf(
                "path" to pathProp, "title" to str("Titolo"), "series" to str("Serie"), "number" to str("Numero"), "volume" to str("Volume"),
                "writer" to str("Autore"), "penciller" to str("Disegnatore"), "summary" to str("Trama"),
                "year" to int("Anno"), "language" to str("Lingua ISO, es. it"), "manga" to bool("È un manga (lettura da destra)"),
            ), listOf("path")), false),
        ToolSpec("clear_metadata_edit", "Annulla le modifiche manuali ai metadati di un file e torna a quelli del file.",
            obj(mapOf("path" to pathProp), listOf("path")), false),
        ToolSpec("set_progress", "Imposta la pagina corrente di un file (da 0).",
            obj(mapOf("path" to pathProp, "page" to int("Pagina, da 0")), listOf("path", "page")), false),
        ToolSpec("set_completed", "Segna un file come letto o da leggere.",
            obj(mapOf("path" to pathProp, "completed" to bool("true = letto")), listOf("path", "completed")), false),
        ToolSpec("reset_progress", "Cancella progresso e segnalibri di un file.",
            obj(mapOf("path" to pathProp), listOf("path")), false),
        ToolSpec("toggle_bookmark", "Aggiunge o toglie il segnalibro su una pagina.",
            obj(mapOf("path" to pathProp, "page" to int("Pagina, da 0")), listOf("path", "page")), false),
        ToolSpec("remove_highlight", "Elimina un'evidenziazione per id.", obj(mapOf("id" to str("Id dell'evidenziazione")), listOf("id")), false),
        ToolSpec("recolor_highlight", "Cambia il colore di un'evidenziazione (yellow, green, blue, pink).",
            obj(mapOf("id" to str("Id dell'evidenziazione"), "color" to str("yellow | green | blue | pink")), listOf("id", "color")), false),
        ToolSpec("set_setting", "Cambia un'impostazione: pageLayout (single|double), transition (slide|pageCurl|vertical), " +
            "readingDirection (auto|leftToRight|rightToLeft), libraryBackground (black|anthracite|midnight|ember), " +
            "epubFont (original|serif|sans), epubFontScale (0.6-2.0), maxFolderDepth (1-20).",
            obj(mapOf("key" to str("Nome dell'impostazione"), "value" to str("Nuovo valore")), listOf("key", "value")), false),
        ToolSpec("rescan_library", "Riesegue la scansione della cartella principale.", obj(), false),
        ToolSpec("open_comic", "Apre un file nel lettore, sullo schermo del telefono.", obj(mapOf("path" to pathProp), listOf("path")), false),
    )

    // ----------------------------------------------------------------------------------------

    override fun call(name: String, args: JSONObject): ToolResult {
        val spec = specs.firstOrNull { it.name == name } ?: return ToolResult.error("Strumento sconosciuto: $name")
        if (!spec.readOnly && !AiAccess.allowWrites) {
            return ToolResult.error("Modifiche disattivate: l'utente deve attivare «Consenti modifiche» in Impostazioni → Accesso AI.")
        }
        return when (name) {
            "library_overview" -> overview()
            "list_folder" -> listFolder(args.optString("path", ""))
            "search" -> search(args.optString("query", ""))
            "get_comic" -> withComic(args) { ToolResult.json(comicDetail(it)) }
            "continue_reading" -> continueReading()
            "reading_stats" -> stats()
            "get_page_image" -> withComic(args) { pageImage(it, args.optInt("page", -1), args.optInt("max_pixel", 1000)) }
            "epub_chapters" -> withComic(args) { epubChapters(it) }
            "epub_text" -> withComic(args) { epubText(it, args.optInt("chapter", -1), args.optInt("offset", 0)) }
            "list_bookmarks" -> bookmarks(args.optString("path", ""))
            "list_highlights" -> withComic(args) { highlights(it) }
            "get_settings" -> settings()
            "set_metadata" -> withComic(args) { setMetadata(it, args) }
            "clear_metadata_edit" -> withComic(args) { c -> mainThread { MetadataStore.clearEdit(c) }; ToolResult.text("Modifiche ai metadati annullate.") }
            "set_progress" -> withComic(args) { setProgress(it, args.optInt("page", -1)) }
            "set_completed" -> withComic(args) { c ->
                val done = args.optBoolean("completed", true)
                mainThread { ProgressStore.setCompleted(done, c.relativePath, totalPagesOf(c)) }
                ToolResult.text(if (done) "Segnato come letto." else "Segnato come da leggere.")
            }
            "reset_progress" -> withComic(args) { c -> mainThread { ProgressStore.resetProgress(c.relativePath) }; ToolResult.text("Progresso cancellato.") }
            "toggle_bookmark" -> withComic(args) { toggleBookmark(it, args.optInt("page", -1)) }
            "remove_highlight" -> highlightAction(args.optString("id", "")) { id -> HighlightStore.remove(id); "Evidenziazione eliminata." }
            "recolor_highlight" -> {
                val color = HighlightColor.from(args.optString("color", ""))
                if (color == null) ToolResult.error("Colore non valido (yellow, green, blue, pink).")
                else highlightAction(args.optString("id", "")) { id -> HighlightStore.recolor(id, color); "Colore cambiato." }
            }
            "set_setting" -> setSetting(args.optString("key", ""), args.optString("value", ""))
            "rescan_library" -> { mainThread { LibraryStore.refresh() }; ToolResult.text("Scansione avviata.") }
            "open_comic" -> withComic(args) { c -> mainThread { AppRouter.open(c) }; ToolResult.text("Aperto: ${LibraryStore.title(c)}") }
            else -> ToolResult.error("Strumento non implementato: $name")
        }
    }

    // ----------------------------------------------------------------------------------------
    // Utilità

    /** Le scritture sugli store passano dal thread principale, come quelle dell'interfaccia. */
    private fun <T> mainThread(block: () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private fun withComic(args: JSONObject, block: (ComicFile) -> ToolResult): ToolResult {
        val path = args.optString("path", "")
        if (path.isEmpty()) return ToolResult.error("Manca «path».")
        val comic = LibraryStore.comic(path) ?: return ToolResult.error("File non trovato nella libreria: $path")
        return block(comic)
    }

    private fun totalPagesOf(c: ComicFile): Int =
        ProgressStore.progress(c.relativePath)?.totalPages?.takeIf { it > 0 }
            ?: MetadataStore.metadata(c).pageCount?.takeIf { it > 0 }
            ?: runCatching { PageSourceFactory.open(c).use { it.pageCount } }.getOrDefault(0)

    private fun metadataJson(m: ComicMetadata): JSONObject {
        val o = JSONObject()
        m.title?.let { o.put("title", it) }
        m.series?.let { o.put("series", it) }
        m.number?.let { o.put("number", it) }
        m.volume?.let { o.put("volume", it) }
        m.writer?.let { o.put("writer", it) }
        m.penciller?.let { o.put("penciller", it) }
        m.summary?.let { o.put("summary", it) }
        m.year?.let { o.put("year", it) }
        m.pageCount?.let { o.put("pageCount", it) }
        m.languageISO?.let { o.put("language", it) }
        m.manga?.let { o.put("manga", it) }
        return o
    }

    private fun progressJson(p: ReadingProgress): JSONObject = JSONObject()
        .put("currentPage", p.currentPage).put("totalPages", p.totalPages)
        .put("completed", p.isCompleted).put("fraction", p.fraction)
        .put("readingSeconds", p.totalReadingTime).put("sessions", p.sessionCount)
        .also { o ->
            p.lastReadAt?.let { o.put("lastReadAtMs", it) }
            p.finishedAt?.let { o.put("finishedAtMs", it) }
        }

    private fun comicSummary(c: ComicFile): JSONObject {
        val o = JSONObject().put("path", c.relativePath).put("title", LibraryStore.title(c))
            .put("subtitle", LibraryStore.subtitle(c)).put("format", c.format.raw)
        ProgressStore.progress(c.relativePath)?.let { o.put("progress", progressJson(it)) }
        return o
    }

    private fun comicDetail(c: ComicFile): JSONObject {
        val o = comicSummary(c)
            .put("fileName", c.fileName).put("folder", c.folderPath).put("sizeBytes", c.fileSizeBytes)
            .put("modifiedMs", c.modifiedMs).put("metadata", metadataJson(MetadataStore.metadata(c)))
        o.put("bookmarkedPages", JSONArray(ProgressStore.bookmarks(c.relativePath).map { it.pageIndex }))
        if (c.format == ComicFormat.EPUB) o.put("highlightCount", HighlightStore.highlights(c.relativePath).size)
        return o
    }

    // ----------------------------------------------------------------------------------------
    // Lettura

    private fun overview(): ToolResult {
        val root = LibraryStore.root
        val o = JSONObject().put("scanning", LibraryStore.isScanning).put("readingMetadata", LibraryStore.isReadingMetadata)
        if (root == null) {
            o.put("ready", false)
            LibraryStore.errorMessage?.let { o.put("error", it) }
        } else {
            o.put("ready", true).put("totalFiles", root.totalComicCount)
            o.put("topFolders", JSONArray(root.subfolders.map { folderJson(it) }))
            o.put("filesInRoot", root.comics.size)
        }
        return ToolResult.json(o)
    }

    private fun folderJson(f: ComicFolder) = JSONObject().put("path", f.relativePath).put("name", f.name).put("fileCount", f.totalComicCount)

    private fun listFolder(path: String): ToolResult {
        val root = LibraryStore.root ?: return ToolResult.error("Libreria non ancora pronta.")
        val folder = if (path.isEmpty()) root else root.find(path) ?: return ToolResult.error("Cartella non trovata: $path")
        val o = JSONObject().put("path", folder.relativePath)
        o.put("folders", JSONArray(folder.subfolders.map { folderJson(it) }))
        val shown = folder.comics.take(500)
        o.put("files", JSONArray(shown.map { comicSummary(it) }))
        if (folder.comics.size > shown.size) o.put("truncated", true)
        return ToolResult.json(o)
    }

    private fun search(query: String): ToolResult {
        if (query.isBlank()) return ToolResult.error("Manca «query».")
        val r = LibraryStore.search(query)
        return ToolResult.json(
            JSONObject().put("folders", JSONArray(r.folders.map { folderJson(it) }))
                .put("files", JSONArray(r.comics.map { comicSummary(it) }))
        )
    }

    private fun continueReading(): ToolResult =
        ToolResult.json(JSONArray(LibraryStore.continueReading(ProgressStore.progress).map { (c, _) -> comicSummary(c) }))

    private fun stats(): ToolResult = ToolResult.json(
        JSONObject().put("completedCount", ProgressStore.completedCount)
            .put("totalReadingSeconds", ProgressStore.totalReadingTime)
            .put("totalReadingText", readingDuration(ProgressStore.totalReadingTime))
            .put("pagesRead", ProgressStore.totalPagesRead)
            .put("sessionCount", ProgressStore.sessions.size)
    )

    private fun pageImage(c: ComicFile, page: Int, maxPixel: Int): ToolResult {
        if (c.format == ComicFormat.EPUB && runCatching { EpubParser.isFixedLayout(c.uri) }.getOrDefault(false).not()) {
            return ToolResult.error("Questo EPUB è a testo scorrevole: usa epub_chapters ed epub_text.")
        }
        return PageSourceFactory.open(c).use { src ->
            if (page < 0 || page >= src.pageCount) return@use ToolResult.error("Pagina non valida (0-${src.pageCount - 1}).")
            val bmp = src.image(page, maxPixel.coerceIn(400, 1600))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            ToolResult.withImage("Pagina $page di ${src.pageCount} — ${LibraryStore.title(c)}", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
        }
    }

    private fun epubChapters(c: ComicFile): ToolResult {
        if (c.format != ComicFormat.EPUB) return ToolResult.error("Non è un EPUB.")
        return ZipArchive.open(c.uri).use { archive ->
            val pkg = EpubParser.packageOf(archive)
            val titles = runCatching { EpubParser.chapterTitles(pkg, archive) }.getOrDefault(emptyMap())
            val arr = JSONArray()
            pkg.spinePaths.forEachIndexed { i, p ->
                arr.put(JSONObject().put("index", i).put("file", p).also { o -> titles[p]?.let { o.put("title", it) } })
            }
            ToolResult.json(JSONObject().put("chapters", arr))
        }
    }

    private fun epubText(c: ComicFile, chapter: Int, offset: Int): ToolResult {
        if (c.format != ComicFormat.EPUB) return ToolResult.error("Non è un EPUB.")
        return ZipArchive.open(c.uri).use { archive ->
            val pkg = EpubParser.packageOf(archive)
            val path = pkg.spinePaths.getOrNull(chapter) ?: return@use ToolResult.error("Capitolo non valido (0-${pkg.spinePaths.size - 1}).")
            val html = String(archive.data(path), Charsets.UTF_8)
            val text = htmlToText(html)
            val from = offset.coerceIn(0, text.length)
            val to = minOf(text.length, from + MAX_TEXT)
            ToolResult.json(
                JSONObject().put("chapter", chapter).put("totalChars", text.length).put("offset", from)
                    .put("hasMore", to < text.length).put("text", text.substring(from, to))
            )
        }
    }

    internal fun htmlToText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|br)\\s*>|<br\\s*/?>"), "\n")
        @Suppress("DEPRECATION")
        val plain = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
        return plain.replace(' ', ' ').replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
    }

    private fun bookmarks(path: String): ToolResult {
        val list = if (path.isEmpty()) ProgressStore.bookmarks else ProgressStore.bookmarks(path)
        return ToolResult.json(JSONArray(list.mapNotNull { b ->
            LibraryStore.comic(b.relativePath)?.let { JSONObject().put("path", b.relativePath).put("page", b.pageIndex).put("createdAtMs", b.createdAt) }
        }))
    }

    private fun highlights(c: ComicFile): ToolResult = ToolResult.json(JSONArray(HighlightStore.highlights(c.relativePath).map { h ->
        JSONObject().put("id", h.id).put("chapter", h.chapter).put("text", h.text).put("color", h.color.raw)
    }))

    private fun settings(): ToolResult = ToolResult.json(
        JSONObject().put("pageLayout", AppSettings.pageLayout.raw).put("transition", AppSettings.transition.raw)
            .put("readingDirection", AppSettings.readingDirection.raw).put("libraryBackground", AppSettings.libraryBackground.raw)
            .put("epubFont", AppSettings.epubFont.raw).put("epubFontScale", AppSettings.epubFontScale)
            .put("maxFolderDepth", AppSettings.maxFolderDepth)
    )

    // ----------------------------------------------------------------------------------------
    // Scrittura

    private fun setMetadata(c: ComicFile, a: JSONObject): ToolResult {
        val base = MetadataStore.edits[c.relativePath] ?: ComicMetadata()
        fun s(key: String, old: String?) = if (a.has(key) && !a.isNull(key)) a.optString(key) else old
        val updated = base.copy(
            title = s("title", base.title), series = s("series", base.series), number = s("number", base.number),
            volume = s("volume", base.volume), writer = s("writer", base.writer), penciller = s("penciller", base.penciller),
            summary = s("summary", base.summary), languageISO = s("language", base.languageISO),
            year = if (a.has("year") && !a.isNull("year")) a.optInt("year") else base.year,
            manga = if (a.has("manga") && !a.isNull("manga")) a.optBoolean("manga") else base.manga,
        )
        mainThread { MetadataStore.saveEdit(updated, c) }
        return ToolResult.json(metadataJson(MetadataStore.metadata(c)))
    }

    private fun setProgress(c: ComicFile, page: Int): ToolResult {
        val total = totalPagesOf(c)
        if (total <= 0) return ToolResult.error("Impossibile leggere il numero di pagine.")
        if (page < 0 || page >= total) return ToolResult.error("Pagina non valida (0-${total - 1}).")
        mainThread { ProgressStore.updatePage(c.relativePath, page, total) }
        return ToolResult.text("Pagina corrente: $page di $total.")
    }

    private fun toggleBookmark(c: ComicFile, page: Int): ToolResult {
        if (page < 0) return ToolResult.error("Pagina non valida.")
        mainThread { ProgressStore.toggleBookmark(c.relativePath, page) }
        return ToolResult.text(if (ProgressStore.isBookmarked(c.relativePath, page)) "Segnalibro aggiunto." else "Segnalibro tolto.")
    }

    private fun highlightAction(id: String, action: (String) -> String): ToolResult {
        if (HighlightStore.all.none { it.id == id }) return ToolResult.error("Evidenziazione non trovata.")
        return ToolResult.text(mainThread { action(id) })
    }

    private fun setSetting(key: String, value: String): ToolResult {
        fun bad(hint: String) = ToolResult.error("Valore non valido per $key: $hint")
        return mainThread {
            when (key) {
                "pageLayout" -> PageLayoutMode.from(value)?.let { AppSettings.pageLayout = it } ?: return@mainThread bad("single, double")
                "transition" -> PageTransitionStyle.from(value)?.let { AppSettings.transition = it } ?: return@mainThread bad("slide, pageCurl, vertical")
                "readingDirection" -> ReadingDirection.from(value)?.let { AppSettings.readingDirection = it } ?: return@mainThread bad("auto, leftToRight, rightToLeft")
                "libraryBackground" -> LibraryBackground.from(value)?.takeIf { it != LibraryBackground.CUSTOM }?.let { AppSettings.libraryBackground = it }
                    ?: return@mainThread bad("black, anthracite, midnight, ember")
                "epubFont" -> EpubFont.from(value)?.let { AppSettings.epubFont = it } ?: return@mainThread bad("original, serif, sans")
                "epubFontScale" -> value.toDoubleOrNull()?.takeIf { it in 0.6..2.0 }?.let { AppSettings.epubFontScale = it } ?: return@mainThread bad("0.6-2.0")
                "maxFolderDepth" -> value.toIntOrNull()?.takeIf { it in 1..20 }?.let { AppSettings.maxFolderDepth = it } ?: return@mainThread bad("1-20")
                else -> return@mainThread ToolResult.error("Impostazione sconosciuta: $key")
            }
            ToolResult.text("Impostato $key = $value")
        }
    }
}
