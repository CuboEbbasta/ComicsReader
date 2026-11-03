# FumettiReader — Navigazione a swipe, e correzioni

## Navigazione a swipe (finalmente)

La libreria ora si naviga con lo swipe invece che a tocco:

- **Verticale** cambia elemento tra "fratelli" dello stesso livello (altre cartelle o fumetti
  nella stessa cartella).
- **Orizzontale**: verso sinistra entra nella cartella corrente, verso destra risale al genitore.
- Basata sull'albero vero (`ComicFolder.parent`/`subfolders`/`comics`), non su una sequenza di
  schermate finta: `SwipeLibraryViewModel` tiene traccia di "in quale cartella sto guardando
  l'N-esimo figlio" e per risalire ritrova semplicemente il genitore.
- Percorso sempre visibile in basso, semi-trasparente.
- Agli angoli/lati si intravede, semi-trasparente, l'elemento verso cui è possibile spostarsi
  (fratello sopra/sotto, genitore a sinistra, anteprima del contenuto a destra), e tutto si
  sposta seguendo il dito durante il trascinamento.

Un tocco sull'elemento centrale lo attiva: entra nella cartella, oppure apre il fumetto.

La vecchia navigazione a tocco (`LibraryFolderView`, griglia a 3/4 colonne) resta nel progetto
ma non più collegata alla schermata principale — la usa ancora la ricerca globale, che mostra
un elenco piatto di risultati per cui la logica ad albero non si applica.

## Correzioni

- **Doppia pagina**: non funzionava perché la larghezza di ogni pagina veniva calcolata a mano
  da una dimensione letta tramite GeometryReader, probabilmente non ancora affidabile al primo
  disegno. Ora ogni pagina dello spread usa semplicemente `.frame(maxWidth: .infinity)`, che
  HStack distribuisce equamente da solo — più semplice e più affidabile.
- **Arricciatura**: tolta la cache dei controller di pagina aggiunta la volta scorsa (sembra
  essere stata la causa per cui l'animazione non si vedeva più, sostituita da uno scatto
  istantaneo). Per la direzione manga, invece di scambiare a mano i numeri di pagina, ora si usa
  `semanticContentAttribute` — il modo corretto e nativo di dire al controller che il contenuto
  si legge da destra a sinistra, che dovrebbe far coincidere gesto e verso dell'arricciatura
  senza doverli far combaciare a mano.
- **Pulsante indietro mancante su errore**: se un fumetto non si apre (es. un CBR, non ancora
  supportato), ora c'è comunque un modo per tornare indietro — prima capitava di restare bloccati
  perché il pulsante indietro personalizzato compariva solo in caso di apertura riuscita.
- **Impostazioni "storte"**: riscritte con lo stesso stile scuro esplicito del resto dell'app
  invece di affidarsi allo stile di sistema (era rimasta l'unica schermata a farlo).
- **Copertine**: le copertine nella griglia ora riempiono sempre la cornice ("fill" invece di
  "fit"), invece di apparire più piccole quando le proporzioni della fonte (tipicamente i PDF)
  differiscono da quelle standard di un fumetto.

## Nota onesta sulla navigazione a swipe

È un sistema di gesti completamente nuovo, con animazioni e logica di trascinamento non banali:
è la parte con più codice nuovo mai scritta in un colpo solo in questo progetto, e non ho potuto
testarla. Prova con calma tutte le direzioni (su/giù tra fratelli, sinistra/destra tra livelli,
risalita dal fondo dell'albero fino alla radice) e dimmi cosa non torna.
