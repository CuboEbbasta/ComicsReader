import Foundation
import PDFKit
import UIKit

/// Estrae copertina e metadati dai file PDF usando PDFKit, incluso in iOS (nessuna libreria esterna).
///
/// Legge il file come Data e poi apre PDFDocument a partire dai dati, invece di passare
/// direttamente l'URL: con le cartelle scelte tramite il picker di sistema è un approccio
/// più affidabile. I messaggi print(...) qui sotto aiutano a capire, dal pannello di output
/// di Xcode/Playgrounds, in quale punto esatto l'estrazione eventualmente si ferma.
enum PDFParser {

    static func extractCoverImage(at url: URL) -> UIImage? {
        guard let fileData = try? Data(contentsOf: url) else {
            print("PDFParser: impossibile leggere i dati del file a \(url.path)")
            return nil
        }
        guard let document = PDFDocument(data: fileData) else {
            print("PDFParser: PDFDocument non riesce ad aprire \(url.lastPathComponent)")
            return nil
        }
        if document.isLocked {
            print("PDFParser: \(url.lastPathComponent) risulta protetto da password")
        }
        guard let firstPage = document.page(at: 0) else {
            print("PDFParser: nessuna pagina trovata in \(url.lastPathComponent) (pageCount=\(document.pageCount))")
            return nil
        }
        let pageBounds = firstPage.bounds(for: .mediaBox)
        guard pageBounds.width > 0, pageBounds.height > 0 else {
            print("PDFParser: dimensioni pagina non valide (\(pageBounds)) in \(url.lastPathComponent)")
            return nil
        }
        let targetWidth: CGFloat = 600
        let scale = targetWidth / pageBounds.width
        let targetSize = CGSize(width: pageBounds.width * scale, height: pageBounds.height * scale)
        return firstPage.thumbnail(of: targetSize, for: .mediaBox)
    }

    static func readMetadata(at url: URL) -> ComicMetadata {
        guard let fileData = try? Data(contentsOf: url), let document = PDFDocument(data: fileData) else {
            return .empty
        }
        var metadata = ComicMetadata()
        let attributes = document.documentAttributes
        metadata.title = attributes?[PDFDocumentAttribute.titleAttribute] as? String
        metadata.writer = attributes?[PDFDocumentAttribute.authorAttribute] as? String
        metadata.pageCount = document.pageCount
        return metadata
    }
}
