import SwiftUI

/// Palette centrale per il tema scuro predefinito dell'app. Tutte le viste dovrebbero attingere
/// i colori da qui invece di definirli localmente: così, quando nella Fase 4 aggiungeremo lo sfondo
/// personalizzabile dalle Impostazioni, basterà cambiare questo unico punto.
enum DarkTheme {
    static let background = Color(red: 0.05, green: 0.05, blue: 0.06)
    static let surface = Color(red: 0.11, green: 0.11, blue: 0.13)
    static let surfaceElevated = Color(red: 0.16, green: 0.16, blue: 0.18)
    static let accent = Color(red: 0.98, green: 0.35, blue: 0.25)
    static let textPrimary = Color.white
    static let textSecondary = Color.white.opacity(0.6)
    /// Usato per il percorso (breadcrumb) in basso: deve essere leggibile ma quasi trasparente.
    static let textTertiary = Color.white.opacity(0.35)
    static let divider = Color.white.opacity(0.08)
}
