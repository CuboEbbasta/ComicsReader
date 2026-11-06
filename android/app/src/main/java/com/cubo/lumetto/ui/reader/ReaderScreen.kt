package com.cubo.lumetto.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cubo.lumetto.archive.EpubParser
import com.cubo.lumetto.data.AppRouter
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicFormat
import com.cubo.lumetto.model.PageLayoutMode
import com.cubo.lumetto.model.PageTransitionStyle
import com.cubo.lumetto.model.ReaderTheme
import com.cubo.lumetto.model.ReadingDirection
import com.cubo.lumetto.ui.BarButton
import com.cubo.lumetto.ui.FlatArrowButton
import com.cubo.lumetto.ui.LColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Ingresso del lettore: gli EPUB vengono prima classificati (layout fisso → a pagine,
 * testo scorrevole → lettore di libri), tutto il resto va al lettore a pagine.
 */
@Composable
fun ReaderContainer(comic: ComicFile, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = null) {}) {
        if (comic.format == ComicFormat.EPUB) {
            val reflowable by produceState<Boolean?>(null, comic.id) {
                value = withContext(Dispatchers.IO) { !(runCatching { EpubParser.isFixedLayout(comic.uri) }.getOrDefault(false)) }
            }
            when (reflowable) {
                null -> CircularProgressIndicator(color = LColors.accent, modifier = Modifier.align(Alignment.Center))
                true -> EpubFlowReader(comic, onClose)
                false -> ImageReader(comic, onClose)
            }
        } else {
            ImageReader(comic, onClose)
        }
    }
}

/** Schermo sempre acceso e barre di sistema nascoste insieme ai comandi. */
@Composable
fun ReaderWindowEffects(showControls: Boolean) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            val window = (view.context as? Activity)?.window
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(showControls) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showControls) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** Avvia e ferma la sessione di lettura quando l'app va in primo piano o in background. */
@Composable
fun SessionLifecycle(onStart: () -> Unit, onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_STOP -> onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

// ---------------------------------------------------------------------------------------------
// Lettore a pagine

@Composable
fun ImageReader(comic: ComicFile, onClose: () -> Unit) {
    val vm = remember(comic.id) { ReaderViewModel(comic) }
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenPixel = with(density) { maxOf(config.screenWidthDp, config.screenHeightDp).dp.toPx().toInt() }
    LaunchedEffect(vm) { vm.load(screenPixel) }
    DisposableEffect(vm) { onDispose { vm.dispose() } }
    SessionLifecycle(onStart = { vm.startSession() }, onStop = { vm.endSession() })
    ReaderWindowEffects(vm.showControls)
    BackHandler(onBack = onClose)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(vm.theme.hex))) {
        val compact = maxWidth < 700.dp
        val loader = vm.loader
        val error = vm.errorMessage
        when {
            loader != null && vm.spreads.isNotEmpty() -> {
                if (vm.mode == PageTransitionStyle.VERTICAL) VerticalReader(vm, loader) else PagedReader(vm, loader)
            }
            error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Filled.Warning, null, tint = LColors.accent, modifier = Modifier.size(36.dp))
                Text(error, color = LColors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 440.dp))
                BarButton(onClick = onClose, prominent = true) { Text("Torna alla libreria") }
            }
            else -> CircularProgressIndicator(color = LColors.accent, modifier = Modifier.align(Alignment.Center))
        }

        if (loader != null) BubbleOverlay(vm, loader)

        AnimatedVisibility(vm.showControls && loader != null, enter = fadeIn(), exit = fadeOut()) {
            ReaderControls(vm, compact, onClose)
        }
    }

    if (vm.showModes) ReaderModesSheet(vm)
    if (vm.showBookmarks) BookmarksSheet(vm)
}

/** Pagine affiancate con scorrimento (o "sfoglia"); in un manga il pager va da destra a sinistra. */
@Composable
private fun PagedReader(vm: ReaderViewModel, loader: PageLoader) {
    val rtl = vm.isRightToLeft
    val curl = vm.mode == PageTransitionStyle.PAGE_CURL
    val signature = "${vm.spreads.size}-$rtl"
    androidx.compose.runtime.key(signature) { PagedReaderContent(vm, loader, rtl, curl) }
}

