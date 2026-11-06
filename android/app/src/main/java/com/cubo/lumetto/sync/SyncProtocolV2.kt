package com.cubo.lumetto.sync

import com.cubo.lumetto.data.Codec.optDoubleOrNull
import com.cubo.lumetto.data.Codec.optStringOrNull
import com.cubo.lumetto.model.NaturalOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Messaggi del protocollo v2 (SYNC_PROTOCOLLO.md §3, §6, §7). Logica pura: si prova con i test.
 */

/** Primo frame di ogni connessione, in chiaro. */
data class SyncHello(val v: Int, val id: String, val name: String, val room: String, val platform: String?, val op: String?) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("v", v).put("id", id).put("name", name).put("room", room)
        platform?.let { o.put("platform", it) }
        op?.let { o.put("op", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = SyncHello(
            v = o.optInt("v", -1), id = o.optString("id"), name = o.optString("name"), room = o.optString("room"),
            platform = o.optStringOrNull("platform"), op = o.optStringOrNull("op"),
        )
    }
}

enum class SyncOp(val raw: String) {
    SYNC("sync"), LIBRARY("library"), FILE("file");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

data class LibraryEntry(val path: String, val size: Long, val modifiedAt: Double?) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("size", size).also { o -> modifiedAt?.let { o.put("modifiedAt", it) } }

    companion object {
        fun fromJson(o: JSONObject): LibraryEntry? {
            val path = o.optStringOrNull("path") ?: return null
            return LibraryEntry(path, o.optLong("size", 0), o.optDoubleOrNull("modifiedAt"))
        }
    }
}

/** Elenco dei fumetti di un dispositivo (percorsi relativi alla sua cartella principale). */
data class LibraryIndex(
    val deviceId: String, val deviceName: String, val platform: String, val createdAt: Double, val files: List<LibraryEntry>,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        files.forEach { arr.put(it.toJson()) }
        return JSONObject().put("deviceId", deviceId).put("deviceName", deviceName).put("platform", platform)
            .put("createdAt", createdAt).put("files", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): LibraryIndex {
            val list = ArrayList<LibraryEntry>()
            val arr = o.optJSONArray("files")
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { e -> LibraryEntry.fromJson(e)?.let(list::add) }
            return LibraryIndex(o.optString("deviceId"), o.optString("deviceName"), o.optString("platform"), o.optDoubleOrNull("createdAt") ?: 0.0, list)
        }
    }
}

data class FileRequest(val path: String, val offset: Long) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("offset", offset)

    companion object {
        fun fromJson(o: JSONObject) = FileRequest(o.optString("path"), o.optLong("offset", 0))
    }
}

data class FileHeader(val path: String, val size: Long, val offset: Long, val error: String?) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("size", size).put("offset", offset).also { o -> error?.let { o.put("error", it) } }

    companion object {
        fun fromJson(o: JSONObject) = FileHeader(o.optString("path"), o.optLong("size", 0), o.optLong("offset", 0), o.optStringOrNull("error"))
    }
}

object SyncFiles {
    const val CHUNK_SIZE = 1024 * 1024

    /** Percorso relativo accettabile: niente percorsi assoluti, niente "..", niente componenti vuote. */
    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.length >= 1024) return false
        if (path.any { it.code < 32 || it == '\\' }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

enum class LibraryStatus { SAME, DIFFERENT, MISSING_HERE, ONLY_HERE }

data class LibraryRow(val path: String, val status: LibraryStatus, val remote: LibraryEntry?, val localSize: Long?)

object LibraryCompare {
    /** Un percorso è nascosto se è uno dei nascosti o sta dentro una cartella nascosta. */
    fun isHidden(path: String, hidden: Set<String>): Boolean =
        hidden.any { path == it || path.startsWith("$it/") }

    /**
     * Confronta la libreria dell'altro dispositivo con la propria (stesso percorso e stessa dimensione = uguale).
     * I fumetti nascosti in Incognito non si mostrano, a meno che Incognito sia sbloccato ([showHidden]).
     * [local] = percorso → dimensione dei file propri.
     */
    fun compare(remote: List<LibraryEntry>, local: Map<String, Long>, hidden: Set<String>, showHidden: Boolean): List<LibraryRow> {
        val rows = ArrayList<LibraryRow>()
        val remotePaths = HashSet<String>()
        for (e in remote) {
            if (!showHidden && isHidden(e.path, hidden)) continue
            remotePaths.add(e.path)
            val mine = local[e.path]
            val status = when {
                mine == null -> LibraryStatus.MISSING_HERE
                mine == e.size -> LibraryStatus.SAME
                else -> LibraryStatus.DIFFERENT
            }
            rows.add(LibraryRow(e.path, status, e, mine))
        }
        for ((path, size) in local) {
            if (path !in remotePaths && (showHidden || !isHidden(path, hidden))) rows.add(LibraryRow(path, LibraryStatus.ONLY_HERE, null, size))
        }
        return rows.sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
    }

    fun count(rows: List<LibraryRow>, status: LibraryStatus) = rows.count { it.status == status }
}
