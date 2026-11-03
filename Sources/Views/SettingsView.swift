import SwiftUI

/// Impostazioni: lettura, libreria, privacy (Incognito — unico punto da cui vi si accede),
/// statistiche. Aspetto (sfondo personalizzato, colore o immagine) resta per una fase successiva.
///
/// Stile scuro esplicito invece di affidarsi a quello di sistema: era l'unica schermata rimasta
/// senza, e mescolata al resto (tutto nero, personalizzato) poteva apparire incoerente.
struct SettingsView: View {
    @ObservedObject var settings: AppSettings
    @EnvironmentObject private var fileSystemManager: FileSystemManager
    @ObservedObject private var incognitoStore = IncognitoStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var showChangeFolderConfirmation = false
    @State private var showIncognitoUnlock = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Direzione", selection: $settings.defaultReadingDirection) {
                        ForEach(ReadingDirection.allCases, id: \.self) { Text($0.displayName).tag($0) }
                    }
                    Picker("Cambio pagina", selection: $settings.defaultTransitionStyle) {
                        ForEach(PageTransitionStyle.allCases, id: \.self) { Text($0.displayName).tag($0) }
                    }
                    Picker("Pagine visibili", selection: $settings.defaultPageLayout) {
                        ForEach(PageLayoutMode.allCases, id: \.self) { Text($0.displayName).tag($0) }
                    }
                } header: {
                    Text("Lettura")
                }

                Section {
                    Stepper("Livelli di sottocartelle: \(settings.maxFolderDepth)", value: $settings.maxFolderDepth, in: 1...12)
                    Button("Cambia cartella principale", role: .destructive) {
                        showChangeFolderConfirmation = true
                    }
                } header: {
                    Text("Libreria")
                }

                Section {
                    NavigationLink {
                        StatisticsView()
                    } label: {
                        Label("Statistiche", systemImage: "chart.bar.fill")
                    }
                }

                Section {
                    Toggle("Richiedi autenticazione per Incognito", isOn: $incognitoStore.requiresAuthentication)
                    Picker("Metodo preferito", selection: $settings.incognitoAuthMethod) {
                        ForEach(IncognitoAuthMethod.allCases, id: \.self) { Text($0.displayName).tag($0) }
                    }
                    Button {
                        if incognitoStore.isEnabled {
                            incognitoStore.exit()
                        } else {
                            Task {
                                let unlocked = await incognitoStore.attemptQuickUnlock(method: settings.incognitoAuthMethod)
                                if !unlocked { showIncognitoUnlock = true }
                            }
                        }
                    } label: {
                        Label(
                            incognitoStore.isEnabled ? "Esci da Incognito" : "Entra in Incognito",
                            systemImage: incognitoStore.isEnabled ? "eye.fill" : "eye.slash"
                        )
                    }
                    if incognitoStore.hasPIN {
                        Button("Cambia PIN") {
                            incognitoStore.clearPIN()
                            showIncognitoUnlock = true
                        }
                        Button("Rimuovi PIN", role: .destructive) { incognitoStore.clearPIN() }
                    }
                } header: {
                    Text("Privacy")
                } footer: {
                    Text("Nella libreria, tieni premuta la copertina di un fumetto per nasconderlo o mostrarlo. Questo è l'unico punto da cui si entra/esce da Incognito.")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color.black.ignoresSafeArea())
            .tint(DarkTheme.accent)
            .foregroundStyle(.white)
            .navigationTitle("Impostazioni")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(Color.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Fatto") { dismiss() } }
            }
            .confirmationDialog(
                "Cambiare cartella principale?",
                isPresented: $showChangeFolderConfirmation,
                titleVisibility: .visible
            ) {
                Button("Cambia cartella", role: .destructive) {
                    fileSystemManager.clearLibraryRoot()
                    dismiss()
                }
                Button("Annulla", role: .cancel) {}
            } message: {
                Text("Dovrai scegliere di nuovo la cartella dei fumetti. Progressi e segnalibri restano salvati.")
            }
            .sheet(isPresented: $showIncognitoUnlock) {
                IncognitoUnlockView(incognitoStore: incognitoStore)
            }
        }
        .preferredColorScheme(.dark)
    }
}
