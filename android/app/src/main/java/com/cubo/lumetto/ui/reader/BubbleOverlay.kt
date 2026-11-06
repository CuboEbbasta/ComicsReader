package com.cubo.lumetto.ui.reader

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.ui.LColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bubble Zoom: la pagina resta visibile e il fumetto corrente si ingrandisce **dove si trova**,
 * crescendo dalla sua posizione (spostato solo quanto basta per restare tutto sullo schermo).
 * Non intercetta i tocchi: i bordi della pagina sotto passano al fumetto successivo o precedente.
 */
@Composable
fun BubbleOverlay(vm: ReaderViewModel, loader: PageLoader) {
    val rect = vm.currentBubble ?: return
    val bmp = rememberPage(loader, vm.currentPage) ?: return
    val density = LocalDensity.current

    val srcX = (rect.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val srcY = (rect.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val srcW = max(1, ((rect.right - rect.left) * bmp.width).toInt()).coerceAtMost(bmp.width - srcX)
    val srcH = max(1, ((rect.bottom - rect.top) * bmp.height).toInt()).coerceAtMost(bmp.height - srcY)

    // Crescita dall'aspetto originale a quello ingrandito, ogni volta che cambia fumetto.
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rect, vm.currentPage) {
        progress = 0f
        animate(0f, 1f, animationSpec = tween(240)) { v, _ -> progress = v }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f))) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val margin = with(density) { 10.dp.toPx() }

        // Dove sta la pagina sullo schermo (adattata e centrata, come nel lettore).
        val ratio = bmp.width.toFloat() / max(1, bmp.height)
        var pageH = screenH
        var pageW = pageH * ratio
        if (pageW > screenW) { pageW = screenW; pageH = pageW / ratio }
        val pageLeft = (screenW - pageW) / 2f
        val pageTop = (screenH - pageH) / 2f

        // Il fumetto com'è sulla pagina.
        val origW = rect.width * pageW
        val origH = rect.height * pageH
        val origCx = pageLeft + rect.centerX * pageW
        val origCy = pageTop + rect.centerY * pageH

        // Ingrandito: il più possibile, ma centrato sul punto d'origine e dentro lo schermo.
        val zoom = min((screenW - 2 * margin) / origW, (screenH * 0.7f) / origH).coerceIn(1f, 3.2f)
        val bigW = origW * zoom
        val bigH = origH * zoom
        val bigCx = origCx.coerceIn(margin + bigW / 2f, max(margin + bigW / 2f, screenW - margin - bigW / 2f))
        val bigCy = origCy.coerceIn(margin + bigH / 2f, max(margin + bigH / 2f, screenH - margin - bigH / 2f))

        val p = progress
        val curW = origW + (bigW - origW) * p
        val curH = origH + (bigH - origH) * p
        val curCx = origCx + (bigCx - origCx) * p
        val curCy = origCy + (bigCy - origCy) * p

        Canvas(
            Modifier
                .offset { IntOffset((curCx - curW / 2f).roundToInt(), (curCy - curH / 2f).roundToInt()) }
                .size(with(density) { curW.toDp() }, with(density) { curH.toDp() })
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, LColors.accent, RoundedCornerShape(14.dp))
        ) {
            drawImage(
                bmp.asImageBitmap(),
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.High,
            )
        }
        Text(
            vm.bubbleProgress,
            color = LColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}
