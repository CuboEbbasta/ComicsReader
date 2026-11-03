package com.cubo.lumetto.ui.library

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.data.AppRouter
import com.cubo.lumetto.data.AppSettings
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.JsonFile
import com.cubo.lumetto.data.LibraryNavigator
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.ComicFolder
import com.cubo.lumetto.model.LibraryBackground
import com.cubo.lumetto.ui.BarButton
import com.cubo.lumetto.ui.FlatArrowButton
import com.cubo.lumetto.ui.LColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/** Misure della scena, per tablet (ampio) e telefono (compatto). */
class SpatialLayout(val width: Dp, val compact: Boolean) {
    val pageWidth: Dp = if (compact) width - 56.dp else minOf(width * 0.62f, 800.dp)
    val spacing: Dp = if (compact) 10.dp else 26.dp
    val sideMargin: Dp = maxOf(0.dp, (width - pageWidth) / 2)
}

/**
 * La libreria: un carosello di pannelli (la cartella attiva al centro, le sorelle ai lati in
 * prospettiva), con transizioni verticali quando si scende o si risale di livello.
 */
@Composable
fun SpatialLibraryScreen() {
    val nav = LibraryNavigator
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = SpatialLayout(maxWidth, maxWidth < 700.dp)
        LibraryBackdrop()
        val folder = nav.current
        when {
            folder != null -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                LibraryTopBar(layout.compact)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = nav.currentRowId,
                        transitionSpec = {
                            when (nav.lastMove) {
                                LibraryNavigator.Move.DOWN ->
                                    (slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow)) { it } + fadeIn()) togetherWith
                                        (slideOutVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow)) { -it } + fadeOut())
                                LibraryNavigator.Move.UP ->
                                    (slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow)) { -it } + fadeIn()) togetherWith
                                        (slideOutVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow)) { it } + fadeOut())
                                else -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            }
                        },
                        label = "row",
                    ) { rowId ->
                        val row = remember(rowId, LibraryStore.version) { nav.currentRow }
                        val initial = remember(rowId, LibraryStore.version) { nav.current?.id ?: "" }
                        FolderCarousel(row, initial, layout)
                    }
                }
                LibraryBottomBar(folder, layout.compact)
            }
            LibraryStore.isScanning -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = LColors.accent)
                Spacer(Modifier.height(14.dp))
                Text("Sto leggendo la libreria…", color = LColors.textSecondary)
            }
            else -> EmptyLibraryView(Modifier.align(Alignment.Center))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Carosello

/**
 * HorizontalPager con prospettiva. La fila è circolare: è ripetuta molte volte e si parte dalla
 * copia centrale, così si può girare all'infinito in entrambe le direzioni.
 */
@Composable
private fun FolderCarousel(row: List<ComicFolder>, initialId: String, layout: SpatialLayout) {
    if (row.isEmpty()) return
    val nav = LibraryNavigator
    val count = row.size
    val copies = if (count > 1) 1001 else 1
    val middle = copies / 2
    val startIndex = maxOf(0, row.indexOfFirst { it.id == initialId })
    val pager = rememberPagerState(initialPage = middle * count + startIndex) { count * copies }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Lo scorrimento a mano aggiorna la cartella corrente.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val f = row[page % count]
            if (f != nav.current) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                nav.selectFromScroll(f.id)
            }
        }
    }
    // Pulsanti ← →, salti e briciole: si scorre dal lato più corto, anche attraverso il "giro".
    LaunchedEffect(nav.current?.id) {
        val targetId = nav.current?.id ?: return@LaunchedEffect
        val targetIndex = row.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return@LaunchedEffect
        val currentIndex = pager.currentPage % count
        if (currentIndex == targetIndex) return@LaunchedEffect
        val forward = (targetIndex - currentIndex + count) % count
        val backward = count - forward
        val target = pager.currentPage + if (forward <= backward) forward else -backward
        scope.launch { pager.animateScrollToPage(target.coerceIn(0, count * copies - 1)) }
    }

    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = layout.sideMargin),
        pageSpacing = layout.spacing,
        beyondViewportPageCount = 1,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val folder = row[page % count]
        val isActive = page == pager.currentPage
        Box(
            Modifier
                .fillMaxSize()
                .padding(vertical = if (layout.compact) 6.dp else 10.dp)
                .graphicsLayer {
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val o = offset.coerceIn(-1f, 1f)
                    rotationY = -o * 28f
                    transformOrigin = TransformOrigin(if (o > 0) 1f else 0f, 0.5f)
                    cameraDistance = 14f * density
                    val s = 1f - abs(o) * 0.08f
                    scaleX = s
                    scaleY = s
                    alpha = 1f - abs(o) * 0.45f
                }
        ) {
            FolderPanel(folder, layout.compact, isActive)
            if (!isActive) {
                // Toccare un pannello laterale lo porta al centro.
                Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null) {
                    scope.launch { pager.animateScrollToPage(page) }
                })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trascina e rilascia per cambiare livello

