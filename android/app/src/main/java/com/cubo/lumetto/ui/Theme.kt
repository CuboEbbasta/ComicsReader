package com.cubo.lumetto.ui

import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.R
import kotlin.math.sin

/** Palette del marchio Lumetto (fumo e fuoco): nero notte, carbone, avorio, accento ambra. */
object LColors {
    val background = Color(0xFF0B0907)
    val panel = Color(0xFF110E0B)
    val surface = Color(0xFF1F1A15)
    val surfaceLow = Color(0xFF181410)
    val card = Color(0xFF1C1813)
    val track = Color(0xFF2E2720)
    val accent = Color(0xFFE39B2D)
    val gold = Color(0xFFF3C661)
    val textPrimary = Color(0xFFF1E8D3)
    val textSecondary = Color(0xFFB3A893)
    val textTertiary = Color(0xFF8F8674)
    val stroke = Color(0x14F1E8D3)
    val error = Color(0xFFFF6B5E)

    val panelRadius = 28.dp
    val coverRadius = 10.dp
}

@Composable
fun LumettoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LColors.accent,
            onPrimary = Color.Black,
            secondary = LColors.gold,
            background = LColors.background,
            onBackground = LColors.textPrimary,
            surface = LColors.panel,
            onSurface = LColors.textPrimary,
            surfaceVariant = LColors.surface,
            onSurfaceVariant = LColors.textSecondary,
            surfaceContainer = LColors.surface,
            surfaceContainerHigh = LColors.surface,
            surfaceContainerHighest = LColors.track,
            surfaceContainerLow = LColors.panel,
            outline = LColors.textTertiary,
            error = LColors.error,
        ),
        content = content,
    )
}

/** Pulsante quadrato scuro delle barre (Impostazioni, Indietro, Aggiorna). */
@Composable
fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.35f)
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LColors.surface.copy(alpha = if (pressed) 0.6f else 1f))
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (prominent) LColors.accent else LColors.textPrimary,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
        ) { content() }
    }
}

/** Pulsanti piatti per Precedente/Successivo. */
@Composable
fun FlatArrowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.3f)
            .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LColors.surfaceLow.copy(alpha = if (pressed) 0.5f else 1f))
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides LColors.textPrimary,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
        ) { content() }
    }
}

/** Barra di avanzamento sottile ambra. */
@Composable
fun ProgressLine(fraction: Double, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(LColors.track)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                .clip(RoundedCornerShape(50))
                .background(LColors.accent)
        )
    }
}

/** Icona di Lumetto con angoli arrotondati. */
@Composable
fun BrandIcon(size: Dp) {
    Image(
        painter = painterResource(R.drawable.lumetto_icon),
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.225f)),
        contentScale = ContentScale.Crop,
    )
}

/** true se l'utente ha disattivato le animazioni di sistema ("Riduci movimento"). */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Logo animato della schermata iniziale: la nuvoletta galleggia piano, la fiamma "respira"
 * con un alone ambra che pulsa e tremola come una candela. Fermo se le animazioni sono disattivate.
 */
@Composable
fun AnimatedBrandLogo(size: Dp, modifier: Modifier = Modifier) {
    val still = reduceMotion()
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) {
            withInfiniteAnimationFrameMillis { t = (it - start) / 1000f }
        }
    }
    val time = t.toDouble()
    val flicker = (sin(time * 7.3) * 0.5 + sin(time * 11.9) * 0.3 + sin(time * 3.1) * 0.2).toFloat()
    val breath = sin(time * 1.4).toFloat()
    val float = sin(time * 0.9).toFloat()

    Box(modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        // Alone della fiamma
        Box(
            Modifier
                .size(size * 1.6f)
                .graphicsLayer {
                    val s = 1f + breath * 0.05f + flicker * 0.015f
                    scaleX = s; scaleY = s
                }
                .blur(18.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            0f to LColors.accent.copy(alpha = (0.42f + flicker * 0.06f).coerceIn(0f, 1f)),
                            0.5f to LColors.accent.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            radius = this.size.minDimension * 0.47f,
                        ),
                        radius = this.size.minDimension * 0.47f,
                    )
                }
        )
        Image(
            painter = painterResource(R.drawable.lumetto_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + breath * 0.02f
                    scaleX = s; scaleY = s
                    rotationZ = float * 2f
                    translationY = float * size.toPx() * 0.03f
                    shadowElevation = 0f
                },
        )
    }
}

/** Area toccabile senza effetto a onda. */
fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, indication = null, interactionSource = null, onClick = onClick)