@Composable
private fun PagedReaderContent(vm: ReaderViewModel, loader: PageLoader, rtl: Boolean, curl: Boolean) {
    val pager = rememberPagerState(initialPage = vm.currentSpreadIndex) { vm.spreads.size }

    // Pagina girata a mano → stato.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { vm.goToSpread(it) }
    }
    // Pagina cambiata da fuori (miniature, segnalibri) → pager.
    LaunchedEffect(pager, vm.currentSpreadIndex) {
        val target = vm.currentSpreadIndex
        if (pager.settledPage != target && !pager.isScrollInProgress) {
            if (abs(pager.currentPage - target) == 1) pager.animateScrollToPage(target) else pager.scrollToPage(target)
        }
    }
    // Lettura guidata / zoom sui fumetti: analizza la pagina corrente e la successiva.
    LaunchedEffect(vm.currentPage, vm.readingMode, vm.focusRects.isEmpty()) {
        vm.ensureRects(vm.currentPage)
        vm.ensureRects(vm.currentPage + 1)
    }
    // Frecce e tocchi.
    val request = vm.turnRequest
    LaunchedEffect(request?.second) {
        val r = request ?: return@LaunchedEffect
        if (r.second <= vm.handledTurnId) return@LaunchedEffect
        vm.handledTurnId = r.second
        val target = vm.targetSpread(r.first)
        if (target in 0 until vm.spreads.size) pager.animateScrollToPage(target)
    }

    // Il pager segue la direzione di lettura: con reverseLayout le pagine successive arrivano da sinistra.
    HorizontalPager(
        state = pager,
        reverseLayout = rtl,
        pageSpacing = if (curl) 0.dp else 20.dp,
        beyondViewportPageCount = 1,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(vm.theme.hex))
                .then(
                    if (curl) Modifier
                        // La pagina che se ne va sta sopra a quella che appare sotto.
                        .zIndex(if (index < pager.currentPage + (if (pager.currentPageOffsetFraction > 0f) 1 else 0)) 1f else 0f)
                        .graphicsLayer {
                            // "Sfoglia": la pagina che esce ruota sul dorso e si apre come in un libro,
                            // scoprendo quella successiva che resta ferma sotto.
                            val d = (index - pager.currentPage) - pager.currentPageOffsetFraction
                            val dir = if (rtl) -1f else 1f
                            if (d > -1f && d < 1f) {
                                translationX = -d * size.width * dir
                                if (d < 0f) {
                                    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                                    rotationY = 90f * d * dir
                                    cameraDistance = 18f * density
                                }
                            }
                            alpha = if (d >= 1f || d <= -1f) 0f else 1f
                        }
                    else Modifier
                )
        ) {
            ZoomableSpread(
                loader, vm.visualPages(index),
                onTap = { x, zoomed ->
                    if ((zoomed && !vm.hasFocusSteps) || x in 0.28f..0.72f) vm.showControls = !vm.showControls else vm.turn(x > 0.72f)
                },
                focus = if (index == vm.currentSpreadIndex) vm.currentFocus else null,
            )
        }
    }
}