/**
 * Tirando oltre l'inizio o oltre la fine del pannello e RILASCIANDO si cambia livello.
 * Conta solo il dito che rilascia, non l'inerzia.
 */
private class PullConnection(
    private val threshold: Float,
    private val canTop: () -> Boolean,
    private val canBottom: () -> Boolean,
    private val onTop: () -> Unit,
    private val onBottom: () -> Unit,
    private val onArm: () -> Unit,
) : NestedScrollConnection {
    var pull by mutableFloatStateOf(0f)

    val armedTop get() = pull >= threshold
    val armedBottom get() = pull <= -threshold

    private fun update(value: Float) {
        val wasArmed = armedTop || armedBottom
        pull = value
        if (!wasArmed && (armedTop || armedBottom)) onArm()
    }

    @Suppress("DEPRECATION")
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.Drag || pull == 0f) return Offset.Zero
        if ((pull > 0 && available.y < 0) || (pull < 0 && available.y > 0)) {
            val before = pull
            val next = if (pull > 0) maxOf(0f, pull + available.y * 0.5f) else minOf(0f, pull + available.y * 0.5f)
            update(next)
            return Offset(0f, (next - before) / 0.5f)
        }
        return Offset.Zero
    }

    @Suppress("DEPRECATION")
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.Drag || available.y == 0f) return Offset.Zero
        if (available.y > 0 && canTop()) { update(pull + available.y * 0.5f); return Offset(0f, available.y) }
        if (available.y < 0 && canBottom()) { update(pull + available.y * 0.5f); return Offset(0f, available.y) }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val top = armedTop
        val bottom = armedBottom
        val hadPull = pull != 0f
        pull = 0f
        if (top) onTop() else if (bottom) onBottom()
        return if (hadPull) available else Velocity.Zero
    }
}

// ---------------------------------------------------------------------------------------------
// Pannello della cartella

@Composable
private fun FolderPanel(folder: ComicFolder, compact: Boolean, isActive: Boolean) {
    val nav = LibraryNavigator
    val canPullUp = folder.parent != null && nav.rowId(folder) != "__top__"
    val childTarget = if (folder.isRoot) null else folder.subfolders.firstOrNull()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val threshold = with(density) { (if (compact) 70.dp else 85.dp).toPx() }
    val connection = remember(folder.id, canPullUp, childTarget) {
        PullConnection(
            threshold = threshold,
            canTop = { canPullUp },
            canBottom = { childTarget != null },
            onTop = { nav.goUp() },
            onBottom = { nav.goDown() },
            onArm = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        )
    }
    val radius = if (compact) 24.dp else LColors.panelRadius
    val shape = RoundedCornerShape(radius)
    val pullOffset by animateFloatAsState(connection.pull, spring(stiffness = Spring.StiffnessHigh), label = "pull")
    val hPad = if (compact) 16.dp else 26.dp
    val progress = ProgressStore.progress
    val continueItems = if (folder.isRoot) LibraryStore.continueReading(progress) else emptyList()

    Box(
        Modifier
            .fillMaxSize()
            .shadow(24.dp, shape)
            .clip(shape)
            .background(LColors.panel)
            .border(1.dp, LColors.stroke, shape)
            .nestedScroll(connection)
    ) {
        LazyVerticalGrid(
            columns = if (compact) GridCells.Fixed(3) else GridCells.Adaptive(112.dp),
            userScrollEnabled = isActive,
            contentPadding = PaddingValues(start = hPad, end = hPad, top = if (compact) 18.dp else 24.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 20.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = pullOffset },
        ) {
            if (canPullUp) {
                val parent = folder.parent
                if (parent != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "parent") {
                        Row { ParentChip(parent.name, connection.armedTop) { nav.goUp() } }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") { PanelHeader(folder, compact) }
            if (continueItems.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Continua a leggere")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
                            items(continueItems, key = { it.first.id }) { (comic, p) -> ContinueReadingCard(comic, p, compact) }
                        }
                    }
                }
            }
            if (folder.comics.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "comicsTitle") {
                    SectionTitle(if (folder.isRoot) "In questa cartella" else "Fumetti", if (folder.comics.size > 1) "${folder.comics.size}" else null)
                }
                items(folder.comics, key = { it.id }) { comic -> ComicGridItem(comic, compact) }
            } else if (folder.isRoot && folder.subfolders.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        if (LibraryStore.isScanning) "Sto leggendo la libreria…" else "Nessun fumetto trovato. Aggiungi file CBZ, CBR, PDF o EPUB alla cartella e tocca Aggiorna.",
                        color = LColors.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 40.dp),
                    )
                }
            }
            if (folder.isRoot) {
                if (folder.subfolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "categories") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Cartelle", "scorri a destra →")
                            FolderChips(folder.subfolders) { nav.jump(it) }
                        }
                    }
                }
            } else if (folder.subfolders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "subfolders") {
                    SubfoldersSection(folder, compact, connection.armedBottom)
                }
            }
        }
    }
}

