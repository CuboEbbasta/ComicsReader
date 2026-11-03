import Foundation
import Compression

/// Sostituto minimale di ZIPFoundation, scritto usando solo Foundation e il framework
/// Compression di Apple (incluso in ogni iPhone/iPad, nessuna installazione necessaria).
///
/// Perché esiste questo file: ZIPFoundation come dipendenza esterna ha causato tre problemi
/// diversi in sequenza nel montaggio del progetto su Swift Playgrounds (manifest del pacchetto,
/// modalità di concorrenza, infine la firma del bundle di risorse per la privacy) — un problema
/// via l'altro, non sfortuna isolata. Legge SOLO archivi ZIP (mai scrivere), senza cifratura,
/// un solo volume: esattamente quello che serve per aprire CBZ ed EPUB, non un lettore ZIP
/// generico completo. Il nome dei tipi (`Archive`, `Entry`) e il modo in cui si usano ricalca
/// apposta ZIPFoundation, così il resto del codice (i parser CBZ ed EPUB) non ha dovuto cambiare,
/// a parte togliere `import ZIPFoundation`.
struct Entry {
    enum EntryType {
        case file
        case directory
    }
    let path: String
    let type: EntryType
    fileprivate let localHeaderOffset: UInt32
    fileprivate let compressedSize: UInt32
    fileprivate let uncompressedSize: UInt32
    fileprivate let compressionMethod: UInt16
}

final class Archive: Sequence {
    enum AccessMode {
        case read
    }

    private let fileHandle: FileHandle
    private let entries: [Entry]

    init(url: URL, accessMode: AccessMode) throws {
        self.fileHandle = try FileHandle(forReadingFrom: url)
        self.entries = try Self.readCentralDirectory(from: fileHandle)
    }

    deinit {
        try? fileHandle.close()
    }

    subscript(path: String) -> Entry? {
        entries.first { $0.path == path }
    }

    func makeIterator() -> IndexingIterator<[Entry]> {
        entries.makeIterator()
    }

    /// Stessa firma di ZIPFoundation (consumer riceve i dati, il chiamante li accumula):
    /// qui il consumer viene chiamato una sola volta con tutti i dati già pronti, invece che
    /// a blocchi, ma per chi chiama (che fa solo `data.append(chunk)`) il risultato è identico.
    @discardableResult
    func extract(_ entry: Entry, consumer: (Data) throws -> Void) throws -> UInt32 {
        let extracted = try data(for: entry)
        try consumer(extracted)
        return 0
    }

    private func data(for entry: Entry) throws -> Data {
        try fileHandle.seek(toOffset: UInt64(entry.localHeaderOffset))
        guard let localHeader = try fileHandle.read(upToCount: 30), localHeader.count == 30 else {
            throw MinimalZipError.corruptArchive
        }
        let filenameLength = Int(Self.readUInt16(localHeader, at: 26))
        let extraLength = Int(Self.readUInt16(localHeader, at: 28))
        let dataOffset = UInt64(entry.localHeaderOffset) + 30 + UInt64(filenameLength) + UInt64(extraLength)
        try fileHandle.seek(toOffset: dataOffset)

        guard entry.compressedSize > 0 else { return Data() }
        guard let compressedData = try fileHandle.read(upToCount: Int(entry.compressedSize)),
              compressedData.count == Int(entry.compressedSize) else {
            throw MinimalZipError.corruptArchive
        }

        switch entry.compressionMethod {
        case 0: // memorizzato, nessuna compressione
            return compressedData
        case 8: // deflate (il metodo usato dalla stragrande maggioranza dei CBZ/EPUB)
            guard let decompressed = Self.inflate(compressedData, expectedSize: Int(entry.uncompressedSize)) else {
                throw MinimalZipError.decompressionFailed
            }
            return decompressed
        default:
            throw MinimalZipError.unsupportedCompression
        }
    }

    // MARK: - Central directory (l'indice dell'archivio: elenco file, dove si trovano)

