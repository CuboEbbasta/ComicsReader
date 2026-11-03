# FumettiReader — Ritorno al progetto principale + Incognito, statistiche, dati di lettura

Da qui in avanti si lavora solo su questo progetto: niente più zip esterni da fondere. Il motore
di lettura (doppia pagina, arricciatura, EPUB classico vs fisso, filmstrip) era già stato
corretto nei round precedenti — qui si aggiunge quello che mancava.

## Novità

- **Incognito**: si entra/esce **solo dalle Impostazioni** (icona in alto a sinistra). PIN
  salvato nel Keychain (non in chiaro), oppure Face ID/Touch ID — se la biometria fallisce o non
  è configurata, non si entra comunque senza verifica, a differenza di un tentativo precedente.
  Per nascondere un fumetto: tieni premuta la copertina nella libreria → "Nascondi (Incognito)".
  Stesso menu per segnare un fumetto come letto/da leggere senza dover entrare nel lettore.
- **Statistiche**: nuova voce nelle Impostazioni, calcolata dai dati già salvati (nessun nuovo
  tracciamento): fumetti finiti, in lettura, pagine lette, tempo totale, sessioni, segnalibri,
  ultimi finiti.
- **Dati di lettura sulla copertina**: ora sotto ogni copertina compaiono insieme barra di
  progresso e percentuale numerica; per i fumetti finiti, tempo impiegato a leggerlo e tempo di
  calendario dalla prima apertura al completamento; per quelli in corso, una stima di quanto
  manca calcolata sul ritmo di lettura tenuto finora.
- **Modalità senza distrazioni di default**: ogni fumetto/libro si apre senza controlli
  visibili; un tocco li mostra, un altro li nasconde. Vale sia per il lettore a immagini sia per
  l'EPUB classico.
- **Refresh**: rimasto solo il tasto in alto, tolto lo swipe-per-aggiornare per evitare i due
  modi sovrapposti.
- **Griglia**: 4 colonne, righe incomplete centrate invece che appiccicate a sinistra.

## Ancora da fare (elenco onesto, non è cambiato molto rispetto a prima)

Navigazione a swipe tra cartelle con l'anteprima ai bordi (il pezzo grande, sempre rimandato),
sfondo personalizzabile (colore E immagine importata — per ora non c'è nessuno dei due),
impostazioni per singolo libro (richiamabili tenendo premuta la copertina, come suggerito),
Guided View, Bubble Zoom e traduzione bolle (da mettere dentro al lettore stesso quando ci
arriviamo, non nelle impostazioni generali, come giustamente fatto notare), CBR.
