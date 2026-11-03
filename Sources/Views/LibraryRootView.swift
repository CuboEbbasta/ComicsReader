import SwiftUI
import UIKit

/// NOTA: navigazione ancora "provvisoria" a tocco (cartelle in elenco, fumetti in griglia).
/// La navigazione a swipe con l'anteprima semi-trasparente ai bordi resta il prossimo pezzo
/// di lavoro grande.
struct LibraryRootView: View {
    @EnvironmentObject private var fileSystemManager: FileSystemManager
    @EnvironmentObject private var appSettings: AppSettings
    @StateObject private var viewModel = LibraryViewModel()
    @ObservedObject private var incognitoStore = IncognitoStore.shared
    @State private var searchText = ""
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isScanning {
                    ProgressView("Scansione della libreria in corso…").tint(.white).foregroundStyle(.white)
                } else if let error = viewModel.scanErrorMessage {
                    ContentUnavailableView("Errore", systemImage: "exclamationmark.triangle", description: Text(error))
                        .foregroundStyle(.white)
                } else if let root = viewModel.rootFolder {
                    if !searchText.isEmpty {
                        SearchResultsView(
                            results: root.allComicsRecursive.filter {
                                $0.displayTitle.localizedCaseInsensitiveContains(searchText)
                                || ($0.metadata.title?.localizedCaseInsensitiveContains(searchText) ?? false)
                            },
                            libraryRootURL: fileSystemManager.libraryRootURL!
                        )
                    } else if root.isEmpty {
                        ContentUnavailableView("Nessun fumetto trovato", systemImage: "tray", description: Text("Aggiungi dei file .cbz, .pdf o .epub nella cartella scelta."))
                            .foregroundStyle(.white)
                    } else {
                        SwipeLibraryView(
                            rootFolder: root,
                            libraryRootURL: fileSystemManager.libraryRootURL!,
                            settings: appSettings,
                            onRefresh: refresh
                        )
                    }
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.ignoresSafeArea())
            .safeAreaInset(edge: .top, spacing: 0) {
                if incognitoStore.isEnabled {
                    incognitoBanner
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(Color.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                // Il tasto aggiorna è ora nella barra in basso, dentro SwipeLibraryView.
                ToolbarItem(placement: .topBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape.fill") }
                }
            }
            .searchable(text: $searchText, prompt: "Cerca nei tuoi fumetti")
            .sheet(isPresented: $showSettings) {
                SettingsView(settings: appSettings)
            }
        }
        .task { await refresh() }
    }

    private var incognitoBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "eye.fill")
            Text("Modalità Incognito attiva")
                .font(.footnote.weight(.semibold))
        }
        .foregroundStyle(.black)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(Color.yellow)
    }

    private func refresh() async {
        guard let rootURL = fileSystemManager.libraryRootURL else { return }
        await viewModel.refreshLibrary(rootURL: rootURL, maxDepth: appSettings.maxFolderDepth)
    }
}

// MARK: - Navigazione a cartelle (provvisoria)

struct LibraryFolderView: View {
    let folder: ComicFolder
    let libraryRootURL: URL
    let settings: AppSettings
    @ObservedObject private var incognitoStore = IncognitoStore.shared

