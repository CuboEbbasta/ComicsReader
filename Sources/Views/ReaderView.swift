import SwiftUI
import UIKit

/// Punto d'ingresso del lettore: per i formati a immagini (CBZ, PDF) va dritto al lettore
/// classico; per EPUB controlla prima se è a layout fisso (fumetto) o a testo scorrevole
/// (ebook classico) e apre il lettore giusto di conseguenza.
struct ReaderView: View {
    let comic: ComicFile
    let libraryRootURL: URL
    @ObservedObject var settings: AppSettings
    @State private var epubIsFixedLayout: Bool?

    var body: some View {
        Group {
            if comic.format == .epub {
                if let epubIsFixedLayout {
                    if epubIsFixedLayout {
                        ImageBasedReaderView(comic: comic, libraryRootURL: libraryRootURL, settings: settings)
                    } else {
                        EPUBFlowReaderView(comic: comic, libraryRootURL: libraryRootURL, settings: settings)
                    }
                } else {
                    ZStack {
                        Color.black.ignoresSafeArea()
                        ProgressView("Apertura…").tint(.white).foregroundStyle(.white)
                    }
                    .task { await detectEPUBLayout() }
                }
            } else {
                ImageBasedReaderView(comic: comic, libraryRootURL: libraryRootURL, settings: settings)
            }
        }
    }

    private func detectEPUBLayout() async {
        let url = libraryRootURL.appendingPathComponent(comic.relativePath)
        epubIsFixedLayout = await Task.detached(priority: .userInitiated) {
            (try? EPUBParser.isFixedLayout(at: url)) ?? false
        }.value
    }
}

// MARK: - Lettore per contenuti a immagini (CBZ, PDF, EPUB a layout fisso)

struct ImageBasedReaderView: View {
    let comic: ComicFile
    let libraryRootURL: URL
    @ObservedObject var settings: AppSettings
    @StateObject private var viewModel: ReaderViewModel
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @State private var showFilmstrip = false
    /// Il fumetto si apre sempre in modalità senza distrazioni: i controlli compaiono solo con
    /// un tocco sulla pagina, non appena l'apertura.
    @State private var showControls = false

    init(comic: ComicFile, libraryRootURL: URL, settings: AppSettings) {
        self.comic = comic
        self.libraryRootURL = libraryRootURL
        self.settings = settings
        _viewModel = StateObject(wrappedValue: ReaderViewModel(
            comic: comic, libraryRootURL: libraryRootURL,
            direction: settings.defaultReadingDirection, layout: settings.defaultPageLayout
        ))
    }

    private var isFinished: Bool {
        progressStore.progress(for: comic.relativePath)?.isCompleted ?? false
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let error = viewModel.errorMessage {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    ContentUnavailableView("Lettore non disponibile", systemImage: "book.closed", description: Text(error))
                        .foregroundStyle(.white)
                    Spacer()
                }
            } else if viewModel.document != nil {
                // Il contenuto occupa SEMPRE tutto lo schermo, safe area comprese: mostrare o
                // nascondere i controlli non deve mai ridimensionarlo, altrimenti la pagina
                // "scatta" visibilmente. I controlli sono overlay in trasparenza sopra, non
                // elementi che spostano lo spazio disponibile.
                Group {
                    if settings.defaultTransitionStyle == .pageCurl {
                        PageCurlReader(viewModel: viewModel, onTap: { withAnimation { showControls.toggle() } })
                    } else {
                        PagedReader(viewModel: viewModel)
                    }
                }
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation { showControls.toggle() }
                }

