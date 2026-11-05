# Guida per le AI che lavorano su Lumetto (Android)

Vale per qualsiasi assistente di programmazione (Claude Code, Codex, Cursor…).

## Cos'è
Lettore di fumetti/manga/EPUB. Kotlin + Jetpack Compose, pacchetto `com.cubo.lumetto`, minSdk 26, compileSdk 34, AGP 8.5.2, Kotlin 2.0.21, Gradle 8.9. La versione iPad (Swift, cartella `Sources/`) è la riferimento: grafica e comportamento devono restare uguali.

## Regole vincolanti
- Testi dell'interfaccia e commenti in **italiano**.
- Non toccare i file iOS (`Sources/`, `*_LEGGIMI*.md`) né la chiave di firma (arriva dai Secrets di GitHub, mai nel repo).
- La sincronizzazione con l'iPad è **vincolante**: leggi `SYNC_PROTOCOLLO.md` prima di toccare `sync/`, `data/Codec.kt`, `data/Stores.kt` (lapidi) o `model/`. Date = Double in ms dal 1970, UUID MAIUSCOLI, stessi nomi di campo del JSON iPad.
- Tema scuro, accento ambra `#E39B2D`, carosello spaziale ← → ↓ ↑ nella libreria.
- Se i test di sync falliscono, correggi il codice, non i test (salvo errore evidente nel test).

## Mappa del codice (`app/src/main/java/com/cubo/lumetto/`)
- `model/` modelli ed enum · `data/` Codec JSON, store (progressi, metadati, evidenziazioni, Incognito, lapidi), file/SAF, libreria
- `archive/` ZIP/RAR e parser CBZ/EPUB/PDF · `ui/` Compose (libreria, lettore, impostazioni)
- `sync/` crittografia e unione (puro) + rete NSD/TCP · `ai/` accesso AI (vedi `AI_ACCESSO.md`)

## Costruire e provare
Non c'è build locale obbligatoria: la CI (`.github/workflows/android.yml`) esegue `gradle :app:testReleaseUnitTest` e `gradle :app:assembleRelease` in `android/`. Push su `main` pubblica l'APK al link fisso `…/releases/download/android-latest/Lumetto.apk`; gli altri rami (`ai-access`) compilano senza pubblicare. Con Gradle 8.9 e JDK 17 in locale: stessi comandi.

## Convenzioni
- Modifiche piccole, un commit chiaro in italiano per giro.
- Le scritture sugli store passano dal thread principale. Nuove funzioni dati con logica pura vanno testate in `app/src/test`.
- Per estendere l'accesso AI: aggiungi uno `ToolSpec` e il ramo in `AiTools.call`; gli strumenti che modificano dati hanno `readOnly = false`.
