import SwiftUI

@main
struct FumettiReaderApp: App {
    @StateObject private var fileSystemManager = FileSystemManager.shared
    @StateObject private var appSettings = AppSettings.shared

    var body: some Scene {
        WindowGroup {
            RootSwitchView()
                .environmentObject(fileSystemManager)
                .environmentObject(appSettings)
                .preferredColorScheme(.dark)
        }
    }
}

/// Decide se mostrare la richiesta di scelta della cartella principale oppure la libreria vera e propria,
/// in base al fatto che l'accesso a una cartella sia già stato concesso in precedenza.
struct RootSwitchView: View {
    @EnvironmentObject private var fileSystemManager: FileSystemManager

    var body: some View {
        Group {
            if fileSystemManager.libraryRootURL != nil {
                LibraryRootView()
            } else {
                FolderPickerPromptView()
            }
        }
    }
}
