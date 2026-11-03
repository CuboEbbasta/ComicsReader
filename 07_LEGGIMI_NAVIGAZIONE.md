# FumettiReader — Regola di navigazione definitiva + correzioni

## Il modello corretto (grazie al documento che mi hai mandato)

Avevo capito l'asse orizzontale al contrario. Ora è così, senza ambiguità:

- **Verticale** = esplora il contenuto del contesto corrente (scroll naturale nella griglia:
  sotto-cartelle e fumetti diretti, mostrati in due gruppi separati con titolo e riga divisoria
  — "Serie" e "Fumetti" — mai mescolati come farebbe un file browser).
- **Orizzontale** = passa al contesto "fratello" successivo/precedente, cioè un'altra
  sottocartella dello stesso genitore, restando alla stessa profondità (es. da "Manga" a
  "Marvel", se sono entrambe sottocartelle dirette della stessa cartella principale). Anteprima
  semi-trasparente del nome ai bordi.
- **Toccare una tessera "Serie"**: quella cartella diventa il nuovo contesto (si scende di un
  livello).
- **Toccare un segmento del percorso in basso**: si risale direttamente a quel livello (il
  percorso è quindi anche il modo per tornare indietro, non serve un gesto apposito).
- Le cartelle non vengono mai mostrate come icona-cartella generica: usano una copertina vera,
  presa dal primo fumetto trovato al loro interno (anche in una sotto-sottocartella), per dare
  la sensazione di una libreria vera e non di un file manager.
- L'intero albero resta in memoria dalla scansione iniziale: cambiare contesto non riscansiona
  mai il filesystem, sposta solo quale cartella è quella "corrente".

## Altre correzioni di questo giro

- **Titolo/pulsante impostazioni sovrapposti**: mancava forzare la modalità compatta del titolo
  (`.navigationBarTitleDisplayMode(.inline)`) — su iPad quella grande, di default, si scontrava
  con il pulsante.
- **Doppia pagina ancora a 1 sola pagina**: trovata la causa vera — l'avevo corretta solo nel
  lettore a scorrimento, non in quello ad arricciatura. Se avevi l'arricciatura attiva, restava
  sempre a una pagina sola indipendentemente da qualunque correzione precedente. Ora anche
  l'arricciatura raggruppa le pagine a coppie quando il layout è "due pagine".

## Nota

Anche questa è una riscrittura sostanziale della homepage, non testabile da parte mia. Prova con
calma: scorrimento verticale dentro una cartella con più fumetti di quanti ne stiano a schermo,
swipe orizzontale tra due sottocartelle sorelle, tocco su una cartella per entrarci, tocco sul
percorso in basso per risalire.