    private static func readCentralDirectory(from handle: FileHandle) throws -> [Entry] {
        let fileSize = try handle.seekToEnd()
        guard fileSize >= 22 else { throw MinimalZipError.notAZipFile }

        // L'End Of Central Directory è di norma negli ultimi 22 byte, ma può esserci prima
        // un commento (fino a 65535 byte): cerchiamo la firma partendo dal fondo del file.
        let searchWindowSize = Swift.min(fileSize, 66_000)
        try handle.seek(toOffset: fileSize - searchWindowSize)
        guard let tail = try handle.read(upToCount: Int(searchWindowSize)) else {
            throw MinimalZipError.corruptArchive
        }
        guard let eocdOffset = findSignatureFromEnd(0x06054b50, in: tail) else {
            throw MinimalZipError.notAZipFile
        }
        guard eocdOffset + 22 <= tail.count else { throw MinimalZipError.corruptArchive }
        let eocd = tail.subdata(in: eocdOffset..<(eocdOffset + 22))

        let totalEntries = Int(readUInt16(eocd, at: 10))
        let centralDirectorySize = UInt64(readUInt32(eocd, at: 12))
        let centralDirectoryOffset = UInt64(readUInt32(eocd, at: 16))

        try handle.seek(toOffset: centralDirectoryOffset)
        guard let directoryData = try handle.read(upToCount: Int(centralDirectorySize)),
              directoryData.count == Int(centralDirectorySize) else {
            throw MinimalZipError.corruptArchive
        }

        var entries: [Entry] = []
        var cursor = 0
        for _ in 0..<totalEntries {
            guard cursor + 46 <= directoryData.count else { break }
            let header = directoryData.subdata(in: cursor..<(cursor + 46))
            guard readUInt32(header, at: 0) == 0x02014b50 else { break }

            let compressionMethod = readUInt16(header, at: 10)
            let compressedSize = readUInt32(header, at: 20)
            let uncompressedSize = readUInt32(header, at: 24)
            let filenameLength = Int(readUInt16(header, at: 28))
            let extraLength = Int(readUInt16(header, at: 30))
            let commentLength = Int(readUInt16(header, at: 32))
            let localHeaderOffset = readUInt32(header, at: 42)

            let nameStart = cursor + 46
            guard nameStart + filenameLength <= directoryData.count else { break }
            let nameData = directoryData.subdata(in: nameStart..<(nameStart + filenameLength))
            let path = String(data: nameData, encoding: .utf8) ?? String(decoding: nameData, as: UTF8.self)

            entries.append(Entry(
                path: path,
                type: path.hasSuffix("/") ? .directory : .file,
                localHeaderOffset: localHeaderOffset,
                compressedSize: compressedSize,
                uncompressedSize: uncompressedSize,
                compressionMethod: compressionMethod
            ))

            cursor = nameStart + filenameLength + extraLength + commentLength
        }
        return entries
    }

    // MARK: - Lettura di interi da 2/4 byte (little-endian, come previsto dal formato ZIP)

    private static func readUInt16(_ data: Data, at offset: Int) -> UInt16 {
        let base = data.startIndex + offset
        return UInt16(data[base]) | (UInt16(data[base + 1]) << 8)
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
        let base = data.startIndex + offset
        return UInt32(data[base])
            | (UInt32(data[base + 1]) << 8)
            | (UInt32(data[base + 2]) << 16)
            | (UInt32(data[base + 3]) << 24)
    }

    private static func findSignatureFromEnd(_ signature: UInt32, in data: Data) -> Int? {
        guard data.count >= 4 else { return nil }
        let target: [UInt8] = [
            UInt8(signature & 0xFF), UInt8((signature >> 8) & 0xFF),
            UInt8((signature >> 16) & 0xFF), UInt8((signature >> 24) & 0xFF)
        ]
        var i = data.count - 4
        while i >= 0 {
            let base = data.startIndex + i
            if data[base] == target[0], data[base + 1] == target[1],
               data[base + 2] == target[2], data[base + 3] == target[3] {
                return i
            }
            i -= 1
        }
        return nil
    }

    // MARK: - Decompressione DEFLATE grezza (RFC 1951), tramite il framework Compression di Apple

    private static func inflate(_ compressed: Data, expectedSize: Int) -> Data? {
        guard expectedSize > 0 else { return Data() }
        var output = Data(count: expectedSize)
        let writtenCount = output.withUnsafeMutableBytes { outBuffer -> Int in
            compressed.withUnsafeBytes { inBuffer -> Int in
                guard let outPointer = outBuffer.bindMemory(to: UInt8.self).baseAddress,
                      let inPointer = inBuffer.bindMemory(to: UInt8.self).baseAddress else {
                    return 0
                }
                // COMPRESSION_ZLIB qui indica il DEFLATE grezzo (RFC 1951, senza intestazione
                // zlib), lo stesso formato usato dal metodo di compressione 8 dentro un ZIP.
                return compression_decode_buffer(
                    outPointer, expectedSize,
                    inPointer, compressed.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard writtenCount == expectedSize else { return nil }
        return output
    }
}

enum MinimalZipError: LocalizedError {
    case notAZipFile
    case corruptArchive
    case decompressionFailed
    case unsupportedCompression

    var errorDescription: String? {
        switch self {
        case .notAZipFile: return "Il file non sembra un archivio ZIP valido."
        case .corruptArchive: return "Archivio ZIP danneggiato o non leggibile."
        case .decompressionFailed: return "Impossibile decomprimere i dati."
        case .unsupportedCompression: return "Metodo di compressione non supportato."
        }
    }
}
