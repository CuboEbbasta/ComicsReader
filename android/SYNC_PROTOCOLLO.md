# Lumetto — Protocollo di sincronizzazione v1

Sincronizzazione **senza server** tra i dispositivi di una stessa *stanza* (nome + password),
sulla stessa rete locale. Questo documento basta per implementarla su Android
(riferimento iOS: `Lumetto.swiftpm/Services/Sync/`).

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

## 2. Scoperta in rete (Bonjour / mDNS)

Ogni dispositivo con l'app aperta e in una stanza:

- pubblica il servizio **`_lumetto._tcp`** (porta TCP a scelta del sistema), nome `Lumetto-<primi 8 caratteri dell'id>`;
- record TXT: `v` = `1`, `r` = tag della stanza, `id` = id del dispositivo (UUID minuscolo, fisso), `n` = nome leggibile (max 40 caratteri);
- cerca `_lumetto._tcp` e considera solo i servizi con lo stesso `r` e un `id` diverso dal proprio.

Su Android: `NsdManager` (`registerService`, `discoverServices`, `resolveService`; gli attributi TXT sono in `NsdServiceInfo.attributes`).

**Chi si collega**: quando compare un nuovo dispositivo, apre la connessione solo quello con l'`id` minore
(confronto tra stringhe). Dopo una modifica locale (attesa di 6 s) o con "Sincronizza ora" può collegarsi chiunque:
lo scambio è idempotente.

## 3. Lo scambio (TCP)

Ogni pacchetto (*frame*) = lunghezza in 4 byte big-endian + contenuto (massimo 64 MB).
Entrambi i lati, appena connessi, **inviano subito** due frame e **leggono** i due dell'altro (in parallelo):

1. **Saluto**, JSON in chiaro: `{"v":1,"id":"…","name":"…","room":"<tag>"}`
2. **Dati**: il JSON del §4 cifrato come al §1.

Controlli su ciò che si riceve: `v == 1`, `room == tag` proprio, decifratura riuscita (altrimenti: password diversa),
`format == 1`. Poi ciascuno unisce (§5), salva e chiude. Tempo massimo: 30 s.

## 4. Il pacchetto dati (JSON)

Date = **millisecondi dal 1970** (numero). Campi facoltativi assenti = `null`/mancanti.
Le chiavi dei fumetti sono **percorsi relativi alla cartella principale** (es. `"Manga/One Piece/Vol 01.cbz"`).

```json
{
  "format": 1,
  "deviceId": "uuid", "deviceName": "iPad", "createdAt": 1762171200000,
  "progress": { "<percorso>": {
      "relativePath": "…", "currentPage": 12, "totalPages": 180,
      "totalReadingTime": 1520.5, "sessionCount": 3,
      "firstOpenedAt": 0, "lastReadAt": 0, "finishedAt": null,
      "isCompleted": false, "modifiedAt": 0 } },
  "bookmarks": [ { "id": "UUID", "relativePath": "…", "pageIndex": 4, "createdAt": 0 } ],
  "sessions": [ { "id": "UUID", "relativePath": "…", "title": "…", "startedAt": 0, "duration": 600.0, "pagesRead": 20 } ],
  "highlights": [ { "id": "…", "path": "…", "chapter": 2, "start": 100, "end": 140,
                    "text": "…", "color": "yellow|green|blue|pink", "createdAt": 0, "modifiedAt": null } ],
  "metadataEdits": { "<percorso>": { "metadata": { "title": "…", "series": "…", "number": "…", "volume": "…",
      "writer": "…", "penciller": "…", "summary": "…", "year": 2001, "pageCount": 200,
      "languageISO": "it", "manga": true }, "modifiedAt": 0 } },
  "hidden": { "<percorso nascosto in Incognito>": 0 },
  "tombstones": { "progress": {"<percorso>": 0}, "bookmarks": {"<UUID>": 0}, "sessions": {"<UUID>": 0},
                  "highlights": {"<id>": 0}, "metadata": {"<percorso>": 0}, "hidden": {"<percorso>": 0} }
}
```

Note: gli UUID di Swift sono in **maiuscolo** nel JSON (`"E621E1F8-…"`): conservali così come arrivano.
`totalReadingTime` e `duration` sono in secondi. Le *tombstones* ("lapidi") registrano le eliminazioni con la loro data.

**Non si sincronizzano**: impostazioni, sfondo, PIN/biometria, cartella scelta, i fumetti stessi.

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

Ogni modifica locale deve aggiornare `modifiedAt` (progressi, colore evidenziazione) o creare la lapide (eliminazioni).
