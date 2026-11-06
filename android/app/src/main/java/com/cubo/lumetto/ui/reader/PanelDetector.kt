package com.cubo.lumetto.ui.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rettangolo normalizzato (0..1) rispetto all'immagine della pagina. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    /** Allarga ogni lato di [fraction] volte la dimensione del rettangolo, restando nella pagina. */
    fun expanded(fraction: Float): NormRect {
        val dx = width * fraction
        val dy = height * fraction
        return NormRect(max(0f, left - dx), max(0f, top - dy), min(1f, right + dx), min(1f, bottom + dy))
    }

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Riconoscimento di vignette e fumetti (nuvolette) su una pagina ridotta, in scala di grigi.
 * Algoritmo semplice e veloce, senza modelli: funziona bene con le tavole dalle canaline chiare (o scure)
 * e le nuvolette bianche con testo; non è perfetto su layout a vignette inclinate o senza bordi.
 * Logica pura: si prova con i test.
 */
object PanelDetector {
    /** Parametri di un tentativo: contrasto minimo, tolleranza dei disegni che invadono la canalina, larghezza minima. */
    private class Pass(val delta: Int, val gutter: Float, val minGap: Float)

    // Dal più rigoroso al più tollerante: si usa il primo che trova almeno due vignette plausibili.
    private val PASSES = listOf(Pass(48, 0.012f, 0.008f), Pass(34, 0.025f, 0.006f), Pass(24, 0.045f, 0.005f))
    private const val MAX_PANELS = 14

    /** Vignette nell'ordine di lettura. Almeno una: se non ne trova, la pagina intera. */
    fun panels(lum: IntArray, w: Int, h: Int, rtl: Boolean): List<NormRect> {
        if (w < 16 || h < 16 || lum.size < w * h) return listOf(NormRect.FULL)
        val bg = borderMedian(lum, w, h)
        var best = listOf(NormRect.FULL)
        for (pass in PASSES) {
            val found = detect(lum, w, h, rtl, bg, pass)
            if (found.size in 2..MAX_PANELS) return found
            if (found.size in (best.size + 1)..MAX_PANELS) best = found
        }
        return best
    }

    private fun detect(lum: IntArray, w: Int, h: Int, rtl: Boolean, bg: Int, pass: Pass): List<NormRect> {
        val gutterFraction = pass.gutter
        val content = BooleanArray(w * h) { abs(lum[it] - bg) > pass.delta }
        val minGapX = max(2, (w * pass.minGap).roundToInt())
        val minGapY = max(2, (h * pass.minGap).roundToInt())
        val minPieceX = (w * 0.07f).roundToInt()
        val minPieceY = (h * 0.07f).roundToInt()

        val leaves = ArrayList<IntArray>() // x0, y0, x1, y1 (esclusi x1, y1)
        fun cut(x0: Int, y0: Int, x1: Int, y1: Int, depth: Int) {
            val box = trim(content, w, x0, y0, x1, y1, gutterFraction) ?: return
            val (bx0, by0, bx1, by1) = box
            if (depth < 8) {
                val big = (bx1 - bx0).toFloat() * (by1 - by0) > w.toFloat() * h * 0.12f
                // Prima canaline pulite; sulle regioni grandi, poi anche canaline con qualche disegno che le invade.
                val attempts = if (big) listOf(gutterFraction to 1f, min(0.06f, gutterFraction * 3.5f) to 2.2f) else listOf(gutterFraction to 1f)
                for ((gf, gapScale) in attempts) {
                    val gapY = (minGapY * gapScale).roundToInt()
                    val gapX = (minGapX * gapScale).roundToInt()
                    splitPoint(content, w, box, horizontal = true, minGap = gapY, minPiece = minPieceY, gutterFraction = gf)?.let { (a, b) ->
                        cut(bx0, by0, bx1, a, depth + 1)
                        cut(bx0, b, bx1, by1, depth + 1)
                        return
                    }
                    splitPoint(content, w, box, horizontal = false, minGap = gapX, minPiece = minPieceX, gutterFraction = gf)?.let { (a, b) ->
                        if (rtl) {
                            cut(b, by0, bx1, by1, depth + 1)
                            cut(bx0, by0, a, by1, depth + 1)
                        } else {
                            cut(bx0, by0, a, by1, depth + 1)
                            cut(b, by0, bx1, by1, depth + 1)
                        }
                        return
                    }
                }
            }
            leaves.add(intArrayOf(bx0, by0, bx1, by1))
        }
        cut(0, 0, w, h, 0)

        val pageArea = w.toFloat() * h
        val rects = leaves
            .filter { (it[2] - it[0]).toFloat() * (it[3] - it[1]) >= pageArea * 0.025f }
            .map { NormRect(it[0] / w.toFloat(), it[1] / h.toFloat(), it[2] / w.toFloat(), it[3] / h.toFloat()) }
        return rects.ifEmpty { listOf(NormRect.FULL) }
    }

