package com.cubo.lumetto.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cubo.lumetto.ui.LColors
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Immagine di una pagina caricata in modo asincrono. */
@Composable
fun rememberPage(loader: PageLoader, index: Int, kind: PageLoader.Kind = PageLoader.Kind.FULL): Bitmap? {
    var bmp by remember(loader, index, kind) { mutableStateOf(loader.cached(index, kind)) }
    LaunchedEffect(loader, index, kind) {
        if (bmp == null) bmp = loader.load(index, kind)
    }
    return bmp
}

@Composable
fun PageImage(loader: PageLoader, index: Int, kind: PageLoader.Kind, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val bmp = rememberPage(loader, index, kind)
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, contentScale = contentScale, filterQuality = FilterQuality.High, modifier = modifier)
    } else {
        Box(modifier.aspectRatio(2f / 3f).background(Color.White.copy(alpha = 0.03f)), contentAlignment = Alignment.Center) {
            if (kind == PageLoader.Kind.FULL) CircularProgressIndicator(color = LColors.textTertiary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Una pagina (o doppia pagina, che si tocca al centro) con zoom: pizzico, doppio tocco sul punto,
 * trascinamento; a bordo pagina il gesto passa al pager per girare pagina.
 */
@Composable
fun ZoomableSpread(
    loader: PageLoader,
    pages: List<Int>,
    onTap: (xFraction: Float, zoomed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val images = pages.map { rememberPage(loader, it) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (box.width * (s - 1f)) / 2f
        val maxY = (box.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(pages) {
                detectTapGestures(
                    onTap = { pos -> onTap(if (size.width > 0) pos.x / size.width else 0.5f, scale > 1.01f) },
                    onDoubleTap = { pos ->
                        scope.launch {
                            if (scale > 1.01f) {
                                val startOffset = offset
                                val from = scale
                                animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                                    scale = from + (1f - from) * t
                                    offset = startOffset * (1f - t)
                                }
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val target = 2.5f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val goal = clamp((center - pos) * (target - 1f), target)
                                animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                                    scale = 1f + (target - 1f) * t
                                    offset = goal * t
                                }
                            }
                        }
                    },
                )
            }
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        val zoomed = scale > 1.01f
                        if (multi || zoomed) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Zoom attorno al punto tra le dita.
                            var newOffset = offset
                            if (zoom != 1f && centroid.isSpecified) {
                                newOffset = (offset - (centroid - center)) * (newScale / scale) + (centroid - center)
                            }
                            newOffset += pan
                            val clamped = clamp(newOffset, newScale)
                            // Ai bordi, un trascinamento orizzontale con un dito va al pager.
                            val atEdge = !multi && abs(pan.x) > abs(pan.y) && abs(clamped.x - newOffset.x) > 0.5f
                            if (!atEdge) {
                                scale = newScale
                                offset = if (newScale <= 1.001f) Offset.Zero else clamped
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Larghezza totale: le pagine affiancate alla stessa altezza.
        val ratios = pages.indices.map { i -> images[i]?.let { it.width.toFloat() / maxOf(1, it.height) } ?: (2f / 3f) }
        val totalRatio = ratios.sum().coerceAtLeast(0.01f)
        var h = maxHeight
        var w = h * totalRatio
        if (w > maxWidth) { w = maxWidth; h = w / totalRatio }
        Row(
            Modifier
                .size(w, h)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            pages.forEachIndexed { i, _ ->
                val bmp = images[i]
                val pw = h * ratios[i]
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High, modifier = Modifier.size(pw, h))
                } else {
                    Box(Modifier.size(pw, h), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Gray, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.fullWidth() = this.fillMaxWidth()
