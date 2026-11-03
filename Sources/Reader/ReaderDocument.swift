import Foundation
import PDFKit
import UIKit

/// Un fumetto aperto nel lettore, indipendentemente dal formato originale: espone solo
/// "quante pagine ha" e "dammi l'immagine della pagina N", così il resto del lettore
/// (ReaderViewModel, ReaderView) non deve sapere nulla del formato specifico.
@MainActor
protocol ReaderDocument {
    var pageCount: Int { get }
    var title: String { get }
    func renderPage(index: Int, targetWidth: CGFloat) throws -> UIImage
}

enum ReaderDocumentError: LocalizedError {
    case unsupportedFormat
    case invalidPage
    case pageDecodeFailed
    case documentOpenFailed

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat: return "Formato non ancora supportato dal lettore (CBR in arrivo)."
        case .invalidPage: return "Pagina non valida."
        case .pageDecodeFailed: return "Impossibile decodificare la pagina."
        case .documentOpenFailed: return "Impossibile aprire il documento."
        }
    }
}

/// Ridimensiona un'immagine pagina alla larghezza target, se è più grande del necessario.
/// Condivisa da CBZ ed EPUB, che dopo la risoluzione del percorso sono in pratica identici:
/// entrambi finiscono per estrarre una singola immagine da un archivio ZIP.
private func scaledPageImage(_ image: UIImage, targetWidth: CGFloat) -> UIImage {
    guard targetWidth > 0, image.size.width > targetWidth else { return image }
    let scale = targetWidth / image.size.width
    let size = CGSize(width: targetWidth, height: image.size.height * scale)
    // Scala esplicita 1.0: l'immagine di una pagina CBZ non ha già informazioni "Retina"
    // incorporate (è una foto/scansione qualsiasi), quindi "size" deve corrispondere
    // direttamente a pixel reali. Senza specificarla, il renderer userebbe la scala del
    // dispositivo corrente, raddoppiando i pixel in modo implicito e meno prevedibile.
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1.0
    let renderer = UIGraphicsImageRenderer(size: size, format: format)
    return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
}

// MARK: - CBZ

@MainActor
final class CBZReaderDocument: ReaderDocument {
    let title: String
    /// Tenuto aperto per tutta la sessione di lettura: riaprire l'intero archivio a ogni singola
    /// pagina (come in una versione precedente di questo file) è lento su archivi grandi.
    private let archive: Archive
    private let pages: [ComicPageInfo]

    init(url: URL, title: String) throws {
        self.title = title
        // NOTA: se questa riga dà un errore di compilazione, vedi la nota in CBZParser.swift
        // sull'inizializzatore di Archive (throws vs fallibile a seconda della versione).
        self.archive = try Archive(url: url, accessMode: .read)
        self.pages = CBZParser.sortedImageEntries(in: archive).enumerated().map { offset, entry in
            ComicPageInfo(id: entry.path, path: entry.path, index: offset)
        }
        guard !pages.isEmpty else { throw ReaderDocumentError.documentOpenFailed }
    }

    var pageCount: Int { pages.count }

    func renderPage(index: Int, targetWidth: CGFloat) throws -> UIImage {
        guard pages.indices.contains(index) else { throw ReaderDocumentError.invalidPage }
        guard let entry = archive[pages[index].path] else { throw ReaderDocumentError.invalidPage }
        let data = try CBZParser.extractData(for: entry, from: archive)
        guard let image = UIImage(data: data) else { throw ReaderDocumentError.pageDecodeFailed }
        return scaledPageImage(image, targetWidth: targetWidth)
    }
}

// MARK: - EPUB (a layout fisso)

@MainActor
final class EPUBReaderDocument: ReaderDocument {
    let title: String
    private let archive: Archive
    private let pages: [ComicPageInfo]

    init(url: URL, title: String) throws {
        self.title = title
        self.archive = try Archive(url: url, accessMode: .read)
        self.pages = try EPUBParser.pages(at: url)
        guard !pages.isEmpty else { throw ReaderDocumentError.documentOpenFailed }
    }

    var pageCount: Int { pages.count }

    func renderPage(index: Int, targetWidth: CGFloat) throws -> UIImage {
        guard pages.indices.contains(index) else { throw ReaderDocumentError.invalidPage }
        guard let entry = archive[pages[index].path] else { throw ReaderDocumentError.invalidPage }
        let data = try CBZParser.extractData(for: entry, from: archive)
        guard let image = UIImage(data: data) else { throw ReaderDocumentError.pageDecodeFailed }
        return scaledPageImage(image, targetWidth: targetWidth)
    }
}

// MARK: - PDF

@MainActor
final class PDFReaderDocument: ReaderDocument {
    let title: String
    private let document: PDFDocument

    init(url: URL, title: String) throws {
        // Come in PDFParser.swift: si legge prima come Data invece di passare l'URL direttamente
        // a PDFDocument, più affidabile con le cartelle scelte tramite il picker di sistema.
        guard let fileData = try? Data(contentsOf: url),
              let document = PDFDocument(data: fileData),
              document.pageCount > 0 else {
            throw ReaderDocumentError.documentOpenFailed
        }
        self.title = title
        self.document = document
    }

    var pageCount: Int { document.pageCount }

    func renderPage(index: Int, targetWidth: CGFloat) throws -> UIImage {
        guard let page = document.page(at: index) else { throw ReaderDocumentError.invalidPage }
        var bounds = page.bounds(for: .cropBox)
        if bounds.width <= 0 || bounds.height <= 0 { bounds = page.bounds(for: .mediaBox) }
        guard bounds.width > 0, bounds.height > 0 else { throw ReaderDocumentError.pageDecodeFailed }
        let scale = targetWidth / bounds.width
        return page.thumbnail(of: CGSize(width: bounds.width * scale, height: bounds.height * scale), for: .cropBox)
    }
}
