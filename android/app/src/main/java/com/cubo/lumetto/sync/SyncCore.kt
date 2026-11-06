package com.cubo.lumetto.sync

import com.cubo.lumetto.data.Codec
import com.cubo.lumetto.data.Codec.optDoubleOrNull
import com.cubo.lumetto.data.SyncTombstones
import com.cubo.lumetto.data.TombKind
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.EpubHighlight
import com.cubo.lumetto.model.PageBookmark
import com.cubo.lumetto.model.ReadingProgress
import com.cubo.lumetto.model.ReadingSession
import com.cubo.lumetto.model.nowMs
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifratura della stanza (SYNC_PROTOCOLLO.md §1), identica all'iPad.
 * - Chiave: PBKDF2-HMAC-SHA256, 120.000 iterazioni, sale "lumetto-sync-v1:" + nome normalizzato.
 * - Pacchetti: AES-256-GCM, nonce 12 byte ‖ testo cifrato ‖ tag 16 byte, AAD "lumetto-sync-v1".
 * - Etichetta pubblica: primi 8 byte di SHA-256("lumetto-stanza-v1:" + nome), in esadecimale.
 */
object SyncCrypto {
    const val ITERATIONS = 120_000
    private val AAD = "lumetto-sync-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun normalizedRoom(name: String): String = name.trim().lowercase(Locale.ROOT)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun roomTag(room: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("lumetto-stanza-v1:${normalizedRoom(room)}".toByteArray(Charsets.UTF_8))
        return hex(digest.copyOf(8))
    }

    /** Lenta di proposito: da chiamare fuori dal thread principale. */
    fun deriveKey(password: String, room: String): ByteArray {
        val salt = "lumetto-sync-v1:${normalizedRoom(room)}".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun seal(plain: ByteArray, key: ByteArray, nonce: ByteArray? = null): ByteArray {
        val iv = nonce ?: ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return iv + cipher.doFinal(plain)
    }

    fun open(data: ByteArray, key: ByteArray): ByteArray {
        try {
            if (data.size < 28) throw SyncException(SyncError.WRONG_PASSWORD)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
            cipher.updateAAD(AAD)
            return cipher.doFinal(data, 12, data.size - 12)
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncError.WRONG_PASSWORD)
        }
    }
}

enum class SyncError(val message: String) {
    ENCRYPTION("Non è stato possibile cifrare i dati."),
    WRONG_PASSWORD("L'altro dispositivo usa una password diversa per questa stanza."),
    DIFFERENT_ROOM("L'altro dispositivo è in un'altra stanza."),
    INCOMPATIBLE_VERSION("L'altro dispositivo ha una versione di Lumetto non compatibile: aggiornala."),
    CONNECTION_CLOSED("La connessione si è interrotta."),
    TIMEOUT("L'altro dispositivo non ha risposto in tempo."),
    INVALID_DATA("Dati ricevuti non validi."),
}

class SyncException(val error: SyncError) : Exception(error.message)

/** Metadati modificati a mano, con il momento della modifica. */
data class StampedMetadata(val metadata: ComicMetadata, val modifiedAt: Double)

/**
 * Tutto ciò che si sincronizza (§4). Le chiavi sono i percorsi relativi alla cartella dei fumetti.
 * Non si sincronizzano: impostazioni, PIN, sfondo, cartella scelta e i fumetti stessi.
 */
/** Un dispositivo conosciuto della stanza (per dare un nome alle statistiche di ciascuno). */
data class SyncDevice(val name: String, val platform: String, val lastSeen: Double)