                if showControls {
                    VStack(spacing: 0) {
                        topBar
                        Spacer()
                        if showFilmstrip {
                            PageFilmstrip(viewModel: viewModel) { index in
                                viewModel.currentPage = index
                                viewModel.persistProgress()
                            }
                        }
                        controlBar
                    }
                    .transition(.opacity)
                    .allowsHitTesting(true)
                }
            } else {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    ProgressView("Apertura…").tint(.white).foregroundStyle(.white)
                    Spacer()
                }
            }
        }
        .ignoresSafeArea()
        .toolbar(.hidden, for: .navigationBar)
        .task { viewModel.load() }
        .onAppear { viewModel.startSession() }
        .onDisappear { viewModel.endSession() }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active { viewModel.startSession() } else { viewModel.endSession() }
        }
    }

    private var topBar: some View {
        HStack(spacing: 14) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
            }
            Text(comic.metadata.title ?? comic.displayTitle)
                .font(.headline)
                .lineLimit(1)
            Spacer()
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial, in: Capsule())
        .padding()
    }

    private var controlBar: some View {
        HStack(spacing: 18) {
            Text("Pagina \(viewModel.currentPage + 1) / \(viewModel.document?.pageCount ?? 0)")
                .font(.caption.monospacedDigit())
            Spacer()
            Button { withAnimation { showFilmstrip.toggle() } } label: {
                Image(systemName: "square.grid.3x3")
                    .symbolVariant(showFilmstrip ? .fill : .none)
            }
            Button { viewModel.toggleBookmarkOnCurrentPage() } label: {
                Image(systemName: viewModel.isCurrentPageBookmarked ? "bookmark.fill" : "bookmark")
            }
            Button { viewModel.toggleFinished() } label: {
                Image(systemName: isFinished ? "checkmark.circle.fill" : "checkmark.circle")
            }
        }
        .font(.body)
        .foregroundStyle(.white)
        .padding(12)
        .background(.ultraThinMaterial, in: Capsule())
        .padding()
    }
}

// MARK: - Lettura standard (scorrimento orizzontale, 1 o 2 pagine)

private struct PagedReader: View {
    @ObservedObject var viewModel: ReaderViewModel
    @State private var selection: Int

    init(viewModel: ReaderViewModel) {
        self.viewModel = viewModel
        let spreads = Self.spreadPositions(pageCount: viewModel.visualIndices.count, double: viewModel.layout == .double)
        let initialSpread = spreads.firstIndex { $0.contains(viewModel.initialVisualIndex) } ?? 0
        _selection = State(initialValue: initialSpread)
    }

    /// Raggruppa le posizioni (0, 1, 2, …, nell'ordine in cui vanno mostrate) in coppie quando
    /// il layout è "due pagine". Le posizioni sono già nell'ordine di lettura corretto
    /// (la direzione manga/occidentale è già stata applicata a monte, in visualIndices/displayPage):
    /// qui basta accoppiarle due a due.
    private static func spreadPositions(pageCount: Int, double: Bool) -> [[Int]] {
        guard double, pageCount > 1 else { return (0..<pageCount).map { [$0] } }
        var result: [[Int]] = []
        var i = 0
        while i < pageCount {
            if i + 1 < pageCount { result.append([i, i + 1]); i += 2 } else { result.append([i]); i += 1 }
        }
        return result
    }

