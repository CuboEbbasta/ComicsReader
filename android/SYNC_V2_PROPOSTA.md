# Lumetto — Protocollo di sincronizzazione v2 (PROPOSTA)

Estende `SYNC_PROTOCOLLO.md` (v1) **senza romperlo**: un dispositivo v1 continua a sincronizzare progressi, segnalibri ecc.
con uno v2. Questo documento è il contratto tra Android (Claude Android) e iPad (Claude iOS): chi vuole cambiare qualcosa
lo scrive qui e avvisa l'altro. Stato: **bozza da approvare**.

Obiettivi richiesti dall'utente:
1. Vedere la **libreria dell'altro dispositivo** (elenco dei fumetti).
2. Vedere le **statistiche di ogni dispositivo**.
3. **Copiare un fumetto** da un dispositivo all'altro (es. scaricato su iPad → lo scarico anche su Android).
4. **Controllare** per ogni fumetto se è presente/uguale sui due dispositivi ("sincronizzato" o no).

## 1. Compatibilità

- Il saluto (frame 1) resta `{"v":1,"id","name","room"}` e **aggiunge** `"proto":2` e `"caps":["catalog","files"]`.
  I campi in più sono ignorati dai v1 (parser JSON tollerante). `v` NON cambia.
- Le funzioni v2 si usano solo se **entrambi** hanno `proto >= 2` e la capacità relativa.
- Condivisione **opt-in**: nelle impostazioni c'è «Condividi la libreria con la stanza» (default **spento**).
  Spento = `caps` senza `catalog`/`files`: l'altro dispositivo non vede nulla e non può scaricare.

## 2. Catalogo (elenco della libreria + statistiche)

Dopo i due frame v1 (saluto, dati), se entrambi hanno `catalog`, ciascuno invia un **terzo frame**: JSON cifrato come al §1 di v1,
ma con AAD = `"lumetto-catalog-v1"`:

```json
{
  "format": 1,
  "deviceId": "uuid", "deviceName": "iPad", "createdAt": 1762171200000,
  "libraryRoot": "Fumetti",
  "comics": [
    { "path": "Manga/One Piece/Vol 01.cbz", "size": 52428800, "modifiedAt": 1730000000000, "format": "cbz",
      "title": "One Piece 1", "sha256": null }
  ],
  "stats": { "sessionsHere": 42, "readingSeconds": 83210.5, "pagesRead": 1900, "completedHere": 12, "lastActiveAt": 1762170000000 }
}
```

- `path`: percorso relativo alla cartella principale (la stessa chiave di progressi e sincronizzazione).
- `size` in byte; `modifiedAt` in ms dal 1970; `format` ∈ `cbz|cbr|pdf|epub`.
- `sha256`: facoltativo (esadecimale minuscolo). Se manca, il confronto usa solo `size`. Calcolarlo al volo è costoso: si
  calcola solo durante un trasferimento (§3) e si può mettere in cache locale per (path, size, modifiedAt).
- `stats`: contatori **di questo dispositivo** (sessioni iniziate qui). Ogni dispositivo tiene localmente l'elenco degli id
  delle sessioni create da sé (`sessions-local.json`) per poterli contare. Prima di v2 non c'è storico per dispositivo.
- Massimo 10 000 voci per frame (oltre: `"truncated": true`).
- Il catalogo dell'ultimo scambio si **conserva** sul dispositivo ricevente (`peer-catalog-<deviceId>.json`) per mostrarlo
  anche quando l'altro non è collegato.

### Stato di un fumetto (calcolato da chi guarda, mai trasmesso)

| Stato | Condizione | Etichetta |
|---|---|---|
| uguale | stesso `path` su entrambi, stessa `size` (e stesso `sha256` se presente in entrambi) | Sincronizzato |
| diverso | stesso `path`, `size`/`sha256` diversi | Versione diversa |
| solo qui | presente solo su questo dispositivo | Solo su questo |
| solo là | presente solo sull'altro | Da scaricare |

