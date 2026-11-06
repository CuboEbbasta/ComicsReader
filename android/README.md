# Lumetto per Android

Versione Android di **Lumetto**, il lettore di fumetti, manga ed EPUB (Kotlin + Jetpack Compose, Android 8.0+, telefono e tablet).
Stessa grafica, stesso marchio e stessa navigazione spaziale della versione per iPad, e sincronizzazione a stanze senza server
compatibile al 100% con l'iPad (vedi [SYNC_PROTOCOLLO.md](SYNC_PROTOCOLLO.md)).

## Scaricare l'APK

Link diretto all'ultima build: https://github.com/CuboEbbasta/ComicsReader/releases/download/android-latest/Lumetto.apk

In alternativa:

1. Apri la scheda **Actions** del repository.
2. Scegli l'ultima esecuzione di **APK Android** (pallino verde).
3. In fondo, sotto **Artifacts**, scarica **Lumetto-APK** (uno zip con dentro `Lumetto.apk`).
4. Copia `Lumetto.apk` sul telefono o tablet e aprilo (la prima volta Android chiede di consentire l'installazione da questa fonte).

Ogni APK è firmato con la stessa chiave (`app/lumetto.keystore`), quindi le versioni nuove si installano sopra le vecchie senza perdere i dati.
La chiave è solo per uso personale: per pubblicare su Google Play ne servirà una nuova e privata.

## Cosa c'è

- **Libreria spaziale**: carosello circolare di cartelle sorelle (← →), "Entra" o trascina oltre il fondo per scendere, oltre l'inizio per risalire, briciole di pane, puntini, "Continua a leggere", ricerca globale, metadati modificabili, modalità Incognito (PIN o impronta).
- **Cartella dei fumetti** scelta con il selettore di sistema: i file non vengono mai copiati né spostati.
- **Formati**: CBZ, CBR (RAR 4 anche compressi; RAR 5 se salvati senza compressione), PDF, EPUB a layout fisso e a testo scorrevole (con evidenziazioni).
- **Lettore**: scorrimento, "Sfoglia", verticale (webtoon); una o due pagine; direzione automatica/manga; zoom con pizzico e doppio tocco; miniature; segnalibri; sfondo per fumetto; tempo di lettura e statistiche.
- **Sincronizzazione v2**: stessa stanza (nome + password) sull'iPad e su Android, stessa rete Wi-Fi. Dalla scheda di un dispositivo si vedono le sue statistiche e la sua libreria a confronto con la tua (✓ uguale, ↓ manca, ! diversa) e si scaricano i fumetti mancanti, nella stessa sottocartella. Si sincronizzano progressi, completati, segnalibri, sessioni, evidenziazioni, metadati modificati e nascosti.

## Compilare

La build avviene su GitHub Actions (`.github/workflows/android.yml` nella radice del repo): prima i test del protocollo di sincronizzazione
(vettori di prova e regole di unione), poi `assembleRelease`. In locale, dalla cartella `android`: `gradle :app:assembleRelease` con JDK 17 e Android SDK 34.
