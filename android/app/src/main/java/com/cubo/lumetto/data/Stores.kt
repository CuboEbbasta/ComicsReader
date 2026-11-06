package com.cubo.lumetto.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.EpubHighlight
import com.cubo.lumetto.model.HighlightColor
import com.cubo.lumetto.model.PageBookmark
import com.cubo.lumetto.model.ReadingProgress
import com.cubo.lumetto.model.ReadingSession
import com.cubo.lumetto.model.newUuid
import com.cubo.lumetto.model.nowMs
import org.json.JSONArray
import org.json.JSONObject

/** Avviso di "modifica locale" (pagina, segnalibro, evidenziazione…): va sincronizzata. */
object LocalChanges {
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun notifyChanged() { onMain { listeners.toList().forEach { it() } } }
}

// ---------------------------------------------------------------------------------------------
// Lapidi

enum class TombKind(val key: String) {
    PROGRESS("progress"), BOOKMARKS("bookmarks"), SESSIONS("sessions"),
    HIGHLIGHTS("highlights"), METADATA("metadata"), HIDDEN("hidden")
}

/** Chi elimina qualcosa lascia una "lapide" con la data: unendo i dati, l'elemento non ritorna. */
data class SyncTombstones(val maps: Map<TombKind, Map<String, Double>> = emptyMap()) {
    operator fun get(kind: TombKind): Map<String, Double> = maps[kind] ?: emptyMap()

    fun with(kind: TombKind, keys: Collection<String>, date: Double): SyncTombstones {
        val updated = maps.toMutableMap()
        val m = (updated[kind] ?: emptyMap()).toMutableMap()
        keys.forEach { m[it] = date }
        updated[kind] = m
        return SyncTombstones(updated)
    }

    fun merged(other: SyncTombstones): SyncTombstones {
        val result = HashMap<TombKind, Map<String, Double>>()
        for (kind in TombKind.entries) {
            val m = HashMap(this[kind])
            for ((k, v) in other[kind]) m[k] = maxOf(m[k] ?: v, v)
            result[kind] = m
        }
        return SyncTombstones(result)
    }

    fun pruned(olderThan: Double): SyncTombstones =
        SyncTombstones(TombKind.entries.associateWith { kind -> this[kind].filterValues { it >= olderThan } })

    fun sameContent(other: SyncTombstones) = TombKind.entries.all { this[it] == other[it] }

    fun toJson(): JSONObject {
        val o = JSONObject()
        for (kind in TombKind.entries) o.put(kind.key, Codec.stampMapToJson(this[kind]))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): SyncTombstones {
            if (o == null) return SyncTombstones()
            return SyncTombstones(TombKind.entries.associateWith { Codec.stampMapFromJson(o.optJSONObject(it.key)) })
        }
    }
}

object TombstoneStore {
    private const val FILE = "sync-tombstones.json"
    var current: SyncTombstones = SyncTombstones()
        private set

    fun load() {
        current = JsonFile.read(FILE)?.let { runCatching { SyncTombstones.fromJson(JSONObject(it)) }.getOrNull() } ?: SyncTombstones()
    }

    fun mark(kind: TombKind, keys: Collection<String>, date: Double = nowMs()) {
        if (keys.isEmpty()) return
        current = current.with(kind, keys, date)
        JsonFile.write(FILE, current.toJson().toString())
    }

    fun mark(kind: TombKind, key: String) = mark(kind, listOf(key))

    fun replace(t: SyncTombstones) {
        if (t.sameContent(current)) return
        current = t
        JsonFile.write(FILE, current.toJson().toString())
    }
}

// ---------------------------------------------------------------------------------------------
// Progressi, segnalibri, sessioni

object ProgressStore {
    var progress by mutableStateOf<Map<String, ReadingProgress>>(emptyMap())
        private set
    var bookmarks by mutableStateOf<List<PageBookmark>>(emptyList())
        private set
    var sessions by mutableStateOf<List<ReadingSession>>(emptyList())
        private set

    private val saver = Debouncer(1000)

    fun load() {
        progress = JsonFile.read("progress.json")?.let { runCatching { Codec.progressMapFromJson(JSONObject(it)) }.getOrNull() } ?: emptyMap()
        bookmarks = JsonFile.read("bookmarks.json")?.let { runCatching { Codec.listFromJson(JSONArray(it), Codec::bookmarkFromJson) }.getOrNull() } ?: emptyList()
        sessions = JsonFile.read("sessions.json")?.let { runCatching { Codec.listFromJson(JSONArray(it), Codec::sessionFromJson) }.getOrNull() } ?: emptyList()
    }

