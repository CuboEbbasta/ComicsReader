import Foundation

/// Gestisce lo stato della scansione della libreria e l'albero di cartelle/fumetti risultante.
final class LibraryViewModel: ObservableObject {
    @Published private(set) var rootFolder: ComicFolder?
    @Published private(set) var isScanning = false
    @Published var scanErrorMessage: String?

    func refreshLibrary(rootURL: URL, maxDepth: Int) async {
        isScanning = true
        scanErrorMessage = nil
        do {
            let rawTree = try await LibraryScanner.scan(rootURL: rootURL, maxDepth: maxDepth)
            rootFolder = LibraryScanner.buildTree(from: rawTree)
        } catch {
            scanErrorMessage = "Scansione della libreria non riuscita: \(error.localizedDescription)"
        }
        isScanning = false
    }
}
