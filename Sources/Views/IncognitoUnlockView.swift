import SwiftUI

/// Mostrata quando bisogna sbloccare l'Incognito con il PIN: se non esiste ancora un PIN,
/// chiede di impostarne uno (due volte, per conferma); altrimenti chiede di inserirlo.
struct IncognitoUnlockView: View {
    @ObservedObject var incognitoStore: IncognitoStore
    @Environment(\.dismiss) private var dismiss
    var onUnlocked: () -> Void = {}

    @State private var enteredPIN = ""
    @State private var confirmPIN = ""
    @State private var errorMessage: String?
    @State private var isSettingUpPIN = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()
                Image(systemName: "lock.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(.white.opacity(0.7))

                if incognitoStore.hasPIN && !isSettingUpPIN {
                    Text("Inserisci il PIN").font(.title3.bold()).foregroundStyle(.white)
                    SecureField("PIN", text: $enteredPIN)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .multilineTextAlignment(.center)
                        .font(.title2.monospacedDigit())
                        .padding()
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 60)
                        .onSubmit(submitUnlock)

                    Button("Sblocca") { submitUnlock() }
                        .buttonStyle(.borderedProminent)
                        .disabled(enteredPIN.isEmpty)

                    Button("Ho dimenticato il PIN") {
                        isSettingUpPIN = true
                        enteredPIN = ""; confirmPIN = ""; errorMessage = nil
                    }
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.6))
                } else {
                    Text(isSettingUpPIN ? "Imposta un nuovo PIN" : "Imposta un PIN")
                        .font(.title3.bold()).foregroundStyle(.white)
                    Text("Serve per proteggere i contenuti che nasconderai.")
                        .font(.footnote).foregroundStyle(.white.opacity(0.6))

                    SecureField("Nuovo PIN", text: $enteredPIN)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .font(.title2.monospacedDigit())
                        .padding()
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 60)

                    SecureField("Conferma PIN", text: $confirmPIN)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .font(.title2.monospacedDigit())
                        .padding()
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 60)

                    Button("Salva PIN") { submitSetup() }
                        .buttonStyle(.borderedProminent)
                        .disabled(enteredPIN.count < 4)
                }

                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(.red)
                }
                Spacer()
                Spacer()
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Annulla") { dismiss() }
                }
            }
        }
    }

    private func submitUnlock() {
        if incognitoStore.verifyPIN(enteredPIN) {
            onUnlocked()
            dismiss()
        } else {
            errorMessage = "PIN errato."
            enteredPIN = ""
        }
    }

    private func submitSetup() {
        guard enteredPIN.count >= 4 else {
            errorMessage = "Usa almeno 4 cifre."
            return
        }
        guard enteredPIN == confirmPIN else {
            errorMessage = "I due PIN non coincidono."
            confirmPIN = ""
            return
        }
        incognitoStore.setPIN(enteredPIN)
        incognitoStore.verifyPIN(enteredPIN)
        onUnlocked()
        dismiss()
    }
}
