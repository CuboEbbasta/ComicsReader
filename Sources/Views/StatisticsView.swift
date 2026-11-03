import SwiftUI

/// Statistiche calcolate a partire dai dati già salvati in ReadingProgressStore: nessun nuovo
/// tracciamento, solo un riepilogo di quello che c'è già.
///
/// I totali (fumetti finiti, tempo, pagine...) contano SEMPRE tutto, anche i fumetti nascosti
/// in Incognito: nasconderli riguarda la libreria, non le statistiche aggregate. L'elenco "finiti
/// di recente" invece mostra titoli specifici, quindi lì i fumetti nascosti non compaiono a meno
/// che l'Incognito non sia sbloccato — altrimenti vedere il titolo vanificherebbe il nasconderlo.
struct StatisticsView: View {
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @ObservedObject private var incognitoStore = IncognitoStore.shared

    private var allProgress: [ReadingProgress] { Array(progressStore.progress.values) }
    private var finished: [ReadingProgress] { allProgress.filter { $0.isCompleted } }
    private var inProgress: [ReadingProgress] { allProgress.filter { !$0.isCompleted && $0.currentPage > 0 } }
    private var totalReadingTime: TimeInterval { allProgress.reduce(0) { $0 + $1.totalReadingTime } }
    private var totalPagesRead: Int {
        allProgress.reduce(0) { $0 + ($1.isCompleted ? $1.totalPages : $1.currentPage + 1) }
    }
    private var totalSessions: Int { allProgress.reduce(0) { $0 + $1.sessionCount } }

    var body: some View {
        List {
            Section {
                statRow(icon: "checkmark.circle.fill", label: "Fumetti finiti", value: "\(finished.count)")
                statRow(icon: "book.fill", label: "In lettura", value: "\(inProgress.count)")
                statRow(icon: "doc.plaintext.fill", label: "Pagine lette (circa)", value: "\(totalPagesRead)")
                statRow(icon: "clock.fill", label: "Tempo di lettura totale", value: totalReadingTime.formattedReadingDuration)
                statRow(icon: "arrow.triangle.2.circlepath", label: "Sessioni di lettura", value: "\(totalSessions)")
                statRow(icon: "bookmark.fill", label: "Segnalibri", value: "\(progressStore.bookmarks.count)")
            }

            if !recentlyFinished.isEmpty {
                Section("Finiti più di recente") {
                    ForEach(recentlyFinished, id: \.relativePath) { item in
                        VStack(alignment: .leading, spacing: 3) {
                            Text((item.relativePath as NSString).lastPathComponent)
                                .font(.subheadline)
                                .lineLimit(1)
                            if let timeToFinish = item.timeToFinish {
                                Text("Finito in \(timeToFinish.formattedReadingDuration)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Statistiche")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var recentlyFinished: [ReadingProgress] {
        finished
            .filter { incognitoStore.isEnabled || !incognitoStore.isHidden($0.relativePath) }
            .sorted { ($0.finishedAt ?? .distantPast) > ($1.finishedAt ?? .distantPast) }
            .prefix(10)
            .map { $0 }
    }

    private func statRow(icon: String, label: String, value: String) -> some View {
        HStack {
            Label(label, systemImage: icon)
            Spacer()
            Text(value).foregroundStyle(.secondary).monospacedDigit()
        }
    }
}
