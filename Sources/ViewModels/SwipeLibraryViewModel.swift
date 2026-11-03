import Foundation

/// Tiene traccia del "contesto" corrente (una cartella dell'albero) e di come muoversi tra
/// contesti. Regola di navigazione (vedi 08_LEGGIMI_NAVIGAZIONE_V2.md):
///
/// - **orizzontale (sinistra/destra)**: cambia contesto spostandosi tra "fratelli" (altri figli
///   dello stesso genitore), mantenendo la stessa profondità. Caso speciale della radice (che
///   non ha fratelli veri, essendo in cima): l'orizzontale mostra i SUOI figli — così da "prima
///   di tutti" (radice) avanzando si entra nel primo figlio, e da lì in poi ci si muove tra i
///   figli come fratelli normali.
/// - **verticale in basso**: entra nella prima sottocartella (mostrata come una superficie che
///   "emerge" in fondo allo schermo, non come una griglia di cartelle).
/// - **verticale in alto / pulsante indietro**: risale alla cartella genitore.
/// - **toccare un segnalibro nel percorso in basso**: risale direttamente a quel livello.
///
/// Sempre basato sull'albero vero già scansionato una volta sola: cambiare contesto significa
/// solo cambiare quale ComicFolder è "corrente", non si riscansiona mai il filesystem.
@MainActor
final class SwipeLibraryViewModel: ObservableObject {
    @Published private(set) var containerFolder: ComicFolder
    private let rootFolder: ComicFolder
    private let progressStore: ReadingProgressStore

    init(rootFolder: ComicFolder, progressStore: ReadingProgressStore = .shared) {
        self.rootFolder = rootFolder
        self.containerFolder = rootFolder
        self.progressStore = progressStore
    }

    var isRoot: Bool { containerFolder.parent == nil }

    // MARK: - Continua a leggere (solo alla radice, su tutta la libreria)

    var continueReadingComics: [ComicFile] {
        let inProgress = progressStore.progress.values.filter { !$0.isCompleted && $0.currentPage > 0 }
        let sortedPaths = inProgress
            .sorted { ($0.lastReadAt ?? .distantPast) > ($1.lastReadAt ?? .distantPast) }
            .map(\.relativePath)
        let byPath = Dictionary(uniqueKeysWithValues: rootFolder.allComicsRecursive.map { ($0.relativePath, $0) })
        return sortedPaths.compactMap { byPath[$0] }
    }

    // MARK: - Contenuto diretto del contesto corrente

    var directComics: [ComicFile] { containerFolder.comics }
    var isEmpty: Bool { directComics.isEmpty && containerFolder.subfolders.isEmpty }

    // MARK: - Prima sottocartella (asse verticale, verso il basso)

    var firstSubfolder: ComicFolder? { containerFolder.subfolders.first }

    func enterFirstSubfolder() {
        guard let first = firstSubfolder else { return }
        containerFolder = first
    }

    // MARK: - Risalita (asse verticale, verso l'alto)

    var canGoUp: Bool { containerFolder.parent != nil }

    func goUp() {
        guard let parent = containerFolder.parent else { return }
        containerFolder = parent
    }

    func goUp(to ancestor: ComicFolder) {
        containerFolder = ancestor
    }

    // MARK: - Fratelli (asse orizzontale)

    private var horizontalItems: [ComicFolder] {
        if isRoot { return containerFolder.subfolders }
        guard let parent = containerFolder.parent else { return [] }
        return parent.subfolders
    }

    /// -1 quando siamo alla radice ("prima" di ogni figlio): così avanzare di uno porta al
    /// primo figlio, in modo uniforme con la logica usata per i fratelli normali.
    private var horizontalCurrentIndex: Int {
        guard !isRoot else { return -1 }
        return horizontalItems.firstIndex { $0.id == containerFolder.id } ?? -1
    }

    var previousSiblingContext: ComicFolder? {
        let idx = horizontalCurrentIndex - 1
        guard horizontalItems.indices.contains(idx) else { return nil }
        return horizontalItems[idx]
    }

    var nextSiblingContext: ComicFolder? {
        let idx = horizontalCurrentIndex + 1
        guard horizontalItems.indices.contains(idx) else { return nil }
        return horizontalItems[idx]
    }

    func moveToPreviousSiblingContext() {
        guard let previous = previousSiblingContext else { return }
        containerFolder = previous
    }

    func moveToNextSiblingContext() {
        guard let next = nextSiblingContext else { return }
        containerFolder = next
    }

    // MARK: - Percorso

    var breadcrumbPath: [ComicFolder] {
        var path: [ComicFolder] = []
        var node: ComicFolder? = containerFolder
        while let current = node {
            path.insert(current, at: 0)
            node = current.parent
        }
        return path
    }
}
