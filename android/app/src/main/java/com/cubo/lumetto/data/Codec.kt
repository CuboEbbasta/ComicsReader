package com.cubo.lumetto.data

import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.EpubHighlight
import com.cubo.lumetto.model.HighlightColor
import com.cubo.lumetto.model.PageBookmark
import com.cubo.lumetto.model.ReadingProgress
import com.cubo.lumetto.model.ReadingSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversione JSON degli stessi oggetti dell'iPad. Le date sono millisecondi dal 1970 (Double),
 * i campi facoltativi assenti non vengono scritti. Usato sia per i file locali sia per la sincronizzazione.
 */
object Codec {
    // --- utilità -----------------------------------------------------------------------------

    fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() }?.toInt() else null

    fun JSONObject.optBoolOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    fun stampMapToJson(map: Map<String, Double>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o
    }

    fun stampMapFromJson(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        val result = HashMap<String, Double>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optDouble(k)
            if (!v.isNaN()) result[k] = v
        }
        return result
    }

    // --- progressi ---------------------------------------------------------------------------

    fun progressToJson(p: ReadingProgress): JSONObject = JSONObject()
        .put("relativePath", p.relativePath)
        .put("currentPage", p.currentPage)
        .put("totalPages", p.totalPages)
        .put("totalReadingTime", p.totalReadingTime)
        .put("sessionCount", p.sessionCount)
        .putOpt2("firstOpenedAt", p.firstOpenedAt)
        .putOpt2("lastReadAt", p.lastReadAt)
        .putOpt2("finishedAt", p.finishedAt)
        .put("isCompleted", p.isCompleted)
        .putOpt2("modifiedAt", p.modifiedAt)

    fun progressFromJson(o: JSONObject): ReadingProgress = ReadingProgress(
        relativePath = o.optString("relativePath"),
        currentPage = o.optIntOrNull("currentPage") ?: 0,
        totalPages = o.optIntOrNull("totalPages") ?: 0,
        totalReadingTime = o.optDoubleOrNull("totalReadingTime") ?: 0.0,
        sessionCount = o.optIntOrNull("sessionCount") ?: 0,
        firstOpenedAt = o.optDoubleOrNull("firstOpenedAt"),
        lastReadAt = o.optDoubleOrNull("lastReadAt"),
        finishedAt = o.optDoubleOrNull("finishedAt"),
        isCompleted = o.optBoolean("isCompleted", false),
        modifiedAt = o.optDoubleOrNull("modifiedAt"),
    )

    fun progressMapToJson(map: Map<String, ReadingProgress>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, progressToJson(v))
        return o
    }

    fun progressMapFromJson(o: JSONObject?): Map<String, ReadingProgress> {
        if (o == null) return emptyMap()
        val result = HashMap<String, ReadingProgress>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            o.optJSONObject(k)?.let { result[k] = progressFromJson(it).copy(relativePath = it.optString("relativePath", k)) }
        }
        return result
    }

    // --- segnalibri e sessioni ---------------------------------------------------------------

    fun bookmarkToJson(b: PageBookmark): JSONObject = JSONObject()
        .put("id", b.id).put("relativePath", b.relativePath).put("pageIndex", b.pageIndex).put("createdAt", b.createdAt)

    fun bookmarkFromJson(o: JSONObject): PageBookmark? {
        val id = o.optStringOrNull("id") ?: return null
        return PageBookmark(id, o.optString("relativePath"), o.optIntOrNull("pageIndex") ?: 0, o.optDoubleOrNull("createdAt") ?: 0.0)
    }

    fun sessionToJson(s: ReadingSession): JSONObject = JSONObject()
        .put("id", s.id).put("relativePath", s.relativePath).put("title", s.title)
        .put("startedAt", s.startedAt).put("duration", s.duration).put("pagesRead", s.pagesRead)
        .also { o -> s.deviceId?.let { o.put("deviceId", it) } }

    fun sessionFromJson(o: JSONObject): ReadingSession? {
        val id = o.optStringOrNull("id") ?: return null
        return ReadingSession(
            id, o.optString("relativePath"), o.optString("title"),
            o.optDoubleOrNull("startedAt") ?: 0.0, o.optDoubleOrNull("duration") ?: 0.0, o.optIntOrNull("pagesRead") ?: 0,
            o.optStringOrNull("deviceId"),
        )
    }

    // --- evidenziazioni ----------------------------------------------------------------------

    fun highlightToJson(h: EpubHighlight): JSONObject = JSONObject()
        .put("id", h.id).put("path", h.path).put("chapter", h.chapter)
        .put("start", h.start).put("end", h.end).put("text", h.text)
        .put("color", h.color.raw).put("createdAt", h.createdAt)
        .putOpt2("modifiedAt", h.modifiedAt)

    fun highlightFromJson(o: JSONObject): EpubHighlight? {
        val id = o.optStringOrNull("id") ?: return null
        return EpubHighlight(
            id = id,
            path = o.optString("path"),
            chapter = o.optIntOrNull("chapter") ?: 0,
            start = o.optIntOrNull("start") ?: 0,
            end = o.optIntOrNull("end") ?: 0,
            text = o.optString("text"),
            color = HighlightColor.from(o.optStringOrNull("color")) ?: HighlightColor.YELLOW,
            createdAt = o.optDoubleOrNull("createdAt") ?: 0.0,
            modifiedAt = o.optDoubleOrNull("modifiedAt"),
        )
    }

    // --- metadati ----------------------------------------------------------------------------

    fun metadataToJson(m: ComicMetadata): JSONObject = JSONObject()
        .putOpt2("title", m.title).putOpt2("series", m.series).putOpt2("number", m.number)
        .putOpt2("volume", m.volume).putOpt2("writer", m.writer).putOpt2("penciller", m.penciller)
        .putOpt2("summary", m.summary).putOpt2("year", m.year).putOpt2("pageCount", m.pageCount)
        .putOpt2("languageISO", m.languageISO).putOpt2("manga", m.manga)

    fun metadataFromJson(o: JSONObject?): ComicMetadata {
        if (o == null) return ComicMetadata.EMPTY
        return ComicMetadata(
            title = o.optStringOrNull("title"),
            series = o.optStringOrNull("series"),
            number = o.optStringOrNull("number"),
            volume = o.optStringOrNull("volume"),
            writer = o.optStringOrNull("writer"),
            penciller = o.optStringOrNull("penciller"),
            summary = o.optStringOrNull("summary"),
            year = o.optIntOrNull("year"),
            pageCount = o.optIntOrNull("pageCount"),
            languageISO = o.optStringOrNull("languageISO"),
            manga = o.optBoolOrNull("manga"),
        )
    }

    // --- liste -------------------------------------------------------------------------------

    fun <T> listToJson(list: List<T>, transform: (T) -> JSONObject): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(transform(it)) }
        return a
    }

    fun <T> listFromJson(a: JSONArray?, transform: (JSONObject) -> T?): List<T> {
        if (a == null) return emptyList()
        val result = ArrayList<T>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            transform(o)?.let { result.add(it) }
        }
        return result
    }
}
