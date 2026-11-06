package com.cubo.lumetto.sync

import com.cubo.lumetto.data.SyncTombstones
import com.cubo.lumetto.model.ReadingSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocollo v2 (SYNC_PROTOCOLLO.md §3, §4, §6, §7): messaggi, confronto delle librerie, percorsi sicuri. */
class SyncV2Test {
    @Test fun helloRoundTripAndFields() {
        val h = SyncHello(2, "id1", "Pixel", "6d32e55d455662cd", "android", "library")
        val o = h.toJson()
        assertEquals(2, o.getInt("v"))
        assertEquals("android", o.getString("platform"))
        assertEquals("library", o.getString("op"))
        assertEquals(h, SyncHello.fromJson(JSONObject(o.toString())))
        // Un saluto senza platform/op (v2 minimale) si legge comunque.
        val bare = SyncHello.fromJson(JSONObject("""{"v":2,"id":"x","name":"n","room":"r"}"""))
        assertNull(bare.op)
        assertEquals(SyncOp.SYNC, SyncOp.from(bare.op) ?: SyncOp.SYNC)
    }

    @Test fun libraryIndexReadsWhatTheIpadWrites() {
        val json = """{"deviceId":"d1","deviceName":"iPad","platform":"ios","createdAt":1762171200000,
            "files":[{"path":"Manga/One Piece/Vol 01.cbz","size":48213044,"modifiedAt":1762000000000.5},{"path":"a.pdf","size":10}]}"""
        val idx = LibraryIndex.fromJson(JSONObject(json))
        assertEquals("ios", idx.platform)
        assertEquals(2, idx.files.size)
        assertEquals(48213044L, idx.files[0].size)
        assertEquals(1762000000000.5, idx.files[0].modifiedAt!!, 0.0)
        assertNull(idx.files[1].modifiedAt)
        val again = LibraryIndex.fromJson(JSONObject(idx.toJson().toString()))
        assertEquals(idx, again)
    }

    @Test fun fileMessagesRoundTrip() {
        val req = FileRequest("Manga/Vol 01.cbz", 1048576)
        assertEquals(req, FileRequest.fromJson(JSONObject(req.toJson().toString())))
        val ok = FileHeader("a.cbz", 100, 40, null)
        assertEquals(ok, FileHeader.fromJson(JSONObject(ok.toJson().toString())))
        assertFalse(ok.toJson().has("error"))
        val bad = FileHeader.fromJson(JSONObject("""{"path":"a","size":0,"offset":0,"error":"non c'è"}"""))
        assertEquals("non c'è", bad.error)
        // "error": null (come lo scrive Swift) non è un errore.
        assertNull(FileHeader.fromJson(JSONObject("""{"path":"a","size":5,"offset":0,"error":null}""")).error)
    }

    @Test fun safePaths() {
        assertTrue(SyncFiles.isSafe("Manga/One Piece/Vol 01.cbz"))
        assertTrue(SyncFiles.isSafe("a.cbz"))
        assertFalse(SyncFiles.isSafe(""))
        assertFalse(SyncFiles.isSafe("/etc/passwd"))
        assertFalse(SyncFiles.isSafe("../x.cbz"))
        assertFalse(SyncFiles.isSafe("a/../../x.cbz"))
        assertFalse(SyncFiles.isSafe("a//b.cbz"))
        assertFalse(SyncFiles.isSafe("a/./b.cbz"))
        assertFalse(SyncFiles.isSafe("a\\b.cbz"))
        assertFalse(SyncFiles.isSafe("a/b\u0000.cbz"))
        assertFalse(SyncFiles.isSafe("x".repeat(1024)))
    }

