import Foundation
import UIKit

/// Punto unico da cui l'interfaccia richiede copertina e metadati di un fumetto: si occupa lui
/// di chiamare il parser giusto in base al formato, e di farlo fuori dal thread principale
/// (tranne il PDF, che si è dimostrato più affidabile se aperto sul thread principale — vedi
/// PDFParser.swift). CBR non è ancora supportato: restituisce nil/vuoto e l'app mostra
/// semplicemente un'icona segnaposto.
enum ComicParsingService {

    static func extractCoverImage(for comic: ComicFile, at url: URL) async -> UIImage? {
        switch comic.format {
        case .cbz:
            return await Task.detached(priority: .userInitiated) {
                try? CBZParser.extractCoverImage(at: url)
            }.value
        case .epub:
            return await Task.detached(priority: .userInitiated) {
                try? EPUBParser.extractCoverImage(at: url)
            }.value
        case .pdf:
            return await MainActor.run {
                PDFParser.extractCoverImage(at: url)
            }
        case .cbr:
            return nil
        }
    }

    static func readMetadata(for comic: ComicFile, at url: URL) async -> ComicMetadata {
        switch comic.format {
        case .cbz:
            return await Task.detached(priority: .utility) {
                (try? CBZParser.readMetadata(at: url)) ?? .empty
            }.value
        case .epub:
            return await Task.detached(priority: .utility) {
                (try? EPUBParser.readMetadata(at: url)) ?? .empty
            }.value
        case .pdf:
            return await MainActor.run {
                PDFParser.readMetadata(at: url)
            }
        case .cbr:
            return .empty
        }
    }
}
