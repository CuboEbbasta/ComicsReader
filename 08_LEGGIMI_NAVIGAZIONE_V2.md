# FumettiReader — Navigazione v2 (documento + immagine di riferimento)

## Cosa è cambiato rispetto alla v1

- **Niente più griglia "Serie"**: le sottocartelle non si vedono come tessere da toccare.
  Solo la **prima sottocartella** è rappresentata, in fondo alla schermata, come un'anteprima
  semi-trasparente ("PRIMA SOTTOCARTELLA" + nome): tocco o swipe verso il basso per entrarci.
- **"Continua a leggere" solo alla radice**: nelle sottocartelle non compare più.
- **Radice trattata come caso speciale per l'orizzontale**: la radice non ha fratelli veri
  (è in cima all'albero), quindi lì lo swipe orizzontale mostra i SUOI figli diretti — da
  "prima di tutti" (radice) avanzando si entra nel primo figlio; da lì in poi ci si muove tra
  fratelli normali (altri figli dello stesso genitore).
- **Swipe verso l'alto, ovunque sullo schermo, risale al genitore** (oltre al pulsante freccia
  in alto vicino al titolo).
- **Titolo dinamico**: mostra sempre il nome della cartella corrente, con la sottolineatura
  colorata, invece del nome fisso dell'app.
- **Barra in basso**: aggiorna (sinistra), percorso (centro), precedente/successivo — frecce
  piatte, non pulsanti circolari — invece delle frecce circolari fluttuanti di prima.
- **Filmstrip del lettore**: in modalità due pagine, le miniature sono raggruppate a coppie con
  uno sfondo condiviso (sembrano "raccordate"), ma restano selezionabili singolarmente.
- **Nascondere un'intera cartella in Incognito**: spostato sul menu a pressione prolungata
  dell'anteprima "prima sottocartella" (prima era sulla griglia Serie, ora rimossa).

## Un'interpretazione che ho dovuto scegliere

Il documento non specifica esplicitamente cosa succede all'orizzontale quando si è ESATTAMENTE
alla radice (che non ha fratelli). Ho scelto l'interpretazione più aderente all'immagine che mi
hai mandato (dove "Fumetti" mostra ai lati "Manga" e "Webtoon", che sono suoi figli diretti):
alla radice l'orizzontale mostra i figli diretti invece dei fratelli (che non esistono). Se
l'idea era diversa, dimmelo pure.

## Non ancora fatto

CBR, sfondo personalizzabile, GVN/bubble zoom/traduzione, impostazioni per singolo libro.
