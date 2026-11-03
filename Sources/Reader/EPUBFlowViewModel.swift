import Foundation

@MainActor
final class EPUBFlowViewModel: ObservableObject {
    @Published var currentChapter: Int
    @Published private(set) var chapterCount = 0
    @Published private(set) var errorMessage: String?
    private(set) var extractedDirectory: URL

    let comic: ComicFile
    private let libraryRootURL: URL
    private let progressStore: ReadingProgressStore
    private var chapterRelativePaths: [String] = []
    private var sessionStartDate: Date?

    init(comic: ComicFile, libraryRootURL: URL, progressStore: ReadingProgressStore? = nil) {
        self.comic = comic
        self.libraryRootURL = libraryRootURL
        let resolvedStore = progressStore ?? .shared
        self.progressStore = resolvedStore
        self.currentChapter = resolvedStore.progress(for: comic.relativePath)?.currentPage ?? 0
        self.extractedDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("epub-flow-\(UUID().uuidString)", isDirectory: true)
    }

    func load() {
        let sourceURL = libraryRootURL.appendingPathComponent(comic.relativePath)
        let destination = extractedDirectory
        Task.detached(priority: .userInitiated) { [comic] in
            do {
                try EPUBParser.extractAll(from: sourceURL, to: destination)
                let chapters = try EPUBParser.chapterPaths(at: sourceURL)
                await MainActor.run { [weak self] in
                    guard let self else { return }
                    self.chapterRelativePaths = chapters
                    self.chapterCount = chapters.count
                    self.currentChapter = min(max(self.currentChapter, 0), max(chapters.count - 1, 0))
                    if chapters.isEmpty {
                        self.errorMessage = "Impossibile trovare i capitoli di \(comic.displayTitle)."
                    }
                }
            } catch {
                await MainActor.run { [weak self] in
                    self?.errorMessage = "Impossibile aprire l'EPUB: \(error.localizedDescription)"
                }
            }
        }
    }

    func chapterFileURL(at index: Int) -> URL {
        guard chapterRelativePaths.indices.contains(index) else { return extractedDirectory }
        return extractedDirectory.appendingPathComponent(chapterRelativePaths[index])
    }

    func persistProgress(chapter: Int) {
        guard chapterCount > 0 else { return }
        progressStore.update(relativePath: comic.relativePath, page: chapter, totalPages: chapterCount)
    }

    func toggleFinished() {
        guard chapterCount > 0 else { return }
        let isFinished = progressStore.progress(for: comic.relativePath)?.isCompleted ?? false
        progressStore.setCompleted(!isFinished, relativePath: comic.relativePath, totalPages: chapterCount)
    }

    func startSession() {
        guard sessionStartDate == nil else { return }
        sessionStartDate = Date()
        progressStore.registerSessionStart(relativePath: comic.relativePath, totalPages: chapterCount)
    }

    func endSession() {
        guard let start = sessionStartDate else { return }
        let elapsed = Date().timeIntervalSince(start)
        sessionStartDate = nil
        progressStore.update(relativePath: comic.relativePath, page: currentChapter, totalPages: max(chapterCount, 1), additionalTime: elapsed)
    }

    deinit {
        let directory = extractedDirectory
        Task.detached(priority: .background) {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
