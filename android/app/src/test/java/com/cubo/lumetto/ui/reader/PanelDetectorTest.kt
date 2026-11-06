package com.cubo.lumetto.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDetectorTest {
    private val w = 300
    private val h = 400

    private fun page(bg: Int = 255) = IntArray(w * h) { bg }

    private fun fill(img: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
        for (y in y0 until y1) for (x in x0 until x1) img[y * w + x] = v
    }

    private fun ellipse(img: IntArray, cx: Int, cy: Int, rx: Int, ry: Int, v: Int) {
        for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
            val dx = (x - cx).toDouble() / rx
            val dy = (y - cy).toDouble() / ry
            if (dx * dx + dy * dy <= 1.0) img[y * w + x] = v
        }
    }

    /** Griglia 2x2 con canaline di 12 px e margine di 14. */
    private fun grid(): IntArray {
        val p = page()
        fill(p, 14, 14, 144, 194, 110)
        fill(p, 156, 14, 286, 194, 110)
        fill(p, 14, 206, 144, 386, 110)
        fill(p, 156, 206, 286, 386, 110)
        return p
    }

    @Test
    fun gridGivesFourPanelsInReadingOrder() {
        val r = PanelDetector.panels(grid(), w, h, rtl = false)
        assertEquals(4, r.size)
        assertTrue(r[0].centerX < 0.5f && r[0].centerY < 0.5f)
        assertTrue(r[1].centerX > 0.5f && r[1].centerY < 0.5f)
        assertTrue(r[2].centerX < 0.5f && r[2].centerY > 0.5f)
        assertTrue(r[3].centerX > 0.5f && r[3].centerY > 0.5f)
    }

    @Test
    fun mangaOrderGoesRightToLeft() {
        val r = PanelDetector.panels(grid(), w, h, rtl = true)
        assertEquals(4, r.size)
        assertTrue(r[0].centerX > 0.5f && r[0].centerY < 0.5f)
        assertTrue(r[1].centerX < 0.5f && r[1].centerY < 0.5f)
        assertTrue(r[2].centerX > 0.5f && r[2].centerY > 0.5f)
    }

    @Test
    fun wideTopPanelAndTwoColumnsBelow() {
        val p = page()
        fill(p, 14, 14, 286, 150, 110)
        fill(p, 14, 162, 144, 386, 110)
        fill(p, 156, 162, 286, 386, 110)
        val r = PanelDetector.panels(p, w, h, rtl = false)
        assertEquals(3, r.size)
        assertTrue(r[0].width > 0.8f)
        assertTrue(r[1].centerX < r[2].centerX)
    }

    @Test
    fun lowContrastPanelsAreFoundByTheTolerantPass() {
        val p = page()
        fill(p, 14, 14, 144, 386, 222) // poco più scure dello sfondo
        fill(p, 156, 14, 286, 386, 222)
        assertEquals(2, PanelDetector.panels(p, w, h, rtl = false).size)
    }

    @Test
    fun drawingOverlappingTheGutterDoesNotMergePanels() {
        val p = grid()
        fill(p, 138, 60, 162, 66, 110) // un fumetto o un braccio che attraversa la canalina verticale
        val r = PanelDetector.panels(p, w, h, rtl = false)
        assertEquals(4, r.size)
    }

    @Test
    fun darkBackgroundWorksToo() {
        val p = page(bg = 0)
        fill(p, 14, 14, 144, 386, 170)
        fill(p, 156, 14, 286, 386, 170)
        assertEquals(2, PanelDetector.panels(p, w, h, rtl = false).size)
    }

    @Test
    fun fullBleedPageIsOnePanel() {
        val r = PanelDetector.panels(IntArray(w * h) { 120 }, w, h, rtl = false)
        assertEquals(1, r.size)
        assertTrue(r[0].area > 0.9f)
    }

    @Test
    fun bubbleWithTextIsFoundBlankShapeIsNot() {
        val p = page()
        fill(p, 14, 14, 286, 386, 110)
        ellipse(p, 100, 120, 50, 35, 255)
        fill(p, 80, 110, 120, 130, 0) // testo
        ellipse(p, 210, 280, 40, 30, 255) // forma bianca senza testo
        val b = PanelDetector.bubbles(p, w, h, rtl = false)
        assertEquals(1, b.size)
        assertTrue(b[0].centerX < 0.5f && b[0].centerY < 0.5f)
    }

    @Test
    fun bubblesFollowPanelOrderAndDirection() {
        val p = page()
        fill(p, 14, 14, 144, 386, 110)
        fill(p, 156, 14, 286, 386, 110)
        ellipse(p, 80, 100, 40, 30, 255); fill(p, 66, 92, 94, 108, 0)
        ellipse(p, 220, 100, 40, 30, 255); fill(p, 206, 92, 234, 108, 0)
        val panels = PanelDetector.panels(p, w, h, rtl = true)
        val b = PanelDetector.bubbles(p, w, h, rtl = true, panels = panels)
        assertEquals(2, b.size)
        assertTrue(b[0].centerX > b[1].centerX)
    }

    @Test
    fun expandedStaysInsidePage() {
        val r = NormRect(0.0f, 0.1f, 0.5f, 0.9f).expanded(0.2f)
        assertEquals(0f, r.left)
        assertTrue(r.bottom <= 1f)
    }
}
