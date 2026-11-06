# Lumetto — Protocollo di sincronizzazione v2

Sincronizzazione **senza server** tra i dispositivi di una stessa *stanza* (nome + password),
sulla stessa rete locale. Questo documento basta per implementarla su Android
(riferimento iOS: `Lumetto.swiftpm/Services/Sync/`, versione app 3.3.0).

**Novità della v2 rispetto alla v1:** ogni connessione è una *richiesta con risposta* con un'operazione
(`sync`, `library`, `file`); si può vedere la libreria dell'altro dispositivo e scaricarne i fumetti;
ogni sessione di lettura porta l'id del dispositivo (statistiche per dispositivo) e il pacchetto dati
contiene l'elenco dei dispositivi della stanza. La v1 non è compatibile: entrambe le app devono usare la v2.

## 1. Stanza e chiavi

- **Nome normalizzato**: nome senza spazi ai lati, in minuscolo (`"Casa Cubo "` → `"casa cubo"`).
- **Etichetta pubblica** (`tag`): primi 8 byte di `SHA-256("lumetto-stanza-v1:" + nome)`, in esadecimale minuscolo.
- **Chiave**: `PBKDF2-HMAC-SHA256(password UTF-8, sale = "lumetto-sync-v1:" + nome, 120000 iterazioni, 32 byte)`.
  Su Android: `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`.
- **Cifratura**: AES-256-GCM, nonce casuale di 12 byte, tag di 128 bit,
  dati associati (AAD) = `"lumetto-sync-v1"`. Pacchetto = `nonce ‖ testo cifrato ‖ tag`.
  Su Android: `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, nonce)`, `updateAAD(...)`;
  l'output di `doFinal` è già `testo cifrato ‖ tag`.

### Vettori di prova

| Voce | Valore |
|---|---|
| Nome stanza | `"Casa Cubo "` |
| Password | `fumetti123` |
| tag | `6d32e55d455662cd` |
| chiave | `f121a0579a3103296253297cf3edfd1886635bbd3c23fa398f9fabcf7e3ea4c9` |
| testo `{"format":1}` cifrato con nonce `000102…0b` | `000102030405060708090a0bc631a856484602b71fa704ab6de32e9a0df6cbe4f820113dc0ad0562` |

## 2. Identità e scoperta in rete (Bonjour / mDNS)

- **Id del dispositivo**: UUID in minuscolo, creato al primo avvio e mai cambiato.
- **Piattaforma**: `"ios"` o `"android"`.

Ogni dispositivo con l'app aperta e in una stanza:

- pubblica il servizio **`_lumetto._tcp`** (porta TCP scelta dal sistema), nome `Lumetto-<primi 8 caratteri dell'id>`;
- record TXT: `v` = `2`, `r` = tag della stanza, `id` = id del dispositivo, `n` = nome leggibile (max 40 caratteri), `p` = piattaforma;
- cerca `_lumetto._tcp` e considera solo i servizi con lo stesso `r`, `v` = `2` e un `id` diverso dal proprio.

Su Android: `NsdManager` (`registerService`, `discoverServices`, `resolveService`; TXT in `NsdServiceInfo.attributes`;
serve un `WifiManager.MulticastLock` durante la ricerca).

**Quando sincronizzare** (`op = sync`): quando compare un nuovo dispositivo apre la connessione solo quello con
l'`id` minore (confronto tra stringhe); dopo una modifica locale (attesa di 6 s) o con "Sincronizza ora" può
collegarsi chiunque, perché lo scambio è idempotente.

## 3. Connessioni (TCP)

Ogni *frame* = lunghezza in 4 byte big-endian + contenuto (massimo 64 MB).
Una connessione resta aperta finché passano dati; dopo **30 s senza traffico** si chiude.

**Chi apre la connessione** invia subito:
1. **Saluto** (JSON in chiaro): `{"v":2,"id":"…","name":"…","room":"<tag>","platform":"android","op":"sync|library|file"}`
2. **Richiesta** cifrata (§1).

**Chi riceve** legge i due frame, controlla `v == 2` e `room == tag` (altrimenti chiude), poi risponde con:
1. il **proprio saluto** (stesso `op`);
2. la **risposta** cifrata. Se non riesce a decifrare la richiesta (password diversa) risponde comunque con
   `{}` cifrato con la *propria* chiave e chiude: così chi ha chiesto, non riuscendo a decifrare, capisce subito
   che la password è diversa.

| op | Richiesta (cifrata) | Risposta (cifrata) |
|---|---|---|
| `sync` | pacchetto dati §4 di chi chiede | pacchetto dati §4 di chi risponde; poi **entrambi** uniscono (§5) e salvano |
| `library` | `{"op":"library"}` | elenco dei fumetti (§6) |
| `file` | `{"path":"Manga/Vol 01.cbz","offset":0}` | intestazione + blocchi (§7) |

## 4. Il pacchetto dati (JSON, `op = sync`)

Date = **millisecondi dal 1970** (numero). Campi facoltativi assenti = `null`/mancanti.
Le chiavi dei fumetti sono **percorsi relativi alla cartella principale** (es. `"Manga/One Piece/Vol 01.cbz"`).

