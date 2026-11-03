# FumettiReader — Lettore, EPUB, tempo di lettura, ricerca

Questo file spiega cosa è cambiato integrando e correggendo il file che avevi caricato, più
le aggiunte richieste in questo passaggio.

## Bug corretti nel file caricato

Questi sarebbero stati problemi reali, non solo differenze di stile:

- **`.withSecurityScope` nel permesso alla cartella**: questa opzione non esiste su iOS (esiste
  solo su macOS — confermato anche da un ingegnere Apple sui forum ufficiali). Usarla avrebbe
  impedito la compilazione dell'intero progetto. Corretto con la sintassi giusta per iOS.
- **`import PDFKit` mancante** nel file che apre i PDF nel lettore: altro errore di compilazione.
- **Ripresa lettura**: il lettore "a scorrimento" (non arricciatura) apriva sempre il fumetto
  dalla prima pagina, ignorando la pagina salvata — nonostante il dato fosse salvato
  correttamente, non veniva letto all'apertura.
- **Arricciatura pagina e manga**: lo swipe per sfogliare non teneva conto della direzione di
  lettura; ora è invertito correttamente per la lettura destra-verso-sinistra.
- **CBZ riaperto a ogni pagina**: ogni cambio pagina riapriva l'intero archivio da zero invece
  di tenerlo aperto per tutta la sessione di lettura — su archivi grandi si sarebbe sentito.
- **Limite di profondità cartelle** e **scelta del metodo per l'incognito** erano stati tolti
  dalle impostazioni: rimessi.
- **Metadati mai collegati**: il campo per titolo/autore esisteva nel modello ma non veniva mai
  effettivamente letto e mostrato da nessuna parte. Ora viene recuperato insieme alla copertina.

## EPUB a layout fisso

Un EPUB è di fatto un file ZIP. Per arrivare alla pagina bisogna seguire tre passaggi dentro
l'archivio: `META-INF/container.xml` (dice dove sta l'indice del libro) → il file `.opf`
(l'indice: elenco pagine in ordine, titolo, autore) → il file `.xhtml` di ogni singola pagina,
che contiene il riferimento all'immagine vera e propria. Il parser segue questa catena e usa
solo l'immagine, ignorando testo scorrevole.

**Limite onesto**: funziona bene con gli EPUB a layout fisso (lo standard per i fumetti, dove
ogni pagina è di fatto un'immagine intera) e con quelli "ibridi" dove ogni pagina ha comunque
un'immagine a piena pagina. Un EPUB di solo testo scorrevole (un romanzo, ad esempio) non avrà
nessuna pagina utilizzabile come fumetto e il file risulterà semplicemente non apribile — non
è nell'obiettivo di questa parte del progetto costruire un motore di lettura per testo fluente.

## Tempo di lettura e flag "letto"

- Ogni fumetto ora tiene: tempo attivo di lettura accumulato (si ferma quando l'app va in
  background), numero di sessioni, data di prima apertura, data di completamento.
- "Quanto ci hai messo a finirlo" = data di completamento meno data di prima apertura (tempo
  di calendario). "Quanto tempo hai passato sul fumetto" = il tempo attivo accumulato, mostrato
  sulla copertina una volta segnato come letto.
- Il segno di spunta in basso nel lettore segna/toglie "letto" manualmente in qualsiasi momento;
  altrimenti scatta da solo arrivando all'ultima pagina.

## Ricerca globale

Campo di ricerca in cima alla libreria: cerca per nome file e per titolo nei metadati, su tutti
i fumetti di tutte le cartelle insieme (non solo quella aperta al momento).

## Cosa manca ancora, di proposito

- **CBR**: serve una libreria diversa (per il formato RAR, che non è gestito da ZIPFoundation);
  la aggiungiamo nel prossimo passaggio insieme al parser.
- **Navigazione a swipe con anteprima ai bordi**: per ora le cartelle si aprono con un tocco.
  È un pezzo di interfaccia a sé, lo costruisco subito dopo questo.
- Guided view, bubble zoom, traduzione bolle, incognito, sfondo personalizzato: fasi successive
  già previste nella roadmap.