    fun progress(path: String): ReadingProgress? = progress[path]

    private fun put(item: ReadingProgress) {
        progress = progress + (item.relativePath to item)
    }

    fun updatePage(path: String, page: Int, totalPages: Int) {
        val now = nowMs()
        var item = progress[path] ?: ReadingProgress.new(path, totalPages)
        item = item.copy(
            firstOpenedAt = item.firstOpenedAt ?: now,
            currentPage = page, totalPages = totalPages, lastReadAt = now, modifiedAt = now,
        )
        if (totalPages > 0 && page >= totalPages - 1 && !item.isCompleted) {
            item = item.copy(isCompleted = true, finishedAt = now)
        }
        put(item)
        scheduleSave()
    }

    fun beginSession(path: String, totalPages: Int) {
        val now = nowMs()
        val item = progress[path] ?: ReadingProgress.new(path, totalPages)
        put(item.copy(firstOpenedAt = item.firstOpenedAt ?: now, sessionCount = item.sessionCount + 1, lastReadAt = now, modifiedAt = now))
        scheduleSave()
    }

    /** [durationSec] in secondi; sotto i 5 secondi non conta. */
    fun endSession(path: String, title: String, startedAt: Double, durationSec: Double, pagesRead: Int) {
        if (durationSec < 5) return
        progress[path]?.let { put(it.copy(totalReadingTime = it.totalReadingTime + durationSec, modifiedAt = nowMs())) }
        val list = ArrayList<ReadingSession>(sessions.size + 1)
        list.add(ReadingSession(newUuid(), path, title, startedAt, durationSec, pagesRead, com.cubo.lumetto.sync.SyncManager.deviceId))
        list.addAll(sessions)
        sessions = if (list.size > 2000) list.subList(0, 2000).toList() else list
        scheduleSave()
    }

    fun setCompleted(completed: Boolean, path: String, totalPages: Int? = null) {
        var item = progress[path] ?: ReadingProgress.new(path, totalPages ?: 0)
        item = item.copy(isCompleted = completed, finishedAt = if (completed) (item.finishedAt ?: nowMs()) else null)
        if (!completed && item.totalPages > 0 && item.currentPage >= item.totalPages - 1) item = item.copy(currentPage = 0)
        put(item.copy(modifiedAt = nowMs()))
        scheduleSave()
    }

    fun resetProgress(path: String) {
        TombstoneStore.mark(TombKind.PROGRESS, path)
        TombstoneStore.mark(TombKind.BOOKMARKS, bookmarks.filter { it.relativePath == path }.map { it.id })
        progress = progress - path
        bookmarks = bookmarks.filter { it.relativePath != path }
        scheduleSave()
    }

    fun resetAll() {
        TombstoneStore.mark(TombKind.PROGRESS, progress.keys.toList())
        TombstoneStore.mark(TombKind.BOOKMARKS, bookmarks.map { it.id })
        TombstoneStore.mark(TombKind.SESSIONS, sessions.map { it.id })
        progress = emptyMap()
        bookmarks = emptyList()
        sessions = emptyList()
        scheduleSave()
    }

    // --- segnalibri ---

    /** Uno per pagina (dopo una sincronizzazione la stessa pagina può averne due). */
    fun bookmarks(path: String): List<PageBookmark> {
        val seen = HashSet<Int>()
        return bookmarks.filter { it.relativePath == path }
            .sortedWith(compareBy<PageBookmark>({ it.pageIndex }, { it.createdAt }))
            .filter { seen.add(it.pageIndex) }
    }

    fun isBookmarked(path: String, page: Int) = bookmarks.any { it.relativePath == path && it.pageIndex == page }

    fun hasBookmarks(path: String) = bookmarks.any { it.relativePath == path }

    fun toggleBookmark(path: String, page: Int) {
        val existing = bookmarks.filter { it.relativePath == path && it.pageIndex == page }
        if (existing.isNotEmpty()) {
            TombstoneStore.mark(TombKind.BOOKMARKS, existing.map { it.id })
            bookmarks = bookmarks.filter { !(it.relativePath == path && it.pageIndex == page) }
        } else {
            bookmarks = bookmarks + PageBookmark(newUuid(), path, page, nowMs())
        }
        scheduleSave()
    }

