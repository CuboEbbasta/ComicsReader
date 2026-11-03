import Foundation

/// Contiene tutte le impostazioni dell'app e le salva automaticamente in UserDefaults
/// ogni volta che cambiano. Le viste vi accedono tramite @EnvironmentObject.
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    /// Quanti livelli di sottocartelle esplorare al massimo durante la scansione,
    /// per non appesantire l'app con librerie enormi. Modificabile dalle Impostazioni
    /// (interfaccia che arriverà in una fase successiva).
    @Published var maxFolderDepth: Int {
        didSet { UserDefaults.standard.set(maxFolderDepth, forKey: Keys.maxFolderDepth) }
    }

    @Published var defaultReadingMode: ReadingMode {
        didSet { UserDefaults.standard.set(defaultReadingMode.rawValue, forKey: Keys.defaultReadingMode) }
    }

    @Published var defaultPageLayout: PageLayoutMode {
        didSet { UserDefaults.standard.set(defaultPageLayout.rawValue, forKey: Keys.defaultPageLayout) }
    }

    @Published var defaultTransitionStyle: PageTransitionStyle {
        didSet { UserDefaults.standard.set(defaultTransitionStyle.rawValue, forKey: Keys.defaultTransitionStyle) }
    }

    @Published var defaultReadingDirection: ReadingDirection {
        didSet { UserDefaults.standard.set(defaultReadingDirection.rawValue, forKey: Keys.defaultReadingDirection) }
    }

    @Published var incognitoAuthMethod: IncognitoAuthMethod {
        didSet { UserDefaults.standard.set(incognitoAuthMethod.rawValue, forKey: Keys.incognitoAuthMethod) }
    }

    private enum Keys {
        static let maxFolderDepth = "settings.maxFolderDepth"
        static let defaultReadingMode = "settings.defaultReadingMode"
        static let defaultPageLayout = "settings.defaultPageLayout"
        static let defaultTransitionStyle = "settings.defaultTransitionStyle"
        static let defaultReadingDirection = "settings.defaultReadingDirection"
        static let incognitoAuthMethod = "settings.incognitoAuthMethod"
    }

    private init() {
        let d = UserDefaults.standard
        maxFolderDepth = d.object(forKey: Keys.maxFolderDepth) as? Int ?? 6
        defaultReadingMode = ReadingMode(rawValue: d.string(forKey: Keys.defaultReadingMode) ?? "") ?? .standard
        defaultPageLayout = PageLayoutMode(rawValue: d.string(forKey: Keys.defaultPageLayout) ?? "") ?? .single
        defaultTransitionStyle = PageTransitionStyle(rawValue: d.string(forKey: Keys.defaultTransitionStyle) ?? "") ?? .horizontalScroll
        defaultReadingDirection = ReadingDirection(rawValue: d.string(forKey: Keys.defaultReadingDirection) ?? "") ?? .auto
        incognitoAuthMethod = IncognitoAuthMethod(rawValue: d.string(forKey: Keys.incognitoAuthMethod) ?? "") ?? .pin
    }
}
