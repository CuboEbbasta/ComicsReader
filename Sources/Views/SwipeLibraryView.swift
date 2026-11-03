import SwiftUI
import UIKit

/// Homepage della libreria secondo la specifica estetica/di navigazione (vedi
/// 08_LEGGIMI_NAVIGAZIONE_V2.md). Ogni schermata rappresenta UN contesto (una cartella):
///
/// - Titolo in alto = nome del contesto corrente.
/// - "Continua a leggere" compare SOLO alla radice, su tutta la libreria.
/// - Sotto, i fumetti diretti del contesto corrente (mai le sottocartelle mostrate come griglia:
///   niente sezione "Serie").
/// - In fondo, un'area semi-trasparente mostra la PRIMA sottocartella: tocco o swipe verso il
///   basso per entrarci.
/// - Swipe orizzontale (o le frecce in basso) passa al contesto fratello successivo/precedente.
/// - Swipe verso l'alto (o il pulsante indietro) risale al genitore.
/// - Percorso e pulsante aggiorna sempre visibili in basso.
struct SwipeLibraryView: View {
    @StateObject private var navModel: SwipeLibraryViewModel
    let libraryRootURL: URL
    let settings: AppSettings
    let onRefresh: () async -> Void

    @State private var dragTranslation: CGSize = .zero
    @State private var openedComic: ComicFile?

    private let peekSize: CGFloat = 40

    init(rootFolder: ComicFolder, libraryRootURL: URL, settings: AppSettings, onRefresh: @escaping () async -> Void) {
        _navModel = StateObject(wrappedValue: SwipeLibraryViewModel(rootFolder: rootFolder))
        self.libraryRootURL = libraryRootURL
        self.settings = settings
        self.onRefresh = onRefresh
    }

    private var horizontalDrag: CGFloat {
        abs(dragTranslation.width) > abs(dragTranslation.height) ? dragTranslation.width : 0
    }

    private var navAnimation: Animation { .spring(response: 0.4, dampingFraction: 0.85) }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.ignoresSafeArea()

                if let previous = navModel.previousSiblingContext {
                    siblingPeek(previous)
                        .offset(x: -(proxy.size.width - peekSize) + horizontalDrag)
                }
                if let next = navModel.nextSiblingContext {
                    siblingPeek(next)
                        .offset(x: (proxy.size.width - peekSize) + horizontalDrag)
                }

