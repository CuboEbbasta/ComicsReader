package com.cubo.lumetto.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.data.AppSettings
import com.cubo.lumetto.data.ComicPrefs
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.PageLayoutMode
import com.cubo.lumetto.model.PageTransitionStyle
import com.cubo.lumetto.model.ReaderTheme
import com.cubo.lumetto.model.ReadingDirection
import com.cubo.lumetto.model.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stato del lettore a pagine (CBZ, CBR, PDF, EPUB a layout fisso). */
class ReaderViewModel(val comic: ComicFile) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val title: String = LibraryStore.title(comic)
    private val path = comic.relativePath

    var loader by mutableStateOf<PageLoader?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var spreads by mutableStateOf<List<List<Int>>>(emptyList())
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var direction by mutableStateOf(ReadingDirection.LEFT_TO_RIGHT)
        private set

    private var _layout by mutableStateOf(AppSettings.pageLayout)
    var layout: PageLayoutMode
        get() = _layout
        set(v) { if (v != _layout) { _layout = v; AppSettings.pageLayout = v; rebuildSpreads() } }

    private var _mode by mutableStateOf(
        PageTransitionStyle.from(ComicPrefs.value("mode", path))
            ?: if (path.lowercase().contains("webtoon")) PageTransitionStyle.VERTICAL else AppSettings.transition
    )
    var mode: PageTransitionStyle
        get() = _mode
        set(v) { if (v != _mode) { _mode = v; ComicPrefs.set(v.raw, "mode", path); rebuildSpreads() } }

    private var _directionSetting by mutableStateOf(ReadingDirection.from(ComicPrefs.value("direction", path)) ?: ReadingDirection.AUTO)
    var directionSetting: ReadingDirection
        get() = _directionSetting
        set(v) {
            if (v == _directionSetting) return
            _directionSetting = v
            ComicPrefs.set(if (v == ReadingDirection.AUTO) null else v.raw, "direction", path)
            applyDirection()
        }

    private var _theme by mutableStateOf(ReaderTheme.from(ComicPrefs.value("theme", path)) ?: ReaderTheme.BLACK)
    var theme: ReaderTheme
        get() = _theme
        set(v) { _theme = v; ComicPrefs.set(v.raw, "theme", path) }

    var showControls by mutableStateOf(true)
    var showThumbnails by mutableStateOf(false)
    var showModes by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)
    /** Richiesta di girare pagina dai pulsanti (gestita dal pager con l'animazione). */
    var turnRequest by mutableStateOf<Pair<Boolean, Int>?>(null)
        private set
    var handledTurnId = 0

    private var widePages: Set<Int> = emptySet()
    private var wideStarted = false
    private var sessionStart: Double? = null
    private val pagesSeen = HashSet<Int>()
    private var hideJob: Job? = null

    val pageCount: Int get() = loader?.source?.pageCount ?: 0

    fun load(screenPixel: Int) {
        if (loader != null || errorMessage != null) return
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) { PageSourceFactory.open(comic) }
                loader = PageLoader(source, screenPixel)
                applyDirection()
                val saved = ProgressStore.progress(path)
                val start = if (saved?.isCompleted == true) 0 else (saved?.currentPage ?: 0)
                currentPage = start.coerceIn(0, source.pageCount - 1)
                rebuildSpreads()
                startSession()
                loader?.prefetch(listOf(currentPage + 1, currentPage + 2))
                scheduleAutoHide()
            } catch (e: Throwable) {
                errorMessage = e.message ?: "Impossibile aprire il fumetto."
            }
        }
    }

    // --- direzione ---

    private fun applyDirection() {
        var resolved = if (directionSetting == ReadingDirection.AUTO) AppSettings.readingDirection else directionSetting
        if (resolved == ReadingDirection.AUTO) {
            val m = MetadataStore.metadata(comic)
            val manga = m.manga == true || m.languageISO?.lowercase()?.startsWith("ja") == true || path.lowercase().contains("manga")
            resolved = if (manga) ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT
        }
        direction = resolved
    }

    val isRightToLeft: Boolean get() = direction == ReadingDirection.RIGHT_TO_LEFT

    // --- impaginazione ---

    val effectiveLayout: PageLayoutMode get() = if (mode == PageTransitionStyle.VERTICAL) PageLayoutMode.SINGLE else layout

    private fun rebuildSpreads() {
        val count = pageCount
        if (count <= 0) { spreads = emptyList(); return }
        if (effectiveLayout != PageLayoutMode.DOUBLE) {
            spreads = (0 until count).map { listOf(it) }
            return
        }
        if (!wideStarted) {
            wideStarted = true
            detectWidePages()
        }
        // Copertina da sola, poi coppie; tavole larghe e ultima pagina dispari da sole.
        val result = mutableListOf(listOf(0))
        var i = 1
        while (i < count) {
            if (i in widePages || i + 1 >= count || (i + 1) in widePages) {
                result.add(listOf(i)); i += 1
            } else {
                result.add(listOf(i, i + 1)); i += 2
            }
        }
        spreads = result
    }

    private fun detectWidePages() {
        val source = loader?.source ?: return
        scope.launch {
            val wide = withContext(Dispatchers.IO) {
                (0 until source.pageCount).filter { runCatching { source.isWide(it) }.getOrDefault(false) }.toSet()
            }
            if (wide.isNotEmpty()) {
                widePages = wide
                rebuildSpreads()
            }
        }
    }

    val currentSpreadIndex: Int get() = spreads.indexOfFirst { currentPage in it }.coerceAtLeast(0)

    fun visualPages(spread: Int): List<Int> {
        val pages = spreads.getOrNull(spread) ?: return emptyList()
        return if (isRightToLeft) pages.reversed() else pages
    }

    // --- navigazione ---

    fun goToSpread(index: Int) {
        val first = spreads.getOrNull(index)?.firstOrNull() ?: return
        if (first == currentPage && spreads[index].size == 1) return
        setPage(first)
    }

    fun goToPage(page: Int) {
        if (page < 0 || page >= pageCount) return
        setPage(page)
    }

    fun pageScrolled(page: Int) {
        if (page != currentPage && page in 0 until pageCount) setPage(page)
    }

    /** Frecce e tocchi seguono lo schermo: in un manga "sinistra" è la pagina successiva. */
    fun turn(visualRight: Boolean) {
        if (mode == PageTransitionStyle.VERTICAL) {
            goToPage(currentPage + if (visualRight) 1 else -1)
            return
        }
        turnRequest = visualRight to ((turnRequest?.second ?: 0) + 1)
    }

    /** Indice della doppia pagina a cui porta un tocco a destra/sinistra. */
    fun targetSpread(visualRight: Boolean): Int {
        val forward = visualRight != isRightToLeft
        return currentSpreadIndex + if (forward) 1 else -1
    }

    private fun setPage(page: Int) {
        currentPage = page
        if (spreads.lastOrNull()?.contains(page) == true && !showControls) showControls = true
        val spread = spreads.getOrNull(currentSpreadIndex) ?: listOf(page)
        pagesSeen.addAll(spread)
        ProgressStore.updatePage(path, spread.last(), pageCount)
        val after = spreads.getOrNull(currentSpreadIndex + 1) ?: emptyList()
        val before = spreads.getOrNull(currentSpreadIndex - 1) ?: emptyList()
        loader?.prefetch(after + before)
    }

    private fun scheduleAutoHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(2500)
            if (showControls && !showModes && !showThumbnails && !showBookmarks) showControls = false
        }
    }

    val nextComic: ComicFile? by lazy { LibraryStore.nextComic(comic) }

    val isAtEnd: Boolean
        get() {
            val last = spreads.lastOrNull() ?: return false
            return currentSpreadIndex == spreads.size - 1 && currentPage in last
        }

    val pageLabel: String
        get() {
            if (pageCount <= 0) return ""
            val pages = spreads.getOrNull(currentSpreadIndex) ?: listOf(currentPage)
            val shown = if (pages.size > 1) "${pages[0] + 1}–${pages[1] + 1}" else "${currentPage + 1}"
            return "$shown di $pageCount"
        }

    val pageLabelFull: String
        get() {
            val pages = spreads.getOrNull(currentSpreadIndex) ?: listOf(currentPage)
            return (if (pages.size > 1) "Pagine " else "Pagina ") + pageLabel
        }

    val fraction: Double
        get() {
            if (pageCount <= 0) return 0.0
            val last = spreads.getOrNull(currentSpreadIndex)?.lastOrNull() ?: currentPage
            return (last + 1).toDouble() / pageCount
        }

    // --- segnalibri e stato ---

    val isCurrentPageBookmarked: Boolean get() = ProgressStore.isBookmarked(path, currentPage)

    fun toggleBookmark() = ProgressStore.toggleBookmark(path, currentPage)

    val isCompleted: Boolean get() = ProgressStore.progress[path]?.isCompleted == true

    fun toggleCompleted() = ProgressStore.setCompleted(!isCompleted, path, pageCount)

    // --- sessione di lettura (tempo attivo) ---

    fun startSession() {
        if (sessionStart != null || pageCount <= 0) return
        sessionStart = nowMs()
        pagesSeen.clear()
        pagesSeen.addAll(spreads.getOrNull(currentSpreadIndex) ?: listOf(currentPage))
        ProgressStore.beginSession(path, pageCount)
    }

    fun endSession() {
        val start = sessionStart ?: return
        sessionStart = null
        ProgressStore.endSession(path, title, start, (nowMs() - start) / 1000.0, pagesSeen.size)
        ProgressStore.flush()
    }

    fun dispose() {
        endSession()
        scope.cancel()
        loader?.close()
    }
}
