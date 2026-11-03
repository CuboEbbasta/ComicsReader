package com.cubo.lumetto.sync

import com.cubo.lumetto.data.SyncTombstones
import com.cubo.lumetto.data.TombKind
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.model.EpubHighlight
import com.cubo.lumetto.model.HighlightColor
import com.cubo.lumetto.model.PageBookmark
import com.cubo.lumetto.model.ReadingProgress
import com.cubo.lumetto.model.ReadingSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifica che Android parli esattamente il protocollo dell'iPad (SYNC_PROTOCOLLO.md). */
class SyncProtocolTest {

    // --- §1: vettori di prova ---

    @Test fun roomTagMatchesVector() {
        assertEquals("6d32e55d455662cd", SyncCrypto.roomTag("Casa Cubo "))
        assertEquals("casa cubo", SyncCrypto.normalizedRoom("Casa Cubo "))
    }

    @Test fun keyMatchesVector() {
        assertEquals("f121a0579a3103296253297cf3edfd1886635bbd3c23fa398f9fabcf7e3ea4c9", SyncCrypto.hex(SyncCrypto.deriveKey("fumetti123", "Casa Cubo ")))
    }

    @Test fun sealMatchesVectorAndOpens() {
        val key = SyncCrypto.deriveKey("fumetti123", "Casa Cubo ")
        val nonce = ByteArray(12) { it.toByte() }
        val sealed = SyncCrypto.seal("{\"format\":1}".toByteArray(), key, nonce)
        assertEquals("000102030405060708090a0bc631a856484602b71fa704ab6de32e9a0df6cbe4f820113dc0ad0562", SyncCrypto.hex(sealed))
        assertEquals("{\"format\":1}", String(SyncCrypto.open(sealed, key)))
    }

    @Test fun wrongPasswordFails() {
        val key = SyncCrypto.deriveKey("fumetti123", "casa")
        val other = SyncCrypto.deriveKey("altrapassword", "casa")
        val sealed = SyncCrypto.seal("ciao".toByteArray(), key)
        try {
            SyncCrypto.open(sealed, other)
            throw AssertionError("doveva fallire")
        } catch (e: SyncException) {
            assertEquals(SyncError.WRONG_PASSWORD, e.error)
        }
    }

    // --- §4: JSON come lo scrive l'iPad ---

    private val ipadJson = """
    {"format":1,"deviceId":"6f1c2b9e-0000-4000-8000-000000000001","deviceName":"iPad","createdAt":1762171200000.123,
     "progress":{"Manga/One Piece/Vol 01.cbz":{"relativePath":"Manga/One Piece/Vol 01.cbz","currentPage":12,"totalPages":180,
        "totalReadingTime":1520.5,"sessionCount":3,"firstOpenedAt":1762000000000,"lastReadAt":1762100000000.5,"isCompleted":false,"modifiedAt":1762100000000.5}},
     "bookmarks":[{"id":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F","relativePath":"Manga/One Piece/Vol 01.cbz","pageIndex":4,"createdAt":1762000000000}],
     "sessions":[{"id":"A621E1F8-C36C-495A-93FC-0C247A3E6E5F","relativePath":"Manga/One Piece/Vol 01.cbz","title":"Vol 01","startedAt":1762000000000,"duration":600,"pagesRead":20}],
     "highlights":[{"id":"B621E1F8-C36C-495A-93FC-0C247A3E6E5F","path":"Libri/a.epub","chapter":2,"start":100,"end":140,"text":"ciao","color":"green","createdAt":1762000000000}],
     "metadataEdits":{"Manga/x.cbz":{"metadata":{"title":"X","year":2001,"manga":true},"modifiedAt":1762000000000}},
     "hidden":{"Segreti":1762000000000},
     "tombstones":{"progress":{},"bookmarks":{"D621E1F8-C36C-495A-93FC-0C247A3E6E5F":1762000000000},"sessions":{},"highlights":{},"metadata":{},"hidden":{}}}
    """.trimIndent()

    @Test fun readsIpadPayloadAndRoundTrips() {
        val p = SyncPayload.fromJson(JSONObject(ipadJson))
        assertEquals(1, p.format)
        val prog = p.progress["Manga/One Piece/Vol 01.cbz"]!!
        assertEquals(12, prog.currentPage)
        assertEquals(1520.5, prog.totalReadingTime, 0.0)
        assertEquals(1762100000000.5, prog.modifiedAt!!, 0.0)
        assertNull(prog.finishedAt)
        assertEquals("E621E1F8-C36C-495A-93FC-0C247A3E6E5F", p.bookmarks[0].id)
        assertEquals(HighlightColor.GREEN, p.highlights[0].color)
        assertEquals(true, p.metadataEdits["Manga/x.cbz"]!!.metadata.manga)
        assertEquals(2001, p.metadataEdits["Manga/x.cbz"]!!.metadata.year)
        assertTrue(p.tombstones[TombKind.BOOKMARKS].containsKey("D621E1F8-C36C-495A-93FC-0C247A3E6E5F"))

        val again = SyncPayload.fromJson(JSONObject(p.toJson().toString()))
        assertTrue(p.sameContent(again))
        // Campi facoltativi assenti non vengono scritti; i campi obbligatori dell'iPad ci sono tutti.
        val out = p.toJson()
        val progOut = out.getJSONObject("progress").getJSONObject("Manga/One Piece/Vol 01.cbz")
        assertFalse(progOut.has("finishedAt"))
        for (k in listOf("relativePath", "currentPage", "totalPages", "totalReadingTime", "sessionCount", "isCompleted")) assertTrue(k, progOut.has(k))
        for (k in listOf("progress", "bookmarks", "sessions", "highlights", "metadata", "hidden")) assertTrue(k, out.getJSONObject("tombstones").has(k))
    }

