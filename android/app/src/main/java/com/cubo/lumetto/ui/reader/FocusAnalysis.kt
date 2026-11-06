package com.cubo.lumetto.ui.reader

import android.graphics.Bitmap
import com.cubo.lumetto.model.ReadingMode
import kotlin.math.max

/** Passa dalla pagina a colori ai riquadri da inquadrare (vedi [PanelDetector]). */
object FocusAnalysis {
    private const val ANALYSIS_PIXELS = 480

    fun analyze(page: Bitmap, mode: ReadingMode, rtl: Boolean): List<NormRect> {
        val scale = ANALYSIS_PIXELS.toFloat() / max(page.width, page.height)
        val w = max(16, (page.width * scale).toInt())
        val h = max(16, (page.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(page, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== page) small.recycle()
        val lum = IntArray(w * h) {
            val c = pixels[it]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
        }
        val panels = PanelDetector.panels(lum, w, h, rtl)
        if (mode == ReadingMode.GUIDED) return if (panels.size >= 2) panels else emptyList()
        val bubbles = PanelDetector.bubbles(lum, w, h, rtl, panels)
        return when {
            bubbles.isNotEmpty() -> bubbles
            panels.size >= 2 -> panels
            else -> emptyList()
        }
    }
}
