# FumettiReader — Niente più ZIPFoundation

## Cosa è cambiato

Il progetto non usa più la libreria esterna ZIPFoundation per leggere CBZ ed EPUB (che sono
entrambi, di fatto, archivi ZIP). Al suo posto c'è `Sources/Services/MinimalZipArchive.swift`,
scritto usando solo **Foundation** e **Compression** — entrambi framework di Apple già inclusi
in iOS, niente da scaricare o aggiungere.

**Non serve più aggiungere nessuna dipendenza Swift Package.** Se avevi già aggiunto
ZIPFoundation al progetto seguendo le istruzioni di una fase precedente, puoi rimuoverla
(non fa più niente di utile, ma non dovrebbe nemmeno dare problemi se la lasci).

## Perché

Con ZIPFoundation come dipendenza esterna abbiamo avuto, in sequenza, tre problemi diversi
provando a far girare il progetto su Swift Playgrounds:

1. Un'icona segnaposto con un nome non valido nel file di configurazione del pacchetto
   (`.books` invece di `.openBook`) — errore di configurazione, non del codice del progetto.
2. La modalità di concorrenza Swift 6, troppo rigida per come era scritto il resto del codice.
3. La firma del bundle di risorse che ZIPFoundation include per la dichiarazione privacy
   richiesta da Apple (introdotta nella versione 0.9.17): Swift Playgrounds non riusciva a
   firmarlo correttamente per l'esecuzione su dispositivo.

Il terzo problema in particolare non aveva una soluzione semplice e affidabile lato nostro: è
un problema abbastanza diffuso con le librerie di terze parti che includono un file di privacy,
non specifico di questo progetto. Piuttosto che continuare a inseguire un problema alla volta,
è stato tolto il problema alla radice: quello che serve davvero (elencare i file dentro un
archivio ZIP ed estrarne uno) è una parte piccola di quello che fa una libreria ZIP completa,
e la parte più delicata (la decompressione) è già inclusa in iOS.

## Limiti onesti di questa soluzione fatta in casa

Legge SOLO archivi ZIP (non ne crea né modifica), senza cifratura, un solo volume — che è
esattamente il caso dei file CBZ ed EPUB che l'app deve aprire. Non gestisce:

- Archivi cifrati con password (rarissimi per CBZ/EPUB; se capita, l'app mostrerà un errore
  invece di aprire il file, anziché fallire in modo silenzioso).
- Archivi multi-volume (`.z01`, `.z02`, ecc. — non usati per fumetti/ebook).
- Metodi di compressione diversi da "memorizzato" (nessuna compressione) e "deflate" (il
  metodo usato dalla stragrande maggioranza degli strumenti che creano CBZ/EPUB).

Se mai dovessi incontrare un file che non si apre per uno di questi motivi, fammelo sapere.