@Composable
private fun ParentChip(name: String, armed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (armed) LColors.accent else LColors.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (armed) Color.Black else LColors.textSecondary
        Icon(Icons.Filled.KeyboardArrowUp, null, tint = color, modifier = Modifier.size(16.dp))
        Text(if (armed) "Rilascia per tornare a $name" else name, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PanelHeader(folder: ComicFolder, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            folder.name, color = LColors.textPrimary, fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 28.sp else 36.sp, lineHeight = if (compact) 32.sp else 40.sp,
            letterSpacing = (-0.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val parts = mutableListOf<String>()
        val total = folder.totalComicCount
        parts.add(if (total == 1) "1 fumetto" else "$total fumetti")
        if (folder.subfolders.isNotEmpty()) parts.add(if (folder.subfolders.size == 1) "1 cartella" else "${folder.subfolders.size} cartelle")
        Text(parts.joinToString(" · "), color = LColors.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(text, color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = LColors.textTertiary, fontSize = 12.sp)
    }
}

/** Separazione + anteprima delle prime due sottocartelle, poi le altre come chip. */
@Composable
private fun SubfoldersSection(folder: ComicFolder, compact: Boolean, armed: Boolean) {
    val nav = LibraryNavigator
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.1f))
            Text(
                if (folder.subfolders.size == 1) "SOTTOCARTELLA" else "${folder.subfolders.size} SOTTOCARTELLE",
                color = LColors.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            )
            HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.1f))
        }
        folder.subfolders.take(2).forEachIndexed { index, sub ->
            SubfolderPreview(sub, compact, armed = index == 0 && armed, hint = index == 0) {
                if (index == 0) nav.goDown() else nav.jump(sub)
            }
        }
        if (folder.subfolders.size > 2) {
            Text("ALTRE CARTELLE", color = LColors.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            FolderChips(folder.subfolders.drop(2)) { nav.jump(it) }
        }
    }
}

@Composable
private fun SubfolderPreview(sub: ComicFolder, compact: Boolean, armed: Boolean, hint: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val scale by animateFloatAsState(if (armed) 0.98f else 1f, label = "armed")
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Color.White.copy(alpha = if (armed) 0.09f else 0.045f))
            .border(1.dp, LColors.accent.copy(alpha = if (armed) 0.8f else 0.25f), shape)
            .clickable(onClick = onClick)
            .padding(if (compact) 14.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(sub.name, color = LColors.textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 18.sp else 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (sub.totalComicCount == 1) "1 fumetto" else "${sub.totalComicCount} fumetti", color = LColors.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (armed) LColors.accent else LColors.accent.copy(alpha = 0.14f))
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val c = if (armed) Color.Black else LColors.accent
                Icon(Icons.Filled.ArrowDownward, null, tint = c, modifier = Modifier.size(16.dp))
                Text(if (armed) "Rilascia" else "Entra", color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState(), enabled = false).alpha(0.7f),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            sub.previewComics(8).forEach { comic ->
                ComicCover(
                    comic,
                    Modifier.width(if (compact) 64.dp else 84.dp).height(if (compact) 96.dp else 126.dp),
                    cornerRadius = 8.dp, showsTitle = false,
                )
            }
        }
        if (hint) {
            Text(
                if (compact) "Tocca, o trascina oltre il fondo e rilascia" else "Tocca, oppure trascina oltre il fondo e rilascia per entrare",
                color = LColors.textTertiary, fontSize = 11.sp,
            )
        }
    }
}

