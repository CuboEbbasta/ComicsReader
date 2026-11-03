package com.cubo.lumetto.archive

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.cubo.lumetto.data.FileAccess
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater

class ArchiveException(message: String) : IOException(message)

/** Archivio di immagini (CBZ, CBR, EPUB) visto dal lettore: elenco dei file ed estrazione. */
interface ComicArchive : Closeable {
    val filePaths: List<String>
    fun data(path: String): ByteArray
}

/** File aperto in sola lettura con accesso casuale, senza copiarlo. */
class RandomFile(val pfd: ParcelFileDescriptor) : Closeable {
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel: FileChannel = stream.channel
    val size: Long = channel.size()

    @Synchronized
    fun read(offset: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(maxOf(0, count))
        var pos = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, pos)
            if (n <= 0) break
            pos += n
        }
        return if (buffer.position() == count) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }

    companion object {
        fun open(uri: Uri) = RandomFile(FileAccess.openFd(uri))
    }
}

private fun u16(d: ByteArray, o: Int): Int = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
private fun u32(d: ByteArray, o: Int): Long =
    (d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
        ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24)

// ---------------------------------------------------------------------------------------------
// ZIP (CBZ ed EPUB)

class ZipEntry(
    val path: String,
    val isDirectory: Boolean,
    val localHeaderOffset: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val method: Int,
)

/** Lettore ZIP in sola lettura (memorizzato e deflate, con ZIP64), thread-safe. */
class ZipArchive(private val file: RandomFile) : ComicArchive {
    val entries: List<ZipEntry> = readCentralDirectory()
    private val index: Map<String, ZipEntry> = entries.associateBy { it.path }

    val fileEntries: List<ZipEntry> get() = entries.filter { !it.isDirectory }
    override val filePaths: List<String> get() = fileEntries.map { it.path }

    fun entry(path: String): ZipEntry? {
        index[path]?.let { return it }
        // Alcuni strumenti usano maiuscole/minuscole diverse da quelle referenziate.
        val lower = path.lowercase()
        return entries.firstOrNull { it.path.lowercase() == lower }
    }

    fun has(path: String) = entry(path) != null

    override fun data(path: String): ByteArray = data(entry(path) ?: throw ArchiveException("Elemento non trovato nell'archivio."))

    fun data(e: ZipEntry): ByteArray {
        val header = file.read(e.localHeaderOffset, 30)
        if (header.size < 30 || u32(header, 0) != 0x04034b50L) throw ArchiveException("Archivio danneggiato o non leggibile.")
        val start = e.localHeaderOffset + 30 + u16(header, 26) + u16(header, 28)
        if (e.compressedSize <= 0) return ByteArray(0)
        if (e.compressedSize > Int.MAX_VALUE || e.uncompressedSize > Int.MAX_VALUE) throw ArchiveException("Elemento troppo grande.")
        val compressed = file.read(start, e.compressedSize.toInt())
        return when (e.method) {
            0 -> compressed
            8 -> inflate(compressed, e.uncompressedSize.toInt())
            else -> throw ArchiveException("Metodo di compressione non supportato.")
        }
    }

    private fun inflate(input: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(if (expected > 0) expected else input.size * 2)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (e: Exception) {
            throw ArchiveException("Impossibile decomprimere i dati.")
        } finally {
            inflater.end()
        }
    }

