# FumettiReader — guida al progetto (stato attuale)

Questo file riflette lo stato attuale completo del progetto. Le note delle fasi precedenti
restano negli altri file `NN_LEGGIMI_*.md` per riferimento, ma per assemblare tutto da zero
oggi basta questo documento.

## Cosa fa l'app, ad oggi

- Sceglie una cartella principale tramite il picker di sistema e ricorda il permesso tra un
  avvio e l'altro.
- Scansiona ricorsivamente cercando .cbz / .pdf / .epub, fino al numero di livelli di
  sottocartelle impostato (6 di default).
- Mostra le copertine vere in griglia, con titolo dai metadati quando disponibili.
- Apre e legge CBZ, PDF ed EPUB a layout fisso (CBR non ancora — vedi più sotto).
- Lettura standard (scorrimento orizzontale) o con arricciatura di pagina, entrambe consapevoli
  della direzione di lettura (sinistra→destra oppure destra→sinistra come i manga, rilevata
  automaticamente o forzabile).
- Salva dove sei rimasto per ogni fumetto, i segnalibri manuali, il tempo di lettura attivo e
  quanto ci hai messo a finirlo; puoi segnare un fumetto come letto anche a mano.
- Griglia pagine (tocco in basso nel lettore) per saltare direttamente a una pagina.
- Ricerca globale su tutta la libreria.

**Non ancora presente**: supporto CBR, la navigazione a swipe tra cartelle (verticale tra
cartelle allo stesso livello, orizzontale per entrare in una sottocartella, con l'anteprima
semi-trasparente ai bordi — per ora la navigazione è a tocco), guided view, bubble zoom e
traduzione, incognito, sfondo personalizzato.

## Struttura dei file

```
Sources/
  App/FumettiReaderApp.swift
  Models/
    Enums.swift, ComicMetadata.swift, ComicFile.swift, ComicFolder.swift,
    ProgressAndBookmarks.swift, AppSettings.swift
  Services/
    FileSystemManager.swift, LibraryScanner.swift, ComicParsingService.swift,
    CoverCache.swift, ReadingProgressStore.swift
    Parsers/
      ComicInfoXMLReader.swift, CBZParser.swift, PDFParser.swift, EPUBParser.swift
  Reader/
    ReaderDocument.swift, ReaderViewModel.swift
  ViewModels/LibraryViewModel.swift
  Utilities/DarkTheme.swift, Extensions.swift
  Views/
    FolderPickerPromptView.swift, LibraryRootView.swift, ReaderView.swift
```

Come sempre: la cartella in cui metti ciascun file in Playgrounds non conta ai fini della
compilazione, è solo per tenerti organizzato.

## Come assemblare in Swift Playgrounds (solo Playgrounds, niente Mac/Xcode)

1. Crea un nuovo progetto di tipo **App** in Swift Playgrounds su iPad.
2. Playgrounds crea due file di partenza: quello con `@main` e un `ContentView.swift`.
   - Sostituisci il contenuto del file con `@main` con quello di `Sources/App/FumettiReaderApp.swift`.
   - Elimina `ContentView.swift`.
   - Deve restare **una sola** `@main` in tutto il progetto.
3. Crea gli altri file uno per uno con lo stesso nome e incollaci il contenuto corrispondente.
4. Nessuna libreria esterna da aggiungere: dalla Fase attuale il progetto legge i file CBZ/EPUB
   (che sono archivi ZIP) con un lettore scritto apposta usando solo Foundation e il framework
   Compression di Apple — niente più dipendenze di terze parti, niente Swift Package da
   aggiungere (vedi `05_LEGGIMI_NIENTE_PIU_ZIPFOUNDATION.md` per il perché).
5. Versione minima iOS: 17 o successiva.
6. Compila ed esegui (▶).

## Se qualcosa non compila

Ho lasciato dei commenti nei punti più a rischio (dove il codice apre un archivio ZIP): a
volte le librerie di terze parti cambiano leggermente sintassi da una versione all'altra.
Se Playgrounds segnala un errore in uno di quei punti, mandami il testo esatto dell'errore.

## Prossimo passo

La navigazione a swipe tra cartelle con l'anteprima semi-trasparente ai bordi, così com'era
stata pensata all'inizio.