/** Chip delle cartelle che vanno a capo. */
@Composable
private fun FolderChips(folders: List<ComicFolder>, onClick: (ComicFolder) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        folders.forEach { f ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(LColors.surface)
                    .clickable { onClick(f) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.ViewModule, null, tint = LColors.textPrimary, modifier = Modifier.size(14.dp))
                Text(f.name, color = LColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${f.totalComicCount}", color = LColors.textTertiary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sfondo

object BackgroundImageStore {
    val file get() = JsonFile.file("library-background.jpg")
}

@Composable
fun LibraryBackdrop() {
    val bg = AppSettings.libraryBackground
    val version = AppSettings.backgroundImageVersion
    val image by produceState<android.graphics.Bitmap?>(null, bg, version) {
        value = if (bg == LibraryBackground.CUSTOM) {
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(BackgroundImageStore.file.path) }.getOrNull() }
        } else null
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(bg.top), Color(bg.bottom))))) {
        val bmp = image
        if (bg == LibraryBackground.CUSTOM && bmp != null) {
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Barre

@Composable
private fun LibraryTopBar(compact: Boolean) {
    val nav = LibraryNavigator
    Row(
        Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 24.dp, end = if (compact) 16.dp else 24.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BarButton(onClick = { AppRouter.showSettings = true }) {
            Icon(Icons.Filled.Settings, "Impostazioni", modifier = Modifier.size(20.dp))
        }
        if (IncognitoStore.isActive) {
            BarButton(onClick = { IncognitoStore.exit() }, prominent = true) {
                Icon(Icons.Filled.VisibilityOff, "Esci dalla modalità Incognito", modifier = Modifier.size(18.dp))
                if (!compact) { Spacer(Modifier.width(6.dp)); Text("Incognito", fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(46.dp).clip(CircleShape).clickable { AppRouter.showSearch = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Search, "Cerca in tutta la libreria", tint = LColors.accent, modifier = Modifier.size(24.dp))
        }
        BarButton(onClick = { nav.goUp() }, enabled = nav.canGoUp) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Indietro", modifier = Modifier.size(22.dp))
            if (!compact) Text("Indietro")
        }
    }
}

@Composable
private fun LibraryBottomBar(folder: ComicFolder, compact: Boolean) {
    val nav = LibraryNavigator
    val (left, right) = nav.neighbors(folder)
    val (index, count) = nav.position(folder)
    Row(
        Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 24.dp, end = if (compact) 16.dp else 24.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f)) {
            BarButton(onClick = { LibraryStore.refresh() }, enabled = !LibraryStore.isScanning) {
                if (LibraryStore.isScanning) {
                    CircularProgressIndicator(color = LColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(Icons.Filled.Refresh, "Aggiorna la libreria", modifier = Modifier.size(20.dp))
                }
                if (!compact) { Spacer(Modifier.width(8.dp)); Text("Aggiorna") }
            }
        }
        Column(
            Modifier.widthIn(max = if (compact) 180.dp else 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PageDots(index, count)
            Breadcrumb(folder)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            FlatArrowButton(onClick = { nav.goLeft() }, enabled = left != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, left?.let { "Precedente: ${it.name}" } ?: "Nessuna cartella", modifier = Modifier.size(20.dp))
                if (!compact && left != null) Text(left.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            }
            FlatArrowButton(onClick = { nav.goRight() }, enabled = right != null) {
                if (!compact && right != null) Text(right.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, right?.let { "Successiva: ${it.name}" } ?: "Nessuna cartella", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Puntini della fila corrente. */
@Composable
private fun PageDots(index: Int, count: Int) {
    if (count <= 1) return
    if (count <= 12) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until count) {
                val w by animateFloatAsState(if (i == index) 16f else 6f, tween(200), label = "dot")
                Box(
                    Modifier.width(w.dp).height(6.dp).clip(CircleShape)
                        .background(if (i == index) LColors.accent else Color.White.copy(alpha = 0.25f))
                )
            }
        }
    } else {
        Text("${index + 1} di $count", color = LColors.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Percorso corrente; ogni segmento riporta direttamente a quel livello. */
@Composable
private fun Breadcrumb(folder: ComicFolder) {
    val chain = folder.ancestry
    val scroll = rememberScrollState()
    LaunchedEffect(folder.id) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .clip(CircleShape)
            .background(LColors.surface.copy(alpha = 0.85f))
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chain.forEachIndexed { i, item ->
            if (i > 0) Text("/", color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
            if (i == chain.size - 1) {
                Text(item.name, color = LColors.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            } else {
                Text(item.name, color = LColors.textSecondary, fontSize = 14.sp, maxLines = 1, modifier = Modifier.clickable { LibraryNavigator.jump(item) })
            }
        }
    }
}

/** La cartella non è leggibile o la scansione è fallita. */
@Composable
private fun EmptyLibraryView(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Filled.LibraryBooks, null, tint = LColors.accent, modifier = Modifier.size(44.dp))
        Text("Nessun fumetto trovato", color = LColors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            LibraryStore.errorMessage ?: "Nella cartella scelta non ci sono file CBZ, CBR, PDF o EPUB.",
            color = LColors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarButton(onClick = { LibraryStore.refresh() }) { Text("Aggiorna") }
            BarButton(onClick = { AppRouter.showSettings = true }, prominent = true) { Text("Impostazioni") }
        }
    }
}

@Suppress("unused")
private fun unusedMin(a: Float, b: Float) = min(a, b)
