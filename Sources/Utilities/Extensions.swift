import Foundation

extension Int64 {
    /// Formatta la dimensione del file in modo leggibile (es. "12,4 MB").
    var formattedFileSize: String {
        ByteCountFormatter.string(fromByteCount: self, countStyle: .file)
    }
}

extension Array {
    /// Accesso sicuro a un indice: restituisce nil invece di andare in crash se non è valido.
    /// Tornerà utile nella Fase 3 per la navigazione tra le pagine.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

extension String {
    /// Scompone il relativePath (es. "Fumetti/Manga/Frieren") nei singoli componenti per il breadcrumb.
    var pathComponentsForBreadcrumb: [String] {
        split(separator: "/").map { String($0) }
    }
}
