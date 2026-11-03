# FumettiReader — Correzioni: EPUB classico, filmstrip, impostazioni

## EPUB: due lettori distinti, scelti automaticamente

Aprendo un EPUB, l'app ora controlla prima se è a layout fisso (fumetto: pagine-immagine) o
classico (testo scorrevole) leggendo il tag standard `rendition:layout` nel file indice
dell'EPUB, con un controllo di riserva se quel tag manca. In base al risultato apre:

- **Layout fisso** → lo stesso lettore a immagini di CBZ/PDF (arricciatura o scorrimento,
  filmstrip in basso, tutto come per un fumetto).
- **Classico** → un lettore nuovo (`EPUBFlowReaderView`) che mostra un capitolo alla volta con
  una WKWebView (il motore di Safari integrato in iOS: gestisce da solo testo, immagini e stile,
  cosa che sarebbe complicatissimo ricostruire a mano) e permette di ingrandire/rimpicciolire il
  testo. Qui "pagina salvata" corrisponde al capitolo, non alla riga esatta: paginare il testo
  scorrevole come un libro stampato vero e proprio è un lavoro a sé, che si può fare più avanti
  se serve.

## Impostazioni

Prima non esisteva alcuna schermata Impostazioni: anche correggendo il codice del lettore non
avresti avuto modo di attivare l'arricciatura pagina o cambiare direzione di lettura. Ora
l'icona in alto a sinistra apre una schermata minima con: direzione lettura, arricciatura vs
scorrimento, una/due pagine, livelli massimi di sottocartelle, cambio cartella (con conferma,
prima partiva senza chiedere nulla). Sfondo personalizzato e incognito restano per una fase
successiva.

## Filmstrip pagine

La griglia a schermo intero è stata sostituita da una striscia orizzontale in basso (come in
Panels), che si apre/chiude con lo stesso pulsante e scorre automaticamente fino alla pagina
corrente.

## Tempo di lettura

Sotto ogni copertina in libreria ora compaiono, quando disponibili: tempo di lettura attivo e,
se il fumetto è finito, quanto tempo ci è voluto dalla prima apertura al completamento. Restano
entrambi visibili insieme alla barra di progresso sulla copertina, non al posto di quella.

## Pulsante refresh

La lettura del contenuto della cartella ora passa da NSFileCoordinator invece che da una
lettura diretta: su cartelle esterne o su iCloud Drive, una lettura non coordinata può
restituire un elenco file non ancora aggiornato, il che spiegherebbe perché serviva un secondo
tentativo. Se dopo questa modifica capita ancora, fammelo sapere: potrebbe essere un'altra causa
(es. il campo di ricerca che intercetta il primo tocco) su cui vale la pena indagare oltre.