                VStack(spacing: 0) {
                    titleHeader
                    contentScrollView
                    bottomBar
                }
                .offset(x: horizontalDrag)
            }
            .clipped()
            .simultaneousGesture(
                DragGesture(minimumDistance: 16)
                    .onChanged { value in dragTranslation = value.translation }
                    .onEnded { value in handleDragEnd(value.translation, containerWidth: proxy.size.width) }
            )
        }
        .navigationDestination(item: $openedComic) { comic in
            ReaderView(comic: comic, libraryRootURL: libraryRootURL, settings: settings)
        }
    }

    // MARK: - Titolo (nome del contesto corrente)

    private var titleHeader: some View {
        VStack(spacing: 6) {
            HStack {
                if navModel.canGoUp {
                    Color.clear.frame(width: 28, height: 28)
                }
                Spacer()
                Text(navModel.containerFolder.name.isEmpty ? "Libreria" : navModel.containerFolder.name)
                    .font(.title.bold())
                    .foregroundStyle(.white)
                Spacer()
                if navModel.canGoUp {
                    Button { withAnimation(navAnimation) { navModel.goUp() } } label: {
                        Image(systemName: "chevron.up.circle.fill")
                            .font(.title2)
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
            }
            Rectangle().fill(DarkTheme.accent).frame(width: 40, height: 3)
        }
        .padding(.horizontal)
        .padding(.top, 12)
    }

    // MARK: - Contenuto scorrevole

    private var contentScrollView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                if navModel.isRoot, !navModel.continueReadingComics.isEmpty {
                    sectionHeader(title: "Continua a leggere")
                    continueReadingRow
                }

                if !navModel.directComics.isEmpty {
                    sectionHeader(title: navModel.containerFolder.name.isEmpty ? "Fumetti" : navModel.containerFolder.name.uppercased())
                    comicGrid
                } else if navModel.isEmpty {
                    ContentUnavailableView("Vuota", systemImage: "tray")
                        .foregroundStyle(.white)
                        .padding(.top, 40)
                }

                if let firstSubfolder = navModel.firstSubfolder {
                    firstSubfolderPreview(firstSubfolder)
                }
            }
            .padding()
            .padding(.bottom, 20)
        }
        .background(Color.black)
    }

    private var continueReadingRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(navModel.continueReadingComics) { comic in
                    Button { openedComic = comic } label: {
                        ContinueReadingTile(comic: comic, libraryRootURL: libraryRootURL)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func sectionHeader(title: String) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.title3.bold())
                .foregroundStyle(.white)
            Rectangle().fill(Color.white.opacity(0.15)).frame(height: 1)
        }
    }

    private var comicGrid: some View {
        TileGrid(items: navModel.directComics) { comic in
            ComicTile(comic: comic, libraryRootURL: libraryRootURL) {
                openedComic = comic
            }
        }
    }

    // MARK: - Anteprima prima sottocartella (asse verticale, verso il basso)

    private func firstSubfolderPreview(_ folder: ComicFolder) -> some View {
        Button {
            withAnimation(navAnimation) { navModel.enterFirstSubfolder() }
        } label: {
            VStack(spacing: 10) {
                HStack {
                    Rectangle().fill(Color.white.opacity(0.12)).frame(height: 1)
                    Text("PRIMA SOTTOCARTELLA")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.35))
                    Rectangle().fill(Color.white.opacity(0.12)).frame(height: 1)
                }
                HStack(spacing: 14) {
                    Image(systemName: "chevron.down")
                        .foregroundStyle(DarkTheme.accent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(folder.name)
                            .font(.headline)
                            .foregroundStyle(.white.opacity(0.85))
                        Text("\(folder.totalComicCount) elementi")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    Spacer()
                }
                .padding(14)
                .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
            }
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            DragGesture(minimumDistance: 20)
                .onEnded { value in
                    if value.translation.height > 40 {
                        withAnimation(navAnimation) { navModel.enterFirstSubfolder() }
                    }
                }
        )
        .contextMenu {
            Button {
                IncognitoStore.shared.toggleHidden(folder.relativePath)
            } label: {
                let isHidden = IncognitoStore.shared.isHidden(folder.relativePath)
                Label(isHidden ? "Mostra cartella (togli da Incognito)" : "Nascondi cartella (Incognito)", systemImage: isHidden ? "eye" : "eye.slash")
            }
        }
    }

    private func siblingPeek(_ folder: ComicFolder) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "book.closed.fill")
                .font(.system(size: 40))
                .foregroundStyle(.white.opacity(0.25))
            Text(folder.name)
                .font(.title2.bold())
                .foregroundStyle(.white.opacity(0.3))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)
            Text("\(folder.totalComicCount) elementi")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.2))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.white.opacity(0.02))
    }

    // MARK: - Barra in basso: aggiorna, percorso, precedente/successivo

    private var bottomBar: some View {
        HStack {
            Button { Task { await onRefresh() } } label: {
                Image(systemName: "arrow.clockwise")
                    .foregroundStyle(.white.opacity(0.7))
            }

            Spacer()

            breadcrumbView

            Spacer()

            HStack(spacing: 18) {
                Button {
                    guard navModel.previousSiblingContext != nil else { return }
                    withAnimation(navAnimation) { navModel.moveToPreviousSiblingContext() }
                } label: {
                    Label("Precedente", systemImage: "chevron.left")
                        .labelStyle(.iconOnly)
                }
                .opacity(navModel.previousSiblingContext != nil ? 1 : 0.25)

                Button {
                    guard navModel.nextSiblingContext != nil else { return }
                    withAnimation(navAnimation) { navModel.moveToNextSiblingContext() }
                } label: {
                    Label("Successivo", systemImage: "chevron.right")
                        .labelStyle(.iconOnly)
                }
                .opacity(navModel.nextSiblingContext != nil ? 1 : 0.25)
            }
            .foregroundStyle(.white.opacity(0.7))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial.opacity(0.6))
    }

    private var breadcrumbView: some View {
        HStack(spacing: 4) {
            ForEach(Array(navModel.breadcrumbPath.enumerated()), id: \.element.id) { index, folder in
                Button { navModel.goUp(to: folder) } label: {
                    Text(folder.name.isEmpty ? "Libreria" : folder.name)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(index == navModel.breadcrumbPath.count - 1 ? 0.7 : 0.4))
                }
                .buttonStyle(.plain)
                if index < navModel.breadcrumbPath.count - 1 {
                    Text("/").font(.caption2).foregroundStyle(.white.opacity(0.25))
                }
            }
        }
        .lineLimit(1)
    }

    // MARK: - Gesto

    private func handleDragEnd(_ translation: CGSize, containerWidth: CGFloat) {
        let horizontalThreshold = containerWidth * 0.22
        if abs(translation.width) > abs(translation.height) {
            if translation.width < -horizontalThreshold, navModel.nextSiblingContext != nil {
                withAnimation(navAnimation) { navModel.moveToNextSiblingContext(); dragTranslation = .zero }
                return
            } else if translation.width > horizontalThreshold, navModel.previousSiblingContext != nil {
                withAnimation(navAnimation) { navModel.moveToPreviousSiblingContext(); dragTranslation = .zero }
                return
            }
        } else if translation.height < -80, navModel.canGoUp {
            withAnimation(navAnimation) { navModel.goUp(); dragTranslation = .zero }
            return
        }
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
            dragTranslation = .zero
        }
    }
}