data class SyncPayload(
    val format: Int = 2,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Double,
    val progress: Map<String, ReadingProgress>,
    val bookmarks: List<PageBookmark>,
    val sessions: List<ReadingSession>,
    val highlights: List<EpubHighlight>,
    val metadataEdits: Map<String, StampedMetadata>,
    val hidden: Map<String, Double>,
    val tombstones: SyncTombstones,
    /** Dispositivi della stanza: id → nome, piattaforma, ultima volta visto (v2). */
    val devices: Map<String, SyncDevice> = emptyMap(),
) {
    /** Il contenuto senza il mittente: serve a capire se l'unione ha cambiato qualcosa. */
    fun sameContent(o: SyncPayload): Boolean =
        progress == o.progress && bookmarks == o.bookmarks && sessions == o.sessions &&
            highlights == o.highlights && metadataEdits == o.metadataEdits && hidden == o.hidden &&
            tombstones.sameContent(o.tombstones) && devices == o.devices

    /**
     * Unisce i dati di un altro dispositivo con questi (§5). Il risultato è lo stesso da qualunque
     * parte lo si calcoli, così i dispositivi finiscono identici. Stessa logica dell'iPad.
     */
    fun merged(other: SyncPayload, now: Double = nowMs()): SyncPayload {
        val tomb = tombstones.merged(other.tombstones).pruned(now - 365.0 * 24 * 3600 * 1000)

        // Progressi: vince la versione modificata più di recente; a parità quella completata, poi più avanti.
        val progressCmp = compareBy<ReadingProgress>({ it.syncStamp }, { if (it.isCompleted) 1 else 0 }, { it.currentPage })
        val progress = LinkedHashMap<String, ReadingProgress>()
        for (key in (this.progress.keys + other.progress.keys)) {
            val best = listOfNotNull(this.progress[key], other.progress[key]).maxWithOrNull(progressCmp) ?: continue
            val deleted = tomb[TombKind.PROGRESS][key]
            if (deleted != null && deleted >= best.syncStamp) continue
            progress[key] = best
        }

        // Segnalibri: unione per id, senza quelli eliminati. Due id sulla stessa pagina restano entrambi.
        val bookmarksById = LinkedHashMap<String, PageBookmark>()
        for (b in this.bookmarks + other.bookmarks) {
            if (tomb[TombKind.BOOKMARKS].containsKey(b.id)) continue
            bookmarksById[b.id] = b
        }
        val bookmarks = bookmarksById.values.sortedWith(compareBy<PageBookmark>({ it.createdAt }, { it.id }))

        // Sessioni: unione per id, le più recenti prima, al massimo 2000.
        val sessionsById = LinkedHashMap<String, ReadingSession>()
        for (s in this.sessions + other.sessions) {
            if (tomb[TombKind.SESSIONS].containsKey(s.id)) continue
            sessionsById[s.id] = s
        }
        val sessions = sessionsById.values
            .sortedWith(compareByDescending<ReadingSession> { it.startedAt }.thenByDescending { it.id })
            .take(MAX_SESSIONS)

        // Evidenziazioni: unione per id; con due versioni vince l'ultimo cambio di colore.
        val highlightsById = LinkedHashMap<String, EpubHighlight>()
        for (h in this.highlights + other.highlights) {
            val existing = highlightsById[h.id]
            if (existing != null) {
                val keep = existing.syncStamp > h.syncStamp ||
                    (existing.syncStamp == h.syncStamp && existing.color.raw >= h.color.raw)
                if (keep) continue
            }
            highlightsById[h.id] = h
        }
        val highlights = highlightsById.values
            .filter { h -> val d = tomb[TombKind.HIGHLIGHTS][h.id]; d == null || d < h.syncStamp }
            .sortedWith(compareBy<EpubHighlight>({ it.path }, { it.chapter }, { it.start }, { it.id }))

        // Metadati modificati a mano: vince la modifica più recente.
        val metadataEdits = LinkedHashMap<String, StampedMetadata>()
        for (key in (this.metadataEdits.keys + other.metadataEdits.keys)) {
            val best = listOfNotNull(this.metadataEdits[key], other.metadataEdits[key]).maxByOrNull { it.modifiedAt } ?: continue
            val deleted = tomb[TombKind.METADATA][key]
            if (deleted != null && deleted >= best.modifiedAt) continue
            metadataEdits[key] = best
        }

        // Nascosti in Incognito: nascosto se nascosto dopo l'ultima volta che è stato mostrato.
        val hidden = LinkedHashMap<String, Double>()
        val allHidden = HashMap(this.hidden)
        for ((k, v) in other.hidden) allHidden[k] = maxOf(allHidden[k] ?: v, v)
        for ((k, v) in allHidden) {
            val shown = tomb[TombKind.HIDDEN][k]
            if (shown != null && shown >= v) continue
            hidden[k] = v
        }

        // Dispositivi: per ognuno vale la scheda vista più di recente (a parità, il nome maggiore).
        val devices = LinkedHashMap(this.devices)
        for ((id, d) in other.devices) {
            val existing = devices[id]
            if (existing != null && (existing.lastSeen > d.lastSeen || (existing.lastSeen == d.lastSeen && existing.name >= d.name))) continue
            devices[id] = d
        }

        return SyncPayload(2, deviceId, deviceName, now, progress, bookmarks, sessions, highlights, metadataEdits, hidden, tomb, devices)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("format", format)
        o.put("deviceId", deviceId)
        o.put("deviceName", deviceName)
        o.put("createdAt", createdAt)
        o.put("progress", Codec.progressMapToJson(progress))
        o.put("bookmarks", Codec.listToJson(bookmarks, Codec::bookmarkToJson))
        o.put("sessions", Codec.listToJson(sessions, Codec::sessionToJson))
        o.put("highlights", Codec.listToJson(highlights, Codec::highlightToJson))
        val edits = JSONObject()
        for ((k, v) in metadataEdits) edits.put(k, JSONObject().put("metadata", Codec.metadataToJson(v.metadata)).put("modifiedAt", v.modifiedAt))
        o.put("metadataEdits", edits)
        o.put("hidden", Codec.stampMapToJson(hidden))
        o.put("tombstones", tombstones.toJson())
        val dev = JSONObject()
        for ((id, d) in devices) dev.put(id, JSONObject().put("name", d.name).put("platform", d.platform).put("lastSeen", d.lastSeen))
        o.put("devices", dev)
        return o
    }

    companion object {
        const val MAX_SESSIONS = 2000

        fun fromJson(o: JSONObject): SyncPayload {
            val edits = HashMap<String, StampedMetadata>()
            o.optJSONObject("metadataEdits")?.let { e ->
                val keys = e.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val entry = e.optJSONObject(k) ?: continue
                    edits[k] = StampedMetadata(Codec.metadataFromJson(entry.optJSONObject("metadata")), entry.optDoubleOrNull("modifiedAt") ?: 0.0)
                }
            }
            val devices = HashMap<String, SyncDevice>()
            o.optJSONObject("devices")?.let { d ->
                val keys = d.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val entry = d.optJSONObject(id) ?: continue
                    devices[id] = SyncDevice(entry.optString("name", "Dispositivo"), entry.optString("platform", ""), entry.optDoubleOrNull("lastSeen") ?: 0.0)
                }
            }
            return SyncPayload(
                devices = devices,
                format = o.optInt("format", 0),
                deviceId = o.optString("deviceId"),
                deviceName = o.optString("deviceName"),
                createdAt = o.optDoubleOrNull("createdAt") ?: 0.0,
                progress = Codec.progressMapFromJson(o.optJSONObject("progress")),
                bookmarks = Codec.listFromJson(o.optJSONArray("bookmarks"), Codec::bookmarkFromJson),
                sessions = Codec.listFromJson(o.optJSONArray("sessions"), Codec::sessionFromJson),
                highlights = Codec.listFromJson(o.optJSONArray("highlights"), Codec::highlightFromJson),
                metadataEdits = edits,
                hidden = Codec.stampMapFromJson(o.optJSONObject("hidden")),
                tombstones = SyncTombstones.fromJson(o.optJSONObject("tombstones")),
            )
        }
    }
}
