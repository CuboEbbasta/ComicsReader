import Foundation
import UIKit

/// Cache su disco delle copertine estratte, per evitare di riaprire l'archivio o il PDF ogni volta
/// che una copertina deve essere mostrata (es. scorrendo la libreria).
enum CoverCache {

    private static let cacheDirectory: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("ComicCovers", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /// Restituisce la copertina per il fumetto indicato, leggendola dalla cache su disco se
    /// presente, altrimenti estraendola dal file originale e salvandola in cache per la prossima volta.
    static func coverImage(for comic: ComicFile, libraryRootURL: URL) async -> UIImage? {
        let cacheFileURL = cacheDirectory.appendingPathComponent(cacheKey(for: comic))

        if let cached = UIImage(contentsOfFile: cacheFileURL.path) {
            return cached
        }

        let sourceURL = libraryRootURL.appendingPathComponent(comic.relativePath)
        guard let extracted = await ComicParsingService.extractCoverImage(for: comic, at: sourceURL) else {
            return nil
        }
        if let data = extracted.jpegData(compressionQuality: 0.85) {
            try? data.write(to: cacheFileURL)
        }
        return extracted
    }

    /// Chiave di cache basata sul percorso relativo e sulla data di modifica: se il file cambia
    /// (es. sostituito con una versione diversa dello stesso fumetto), la copertina viene ri-estratta
    /// automaticamente invece di mostrare quella vecchia.
    private static func cacheKey(for comic: ComicFile) -> String {
        let sanitizedPath = comic.relativePath.replacingOccurrences(of: "/", with: "_")
        let modStamp = comic.modificationDate?.timeIntervalSince1970 ?? 0
        return "\(sanitizedPath)_\(Int(modStamp)).jpg"
    }
}
