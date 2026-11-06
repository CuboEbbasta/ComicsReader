package com.cubo.lumetto.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Bubble Zoom: la pagina resta intera e sfocata sotto, mentre il fumetto corrente compare ingrandito al centro.
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))) {
        val maxW = with(density) { (maxWidth * 0.92f).toPx() }
        val maxH = with(density) { (maxHeight * 0.62f).toPx() }
        val scale = min(maxW / srcW, maxH / srcH)
        val dstW = srcW * scale
        val dstH = srcH * scale
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(with(density) { dstW.toDp() }, with(density) { dstH.toDp() })
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, LColors.accent, RoundedCornerShape(18.dp))
            ) {
                drawImage(
                    bmp.asImageBitmap(),
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(srcW, srcH),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        Text(
            vm.bubbleProgress,
            color = LColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}
