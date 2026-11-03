import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Schermata mostrata al primo avvio (o se il permesso alla cartella è andato perso):
/// chiede di scegliere la cartella principale dove si trovano i fumetti.
struct FolderPickerPromptView: View {
    @EnvironmentObject private var fileSystemManager: FileSystemManager
    @State private var isPickerPresented = false

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "books.vertical.fill")
                .font(.system(size: 64))
                .foregroundStyle(DarkTheme.accent)

            Text("Benvenuto")
                .font(.title.bold())
                .foregroundStyle(DarkTheme.textPrimary)

            Text("Scegli la cartella principale dove tieni i tuoi fumetti.\nPotrai organizzarli in sottocartelle come Manga, Comics Americani, Webtoon…")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(DarkTheme.textSecondary)
                .padding(.horizontal, 32)

            if let error = fileSystemManager.lastAccessError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Button {
                isPickerPresented = true
            } label: {
                Text("Scegli cartella")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 14)
                    .background(DarkTheme.accent, in: Capsule())
            }

            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DarkTheme.background.ignoresSafeArea())
        .sheet(isPresented: $isPickerPresented) {
            FolderPickerView { pickedURL in
                fileSystemManager.setLibraryRoot(from: pickedURL)
            }
            .ignoresSafeArea()
        }
    }
}

/// Wrapper SwiftUI attorno a UIDocumentPickerViewController per scegliere una cartella dal Files system.
/// Usa `asCopy: false` (il valore di default) in modo da ottenere un riferimento diretto alla cartella
/// scelta, e non una copia: è quello che ci permette di leggerne il contenuto e tenerlo aggiornato.
struct FolderPickerView: UIViewControllerRepresentable {
    var onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            onPick(url)
        }
    }
}