Il confronto si fa sul percorso **normalizzato**: separatore `/`, Unicode NFC, maiuscole/minuscole significative.

## 3. Trasferimento di un fumetto (`files`)

Nuova connessione TCP allo stesso servizio `_lumetto._tcp` (stessa stanza, stesso scambio di saluto). Dopo il saluto
l'iniziatore invia una **richiesta** (frame cifrato, AAD `"lumetto-file-v1"`):

```json
{ "op": "get", "path": "Manga/One Piece/Vol 01.cbz" }
```

Chi condivide risponde con un frame di **intestazione** e poi con i **blocchi**:

```json
{ "ok": true, "path": "…", "size": 52428800, "sha256": "…", "chunks": 50 }
```
oppure `{ "ok": false, "error": "non-trovato|non-condiviso|troppo-grande" }`.

- Ogni blocco è un frame: `nonce(12) ‖ cifrato ‖ tag` con AES-256-GCM, chiave della stanza, **AAD = `"lumetto-file-v1" ‖ indice(4 byte big-endian)`**
  (l'indice impedisce di riordinare o ripetere blocchi). Dimensione in chiaro: 1 MiB (l'ultimo può essere più piccolo).
- Il ricevente scrive in un file temporaneo, alla fine verifica `size` e `sha256` e solo allora lo mette al suo posto.
  Se manca o non coincide: scarta e mostra errore. Mai sovrascrivere un file già presente: se esiste con lo stesso `path`
  (stato «Versione diversa») chiede conferma.
- Il fumetto viene salvato **nella cartella principale scelta, allo stesso percorso relativo**, creando le sottocartelle.
  Così progressi, segnalibri e metadati (chiavi = percorso) valgono subito anche sul nuovo dispositivo.
- Sicurezza: il percorso richiesto deve esistere nel catalogo del condivisore; rifiutare `..`, percorsi assoluti e caratteri
  di controllo. Un solo trasferimento alla volta per dispositivo; annullabile; limite facoltativo di dimensione (es. 2 GiB).
- Solo estensioni `cbz|cbr|pdf|epub`.

## 4. Interfaccia (identica nei due dispositivi, testi in italiano)

Impostazioni → Sincronizzazione → **Dispositivi della stanza**:
- per ogni dispositivo: nome, ultimo contatto, statistiche (§2) e pulsante **Libreria**;
- schermata Libreria dell'altro: elenco ad albero come la propria, con badge di stato (§2), ricerca, filtro «Da scaricare»;
- tocco su un «Da scaricare» → **Scarica** (con barra di avanzamento, annulla); selezione multipla facoltativa;
- «Sincronizza ora» aggiorna anche il catalogo.

## 5. Cosa deve fare ciascuno

**Android** (`ai-access`/`main`, cartella `android/`): `sync/SyncCatalog.kt` (logica pura: catalogo, confronto, chiavi),
estensione di `SyncManager` (terzo frame, server dei file, client di download), schermate in `sync/`, test con vettori di prova.

**iPad** (`Sources/`): stesso comportamento in Swift, stessi nomi di campo, stessi AAD. Non toccare il formato v1.

### Vettori di prova (da concordare e mettere nei test di entrambi)
- AAD blocco 0 = ASCII `lumetto-file-v1` seguito da `00 00 00 00`.
- Stato: ("a.cbz",100) vs ("a.cbz",100) → uguale; vs ("a.cbz",101) → diverso.

## 6. Punti aperti (decidere insieme)
1. Soglia di dimensione massima e permesso per rete mobile/hotspot.
2. Se mostrare anche i fumetti **nascosti in Incognito** dell'altro: proposta **no** (mai nel catalogo se nascosti).
3. Anteprime (copertine) nel catalogo: proposta rimandata alla v2.1 (richiede un frame per le miniature).