/** Webtoon: scorrimento verticale continuo, pizzico per allargare la striscia. */
@Composable
private fun VerticalReader(vm: ReaderViewModel, loader: PageLoader) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = vm.currentPage)
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { vm.pageScrolled(it) }
    }
    LaunchedEffect(vm.currentPage) {
        if (list.firstVisibleItemIndex != vm.currentPage && !list.isScrollInProgress) list.scrollToItem(vm.currentPage)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { vm.showControls = !vm.showControls },
                    onDoubleTap = { scale = if (scale > 1.05f) 1f else 1.8f; panX = 0f },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 3f)
                            val maxPan = size.width * (scale - 1f) / 2f
                            panX = (panX + event.calculatePan().x).coerceIn(-maxPan, maxPan)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = panX
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        ) {
            items(vm.pageCount, key = { it }) { index ->
                PageImage(loader, index, PageLoader.Kind.FULL, Modifier.fillMaxWidth(), androidx.compose.ui.layout.ContentScale.FillWidth)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Comandi

@Composable
private fun ReaderControls(vm: ReaderViewModel, compact: Boolean, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Barra superiore
        Row(
            Modifier
                .fillMaxWidth()
                .background(LColors.panel.copy(alpha = 0.92f))
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).heightIn(min = 44.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Torna alla libreria", tint = LColors.textPrimary)
                if (!compact) Text("Libreria", color = LColors.textPrimary, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.title, color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${vm.pageLabelFull} · ${Math.round(vm.fraction * 100)}%", color = LColors.textSecondary, fontSize = 12.sp, maxLines = 1)
            }
            val bookmarked = vm.isCurrentPageBookmarked
            IconButton(onClick = { vm.toggleBookmark() }) {
                Icon(
                    if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    if (bookmarked) "Rimuovi segnalibro" else "Aggiungi segnalibro",
                    tint = if (bookmarked) LColors.accent else LColors.textPrimary,
                )
            }
            IconButton(onClick = { vm.showBookmarks = true }) { Icon(Icons.AutoMirrored.Filled.List, "Segnalibri", tint = LColors.textPrimary) }
            val done = vm.isCompleted
            IconButton(onClick = { vm.toggleCompleted() }) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    if (done) "Segna come non letto" else "Segna come letto",
                    tint = if (done) LColors.accent else LColors.textPrimary,
                )
            }
            IconButton(onClick = { vm.showModes = true }) { Icon(Icons.Filled.Tune, "Modalità di lettura", tint = LColors.textPrimary) }
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier
                .fillMaxWidth()
                .then(if (vm.showThumbnails) Modifier.background(LColors.panel.copy(alpha = 0.95f)) else Modifier)
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = if (vm.showThumbnails) 0.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val next = vm.nextComic
            if (vm.isAtEnd && next != null) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(LColors.panel.copy(alpha = 0.95f))
                        .border(1.dp, LColors.accent.copy(alpha = 0.5f), CircleShape)
                        .clickable { AppRouter.open(next) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = LColors.accent)
                    Column {
                        Text("Hai finito. Prossimo fumetto:", color = LColors.textSecondary, fontSize = 11.sp)
                        Text(LibraryStore.title(next), color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LColors.accent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatArrowButton(onClick = { vm.turn(false) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, if (vm.isRightToLeft) "Pagina successiva" else "Pagina precedente")
                }
                Text(
                    if (vm.showThumbnails) "Nascondi miniature" else "Mostra miniature",
                    color = LColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(LColors.panel.copy(alpha = 0.85f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickable { vm.showThumbnails = !vm.showThumbnails }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
                FlatArrowButton(onClick = { vm.turn(true) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (vm.isRightToLeft) "Pagina precedente" else "Pagina successiva")
                }
            }
            AnimatedVisibility(vm.showThumbnails, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                vm.loader?.let { ThumbnailStrip(vm, it) }
            }
        }
    }
}

/** Cursore e striscia di miniature, centrata sulla pagina corrente. */
@Composable
private fun ThumbnailStrip(vm: ReaderViewModel, loader: PageLoader) {
    val dir = if (vm.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val current = (vm.spreads.getOrNull(vm.currentSpreadIndex) ?: listOf(vm.currentPage)).toSet()
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1", color = LColors.textSecondary, fontSize = 12.sp)
                var drag by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = drag ?: vm.currentPage.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { vm.goToPage(Math.round(it)) }; drag = null },
                    valueRange = 0f..maxOf(1, vm.pageCount - 1).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = LColors.accent, activeTrackColor = LColors.accent, inactiveTrackColor = LColors.track),
                    modifier = Modifier.weight(1f),
                )
                Text("${vm.pageCount}", color = LColors.textSecondary, fontSize = 12.sp)
            }
            val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, vm.currentPage - 3))
            LaunchedEffect(vm.currentPage) { state.animateScrollToItem(maxOf(0, vm.currentPage - 3)) }
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
                items(vm.pageCount, key = { it }) { index ->
                    val selected = index in current
                    Column(
                        Modifier.clickable { vm.goToPage(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier.width(52.dp).height(74.dp).clip(RoundedCornerShape(4.dp))
                                .border(2.dp, if (selected) LColors.accent else Color.Transparent, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PageImage(loader, index, PageLoader.Kind.THUMB, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Crop)
                        }
                        Text("${index + 1}", color = if (selected) LColors.accent else LColors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Modalità di lettura, direzione e tema del singolo fumetto. */
@Composable
private fun ReaderModesSheet(vm: ReaderViewModel) {
    ModalBottomSheet(onDismissRequest = { vm.showModes = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LColors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group("Lettura") {
                Segmented(PageTransitionStyle.entries, vm.mode, { it.displayName }) { vm.mode = it }
            }
            if (vm.mode != PageTransitionStyle.VERTICAL) {
                Group("Zoom") {
                    Segmented(com.cubo.lumetto.model.ReadingMode.entries, vm.readingMode, { it.shortName }) { vm.readingMode = it }
                }
                if (vm.readingMode != com.cubo.lumetto.model.ReadingMode.STANDARD) {
                    Text(
                        if (vm.readingMode == com.cubo.lumetto.model.ReadingMode.GUIDED) "Tocca i bordi per passare da una vignetta all'altra."
                        else "Tocca i bordi per passare da un fumetto all'altro (se non ne trova, usa le vignette).",
                        color = LColors.textSecondary, fontSize = 12.sp,
                    )
                } else {
                    Group("Pagine") {
                        Segmented(PageLayoutMode.entries, vm.layout, { it.shortName }) { vm.layout = it }
                    }
                }
            }
            Group("Direzione di lettura") {
                Segmented(ReadingDirection.entries, vm.directionSetting, { it.shortName }) { vm.directionSetting = it }
            }
            if (vm.directionSetting == ReadingDirection.AUTO) {
                Text(if (vm.isRightToLeft) "Rilevata: destra → sinistra" else "Rilevata: sinistra → destra", color = LColors.textSecondary, fontSize = 12.sp)
            }
            Group("Sfondo (solo questo fumetto)") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderTheme.entries.forEach { t ->
                        Box(
                            Modifier.size(44.dp).clickable { vm.theme = t }.semanticsLabel(t.displayName),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(t.hex))
                                    .border(if (vm.theme == t) 3.dp else 1.dp, if (vm.theme == t) LColors.accent else Color.White.copy(alpha = 0.25f), CircleShape)
                            )
                        }
                    }
                }
            }
            Text("Pizzica o tocca due volte per ingrandire. Tocca i bordi per girare pagina, il centro per i comandi.", color = LColors.textTertiary, fontSize = 12.sp)
        }
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = LColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = LColors.accent.copy(alpha = 0.22f),
                    activeContentColor = LColors.textPrimary,
                    activeBorderColor = LColors.accent,
                    inactiveContainerColor = LColors.surfaceLow,
                    inactiveContentColor = LColors.textSecondary,
                    inactiveBorderColor = LColors.track,
                ),
                icon = {},
            ) { Text(label(o), fontSize = 13.sp, maxLines = 1) }
        }
    }
}