    private var visibleComics: [ComicFile] {
        folder.comics.filter { incognitoStore.isEnabled || !incognitoStore.isHidden($0.relativePath) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                breadcrumb
                folders
                ComicGrid(comics: visibleComics, libraryRootURL: libraryRootURL, settings: settings)
            }
            .padding(.horizontal)
            .padding(.bottom, 40)
        }
    }

    private var breadcrumb: some View {
        Text(folder.breadcrumbComponents.joined(separator: " / "))
            .font(.footnote)
            .foregroundStyle(.white.opacity(0.35))
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var folders: some View {
        ForEach(folder.subfolders) { child in
            NavigationLink {
                LibraryFolderView(folder: child, libraryRootURL: libraryRootURL, settings: settings)
            } label: {
                HStack(spacing: 14) {
                    Image(systemName: "folder.fill").font(.title3)
                    VStack(alignment: .leading) {
                        Text(child.name).font(.headline)
                        Text("\(child.totalComicCount) fumetti").font(.caption).foregroundStyle(.white.opacity(0.55))
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.white.opacity(0.4))
                }
                .foregroundStyle(.white)
                .padding()
                .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - Risultati ricerca globale

private struct SearchResultsView: View {
    let results: [ComicFile]
    let libraryRootURL: URL
    @EnvironmentObject private var appSettings: AppSettings
    @ObservedObject private var incognitoStore = IncognitoStore.shared

    private var visibleResults: [ComicFile] {
        results.filter { incognitoStore.isEnabled || !incognitoStore.isHidden($0.relativePath) }
    }

    var body: some View {
        ScrollView {
            if visibleResults.isEmpty {
                ContentUnavailableView.search
                    .foregroundStyle(.white)
                    .padding(.top, 60)
            } else {
                ComicGrid(comics: visibleResults, libraryRootURL: libraryRootURL, settings: appSettings)
                    .padding()
            }
        }
    }
}

// MARK: - Griglia: 4 colonne, righe incomplete centrate invece che allineate a sinistra

struct ComicGrid: View {
    let comics: [ComicFile]
    let libraryRootURL: URL
    let settings: AppSettings
    private let columnCount = 3
    private let spacing: CGFloat = 30
    /// Valore ragionevole finché non arriva la prima misurazione reale (sotto, via GeometryReader).
    @State private var availableWidth: CGFloat = 900

    private var cardWidth: CGFloat {
        (availableWidth - spacing * CGFloat(columnCount - 1)) / CGFloat(columnCount)
    }

    var body: some View {
        LazyVStack(spacing: 36) {
            ForEach(rows, id: \.self) { rowIndices in
                // Spacer espliciti ai due lati invece di affidarsi al comportamento predefinito
                // di .frame(maxWidth: .infinity): più prevedibile per centrare davvero anche le
                // righe incomplete, invece di lasciarle scivolare verso sinistra.
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    HStack(spacing: spacing) {
                        ForEach(rowIndices, id: \.self) { index in
                            NavigationLink {
                                ReaderView(comic: comics[index], libraryRootURL: libraryRootURL, settings: settings)
                            } label: {
                                ComicCard(comic: comics[index], libraryRootURL: libraryRootURL)
                            }
                            .buttonStyle(.plain)
                            .frame(width: cardWidth)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .background(
            // Misura lo spazio che il contenitore genitore offre davvero, senza influenzare
            // il proprio layout (uno sfondo non partecipa al dimensionamento della vista a cui
            // è applicato): sostituisce UIScreen.main, deprecato dal sistema.
            GeometryReader { proxy in
                Color.clear
                    .onAppear { availableWidth = proxy.size.width }
                    .onChange(of: proxy.size.width) { _, newValue in availableWidth = newValue }
            }
        )
    }

    private var rows: [[Int]] {
        stride(from: 0, to: comics.count, by: columnCount).map { start in
            Array(start..<min(start + columnCount, comics.count))
        }
    }
}

// MARK: - Cella copertina

struct ComicCard: View {
    let comic: ComicFile
    let libraryRootURL: URL
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @ObservedObject private var incognitoStore = IncognitoStore.shared
    @State private var cover: UIImage?
    @State private var resolvedTitle: String?
    @State private var showStatistics = false

    private var progress: ReadingProgress? { progressStore.progress(for: comic.relativePath) }
    private var isFinished: Bool { progress?.isCompleted ?? false }
    private var isHidden: Bool { incognitoStore.isHidden(comic.relativePath) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Copertina "pulita": solo il segno di spunta se finito, nessuna barra sopra.
            ZStack(alignment: .topTrailing) {
                Group {
                    if let cover {
                        Image(uiImage: cover).resizable().scaledToFill()
                    } else {
                        RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.07))
                            .overlay(Image(systemName: icon).font(.largeTitle).foregroundStyle(.white.opacity(0.5)))
                    }
                }
                .frame(maxWidth: .infinity)
                .aspectRatio(0.68, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .opacity(isHidden ? 0.4 : 1)

                if isFinished {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .green)
                        .background(Circle().fill(.black.opacity(0.4)).padding(-2))
                        .padding(8)
                        .font(.title2)
                }
                if isHidden {
                    Image(systemName: "eye.slash.fill")
                        .foregroundStyle(.white)
                        .padding(6)
                        .background(.black.opacity(0.5), in: Circle())
                        .padding(8)
                }
            }

            Text(resolvedTitle ?? comic.displayTitle)
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(2)

            Text(comic.format.displayName)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.55))

            // Barra + percentuale SOTTO la copertina, non sovrapposte.
            if let fraction = progress?.fraction, fraction > 0 {
                HStack(spacing: 8) {
                    ProgressView(value: fraction).tint(isFinished ? .green : .white)
                    Text("\(Int(fraction * 100))%")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.white.opacity(0.6))
                }
            }

            readingTimeInfo
        }
        .task {
            let source = libraryRootURL.appendingPathComponent(comic.relativePath)
            async let coverTask = CoverCache.coverImage(for: comic, libraryRootURL: libraryRootURL)
            async let metadataTask = ComicParsingService.readMetadata(for: comic, at: source)
            let (fetchedCover, fetchedMetadata) = await (coverTask, metadataTask)
            cover = fetchedCover
            resolvedTitle = fetchedMetadata.title
        }
        .contextMenu {
            Button {
                progressStore.setCompleted(!isFinished, relativePath: comic.relativePath, totalPages: progress?.totalPages ?? 0)
            } label: {
                Label(isFinished ? "Segna come da leggere" : "Segna come letto", systemImage: isFinished ? "arrow.uturn.backward.circle" : "checkmark.circle")
            }
            Button {
                incognitoStore.toggleHidden(comic.relativePath)
            } label: {
                Label(isHidden ? "Mostra (togli da Incognito)" : "Nascondi (Incognito)", systemImage: isHidden ? "eye" : "eye.slash")
            }
            Button {
                showStatistics = true
            } label: {
                Label("Statistiche", systemImage: "chart.bar")
            }
        }
        .sheet(isPresented: $showStatistics) {
            ComicStatisticsView(comic: comic)
        }
    }

    /// Tempo speso a leggere; se finito, quanto ci si è messi; se ancora in corso, una stima di
    /// quanto manca calcolata sul ritmo di lettura tenuto finora (tempo speso ÷ pagine lette,
    /// moltiplicato per le pagine rimanenti).
    @ViewBuilder
    private var readingTimeInfo: some View {
        if let progress {
            VStack(alignment: .leading, spacing: 1) {
                if progress.totalReadingTime > 0 {
                    Label(progress.totalReadingTime.formattedReadingDuration, systemImage: "clock")
                }
                if isFinished, let timeToFinish = progress.timeToFinish {
                    Label("finito in \(timeToFinish.formattedReadingDuration)", systemImage: "flag.checkered")
                } else if !isFinished, progress.totalReadingTime > 0, progress.totalPages > 0 {
                    let pagesRead = max(progress.currentPage + 1, 1)
                    let remainingPages = max(progress.totalPages - pagesRead, 0)
                    let averagePerPage = progress.totalReadingTime / Double(pagesRead)
                    let estimate = averagePerPage * Double(remainingPages)
                    if remainingPages > 0 {
                        Label("~\(estimate.formattedReadingDuration) rimanenti", systemImage: "hourglass")
                    }
                }
            }
            .font(.caption)
            .foregroundStyle(.white.opacity(0.55))
        }
    }

    private var icon: String {
        switch comic.format {
        case .pdf: return "doc.richtext"
        case .epub: return "text.book.closed"
        default: return "book.closed"
        }
    }
}
