import Foundation

/// Nodo dell'albero delle cartelle. Ogni cartella può contenere sia sottocartelle
/// (che diventano sottocategorie) sia fumetti veri e propri.
final class ComicFolder: Identifiable, Hashable {
    let relativePath: String
    let name: String
    let depth: Int
    var subfolders: [ComicFolder]
    var comics: [ComicFile]
    weak var parent: ComicFolder?

    var id: String { relativePath.isEmpty ? "__root__" : relativePath }

    init(
        relativePath: String,
        name: String,
        depth: Int,
        subfolders: [ComicFolder] = [],
        comics: [ComicFile] = [],
        parent: ComicFolder? = nil
    ) {
        self.relativePath = relativePath
        self.name = name
        self.depth = depth
        self.subfolders = subfolders
        self.comics = comics
        self.parent = parent
    }

    static func == (lhs: ComicFolder, rhs: ComicFolder) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }

    var totalComicCount: Int {
        comics.count + subfolders.reduce(0) { $0 + $1.totalComicCount }
    }

    var isEmpty: Bool {
        comics.isEmpty && subfolders.allSatisfy { $0.isEmpty }
    }

    /// Componenti del percorso per il breadcrumb. Alla radice mostra il nome della radice stessa
    /// (es. ["Fumetti"]), non un elenco vuoto.
    var breadcrumbComponents: [String] {
        guard !relativePath.isEmpty else { return [name] }
        return relativePath.pathComponentsForBreadcrumb
    }

    /// Tutti i fumetti in questa cartella e in ogni sottocartella, appiattiti in un unico elenco.
    /// Usato dalla ricerca globale.
    var allComicsRecursive: [ComicFile] {
        comics + subfolders.flatMap { $0.allComicsRecursive }
    }

    /// Un fumetto "rappresentativo" della cartella, da mostrare al posto di una generica icona
    /// cartella: la homepage non deve sembrare un file browser, quindi anche i sotto-contenitori
    /// si presentano con una copertina vera. Il primo trovato scendendo nell'albero (stesso
    /// ordine della scansione: prima le sottocartelle, poi i fumetti diretti).
    var representativeComic: ComicFile? {
        if let first = comics.first { return first }
        for subfolder in subfolders {
            if let found = subfolder.representativeComic { return found }
        }
        return nil
    }

    /// Fino a `limit` fumetti rappresentativi, per un piccolo mosaico di copertine invece di
    /// una sola: usato dal carosello spaziale ai lati/in basso, dove si deve intravedere
    /// davvero il contenuto della cartella adiacente, non solo il suo nome.
    func representativeComics(limit: Int) -> [ComicFile] {
        var result: [ComicFile] = []
        func visit(_ folder: ComicFolder) {
            for comic in folder.comics {
                guard result.count < limit else { return }
                result.append(comic)
            }
            for subfolder in folder.subfolders {
                guard result.count < limit else { return }
                visit(subfolder)
            }
        }
        visit(self)
        return result
    }
}