// MARK: - Griglia a 3 colonne, righe incomplete centrate, identità basata sui dati veri

private struct TileGrid<Item: Identifiable, Content: View>: View {
    let items: [Item]
    let content: (Item) -> Content
    private let columnCount = 3
    private let spacing: CGFloat = 24

    init(items: [Item], @ViewBuilder content: @escaping (Item) -> Content) {
        self.items = items
        self.content = content
    }

    private struct Row: Identifiable {
        let id: String
        let items: [Item]
    }

    private var rows: [Row] {
        stride(from: 0, to: items.count, by: columnCount).map { start in
            let rowItems = Array(items[start..<Swift.min(start + columnCount, items.count)])
            let rowID = rowItems.map { "\($0.id)" }.joined(separator: "|")
            return Row(id: rowID, items: rowItems)
        }
    }

    var body: some View {
        VStack(spacing: 26) {
            ForEach(rows) { row in
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    HStack(spacing: spacing) {
                        ForEach(row.items) { item in
                            content(item)
                                .frame(width: 190)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
        }
    }
}

// MARK: - Tessere

private struct ComicTile: View {
    let comic: ComicFile
    let libraryRootURL: URL
    let onSelect: () -> Void
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @ObservedObject private var incognitoStore = IncognitoStore.shared
    @State private var cover: UIImage?
    @State private var resolvedTitle: String?
    @State private var showStatistics = false

    private var progress: ReadingProgress? { progressStore.progress(for: comic.relativePath) }
    private var isFinished: Bool { progress?.isCompleted ?? false }
    private var isHidden: Bool { incognitoStore.isHidden(comic.relativePath) }

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                ZStack(alignment: .topTrailing) {
                    Group {
                        if let cover {
                            Image(uiImage: cover).resizable().scaledToFill()
                        } else {
                            RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.07))
                                .overlay(Image(systemName: "book.closed").foregroundStyle(.white.opacity(0.4)))
                        }
                    }
                    .aspectRatio(0.68, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .opacity(isHidden ? 0.4 : 1)

                    if isFinished {
                        Image(systemName: "checkmark.circle.fill")
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .green)
                            .background(Circle().fill(.black.opacity(0.4)).padding(-2))
                            .padding(6)
                            .font(.caption)
                    }
                }
                Text(resolvedTitle ?? comic.displayTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if let progress, progress.fraction > 0 {
                    ProgressView(value: progress.fraction).tint(isFinished ? .green : .white)
                }
            }
        }
        .buttonStyle(.plain)
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
}

private struct ContinueReadingTile: View {
    let comic: ComicFile
    let libraryRootURL: URL
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @State private var cover: UIImage?
    @State private var resolvedTitle: String?
    private let tileWidth: CGFloat = 130

    private var progress: ReadingProgress? { progressStore.progress(for: comic.relativePath) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if let cover {
                    Image(uiImage: cover).resizable().scaledToFill()
                } else {
                    RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.07))
                }
            }
            .frame(width: tileWidth)
            .aspectRatio(0.68, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(resolvedTitle ?? comic.displayTitle)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .frame(width: tileWidth, alignment: .leading)

            if let progress {
                Text("Pag. \(progress.currentPage + 1)/\(max(progress.totalPages, 1))")
                    .font(.system(size: 9))
                    .foregroundStyle(.white.opacity(0.5))
                    .frame(width: tileWidth, alignment: .leading)
                if progress.fraction > 0 {
                    HStack(spacing: 4) {
                        ProgressView(value: progress.fraction).tint(.white)
                        Text("\(Int(progress.fraction * 100))%")
                            .font(.system(size: 9))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                    .frame(width: tileWidth)
                }
            }
        }
        .task {
            let source = libraryRootURL.appendingPathComponent(comic.relativePath)
            async let coverTask = CoverCache.coverImage(for: comic, libraryRootURL: libraryRootURL)
            async let metadataTask = ComicParsingService.readMetadata(for: comic, at: source)
            let (fetchedCover, fetchedMetadata) = await (coverTask, metadataTask)
            cover = fetchedCover
            resolvedTitle = fetchedMetadata.title
        }
    }
}