    private fun readCentralDirectory(): List<ZipEntry> {
        val size = file.size
        if (size < 22) throw ArchiveException("Il file non è un archivio ZIP valido.")
        val window = minOf(size, 66_000L).toInt()
        val tailStart = size - window
        val tail = file.read(tailStart, window)
        var eocd = -1
        var i = tail.size - 22
        while (i >= 0) {
            if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
            i--
        }
        if (eocd < 0) throw ArchiveException("Il file non è un archivio ZIP valido.")
        var count = u16(tail, eocd + 10).toLong()
        var dirSize = u32(tail, eocd + 12)
        var dirOffset = u32(tail, eocd + 16)

        // ZIP64: il record "end of central directory" ha un localizzatore subito prima.
        if (dirOffset == 0xFFFFFFFFL || count == 0xFFFFL || dirSize == 0xFFFFFFFFL) {
            val loc = eocd - 20
            if (loc >= 0 && u32(tail, loc) == 0x07064b50L) {
                val z64Offset = u32(tail, loc + 8) or (u32(tail, loc + 12) shl 32)
                val rec = file.read(z64Offset, 56)
                if (rec.size >= 56 && u32(rec, 0) == 0x06064b50L) {
                    count = u32(rec, 32) or (u32(rec, 36) shl 32)
                    dirSize = u32(rec, 40) or (u32(rec, 44) shl 32)
                    dirOffset = u32(rec, 48) or (u32(rec, 52) shl 32)
                }
            }
        }
        if (dirSize > Int.MAX_VALUE) throw ArchiveException("Archivio troppo grande.")
        val dir = file.read(dirOffset, dirSize.toInt())
        val result = ArrayList<ZipEntry>()
        var c = 0
        var n = 0L
        while (n < count && c + 46 <= dir.size && u32(dir, c) == 0x02014b50L) {
            val flags = u16(dir, c + 8)
            val method = u16(dir, c + 10)
            var compressed = u32(dir, c + 20)
            var uncompressed = u32(dir, c + 24)
            val nameLen = u16(dir, c + 28)
            val extraLen = u16(dir, c + 30)
            val commentLen = u16(dir, c + 32)
            var localOffset = u32(dir, c + 42)
            if (c + 46 + nameLen > dir.size) break
            val nameBytes = dir.copyOfRange(c + 46, c + 46 + nameLen)
            val name = if (flags and 0x800 != 0) String(nameBytes, Charsets.UTF_8) else decodeName(nameBytes)
            // Campo extra ZIP64 (id 0x0001): dimensioni e posizione a 64 bit.
            var e = c + 46 + nameLen
            val extraEnd = minOf(e + extraLen, dir.size)
            while (e + 4 <= extraEnd) {
                val id = u16(dir, e)
                val len = u16(dir, e + 2)
                if (id == 1) {
                    var p = e + 4
                    if (uncompressed == 0xFFFFFFFFL && p + 8 <= extraEnd) { uncompressed = u32(dir, p) or (u32(dir, p + 4) shl 32); p += 8 }
                    if (compressed == 0xFFFFFFFFL && p + 8 <= extraEnd) { compressed = u32(dir, p) or (u32(dir, p + 4) shl 32); p += 8 }
                    if (localOffset == 0xFFFFFFFFL && p + 8 <= extraEnd) { localOffset = u32(dir, p) or (u32(dir, p + 4) shl 32) }
                }
                e += 4 + len
            }
            result.add(ZipEntry(name, name.endsWith("/"), localOffset, compressed, uncompressed, method))
            c += 46 + nameLen + extraLen + commentLen
            n++
        }
        return result
    }

