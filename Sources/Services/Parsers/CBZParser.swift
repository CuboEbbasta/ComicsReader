import Foundation
import UIKit

/// Riferimento a una pagina immagine dentro un archivio CBZ (o un EPUB a layout fisso, che
/// riusa lo stesso meccanismo essendo anch'esso uno ZIP).
struct ComicPageInfo: Identifiable, Hashable, Sendable {
    let id: String
    let path: String
    let index: Int
}

/// Estrae copertina, metadati e singole pagine dai file CBZ, che sono di fatto archivi ZIP
/// contenenti immagini (una per pagina) ed eventualmente un file ComicInfo.xml con i metadati.
///
/// Richiede il pacchetto Swift "ZIPFoundation" (vedi 01_LEGGIMI_FASE2A.md).
enum CBZParser {

    private static let imageExtensions: Set<String> = ["jpg", "jpeg", "png", "webp", "gif"]

    static func extractCoverImage(at url: URL) throws -> UIImage? {
        // NOTA: se questa riga dà un errore di compilazione relativo a "throws" o a un valore
        // opzionale, la versione di ZIPFoundation scaricata potrebbe usare l'API precedente
        // (inizializzatore fallibile invece che throws). In quel caso sostituisci con:
        //   guard let archive = Archive(url: url, accessMode: .read) else { return nil }
        let archive = try Archive(url: url, accessMode: .read)
        guard let firstImageEntry = sortedImageEntries(in: archive).first else { return nil }
        let data = try extractData(for: firstImageEntry, from: archive)
        return UIImage(data: data)
    }

    static func readMetadata(at url: URL) throws -> ComicMetadata {
        let archive = try Archive(url: url, accessMode: .read)
        var metadata = ComicMetadata()
        if let comicInfoEntry = archive["ComicInfo.xml"] {
            let data = try extractData(for: comicInfoEntry, from: archive)
            metadata = ComicInfoXMLReader.parse(data: data)
        }
        if metadata.pageCount == nil {
            metadata.pageCount = sortedImageEntries(in: archive).count
        }
        return metadata
    }

    /// Elenco ordinato delle pagine (solo un inventario leggero: indice e percorso dentro
    /// l'archivio), usato dal lettore per costruire la lista pagine senza estrarre subito
    /// tutte le immagini.
    static func pages(at url: URL) throws -> [ComicPageInfo] {
        let archive = try Archive(url: url, accessMode: .read)
        return sortedImageEntries(in: archive).enumerated().map { offset, entry in
            ComicPageInfo(id: entry.path, path: entry.path, index: offset)
        }
    }

    // MARK: - Helpers condivisi (usati anche da CBZReaderDocument, che tiene aperto un solo
    // Archive per tutta la sessione di lettura invece di riaprirlo a ogni pagina)

    static func sortedImageEntries(in archive: Archive) -> [Entry] {
        archive
            .filter { entry in
                guard entry.type == .file else { return false }
                let ext = (entry.path as NSString).pathExtension.lowercased()
                guard imageExtensions.contains(ext) else { return false }
                guard !entry.path.hasPrefix("__MACOSX/") else { return false }
                let fileName = (entry.path as NSString).lastPathComponent
                return !fileName.hasPrefix(".")
            }
            .sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }

    static func extractData(for entry: Entry, from archive: Archive) throws -> Data {
        var data = Data()
        _ = try archive.extract(entry) { chunk in
            data.append(chunk)
        }
        return data
    }
}
