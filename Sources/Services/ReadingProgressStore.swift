import Foundation

/// Salva e carica il progresso di lettura di ogni fumetto (pagina corrente, tempo di lettura
/// accumulato, data di prima apertura e di completamento) e i segnalibri manuali. Tutto persistito
/// in UserDefaults come JSON: per una libreria personale è più che sufficiente, niente database.
final class ReadingProgressStore: ObservableObject {
    static let shared = ReadingProgressStore()

    @Published private(set) var progress: [String: ReadingProgress] = [:]
    @Published private(set) var bookmarks: [PageBookmark] = []

    private let progressKey = "reading.progress.v1"
    private let bookmarkKey = "reading.bookmarks.v1"

    private init() { load() }

    func progress(for relativePath: String) -> ReadingProgress? {
        progress[relativePath]
    }

    /// Aggiorna pagina corrente e (se fornito) tempo di lettura accumulato nell'ultima sessione.
    /// `additionalTime` è il tempo attivo trascorso da quando il lettore è stato aperto o
    /// dall'ultimo aggiornamento, non tempo di calendario: se l'app va in background non va
    /// passato qui (vedi ReaderView, che misura solo il tempo in primo piano).
    @discardableResult
    func update(relativePath: String, page: Int, totalPages: Int, additionalTime: TimeInterval = 0, completed: Bool? = nil) -> ReadingProgress {
        var item = progress[relativePath] ?? ReadingProgress(
            relativePath: relativePath, currentPage: 0, totalPages: totalPages,
            totalReadingTime: 0, sessionCount: 0, firstOpenedAt: nil, lastReadAt: nil,
            finishedAt: nil, isCompleted: false
        )
        if item.firstOpenedAt == nil { item.firstOpenedAt = Date() }
        item.currentPage = page
        item.totalPages = totalPages
        item.totalReadingTime += max(0, additionalTime)
        item.lastReadAt = Date()

        let willBeCompleted = completed ?? (page >= max(totalPages - 1, 0))
        if willBeCompleted, !item.isCompleted {
            item.finishedAt = Date()
        }
        if !willBeCompleted {
            // Se l'utente riapre e sfoglia indietro un fumetto già segnato come finito, non
            // sembra un problema lasciarlo "finito"; si toglie solo con il tocco esplicito
            // sul segno di spunta (vedi toggleCompleted).
        }
        item.isCompleted = willBeCompleted || item.isCompleted

        progress[relativePath] = item
        save()
        return item
    }

    /// Registra l'inizio di una nuova sessione di lettura (per il conteggio sessioni).
    func registerSessionStart(relativePath: String, totalPages: Int) {
        var item = progress[relativePath] ?? ReadingProgress(
            relativePath: relativePath, currentPage: 0, totalPages: totalPages,
            totalReadingTime: 0, sessionCount: 0, firstOpenedAt: nil, lastReadAt: nil,
            finishedAt: nil, isCompleted: false
        )
        if item.firstOpenedAt == nil { item.firstOpenedAt = Date() }
        item.sessionCount += 1
        progress[relativePath] = item
        save()
    }

    /// Toglie o mette manualmente il segno "letto", indipendentemente dalla pagina raggiunta
    /// (per il tocco diretto sul segno di spunta nella libreria).
    func setCompleted(_ completed: Bool, relativePath: String, totalPages: Int) {
        var item = progress[relativePath] ?? ReadingProgress(
            relativePath: relativePath, currentPage: 0, totalPages: totalPages,
            totalReadingTime: 0, sessionCount: 0, firstOpenedAt: nil, lastReadAt: nil,
            finishedAt: nil, isCompleted: false
        )
        item.isCompleted = completed
        item.finishedAt = completed ? (item.finishedAt ?? Date()) : nil
        progress[relativePath] = item
        save()
    }

    func addBookmark(relativePath: String, pageIndex: Int, note: String? = nil) {
        let bookmark = PageBookmark(id: UUID(), relativePath: relativePath, pageIndex: pageIndex, createdAt: Date(), note: note)
        bookmarks.append(bookmark)
        save()
    }

    func removeBookmark(_ bookmark: PageBookmark) {
        bookmarks.removeAll { $0.id == bookmark.id }
        save()
    }

    func bookmarks(for relativePath: String) -> [PageBookmark] {
        bookmarks.filter { $0.relativePath == relativePath }.sorted { $0.pageIndex < $1.pageIndex }
    }

    private func load() {
        if let data = UserDefaults.standard.data(forKey: progressKey),
           let decoded = try? JSONDecoder().decode([String: ReadingProgress].self, from: data) {
            progress = decoded
        }
        if let data = UserDefaults.standard.data(forKey: bookmarkKey),
           let decoded = try? JSONDecoder().decode([PageBookmark].self, from: data) {
            bookmarks = decoded
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(progress) {
            UserDefaults.standard.set(data, forKey: progressKey)
        }
        if let data = try? JSONEncoder().encode(bookmarks) {
            UserDefaults.standard.set(data, forKey: bookmarkKey)
        }
    }
}

extension TimeInterval {
    /// Formatta una durata in modo leggibile e compatto (es. "2h 14min", "45min", "3g 1h").
    var formattedReadingDuration: String {
        let totalMinutes = Int(self / 60)
        let days = totalMinutes / (60 * 24)
        let hours = (totalMinutes / 60) % 24
        let minutes = totalMinutes % 60
        if days > 0 { return "\(days)g \(hours)h" }
        if hours > 0 { return "\(hours)h \(minutes)min" }
        if minutes > 0 { return "\(minutes)min" }
        return "< 1min"
    }
}
