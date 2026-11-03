import Foundation
import SwiftUI
import UIKit

@MainActor
final class ReaderViewModel: ObservableObject {
    @Published private(set) var document: (any ReaderDocument)?
    @Published private(set) var pageImages: [Int: UIImage] = [:]
    @Published private(set) var errorMessage: String?
    @Published var currentPage: Int

    let comic: ComicFile
    let libraryRootURL: URL
    let direction: ReadingDirection
    let layout: PageLayoutMode

    private let progressStore: ReadingProgressStore
    private var sessionStartDate: Date?

    init(comic: ComicFile, libraryRootURL: URL, direction: ReadingDirection, layout: PageLayoutMode, progressStore: ReadingProgressStore? = nil) {
        self.comic = comic
        self.libraryRootURL = libraryRootURL
        self.direction = direction == .auto ? Self.guessDirection(for: comic) : direction
        self.layout = layout
        let resolvedStore = progressStore ?? .shared
        self.progressStore = resolvedStore
        self.currentPage = resolvedStore.progress(for: comic.relativePath)?.currentPage ?? 0
    }

    /// Euristica per "Automatico": manga (lingua giapponese nei metadati, o la parola "manga"
    /// nel percorso, come nella cartella categoria) si legge da destra a sinistra, il resto
    /// da sinistra a destra. Il lettore può comunque forzare la direzione dalle impostazioni.
    private static func guessDirection(for comic: ComicFile) -> ReadingDirection {
        if let language = comic.metadata.languageISO?.lowercased(), language.hasPrefix("ja") {
            return .rightToLeft
        }
        if comic.relativePath.lowercased().contains("manga") {
            return .rightToLeft
        }
        return .leftToRight
    }

    /// Indici di pagina nell'ordine in cui vanno mostrati visivamente (invertiti per la lettura
    /// destra-verso-sinistra).
    var visualIndices: [Int] {
        guard let document else { return [] }
        let indices = Array(0..<document.pageCount)
        return direction == .rightToLeft ? indices.reversed() : indices
    }

    /// Posizione visiva da cui partire, corrispondente alla pagina salvata (currentPage).
    /// Va letta una sola volta all'apertura per impostare la pagina iniziale del TabView.
    var initialVisualIndex: Int {
        visualIndices.firstIndex(of: currentPage) ?? 0
    }

    func load() {
        errorMessage = nil
        let url = libraryRootURL.appendingPathComponent(comic.relativePath)
        do {
            switch comic.format {
            case .cbz: document = try CBZReaderDocument(url: url, title: comic.displayTitle)
            case .pdf: document = try PDFReaderDocument(url: url, title: comic.displayTitle)
            case .epub: document = try EPUBReaderDocument(url: url, title: comic.displayTitle)
            case .cbr: throw ReaderDocumentError.unsupportedFormat
            }
            clampPage()
            prefetchAroundCurrentPage()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func displayPage(at visualIndex: Int) -> Int {
        guard let document else { return visualIndex }
        if direction == .rightToLeft { return document.pageCount - 1 - visualIndex }
        return visualIndex
    }

    func setVisualPage(_ visualIndex: Int) {
        currentPage = displayPage(at: visualIndex)
        persistProgress()
        prefetchAroundCurrentPage()
    }

    func render(page index: Int, targetWidth: CGFloat) -> UIImage? {
        // Attenzione: la stessa cache è usata sia dal lettore a piena pagina sia dalle
        // miniature della filmstrip (che chiedono una larghezza molto più piccola). Se
        // restituissimo sempre la prima versione trovata in cache, una pagina caricata prima
        // come miniatura resterebbe sfocata anche quando serve a piena pagina: per questo si
        // rigenera ogni volta che la versione in cache è più piccola di quanto richiesto ora.
        if let cached = pageImages[index], cached.size.width >= targetWidth - 1 {
            return cached
        }
        guard let image = try? document?.renderPage(index: index, targetWidth: targetWidth) else {
            return pageImages[index]
        }
        pageImages[index] = image
        return image
    }

    func persistProgress(completed: Bool? = nil) {
        guard let document else { return }
        progressStore.update(relativePath: comic.relativePath, page: currentPage, totalPages: document.pageCount, completed: completed)
    }

    func bookmarkCurrentPage() {
        progressStore.addBookmark(relativePath: comic.relativePath, pageIndex: currentPage)
    }

    /// Aggiunge un segnalibro sulla pagina corrente se non c'è già, altrimenti lo rimuove.
    func toggleBookmarkOnCurrentPage() {
        if let existing = progressStore.bookmarks(for: comic.relativePath).first(where: { $0.pageIndex == currentPage }) {
            progressStore.removeBookmark(existing)
        } else {
            bookmarkCurrentPage()
        }
    }

    var isCurrentPageBookmarked: Bool {
        progressStore.bookmarks(for: comic.relativePath).contains { $0.pageIndex == currentPage }
    }

    func toggleFinished() {
        guard let document else { return }
        let isFinished = progressStore.progress(for: comic.relativePath)?.isCompleted ?? false
        progressStore.setCompleted(!isFinished, relativePath: comic.relativePath, totalPages: document.pageCount)
    }

    // MARK: - Tempo di lettura

    /// Da chiamare quando il lettore diventa visibile e attivo (comparsa della vista, o rientro
    /// in primo piano dopo che l'app era stata messa in background mentre questo fumetto era aperto).
    func startSession() {
        guard sessionStartDate == nil else { return }
        sessionStartDate = Date()
        progressStore.registerSessionStart(relativePath: comic.relativePath, totalPages: document?.pageCount ?? 0)
    }

    /// Da chiamare quando il lettore sparisce o l'app va in background: somma solo il tempo
    /// passato attivamente in primo piano, non il tempo di calendario.
    func endSession() {
        guard let start = sessionStartDate, let document else { sessionStartDate = nil; return }
        let elapsed = Date().timeIntervalSince(start)
        sessionStartDate = nil
        progressStore.update(relativePath: comic.relativePath, page: currentPage, totalPages: document.pageCount, additionalTime: elapsed)
    }

    // MARK: - Helpers privati

    private func clampPage() {
        guard let document else { currentPage = 0; return }
        currentPage = min(max(currentPage, 0), max(document.pageCount - 1, 0))
    }

    private func prefetchAroundCurrentPage() {
        guard let document else { return }
        let width: CGFloat = 1200
        let candidates = Set([currentPage - 1, currentPage, currentPage + 1]).filter { $0 >= 0 && $0 < document.pageCount }
        for index in candidates where pageImages[index] == nil {
            pageImages[index] = try? document.renderPage(index: index, targetWidth: width)
        }
    }
}
