import Foundation

/// Gestisce l'accesso alla cartella principale della libreria: tiene traccia dell'URL scelto
/// dall'utente e lo salva come "security-scoped bookmark", il meccanismo che iOS richiede per
/// poter riaprire una cartella scelta dall'utente (tramite Files) anche dopo aver chiuso l'app,
/// senza dover richiedere il permesso ogni volta.
final class FileSystemManager: ObservableObject {
    static let shared = FileSystemManager()

    /// La cartella principale attualmente accessibile, se impostata.
    @Published private(set) var libraryRootURL: URL?
    @Published var lastAccessError: String?

    private let bookmarkKey = "libraryRootBookmarkData"
    private var isAccessingRoot = false

    private init() {
        restoreSavedRoot()
    }

    // MARK: - Ripristino all'avvio

    private func restoreSavedRoot() {
        guard let data = UserDefaults.standard.data(forKey: bookmarkKey) else { return }
        do {
            var isStale = false
            let url = try URL(
                resolvingBookmarkData: data,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
            activate(url: url)
            if isStale {
                // Il bookmark risolve ancora correttamente ora, ma va rigenerato per la prossima volta.
                saveBookmark(for: url)
            }
        } catch {
            lastAccessError = "Non è stato possibile riaprire la cartella principale. Selezionala di nuovo."
            libraryRootURL = nil
        }
    }

    private func activate(url: URL) {
        // Chiude un eventuale accesso precedente prima di aprirne uno nuovo.
        if isAccessingRoot, let previous = libraryRootURL {
            previous.stopAccessingSecurityScopedResource()
            isAccessingRoot = false
        }
        let granted = url.startAccessingSecurityScopedResource()
        isAccessingRoot = granted
        if granted {
            libraryRootURL = url
            lastAccessError = nil
        } else {
            libraryRootURL = nil
            lastAccessError = "Il sistema ha negato l'accesso alla cartella scelta."
        }
    }

    // MARK: - Selezione nuova cartella

    /// Da chiamare con l'URL restituito dal file picker dopo che l'utente ha scelto la cartella principale.
    func setLibraryRoot(from pickedURL: URL) {
        saveBookmark(for: pickedURL)
        activate(url: pickedURL)
    }

    private func saveBookmark(for url: URL) {
        do {
            let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            UserDefaults.standard.set(data, forKey: bookmarkKey)
        } catch {
            lastAccessError = "Non è stato possibile salvare il permesso di accesso alla cartella."
        }
    }

    /// Rimuove la cartella principale impostata (es. per sceglierne un'altra dalle Impostazioni).
    func clearLibraryRoot() {
        if isAccessingRoot, let url = libraryRootURL {
            url.stopAccessingSecurityScopedResource()
        }
        isAccessingRoot = false
        libraryRootURL = nil
        UserDefaults.standard.removeObject(forKey: bookmarkKey)
    }
}
