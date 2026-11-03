import SwiftUI
import WebKit

/// Lettore per gli EPUB a testo scorrevole (non a layout fisso): mostra un capitolo alla volta
/// tramite una WKWebView (che si occupa lei di impaginare correttamente testo, immagini e CSS,
/// cosa che sarebbe complicatissimo ricostruire a mano), con swipe orizzontale tra un capitolo
/// e il successivo. All'interno di un capitolo lungo si scorre verticalmente come in un normale
/// browser.
///
/// Nota di impostazione: qui "pagina" = capitolo. È una scelta pragmatica: paginare il testo
/// scorrevole in tante piccole pagine "come un libro stampato" richiederebbe un motore di
/// impaginazione dedicato; a livello di capitolo il risultato resta comunque comodo da leggere
/// e da riprendere da dove si era rimasti.
struct EPUBFlowReaderView: View {
    let comic: ComicFile
    let libraryRootURL: URL
    @ObservedObject var settings: AppSettings
    @StateObject private var viewModel: EPUBFlowViewModel
    @ObservedObject private var progressStore = ReadingProgressStore.shared
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @State private var showChapterList = false
    @State private var fontScale: Double = 1.0
    /// Come nel lettore a immagini: si apre sempre senza controlli visibili.
    @State private var showControls = false

    init(comic: ComicFile, libraryRootURL: URL, settings: AppSettings) {
        self.comic = comic
        self.libraryRootURL = libraryRootURL
        self.settings = settings
        _viewModel = StateObject(wrappedValue: EPUBFlowViewModel(comic: comic, libraryRootURL: libraryRootURL))
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
            } else if viewModel.chapterCount == 0 {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    ProgressView("Apertura…").tint(.white).foregroundStyle(.white)
                    Spacer()
                }
            } else {
                TabView(selection: $viewModel.currentChapter) {
                    ForEach(0..<viewModel.chapterCount, id: \.self) { index in
                        EPUBChapterWebView(
                            fileURL: viewModel.chapterFileURL(at: index),
                            baseDirectory: viewModel.extractedDirectory,
                            fontScale: fontScale,
                            onTap: { withAnimation { showControls.toggle() } }
                        )
                        .tag(index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .ignoresSafeArea()
                .onChange(of: viewModel.currentChapter) { _, newValue in
                    viewModel.persistProgress(chapter: newValue)
                }
                if showControls {
                    VStack(spacing: 0) {
                        topBar
                        Spacer()
                        overlayControls
                    }
                    .transition(.opacity)
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
        .sheet(isPresented: $showChapterList) {
            ChapterListSheet(count: viewModel.chapterCount, current: viewModel.currentChapter) { index in
                viewModel.currentChapter = index
                viewModel.persistProgress(chapter: index)
            }
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

    private var overlayControls: some View {
        VStack {
            HStack(spacing: 16) {
                Text("Capitolo \(viewModel.currentChapter + 1) / \(viewModel.chapterCount)")
                    .font(.caption.monospacedDigit())
                Spacer()
                Button { fontScale = max(0.8, fontScale - 0.1) } label: { Image(systemName: "textformat.size.smaller") }
                Button { fontScale = min(2.0, fontScale + 0.1) } label: { Image(systemName: "textformat.size.larger") }
                Button { showChapterList = true } label: { Image(systemName: "list.bullet") }
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
}

private struct ChapterListSheet: View {
    let count: Int
    let current: Int
    let onSelect: (Int) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(0..<count, id: \.self) { index in
                Button {
                    onSelect(index)
                    dismiss()
                } label: {
                    HStack {
                        Text("Capitolo \(index + 1)")
                        if index == current {
                            Spacer()
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
            .navigationTitle("Capitoli")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Chiudi") { dismiss() } }
            }
        }
    }
}

/// Wrapper attorno a WKWebView per mostrare un singolo capitolo (file XHTML già estratto su
/// disco, con immagini e CSS accanto) e applicarci la dimensione del testo scelta dal lettore.
private struct EPUBChapterWebView: UIViewRepresentable {
    let fileURL: URL
    let baseDirectory: URL
    let fontScale: Double
    var onTap: () -> Void = {}

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView(frame: .zero)
        webView.backgroundColor = .black
        webView.isOpaque = false
        webView.scrollView.backgroundColor = .black
        webView.navigationDelegate = context.coordinator
        webView.loadFileURL(fileURL, allowingReadAccessTo: baseDirectory)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap))
        // Un solo tocco non deve competere con lo scorrimento/lo zoom nativi della pagina.
        tap.numberOfTapsRequired = 1
        tap.delegate = context.coordinator
        webView.addGestureRecognizer(tap)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.fontScale = fontScale
        context.coordinator.onTap = onTap
        context.coordinator.applyFontScale(to: webView)
    }

    func makeCoordinator() -> Coordinator { Coordinator(onTap: onTap) }

    final class Coordinator: NSObject, WKNavigationDelegate, UIGestureRecognizerDelegate {
        var fontScale: Double = 1.0
        var onTap: () -> Void
        init(onTap: @escaping () -> Void) { self.onTap = onTap }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            applyFontScale(to: webView)
        }
        func applyFontScale(to webView: WKWebView) {
            let js = """
            document.documentElement.style.setProperty('font-size', '\(fontScale)em', 'important');
            document.body.style.setProperty('color', '#eaeaea', 'important');
            document.body.style.setProperty('background-color', '#000000', 'important');
            """
            webView.evaluateJavaScript(js)
        }
        @objc func handleTap() {
            onTap()
        }
        // Lascia comunque funzionare gli altri gesti nativi (scorrimento, zoom, tocco su link).
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            true
        }
    }
}