```json
{
  "format": 2,
  "deviceId": "uuid", "deviceName": "iPad", "createdAt": 1762171200000,
  "progress": { "<percorso>": {
      "relativePath": "…", "currentPage": 12, "totalPages": 180,
      "totalReadingTime": 1520.5, "sessionCount": 3,
      "firstOpenedAt": 0, "lastReadAt": 0, "finishedAt": null,
      "isCompleted": false, "modifiedAt": 0 } },
  "bookmarks": [ { "id": "UUID", "relativePath": "…", "pageIndex": 4, "createdAt": 0 } ],
  "sessions": [ { "id": "UUID", "relativePath": "…", "title": "…", "startedAt": 0, "duration": 600.0,
                  "pagesRead": 20, "deviceId": "uuid del dispositivo su cui si è letto" } ],
  "highlights": [ { "id": "…", "path": "…", "chapter": 2, "start": 100, "end": 140,
                    "text": "…", "color": "yellow|green|blue|pink", "createdAt": 0, "modifiedAt": null } ],
  "metadataEdits": { "<percorso>": { "metadata": { "title": "…", "series": "…", "number": "…", "volume": "…",
      "writer": "…", "penciller": "…", "summary": "…", "year": 2001, "pageCount": 200,
      "languageISO": "it", "manga": true }, "modifiedAt": 0 } },
  "hidden": { "<percorso nascosto in Incognito>": 0 },
  "tombstones": { "progress": {"<percorso>": 0}, "bookmarks": {"<UUID>": 0}, "sessions": {"<UUID>": 0},
                  "highlights": {"<id>": 0}, "metadata": {"<percorso>": 0}, "hidden": {"<percorso>": 0} },
  "devices": { "<id dispositivo>": { "name": "iPad", "platform": "ios", "lastSeen": 0 } }
}
```

Note:
- Gli UUID di Swift sono in **maiuscolo** nel JSON (`"E621E1F8-…"`): conservali così come arrivano.
- `totalReadingTime` e `duration` sono in secondi.
- `sessions[].deviceId` può mancare nelle sessioni registrate prima della v2 ("prima della sincronizzazione").
- In `devices` ognuno mette sé stesso con `lastSeen` = adesso. Serve a dare un nome alle statistiche di ciascun dispositivo.
- **Non si sincronizzano**: impostazioni, sfondo, PIN/biometria, cartella scelta. I fumetti si copiano solo su richiesta (§6–7).

## 5. Regole di unione

Il risultato deve essere identico qualunque sia l'ordine degli scambi (verificato con 3 dispositivi).

- **Lapidi**: unione; per ogni chiave la data più recente. Si scartano quelle più vecchie di 365 giorni.
- **Progressi**: per percorso vince il più recente secondo `stamp = modifiedAt ?? lastReadAt ?? firstOpenedAt ?? 0`;
  a parità `isCompleted` vero, poi `currentPage` maggiore. Scartato se lapide ≥ stamp.
- **Segnalibri**: unione per `id`, senza quelli con lapide. Due id sulla stessa pagina restano entrambi
  (l'interfaccia ne mostra uno); togliere il segnalibro di una pagina mette la lapide a **tutti** quelli di quella pagina.
- **Sessioni**: unione per `id` senza lapidi, ordinate per `startedAt` decrescente, massimo 2000.
- **Evidenziazioni**: unione per `id`; con due versioni vince `modifiedAt ?? createdAt` maggiore (a parità, colore
  alfabeticamente maggiore). Scartata se lapide ≥ quella data. Aggiungere un'evidenziazione che si sovrappone ad altre
  le sostituisce: le sostituite ricevono la lapide.
- **Metadati modificati**: per percorso vince `modifiedAt` maggiore; scartato se lapide ≥ `modifiedAt`.
- **Nascosti**: data massima per percorso; scartato se lapide (= "mostrato di nuovo") ≥ data.
- **Dispositivi**: per id vince la scheda con `lastSeen` maggiore (a parità, `name` maggiore).

Ogni modifica locale deve aggiornare `modifiedAt` (progressi, colore evidenziazione) o creare la lapide (eliminazioni).

## 6. Libreria dell'altro dispositivo (`op = library`)

Risposta:

```json
{ "deviceId": "…", "deviceName": "…", "platform": "ios", "createdAt": 0,
  "files": [ { "path": "Manga/One Piece/Vol 01.cbz", "size": 48213044, "modifiedAt": 0 } ] }
```

- Contiene **tutti** i fumetti, anche quelli nascosti in Incognito: è chi visualizza a non mostrarli
  (l'elenco dei nascosti è sincronizzato), a meno che lì Incognito sia sbloccato.
- **Confronto** (stesso percorso): stessa `size` → ✓ uguale; `size` diversa → versione diversa; assente → manca qui.
  Si mostrano anche i fumetti presenti solo sul dispositivo locale.

## 7. Scaricare un fumetto (`op = file`)

Richiesta: `{"path": "<percorso>", "offset": <byte già scaricati>}` (0 la prima volta; per riprendere un download
interrotto si manda la dimensione del file parziale).

Chi ha il file:
- risponde **solo** per file presenti nella sua libreria e con percorso valido (relativo, senza `..`, `.`, componenti vuote);
- manda l'**intestazione** cifrata `{"path":"…","size":<totale>,"offset":<da dove riparte>,"error":null}`
  (con un problema: `"error":"messaggio leggibile"` e nient'altro);
- poi i **blocchi**: ogni frame = file letto a pezzi da **1 MiB** (l'ultimo più corto), cifrato singolarmente come al §1,
  fino a `size - offset` byte in tutto.

Chi scarica:
- scrive in un file parziale **fuori dalla libreria** (cache dell'app), troncato a `offset`, e aggiunge i blocchi;
- alla fine controlla che i byte siano esattamente `size`;
- poi lo copia nella cartella principale **allo stesso percorso relativo** (creando le sottocartelle) e riscansiona la libreria.
  Su Android, con la cartella scelta tramite SAF: `DocumentFile.createDirectory/createFile` lungo il percorso.
- Download uno alla volta; ogni GCM fallito o connessione interrotta = errore, si potrà riprendere dall'offset.