    fun removeBookmark(b: PageBookmark) {
        val samePage = bookmarks.filter { it.relativePath == b.relativePath && it.pageIndex == b.pageIndex }
        TombstoneStore.mark(TombKind.BOOKMARKS, samePage.map { it.id } + b.id)
        bookmarks = bookmarks.filter { it.id != b.id && !(it.relativePath == b.relativePath && it.pageIndex == b.pageIndex) }
        scheduleSave()
    }

    // --- statistiche ---

    val completedCount get() = progress.values.count { it.isCompleted }
    val totalReadingTime get() = progress.values.sumOf { it.totalReadingTime }
    val totalPagesRead get() = sessions.sumOf { it.pagesRead }

    // --- salvataggio ---

    private fun scheduleSave() {
        saver.schedule { saveNow() }
        LocalChanges.notifyChanged()
    }

    fun flush() = saver.flush()

    /** Dati arrivati dalla sincronizzazione: si salvano subito, senza segnalare una modifica locale. */
    fun applySynced(progress: Map<String, ReadingProgress>, bookmarks: List<PageBookmark>, sessions: List<ReadingSession>) {
        saver.flush()
        if (this.progress != progress) this.progress = progress
        if (this.bookmarks != bookmarks) this.bookmarks = bookmarks
        if (this.sessions != sessions) this.sessions = sessions
        saveNow()
    }

    private fun saveNow() {
        JsonFile.write("progress.json", Codec.progressMapToJson(progress).toString())
        JsonFile.write("bookmarks.json", Codec.listToJson(bookmarks, Codec::bookmarkToJson).toString())
        JsonFile.write("sessions.json", Codec.listToJson(sessions, Codec::sessionToJson).toString())
    }
}

// ---------------------------------------------------------------------------------------------
// Metadati (letti dai file + modifiche manuali)

object MetadataStore {
    var parsed by mutableStateOf<Map<String, ComicMetadata>>(emptyMap())
        private set
    var edits by mutableStateOf<Map<String, ComicMetadata>>(emptyMap())
        private set
    var editStamps: Map<String, Double> = emptyMap()
        private set
    private var stamps: Map<String, Long> = emptyMap()
    private val saver = Debouncer(2000)