/** Elenco dei segnalibri del fumetto. */
@Composable
private fun BookmarksSheet(vm: ReaderViewModel) {
    ModalBottomSheet(onDismissRequest = { vm.showBookmarks = false }, containerColor = LColors.surface) {
        val marks = ProgressStore.bookmarks(vm.comic.relativePath)
        val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.ITALIAN) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Segnalibri", color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (marks.isEmpty()) {
                Text("Nessun segnalibro. Usa l'icona del segnalibro in alto per aggiungerne uno sulla pagina corrente.", color = LColors.textSecondary)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(marks.size, key = { marks[it].id }) { i ->
                    val b = marks[i]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LColors.card)
                            .clickable { vm.goToPage(b.pageIndex); vm.showBookmarks = false }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        vm.loader?.let { PageImage(it, b.pageIndex, PageLoader.Kind.THUMB, Modifier.width(40.dp).height(58.dp).clip(RoundedCornerShape(4.dp)), androidx.compose.ui.layout.ContentScale.Crop) }
                        Column(Modifier.weight(1f)) {
                            Text("Pagina ${b.pageIndex + 1}", color = LColors.textPrimary)
                            Text(fmt.format(java.util.Date(b.createdAt.toLong())), color = LColors.textSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { ProgressStore.removeBookmark(b) }) { Icon(Icons.Filled.Delete, "Rimuovi", tint = LColors.textSecondary) }
                    }
                }
            }
        }
    }
}

