import Foundation
import LocalAuthentication

/// Gestisce la modalità Incognito: quali fumetti sono nascosti, se la sessione è attualmente
/// sbloccata, e l'autenticazione (PIN oppure Face ID/Touch ID, a seconda di cosa sceglie
/// l'utente nelle Impostazioni — è l'unico punto da cui si entra in Incognito).
///
/// Non è @MainActor: essendo un singleton letto da molte viste diverse tramite `.shared`,
/// isolarlo al thread principale crea più complicazioni (con Swift 6) di quante ne risolva;
/// in pratica viene comunque sempre letto/scritto da codice SwiftUI sul thread principale.
///
/// Non si entra MAI in Incognito senza una verifica riuscita: se la biometria non è disponibile
/// o fallisce, l'accesso resta negato — non c'è nessun percorso che "lascia entrare comunque".
final class IncognitoStore: ObservableObject {
    static let shared = IncognitoStore()

    @Published private(set) var isEnabled = false
    @Published private(set) var hiddenRelativePaths: Set<String>
    @Published var requiresAuthentication: Bool {
        didSet { UserDefaults.standard.set(requiresAuthentication, forKey: Keys.requiresAuthentication) }
    }

    private enum Keys {
        static let hiddenPaths = "privacy.hiddenRelativePaths.v1"
        static let requiresAuthentication = "privacy.incognitoRequiresAuthentication"
    }
    private let pinAccount = "incognito-pin"

    private init() {
        hiddenRelativePaths = Set(UserDefaults.standard.stringArray(forKey: Keys.hiddenPaths) ?? [])
        requiresAuthentication = UserDefaults.standard.object(forKey: Keys.requiresAuthentication) as? Bool ?? true
    }

    // MARK: - Contenuti nascosti

    func isHidden(_ relativePath: String) -> Bool {
        hiddenRelativePaths.contains(relativePath)
    }

    func toggleHidden(_ relativePath: String) {
        if hiddenRelativePaths.contains(relativePath) {
            hiddenRelativePaths.remove(relativePath)
        } else {
            hiddenRelativePaths.insert(relativePath)
        }
        UserDefaults.standard.set(Array(hiddenRelativePaths).sorted(), forKey: Keys.hiddenPaths)
    }

    // MARK: - Sessione

    func exit() { isEnabled = false }

    // MARK: - PIN (Keychain, non UserDefaults)

    var hasPIN: Bool { KeychainString.read(account: pinAccount) != nil }

    func setPIN(_ pin: String) {
        KeychainString.save(pin, account: pinAccount)
    }

    func clearPIN() {
        KeychainString.delete(account: pinAccount)
    }

    @discardableResult
    func verifyPIN(_ pin: String) -> Bool {
        guard !pin.isEmpty, let stored = KeychainString.read(account: pinAccount), stored == pin else {
            return false
        }
        isEnabled = true
        return true
    }

    // MARK: - Face ID / Touch ID

    /// `.deviceOwnerAuthentication` (non solo biometria): se Face ID/Touch ID non è configurato
    /// o fallisce, il sistema propone comunque il codice di sblocco del dispositivo, invece di
    /// lasciare passare senza alcuna verifica.
    func authenticateWithBiometrics(reason: String = "Sblocca i contenuti nascosti") async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            return false
        }
        do {
            let success = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            if success { isEnabled = true }
            return success
        } catch {
            return false
        }
    }

    /// Punto d'ingresso unico (dalle Impostazioni): se non serve autenticazione, entra subito.
    /// Se il metodo scelto è Face ID/Touch ID, prova subito la biometria. Se il metodo è PIN
    /// (o la biometria fallisce), restituisce false: la vista chiamante deve mostrare
    /// IncognitoUnlockView per l'inserimento/impostazione del PIN.
    func attemptQuickUnlock(method: IncognitoAuthMethod) async -> Bool {
        guard requiresAuthentication else { isEnabled = true; return true }
        switch method {
        case .pin:
            return false
        case .faceID, .touchID:
            return await authenticateWithBiometrics()
        }
    }
}