    /** Fumetti con testo, ordinati per vignetta e poi dall'alto in basso (da destra a sinistra nei manga). */
    fun bubbles(lum: IntArray, w: Int, h: Int, rtl: Boolean, panels: List<NormRect> = emptyList()): List<NormRect> {
        if (w < 16 || h < 16 || lum.size < w * h) return emptyList()
        val bright = BooleanArray(w * h) { lum[it] >= 200 }
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val pageArea = w.toFloat() * h
        val found = ArrayList<NormRect>()
        for (start in 0 until w * h) {
            if (!bright[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && bright[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1 }
                if (x < w - 1 && bright[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1 }
                if (y > 0 && bright[p - w] && !seen[p - w]) { seen[p - w] = true; queue[tail++] = p - w }
                if (y < h - 1 && bright[p + w] && !seen[p + w]) { seen[p + w] = true; queue[tail++] = p + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val boxArea = bw.toFloat() * bh
            if (boxArea < pageArea * 0.004f || boxArea > pageArea * 0.20f) continue
            if (minX <= 1 || minY <= 1 || maxX >= w - 2 || maxY >= h - 2) continue // tocca il bordo: sfondo
            val fill = area / boxArea
            if (fill < 0.45f || fill > 0.95f) continue
            // Testo: pixel scuri nella parte interna del riquadro.
            val ix0 = minX + (bw * 0.15f).toInt(); val ix1 = maxX - (bw * 0.15f).toInt()
            val iy0 = minY + (bh * 0.15f).toInt(); val iy1 = maxY - (bh * 0.15f).toInt()
            var dark = 0
            var total = 0
            for (y in iy0..iy1) for (x in ix0..ix1) {
                total++
                if (lum[y * w + x] < 110) dark++
            }
            if (total == 0) continue
            val ink = dark.toFloat() / total
            if (ink < 0.03f || ink > 0.45f) continue
            found.add(NormRect(minX / w.toFloat(), minY / h.toFloat(), (maxX + 1) / w.toFloat(), (maxY + 1) / h.toFloat()))
        }
        return sortReading(found, rtl, panels)
    }

    private fun sortReading(items: List<NormRect>, rtl: Boolean, panels: List<NormRect>): List<NormRect> {
        fun panelOf(r: NormRect): Int {
            val i = panels.indexOfFirst { it.contains(r.centerX, r.centerY) }
            return if (i < 0) Int.MAX_VALUE else i
        }
        return items.sortedWith { a, b ->
            val pa = panelOf(a)
            val pb = panelOf(b)
            when {
                pa != pb -> pa.compareTo(pb)
                abs(a.centerY - b.centerY) > 0.5f * min(a.height, b.height) -> a.centerY.compareTo(b.centerY)
                rtl -> b.centerX.compareTo(a.centerX)
                else -> a.centerX.compareTo(b.centerX)
            }
        }
    }

    private fun borderMedian(lum: IntArray, w: Int, h: Int): Int {
        val samples = ArrayList<Int>()
        val m = max(1, min(w, h) / 50)
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            if (x < m || y < m || x >= w - m || y >= h - m) samples.add(lum[y * w + x])
        }
        samples.sort()
        return samples[samples.size / 2]
    }

    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** Riduce la regione al rettangolo che contiene il disegno; null se è vuota. */
    private fun trim(content: BooleanArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, gutterFraction: Float): Box? {
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        var minX = x1; var maxX = x0 - 1; var minY = y1; var maxY = y0 - 1
        val rw = x1 - x0
        val rh = y1 - y0
        val colCount = IntArray(rw)
        val rowCount = IntArray(rh)
        for (y in y0 until y1) for (x in x0 until x1) if (content[y * w + x]) { colCount[x - x0]++; rowCount[y - y0]++ }
        val colMin = max(1, (rh * gutterFraction).toInt())
        val rowMin = max(1, (rw * gutterFraction).toInt())
        for (i in 0 until rw) if (colCount[i] >= colMin) { minX = x0 + i; break }
        for (i in rw - 1 downTo 0) if (colCount[i] >= colMin) { maxX = x0 + i; break }
        for (i in 0 until rh) if (rowCount[i] >= rowMin) { minY = y0 + i; break }
        for (i in rh - 1 downTo 0) if (rowCount[i] >= rowMin) { maxY = y0 + i; break }
        if (maxX < minX || maxY < minY) return null
        return Box(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * Cerca la canalina vuota più larga che lasci pezzi abbastanza grandi.
     * Restituisce (fine del primo pezzo, inizio del secondo).
     */
    private fun splitPoint(content: BooleanArray, w: Int, box: Box, horizontal: Boolean, minGap: Int, minPiece: Int, gutterFraction: Float): Pair<Int, Int>? {
        val (x0, y0, x1, y1) = box
        val length = if (horizontal) y1 - y0 else x1 - x0
        val across = if (horizontal) x1 - x0 else y1 - y0
        val limit = max(1, (across * gutterFraction).toInt())
        val empty = BooleanArray(length)
        for (i in 0 until length) {
            var c = 0
            if (horizontal) {
                val y = y0 + i
                for (x in x0 until x1) if (content[y * w + x]) c++
            } else {
                val x = x0 + i
                for (y in y0 until y1) if (content[y * w + x]) c++
            }
            empty[i] = c < limit
        }
        var best: Pair<Int, Int>? = null
        var bestLen = 0
        var i = 0
        while (i < length) {
            if (!empty[i]) { i++; continue }
            var j = i
            while (j < length && empty[j]) j++
            val len = j - i
            if (len >= minGap && i >= minPiece && length - j >= minPiece && len > bestLen) {
                bestLen = len
                best = (if (horizontal) y0 else x0) + i to (if (horizontal) y0 else x0) + j
            }
            i = j
        }
        return best
    }
}
