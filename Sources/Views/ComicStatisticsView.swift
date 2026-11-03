import SwiftUI

/// Statistiche di lettura di un singolo fumetto: si apre tenendo premuta la sua copertina in
/// libreria, dallo stesso menu di "nascondi"/"segna come letto".
struct ComicStatisticsView: View {
    let comic: ComicFile
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @Environment(\.dismiss) private var dismiss

    private var progress: ReadingProgress? { progressStore.progress(for: comic.relativePath) }

    var body: some View {
        NavigationStack {
            List {
                if let progress {
                    Section {
                        statRow(icon: "checkmark.seal", label: "Stato", value: progress.isCompleted ? "Letto" : (progress.currentPage > 0 ? "In lettura" : "Da leggere"))
                        statRow(icon: "book.pages", label: "Pagina", value: "\(progress.currentPage + 1) / \(max(progress.totalPages, 1))")
                        if progress.totalPages > 0 {
                            statRow(icon: "percent", label: "Completamento", value: "\(Int(progress.fraction * 100))%")
                        }
                        statRow(icon: "clock", label: "Tempo di lettura", value: progress.totalReadingTime > 0 ? progress.totalReadingTime.formattedReadingDuration : "—")
                        statRow(icon: "arrow.triangle.2.circlepath", label: "Sessioni", value: "\(progress.sessionCount)")
                        if let firstOpened = progress.firstOpenedAt {
                            statRow(icon: "calendar", label: "Prima apertura", value: Self.dateFormatter.string(from: firstOpened))
                        }
                        if progress.isCompleted, let timeToFinish = progress.timeToFinish {
                            statRow(icon: "flag.checkered", label: "Finito in", value: timeToFinish.formattedReadingDuration)
                        }
                    }

                    let bookmarks = progressStore.bookmarks(for: comic.relativePath)
                    if !bookmarks.isEmpty {
                        Section("Segnalibri (\(bookmarks.count))") {
                            ForEach(bookmarks) { bookmark in
                                Text("Pagina \(bookmark.pageIndex + 1)")
                            }
                        }
                    }
                } else {
                    ContentUnavailableView("Non ancora aperto", systemImage: "book.closed", description: Text("Non ci sono ancora dati di lettura per questo fumetto."))
                }
            }
            .navigationTitle(comic.metadata.title ?? comic.displayTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Chiudi") { dismiss() } }
            }
        }
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter
    }()

    private func statRow(icon: String, label: String, value: String) -> some View {
        HStack {
            Label(label, systemImage: icon)
            Spacer()
            Text(value).foregroundStyle(.secondary).monospacedDigit()
        }
    }
}
