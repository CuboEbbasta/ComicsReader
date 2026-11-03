import Foundation

/// Progresso di lettura di un fumetto: pagina corrente, tempo di lettura accumulato, quando è
/// stato aperto la prima volta e quando è stato completato (per sapere quanto ci si è messi
/// a finirlo, non solo quanto tempo attivo vi si è passato sopra).
struct ReadingProgress: Codable, Hashable, Sendable {
    var relativePath: String
    var currentPage: Int
    var totalPages: Int
    /// Somma del tempo attivamente passato a leggere (tra apertura e chiusura del lettore,
    /// non tempo di calendario: se l'app resta in background non viene conteggiato).
    var totalReadingTime: TimeInterval
    var sessionCount: Int
    var firstOpenedAt: Date?
    var lastReadAt: Date?
    var finishedAt: Date?
    var isCompleted: Bool

    var fraction: Double {
        guard totalPages > 0 else { return 0 }
        return min(max(Double(currentPage + 1) / Double(totalPages), 0), 1)
    }

    /// Tempo di calendario trascorso dalla prima apertura al completamento. nil se non ancora
    /// finito, o se il progresso è stato creato prima che questo campo esistesse.
    var timeToFinish: TimeInterval? {
        guard let finishedAt, let firstOpenedAt else { return nil }
        return finishedAt.timeIntervalSince(firstOpenedAt)
    }
}

/// Segnalibro manuale su una pagina specifica di un fumetto. Diverso dal progresso automatico:
/// l'utente può crearne quanti ne vuole per lo stesso fumetto.
struct PageBookmark: Identifiable, Codable, Hashable, Sendable {
    let id: UUID
    let relativePath: String
    let pageIndex: Int
    let createdAt: Date
    var note: String?
}