    @Test fun libraryCompareStatuses() {
        val remote = listOf(
            LibraryEntry("uguale.cbz", 100, null),
            LibraryEntry("diverso.cbz", 200, null),
            LibraryEntry("manca/qui.cbz", 300, null),
        )
        val local = mapOf("uguale.cbz" to 100L, "diverso.cbz" to 201L, "solo-mio.cbz" to 5L)
        val rows = LibraryCompare.compare(remote, local, emptySet(), showHidden = false)
        fun status(p: String) = rows.first { it.path == p }.status
        assertEquals(LibraryStatus.SAME, status("uguale.cbz"))
        assertEquals(LibraryStatus.DIFFERENT, status("diverso.cbz"))
        assertEquals(LibraryStatus.MISSING_HERE, status("manca/qui.cbz"))
        assertEquals(LibraryStatus.ONLY_HERE, status("solo-mio.cbz"))
        assertEquals(1, LibraryCompare.count(rows, LibraryStatus.MISSING_HERE))
    }

    @Test fun hiddenFilesAndFoldersAreNotShownUnlessIncognitoIsOpen() {
        val remote = listOf(LibraryEntry("Segreti/a.cbz", 1, null), LibraryEntry("Segretissimo.cbz", 1, null), LibraryEntry("x.cbz", 1, null))
        val hidden = setOf("Segreti", "x.cbz")
        val rows = LibraryCompare.compare(remote, emptyMap(), hidden, showHidden = false)
        assertEquals(listOf("Segretissimo.cbz"), rows.map { it.path })
        assertEquals(3, LibraryCompare.compare(remote, emptyMap(), hidden, showHidden = true).size)
    }

    @Test fun rowsAreInNaturalOrder() {
        val remote = listOf(LibraryEntry("Vol 10.cbz", 1, null), LibraryEntry("Vol 2.cbz", 1, null))
        assertEquals(listOf("Vol 2.cbz", "Vol 10.cbz"), LibraryCompare.compare(remote, emptyMap(), emptySet(), false).map { it.path })
    }

    @Test fun chunkRoundTripWithRoomKey() {
        val key = SyncCrypto.deriveKey("fumetti123", "Casa Cubo ")
        val chunk = ByteArray(SyncFiles.CHUNK_SIZE) { (it % 251).toByte() }
        val sealed = SyncCrypto.seal(chunk, key)
        assertEquals(SyncFiles.CHUNK_SIZE + 12 + 16, sealed.size)
        assertTrue(chunk.contentEquals(SyncCrypto.open(sealed, key)))
    }

    @Test fun sessionKeepsDeviceIdThroughJson() {
        val s = ReadingSession("S1", "a.cbz", "a", 1.0, 2.0, 3, "dev1")
        val back = com.cubo.lumetto.data.Codec.sessionFromJson(JSONObject(com.cubo.lumetto.data.Codec.sessionToJson(s).toString()))
        assertEquals("dev1", back!!.deviceId)
        val old = com.cubo.lumetto.data.Codec.sessionFromJson(JSONObject("""{"id":"S2","relativePath":"a","title":"t","startedAt":1,"duration":2,"pagesRead":3}"""))
        assertNull(old!!.deviceId)
    }

    @Test fun devicesMergeKeepsMostRecentAndIsSymmetric() {
        fun payload(devs: Map<String, SyncDevice>) = SyncPayload(
            2, "x", "x", 0.0, emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), SyncTombstones(), devs,
        )
        val a = payload(mapOf("d1" to SyncDevice("iPad", "ios", 100.0), "d2" to SyncDevice("Vecchio nome", "android", 50.0)))
        val b = payload(mapOf("d2" to SyncDevice("Pixel", "android", 200.0), "d3" to SyncDevice("Mac", "ios", 10.0)))
        val ab = a.merged(b, 1_800_000_000_000.0).devices
        val ba = b.merged(a, 1_800_000_000_000.0).devices
        assertEquals(ab, ba)
        assertEquals("Pixel", ab["d2"]!!.name)
        assertEquals(3, ab.size)
        // Parità di lastSeen: vince il nome maggiore.
        val c = payload(mapOf("d9" to SyncDevice("A", "ios", 5.0)))
        val d = payload(mapOf("d9" to SyncDevice("B", "ios", 5.0)))
        assertEquals("B", c.merged(d, 1.8e12).devices["d9"]!!.name)
        assertEquals("B", d.merged(c, 1.8e12).devices["d9"]!!.name)
    }
}