    private fun decodeName(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, charset("IBM437"))
        }
    }

    override fun close() = file.close()

    companion object {
        fun open(uri: Uri): ZipArchive {
            val f = RandomFile.open(uri)
            return try {
                ZipArchive(f)
            } catch (e: Exception) {
                f.close()
                throw e
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// RAR (CBR)

/**
 * RAR 5 e RAR 4 "memorizzati" (senza compressione, il caso di molti CBR): lettura diretta.
 * I RAR 4 compressi passano da junrar.
 */
class StoredRarArchive(private val file: RandomFile) : ComicArchive {
    private class Entry(val offset: Long, val size: Long, val stored: Boolean)

    private val entries = LinkedHashMap<String, Entry>()
    override val filePaths: List<String>

    init {
        val sig = file.read(0, 8)
        if (sig.size < 7) throw ArchiveException("Archivio RAR non valido o danneggiato.")
        val isRar5 = sig.size == 8 && sig[6].toInt() == 0x01
        val list = if (isRar5) readRar5() else readRar4()
        for ((path, entry) in list) if (!entries.containsKey(path)) entries[path] = entry
        filePaths = entries.keys.toList()
    }

    val hasStoredFiles: Boolean get() = entries.values.any { it.stored }
    val allStored: Boolean get() = entries.values.all { it.stored }

    override fun data(path: String): ByteArray {
        val e = entries[path] ?: throw ArchiveException("Elemento non trovato nell'archivio.")
        if (!e.stored) throw ArchiveException(COMPRESSED_MESSAGE)
        return file.read(e.offset, e.size.toInt())
    }

    private fun readRar4(): List<Pair<String, Entry>> {
        val result = ArrayList<Pair<String, Entry>>()
        var pos = 7L
        while (pos + 7 <= file.size) {
            val base = file.read(pos, 7)
            if (base.size < 7) break
            val type = base[2].toInt() and 0xFF
            val flags = u16(base, 3)
            val headSize = u16(base, 5).toLong()
            if (headSize < 7) throw ArchiveException("Archivio RAR non valido o danneggiato.")
            var addSize = 0L
            if (type == 0x74) {
                val h = file.read(pos, headSize.toInt())
                if (h.size < 32) throw ArchiveException("Archivio RAR non valido o danneggiato.")
                var packed = u32(h, 7)
                val method = h[25].toInt() and 0xFF
                val nameSize = u16(h, 26)
                var nameOffset = 32
                if (flags and 0x100 != 0 && h.size >= 40) {
                    packed = packed or (u32(h, 32) shl 32)
                    nameOffset = 40
                }
                if (nameOffset + nameSize > h.size) throw ArchiveException("Archivio RAR non valido o danneggiato.")
                var nameBytes = h.copyOfRange(nameOffset, nameOffset + nameSize)
                if (flags and 0x200 != 0) {
                    val zero = nameBytes.indexOf(0)
                    if (zero >= 0) nameBytes = nameBytes.copyOf(zero)
                }
                val name = String(nameBytes, Charsets.UTF_8).replace('\\', '/')
                val isDir = flags and 0xE0 == 0xE0
                if (flags and 0x04 != 0) throw ArchiveException(ENCRYPTED_MESSAGE)
                if (!isDir) result.add(name to Entry(pos + headSize, packed, method == 0x30))
                addSize = packed
            } else if (flags and 0x8000 != 0) {
                val extra = file.read(pos + 7, 4)
                if (extra.size == 4) addSize = u32(extra, 0)
            }
            if (type == 0x7B) break
            pos += headSize + addSize
        }
        return result
    }

    private fun readRar5(): List<Pair<String, Entry>> {
        val result = ArrayList<Pair<String, Entry>>()
        var pos = 8L
        while (pos + 6 < file.size) {
            val prefix = file.read(pos + 4, 4)
            val cur = intArrayOf(0)
            val headerSize = vint(prefix, cur) ?: throw ArchiveException("Archivio RAR non valido o danneggiato.")
            if (headerSize <= 0 || headerSize > 2_000_000) throw ArchiveException("Archivio RAR non valido o danneggiato.")
            val headerStart = pos + 4 + cur[0]
            val h = file.read(headerStart, headerSize.toInt())
            if (h.size != headerSize.toInt()) throw ArchiveException("Archivio RAR non valido o danneggiato.")
            val c = intArrayOf(0)
            val type = vint(h, c) ?: throw ArchiveException("Archivio RAR non valido o danneggiato.")
            val flags = vint(h, c) ?: throw ArchiveException("Archivio RAR non valido o danneggiato.")
            if (flags and 0x1L != 0L) vint(h, c)
            var dataSize = 0L
            if (flags and 0x2L != 0L) dataSize = vint(h, c) ?: 0L
            val dataStart = headerStart + headerSize
            when (type) {
                2L -> {
                    val fileFlags = vint(h, c) ?: throw ArchiveException("Archivio RAR non valido o danneggiato.")
                    vint(h, c); vint(h, c)
                    if (fileFlags and 0x2L != 0L) c[0] += 4
                    if (fileFlags and 0x4L != 0L) c[0] += 4
                    val compression = vint(h, c) ?: throw ArchiveException("Archivio RAR non valido o danneggiato.")
                    vint(h, c)
                    val nameLen = (vint(h, c) ?: 0L).toInt()
                    if (c[0] + nameLen > h.size) throw ArchiveException("Archivio RAR non valido o danneggiato.")
                    val name = String(h, c[0], nameLen, Charsets.UTF_8)
                    val method = (compression shr 7) and 0x7L
                    if (fileFlags and 0x1L == 0L) result.add(name to Entry(dataStart, dataSize, method == 0L))
                }
                4L -> throw ArchiveException(ENCRYPTED_MESSAGE)
                5L -> return result
                else -> {}
            }
            pos = dataStart + dataSize
        }
        return result
    }

    private fun vint(d: ByteArray, cursor: IntArray): Long? {
        var value = 0L
        var shift = 0
        while (cursor[0] < d.size && shift < 64) {
            val b = d[cursor[0]].toInt() and 0xFF
            cursor[0]++
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
        return null
    }

    override fun close() = file.close()

    companion object {
        const val COMPRESSED_MESSAGE = "Questo CBR usa la compressione RAR 5, che Lumetto non sa decomprimere. Convertilo in CBZ e aggiorna la libreria."
        const val ENCRYPTED_MESSAGE = "Questo CBR è protetto da password."
    }
}

/** RAR 4 compressi con junrar (Java puro). Il file è letto tramite il suo descrittore, senza copiarlo. */
class JunrarArchive(private val pfd: ParcelFileDescriptor) : ComicArchive {
    private val archive = com.github.junrar.Archive(File("/proc/self/fd/" + pfd.fd))
    private val headers = archive.fileHeaders.filter { !it.isDirectory }
        .associateBy { it.fileName.replace('\\', '/') }
    override val filePaths: List<String> = headers.keys.toList()

    @Synchronized
    override fun data(path: String): ByteArray {
        val h = headers[path] ?: throw ArchiveException("Elemento non trovato nell'archivio.")
        if (h.isEncrypted) throw ArchiveException(StoredRarArchive.ENCRYPTED_MESSAGE)
        val out = ByteArrayOutputStream(maxOf(1024, h.fullUnpackSize.toInt()))
        archive.extractFile(h, out)
        return out.toByteArray()
    }

    override fun close() {
        runCatching { archive.close() }
        runCatching { pfd.close() }
    }
}

/** Sceglie il lettore guardando i primi byte, non l'estensione (molti ".cbr" sono ZIP rinominati). */
object ArchiveOpener {
    fun open(uri: Uri): ComicArchive {
        val head = FileAccess.openFd(uri).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { s ->
                val b = ByteArray(8)
                val n = s.read(b)
                if (n < 0) ByteArray(0) else b.copyOf(n)
            }
        }
        val isRar = head.size >= 6 && head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
            head[2] == 0x72.toByte() && head[3] == 0x21.toByte() && head[4] == 0x1A.toByte() && head[5] == 0x07.toByte()
        if (!isRar) return ZipArchive.open(uri)

        val isRar5 = head.size >= 7 && head[6] == 0x01.toByte()
        val stored = RandomFile.open(uri).let { f ->
            try {
                StoredRarArchive(f)
            } catch (e: Exception) {
                f.close()
                throw e
            }
        }
        if (isRar5 || stored.allStored) return stored
        // RAR 4 compresso: junrar.
        stored.close()
        val pfd = FileAccess.openFd(uri)
        return try {
            JunrarArchive(pfd)
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            throw ArchiveException("Archivio RAR non leggibile: ${e.message ?: "errore sconosciuto"}")
        }
    }
}
