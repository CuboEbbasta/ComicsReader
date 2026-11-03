import Foundation

/// Rappresenta un singolo fumetto/file leggibile trovato nella libreria.
struct ComicFile: Identifiable, Hashable, Sendable {
    let relativePath: String
    let fileName: String
    let format: ComicFormat
    let fileSizeBytes: Int64
    let modificationDate: Date?
    /// Metadati (titolo, autore...): vuoti finché non vengono letti in modo asincrono dopo la
    /// scansione (es. da ComicInfo.xml o dall'OPF di un EPUB). Finché sono vuoti l'interfaccia
    /// usa displayTitle al loro posto.
    var metadata: ComicMetadata = .empty

    var id: String { relativePath }

    /// Titolo di riserva quando non ci sono ancora metadati: il nome del file senza estensione.
    var displayTitle: String {
        (fileName as NSString).deletingPathExtension
    }
}