    private var spreads: [[Int]] {
        Self.spreadPositions(pageCount: viewModel.visualIndices.count, double: viewModel.layout == .double)
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(Array(spreads.enumerated()), id: \.offset) { index, positions in
                spreadView(positions: positions)
                    .tag(index)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .onChange(of: selection) { _, newValue in
            guard spreads.indices.contains(newValue), let lastPosition = spreads[newValue].last else { return }
            viewModel.setVisualPage(lastPosition)
        }
        .onChange(of: viewModel.currentPage) { _, newPage in
            // Se la pagina cambia da fuori (filmstrip, segnalibri) invece che dallo swipe qui
            // dentro, sposta davvero la vista alla pagina giusta invece di lasciare che il dato
            // e ciò che si vede sullo schermo si disallineino.
            guard let targetVisualIndex = viewModel.visualIndices.firstIndex(of: newPage),
                  let targetSpread = spreads.firstIndex(where: { $0.contains(targetVisualIndex) }),
                  targetSpread != selection else { return }
            selection = targetSpread
        }
        .onAppear {
            guard spreads.indices.contains(selection), let lastPosition = spreads[selection].last else { return }
            viewModel.setVisualPage(lastPosition)
        }
    }

    @ViewBuilder
    private func spreadView(positions: [Int]) -> some View {
        let documentIndices = positions.map { viewModel.displayPage(at: $0) }
        // Il primo elemento è quello letto per primo: in occidentale va a sinistra, in manga
        // (destra→sinistra) va a destra, quindi l'ordine sullo schermo si inverte.
        let ordered = viewModel.direction == .rightToLeft ? Array(documentIndices.reversed()) : documentIndices
        HStack(spacing: 3) {
            ForEach(ordered, id: \.self) { documentIndex in
                pageView(documentIndex: documentIndex)
                    // .frame(maxWidth: .infinity) su più figli in una HStack fa sì che lo spazio
                    // disponibile venga diviso equamente tra loro: non serve calcolare a mano una
                    // larghezza (che in precedenza dipendeva da una dimensione letta da un
                    // GeometryReader, probabile causa per cui in modalità due pagine se ne vedeva
                    // sempre e solo una).
                    .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder
    private func pageView(documentIndex: Int) -> some View {
        // Sempre una risoluzione generosa e fissa per il rendering, anche in modalità due
        // pagine: dimezzarla in base allo spazio a schermo produceva pagine visibilmente più
        // sfocate rispetto alla modalità singola.
        if let image = viewModel.render(page: documentIndex, targetWidth: 1200) {
            Image(uiImage: image).resizable().scaledToFit()
                .padding(.horizontal, viewModel.layout == .double ? 2 : 20)
        } else {
            ProgressView().tint(.white)
        }
    }
}

// MARK: - Lettura con arricciatura pagina

private struct PageCurlReader: UIViewControllerRepresentable {
    @ObservedObject var viewModel: ReaderViewModel
    var onTap: () -> Void = {}

    func makeUIViewController(context: Context) -> UIPageViewController {
        let controller = UIPageViewController(transitionStyle: .pageCurl, navigationOrientation: .horizontal, options: nil)
        controller.dataSource = context.coordinator
        controller.delegate = context.coordinator
        // Sfondo trasparente: altrimenti l'arricciatura "volta" anche i margini neri intorno
        // all'immagine (quando la pagina non riempie tutto lo schermo), che sembra sbagliato.
        // Con lo sfondo trasparente si vede lo sfondo nero statico dietro, che non si arriccia.
        controller.view.backgroundColor = .clear
        for subview in controller.view.subviews where subview is UIScrollView {
            subview.backgroundColor = .clear
        }
        // Dice al controller che il contenuto si legge da destra verso sinistra (manga): è il
        // modo corretto e nativo per far coincidere sia il verso dello swipe sia la direzione
        // visiva dell'arricciatura, invece di scambiare a mano i numeri di pagina.
        controller.view.semanticContentAttribute = viewModel.direction == .rightToLeft ? .forceRightToLeft : .forceLeftToRight
        let coordinator = context.coordinator
        let initialSpreadIndex = coordinator.spreads.firstIndex { $0.contains(viewModel.initialVisualIndex) } ?? 0
        controller.setViewControllers([coordinator.controllerFor(spreadIndex: initialSpreadIndex)], direction: .forward, animated: false)
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        context.coordinator.onTap = onTap
        // Se la pagina cambia da fuori (filmstrip, segnalibri) invece che dallo swipe qui
        // dentro, sposta davvero la vista invece di lasciare che il dato e ciò che si vede
        // sullo schermo si disallineino.
        let coordinator = context.coordinator
        guard let visibleController = controller.viewControllers?.first,
              let currentSpreadIndex = coordinator.spreadIndex(from: visibleController),
              let targetVisualIndex = viewModel.visualIndices.firstIndex(of: viewModel.currentPage),
              let targetSpreadIndex = coordinator.spreads.firstIndex(where: { $0.contains(targetVisualIndex) }),
              targetSpreadIndex != currentSpreadIndex else { return }
        let direction: UIPageViewController.NavigationDirection = targetSpreadIndex > currentSpreadIndex ? .forward : .reverse
        controller.setViewControllers([coordinator.controllerFor(spreadIndex: targetSpreadIndex)], direction: direction, animated: false)
    }
    func makeCoordinator() -> Coordinator { Coordinator(viewModel: viewModel, onTap: onTap) }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        let viewModel: ReaderViewModel
        var onTap: () -> Void

        init(viewModel: ReaderViewModel, onTap: @escaping () -> Void) {
            self.viewModel = viewModel
            self.onTap = onTap
        }

        /// Stessa logica di raggruppamento del lettore a scorrimento: con il layout "due pagine"
        /// ogni "pagina" dell'arricciatura è in realtà una coppia di pagine affiancate — prima
        /// questo raggruppamento mancava qui, quindi l'arricciatura mostrava sempre una singola
        /// pagina indipendentemente dall'impostazione.
        var spreads: [[Int]] {
            let pageCount = viewModel.visualIndices.count
            guard viewModel.layout == .double, pageCount > 1 else { return (0..<pageCount).map { [$0] } }
            var result: [[Int]] = []
            var i = 0
            while i < pageCount {
                if i + 1 < pageCount { result.append([i, i + 1]); i += 2 } else { result.append([i]); i += 1 }
            }
            return result
        }

        func controllerFor(spreadIndex: Int) -> UIViewController {
            // NOTA: costruito sempre da capo, niente cache di controller (una versione
            // precedente riusava i controller già creati, e sembra aver impedito
            // all'arricciatura di essere visibile: la pagina cambiava di scatto).
            let controller = UIViewController()
            controller.view.backgroundColor = .clear
            controller.restorationIdentifier = "spread-\(spreadIndex)"
            guard spreads.indices.contains(spreadIndex) else { return controller }

            let positions = spreads[spreadIndex]
            let documentIndices = positions.map { viewModel.displayPage(at: $0) }
            // Il primo elemento è quello letto per primo: in occidentale va a sinistra, in manga
            // va a destra (stessa regola del lettore a scorrimento).
            let ordered = viewModel.direction == .rightToLeft ? Array(documentIndices.reversed()) : documentIndices

            // Uno UIStackView con distribuzione equa affianca 1 o 2 immagini con larghezza
            // sempre uguale, senza dover calcolare a mano le dimensioni.
            let stack = UIStackView()
            stack.axis = .horizontal
            stack.distribution = .fillEqually
            stack.spacing = 3
            stack.translatesAutoresizingMaskIntoConstraints = false

            let totalRenderWidth: CGFloat = 2000
            for documentIndex in ordered {
                let imageView = UIImageView()
                imageView.contentMode = .scaleAspectFit
                imageView.backgroundColor = .clear
                imageView.image = viewModel.render(page: documentIndex, targetWidth: totalRenderWidth / CGFloat(ordered.count))
                stack.addArrangedSubview(imageView)
            }
            controller.view.addSubview(stack)
            NSLayoutConstraint.activate([
                stack.leadingAnchor.constraint(equalTo: controller.view.leadingAnchor),
                stack.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor),
                stack.topAnchor.constraint(equalTo: controller.view.topAnchor),
                stack.bottomAnchor.constraint(equalTo: controller.view.bottomAnchor)
            ])
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
            controller.view.addGestureRecognizer(tap)
            return controller
        }

        @objc private func handleTap() {
            onTap()
        }

        func spreadIndex(from controller: UIViewController) -> Int? {
            guard let id = controller.restorationIdentifier, let value = id.split(separator: "-").last else { return nil }
            return Int(value)
        }

        func pageViewController(_ pageViewController: UIPageViewController, viewControllerBefore viewController: UIViewController) -> UIViewController? {
            guard let index = spreadIndex(from: viewController), index - 1 >= 0 else { return nil }
            return controllerFor(spreadIndex: index - 1)
        }

        func pageViewController(_ pageViewController: UIPageViewController, viewControllerAfter viewController: UIViewController) -> UIViewController? {
            guard let index = spreadIndex(from: viewController), index + 1 < spreads.count else { return nil }
            return controllerFor(spreadIndex: index + 1)
        }

        func pageViewController(_ pageViewController: UIPageViewController, didFinishAnimating finished: Bool, previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            guard completed, let controller = pageViewController.viewControllers?.first,
                  let index = spreadIndex(from: controller), spreads.indices.contains(index),
                  let lastPosition = spreads[index].last else { return }
            viewModel.currentPage = viewModel.displayPage(at: lastPosition)
            viewModel.persistProgress()
        }
    }
}

// MARK: - Striscia miniature in basso, a scorrimento orizzontale (come Panels)

private struct PageFilmstrip: View {
    @ObservedObject var viewModel: ReaderViewModel
    let onSelectPage: (Int) -> Void
    @ObservedObject private var progressStore = ReadingProgressStore.shared

    /// Le stesse coppie (spread) usate dal lettore quando il layout è "due pagine": qui servono
    /// solo per raggrupparle visivamente nella filmstrip, ogni miniatura resta comunque
    /// selezionabile singolarmente.
    private var groups: [[Int]] {
        let pageCount = viewModel.visualIndices.count
        guard viewModel.layout == .double, pageCount > 1 else { return (0..<pageCount).map { [$0] } }
        var result: [[Int]] = []
        var i = 0
        while i < pageCount {
            if i + 1 < pageCount { result.append([i, i + 1]); i += 2 } else { result.append([i]); i += 1 }
        }
        return result
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(Array(groups.enumerated()), id: \.offset) { _, positions in
                        let documentIndices = positions.map { viewModel.displayPage(at: $0) }
                        HStack(spacing: 3) {
                            ForEach(documentIndices, id: \.self) { index in
                                Button {
                                    onSelectPage(index)
                                } label: {
                                    PageThumbnailCell(
                                        viewModel: viewModel, pageIndex: index,
                                        isCurrent: index == viewModel.currentPage,
                                        isBookmarked: progressStore.bookmarks(for: viewModel.comic.relativePath).contains { $0.pageIndex == index }
                                    )
                                }
                                .buttonStyle(.plain)
                                .id(index)
                            }
                        }
                        .padding(4)
                        .background(
                            documentIndices.count > 1 ? Color.white.opacity(0.08) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 6)
                        )
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }
            .onChange(of: viewModel.currentPage) { _, newValue in
                withAnimation { proxy.scrollTo(newValue, anchor: .center) }
            }
            .onAppear {
                proxy.scrollTo(viewModel.currentPage, anchor: .center)
            }
        }
        .frame(height: 96)
        .background(.ultraThinMaterial)
    }
}

private struct PageThumbnailCell: View {
    let viewModel: ReaderViewModel
    let pageIndex: Int
    let isCurrent: Bool
    let isBookmarked: Bool
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                } else {
                    RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.08))
                }
            }
            .frame(width: 50, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .overlay(
                RoundedRectangle(cornerRadius: 4).stroke(isCurrent ? Color.white : Color.clear, lineWidth: 2)
            )

            if isBookmarked {
                Image(systemName: "bookmark.fill")
                    .font(.system(size: 10))
                    .foregroundStyle(.yellow)
                    .padding(2)
            }
        }
        .task {
            if image == nil { image = viewModel.render(page: pageIndex, targetWidth: 140) }
        }
    }
}