    // --- §5: regole di unione ---

    private fun payload(id: String, block: Builder.() -> Unit): SyncPayload = Builder(id).apply(block).build()

    private class Builder(val id: String) {
        val progress = HashMap<String, ReadingProgress>()
        val bookmarks = ArrayList<PageBookmark>()
        val sessions = ArrayList<ReadingSession>()
        val highlights = ArrayList<EpubHighlight>()
        val edits = HashMap<String, StampedMetadata>()
        val hidden = HashMap<String, Double>()
        var tomb = SyncTombstones()
        fun build() = SyncPayload(1, id, id, 0.0, progress, bookmarks, sessions, highlights, edits, hidden, tomb)
    }

    private val now = 1_800_000_000_000.0

    private fun prog(path: String, page: Int, stamp: Double, done: Boolean = false) =
        ReadingProgress(path, page, 100, 10.0, 1, stamp - 1000, stamp, null, done, stamp)

    private fun devices(): List<SyncPayload> = listOf(
        payload("a") {
            progress["x.cbz"] = prog("x.cbz", 10, now - 5000)
            progress["y.cbz"] = prog("y.cbz", 3, now - 9000)
            bookmarks += PageBookmark("AAAA0000-0000-4000-8000-000000000001", "x.cbz", 4, now - 7000)
            sessions += ReadingSession("SSSS0000-0000-4000-8000-000000000001", "x.cbz", "x", now - 8000, 60.0, 3)
            highlights += EpubHighlight("HHHH0000-0000-4000-8000-000000000001", "b.epub", 1, 10, 20, "t", HighlightColor.YELLOW, now - 6000)
            hidden["segreto.cbz"] = now - 4000
        },
        payload("b") {
            progress["x.cbz"] = prog("x.cbz", 20, now - 3000)
            bookmarks += PageBookmark("BBBB0000-0000-4000-8000-000000000002", "x.cbz", 4, now - 6500)
            highlights += EpubHighlight("HHHH0000-0000-4000-8000-000000000001", "b.epub", 1, 10, 20, "t", HighlightColor.PINK, now - 6000, now - 2000)
            edits["x.cbz"] = StampedMetadata(ComicMetadata(title = "B"), now - 4000)
            tomb = tomb.with(TombKind.PROGRESS, listOf("y.cbz"), now - 1000)
        },
        payload("c") {
            progress["x.cbz"] = prog("x.cbz", 20, now - 3000, done = true)
            edits["x.cbz"] = StampedMetadata(ComicMetadata(title = "C"), now - 3500)
            tomb = tomb.with(TombKind.BOOKMARKS, listOf("AAAA0000-0000-4000-8000-000000000001"), now - 500)
                .with(TombKind.HIDDEN, listOf("segreto.cbz"), now - 4500)
        },
    )

    @Test fun mergeRules() {
        val (a, b, c) = devices()
        val m = a.merged(b, now).merged(c, now)
        assertEquals(20, m.progress["x.cbz"]!!.currentPage)
        assertTrue("a parità vince il completato", m.progress["x.cbz"]!!.isCompleted)
        assertNull("progresso con lapide più recente", m.progress["y.cbz"])
        assertEquals(listOf("BBBB0000-0000-4000-8000-000000000002"), m.bookmarks.map { it.id })
        assertEquals(HighlightColor.PINK, m.highlights.single().color)
        assertEquals("C", m.metadataEdits["x.cbz"]!!.metadata.title)
        assertTrue("nascosto dopo essere stato mostrato", m.hidden.containsKey("segreto.cbz"))
    }

    @Test fun mergeIsOrderIndependentWithThreeDevices() {
        val (a, b, c) = devices()
        val r1 = a.merged(b, now).merged(c, now)
        val r2 = c.merged(a, now).merged(b, now)
        val r3 = b.merged(c, now).merged(a, now)
        // Scambi a coppie come avvengono in rete: ognuno unisce con l'altro.
        val ab = a.merged(b, now); val ba = b.merged(a, now)
        val abc = ab.merged(c, now); val cab = c.merged(ab, now)
        val final = ba.merged(cab, now)
        assertTrue(r1.sameContent(r2))
        assertTrue(r1.sameContent(r3))
        assertTrue(r1.sameContent(abc))
        assertTrue(r1.sameContent(final))
    }

    @Test fun oldTombstonesAreForgotten() {
        val old = payload("a") { tomb = tomb.with(TombKind.PROGRESS, listOf("z"), now - 400.0 * 24 * 3600 * 1000) }
        val m = old.merged(payload("b") {}, now)
        assertTrue(m.tombstones[TombKind.PROGRESS].isEmpty())
    }
}
