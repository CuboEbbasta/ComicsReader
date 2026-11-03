import Foundation

/// Formato di un fumetto riconosciuto dall'app.
enum ComicFormat: String, Codable, CaseIterable, Hashable, Sendable {
    case cbz, cbr, pdf, epub

    /// Riconosce il formato a partire dall'estensione del file (case-insensitive).
    static func from(fileExtension: String) -> ComicFormat? {
        ComicFormat(rawValue: fileExtension.lowercased())
    }

    var displayName: String {
        switch self {
        case .cbz: return "CBZ"
        case .cbr: return "CBR"
        case .pdf: return "PDF"
        case .epub: return "EPUB"
        }
    }
}

/// Direzione di sfoglio delle pagine all'interno di un fumetto (non è la navigazione tra cartelle).
enum ReadingDirection: String, Codable, CaseIterable, Hashable, Sendable {
    case leftToRight
    case rightToLeft
    case auto

    var displayName: String {
        switch self {
        case .leftToRight: return "Sinistra → Destra"
        case .rightToLeft: return "Destra → Sinistra (Manga)"
        case .auto: return "Automatico"
        }
    }
}

/// Modalità di lettura di un fumetto.
enum ReadingMode: String, Codable, CaseIterable, Hashable, Sendable {
    case standard
    case guidedView   // GVN: mostra una vignetta alla volta
    case bubbleZoom   // Zoom sulle zone di testo/dialogo

    var displayName: String {
        switch self {
        case .standard: return "Standard"
        case .guidedView: return "Lettura guidata (GVN)"
        case .bubbleZoom: return "Bubble Zoom"
        }
    }
}

/// Quante pagine mostrare contemporaneamente durante la lettura standard.
enum PageLayoutMode: String, Codable, CaseIterable, Hashable, Sendable {
    case single
    case double

    var displayName: String {
        switch self {
        case .single: return "Una pagina"
        case .double: return "Due pagine"
        }
    }
}

/// Effetto di transizione tra una pagina e l'altra.
enum PageTransitionStyle: String, Codable, CaseIterable, Hashable, Sendable {
    case horizontalScroll
    case pageCurl

    var displayName: String {
        switch self {
        case .horizontalScroll: return "Scorrimento orizzontale"
        case .pageCurl: return "Arricciatura pagina"
        }
    }
}

/// Metodo di sblocco per la modalità Incognito.
enum IncognitoAuthMethod: String, Codable, CaseIterable, Hashable, Sendable {
    case pin
    case faceID
    case touchID

    var displayName: String {
        switch self {
        case .pin: return "PIN"
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        }
    }
}