    fun load() {
        JsonFile.read("metadata-cache.json")?.let { text ->
            runCatching {
                val o = JSONObject(text)
                val p = HashMap<String, ComicMetadata>()
                val s = HashMap<String, Long>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val entry = o.optJSONObject(k) ?: continue
                    p[k] = Codec.metadataFromJson(entry.optJSONObject("metadata"))
                    s[k] = entry.optLong("stamp")
                }
                parsed = p
                stamps = s
            }
        }
        JsonFile.read("metadata-edits.json")?.let { text ->
            runCatching {
                val o = JSONObject(text)
                val e = HashMap<String, ComicMetadata>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    e[k] = Codec.metadataFromJson(o.optJSONObject(k))
                }
                edits = e
            }
        }
        editStamps = JsonFile.read("metadata-edit-stamps.json")?.let { runCatching { Codec.stampMapFromJson(JSONObject(it)) }.getOrNull() } ?: emptyMap()
    }

    fun metadata(comic: ComicFile): ComicMetadata = (parsed[comic.relativePath] ?: ComicMetadata.EMPTY).overlaid(edits[comic.relativePath])

    fun needsParsing(comic: ComicFile) = stamps[comic.relativePath] != comic.modificationStamp

    fun store(batch: List<Pair<ComicFile, ComicMetadata>>) {
        if (batch.isEmpty()) return
        val p = parsed.toMutableMap()
        val s = stamps.toMutableMap()
        for ((comic, value) in batch) {
            p[comic.relativePath] = value
            s[comic.relativePath] = comic.modificationStamp
        }
        parsed = p
        stamps = s
        scheduleSave()
    }

    fun saveEdit(metadata: ComicMetadata, comic: ComicFile) {
        edits = edits + (comic.relativePath to metadata)
        editStamps = editStamps + (comic.relativePath to nowMs())
        scheduleSave()
        LocalChanges.notifyChanged()
    }

    fun clearEdit(comic: ComicFile) {
        edits = edits - comic.relativePath
        editStamps = editStamps - comic.relativePath
        TombstoneStore.mark(TombKind.METADATA, comic.relativePath)
        scheduleSave()
        LocalChanges.notifyChanged()
    }

    fun applySynced(edits: Map<String, ComicMetadata>, stamps: Map<String, Double>) {
        if (this.edits != edits) this.edits = edits
        editStamps = stamps
        scheduleSave()
    }

    private fun scheduleSave() {
        saver.schedule {
            val cache = JSONObject()
            for ((path, value) in parsed) {
                cache.put(path, JSONObject().put("stamp", stamps[path] ?: 0L).put("metadata", Codec.metadataToJson(value)))
            }
            JsonFile.write("metadata-cache.json", cache.toString())
            val e = JSONObject()
            for ((path, value) in edits) e.put(path, Codec.metadataToJson(value))
            JsonFile.write("metadata-edits.json", e.toString())
            JsonFile.write("metadata-edit-stamps.json", Codec.stampMapToJson(editStamps).toString())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Evidenziazioni EPUB

object HighlightStore {
    var all by mutableStateOf<List<EpubHighlight>>(emptyList())
        private set

    fun load() {
        all = JsonFile.read("highlights.json")?.let { runCatching { Codec.listFromJson(JSONArray(it), Codec::highlightFromJson) }.getOrNull() } ?: emptyList()
    }

    fun highlights(path: String): List<EpubHighlight> =
        all.filter { it.path == path }.sortedWith(compareBy<EpubHighlight>({ it.chapter }, { it.start }))

    fun highlights(path: String, chapter: Int) = highlights(path).filter { it.chapter == chapter }

    /** Le evidenziazioni che si sovrappongono nello stesso capitolo vengono sostituite. */
    fun add(h: EpubHighlight) {
        val replaced = all.filter { it.path == h.path && it.chapter == h.chapter && it.start < h.end && h.start < it.end }
        TombstoneStore.mark(TombKind.HIGHLIGHTS, replaced.map { it.id })
        val ids = replaced.map { it.id }.toSet()
        all = all.filter { it.id !in ids } + h
        save()
    }

    fun remove(id: String) {
        TombstoneStore.mark(TombKind.HIGHLIGHTS, id)
        all = all.filter { it.id != id }
        save()
    }

    fun recolor(id: String, color: HighlightColor) {
        all = all.map { if (it.id == id) it.copy(color = color, modifiedAt = nowMs()) else it }
        save()
    }

    private fun save() {
        JsonFile.write("highlights.json", Codec.listToJson(all, Codec::highlightToJson).toString())
        LocalChanges.notifyChanged()
    }

    fun applySynced(list: List<EpubHighlight>) {
        if (all != list) all = list
        JsonFile.write("highlights.json", Codec.listToJson(all, Codec::highlightToJson).toString())
    }
}

// ---------------------------------------------------------------------------------------------
// Incognito

object IncognitoStore {
    var isActive by mutableStateOf(false)
        private set
    /** Percorso nascosto → momento in cui è stato nascosto (ms). */
    var hidden by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    val hiddenPaths: Set<String> get() = hidden.keys

    fun load() {
        hidden = JsonFile.read("hidden.json")?.let { runCatching { Codec.stampMapFromJson(JSONObject(it)) }.getOrNull() } ?: emptyMap()
    }

    fun isHidden(path: String) = hidden.containsKey(path)

    fun toggleHidden(path: String) {
        hidden = if (hidden.containsKey(path)) {
            TombstoneStore.mark(TombKind.HIDDEN, path)
            hidden - path
        } else {
            hidden + (path to nowMs())
        }
        persist()
        LocalChanges.notifyChanged()
    }

    fun applySynced(stamps: Map<String, Double>) {
        if (stamps != hidden) hidden = stamps
        persist()
    }

    private fun persist() = JsonFile.write("hidden.json", Codec.stampMapToJson(hidden).toString())

    fun exit() { isActive = false }
    fun enter() { isActive = true }

    // --- PIN (salvato come impronta SHA-256, mai in chiaro) ---

    private fun hash(pin: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(("lumetto-pin:" + pin).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    val hasPin: Boolean get() = AppContext.prefs.getString("incognito.pinHash", null) != null

    fun setPin(pin: String) = AppContext.prefs.edit().putString("incognito.pinHash", hash(pin)).apply()

    fun clearPin() = AppContext.prefs.edit().remove("incognito.pinHash").apply()

    fun unlock(pin: String): Boolean {
        if (pin.isEmpty() || AppContext.prefs.getString("incognito.pinHash", null) != hash(pin)) return false
        isActive = true
        return true
    }
}
