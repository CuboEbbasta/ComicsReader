# Prompt per la sessione iOS: «Accesso AI» (MCP + REST) su Lumetto per iPad

Da incollare alla sessione che sviluppa Lumetto per iPad (Swift Playgrounds, `Lumetto.swiftpm`, versione 3.3.x).

---

Ciao, sono la sessione che segue Lumetto per Android. Su Android abbiamo aggiunto l'**Accesso AI**: un piccolo server locale nell'app con cui
qualsiasi AI (Claude Code, Claude Desktop tramite ponte, altri client MCP) legge e usa Lumetto. Va portato sull'iPad **identico per
comportamento, nomi e forme JSON**, così la stessa AI e lo stesso ponte funzionano con entrambi i dispositivi. L'implementazione di
riferimento è nel repo `CuboEbbasta/ComicsReader`, ramo `main`:

- `android/AI_ACCESSO.md` (descrizione e sicurezza)
- `android/app/src/main/java/com/cubo/lumetto/ai/`: `McpProtocol.kt` (JSON-RPC MCP + OpenAPI), `AiHttpServer.kt` (server HTTP),
  `AiTools.kt` (tutti gli strumenti), `AiAccess.kt` (stato, token, rete), `AiConfirm.kt` (conferme), `AiSettingsScreen.kt` (schermata)
- `android/app/src/test/java/com/cubo/lumetto/ai/AiAccessTest.kt` (test da replicare)
- `tools/lumetto-bridge/` (ponte stdio→HTTP già pronto, funziona anche con l'iPad senza modifiche)

Leggili prima di scrivere codice, e **non toccare il formato della sincronizzazione**.

## Cosa costruire

Un server HTTP/1.1 nell'app, **spento di default**, attivabile da Impostazioni → «Accesso AI».

**Rete.** `NWListener` (Network.framework) su porta TCP **8765** (configurabile), una richiesta per connessione, `Connection: close`.
Opzione «Consenti dalla rete di casa»: se spenta ascolta solo su loopback. Corpo massimo 1 MB, intestazioni massimo 16 KB, timeout 15 s.
Niente dipendenze esterne (è un .swiftpm): parser HTTP scritto a mano, JSON con `JSONSerialization`. Riusa l'autorizzazione «Rete locale»
e le chiavi Info.plist già presenti per la sincronizzazione (`NSLocalNetworkUsageDescription`).

**Sicurezza (identica ad Android).**
- Token casuale `lum_` + 48 caratteri esadecimali (24 byte da `SecRandomCopyBytes`), salvato nel Keychain (hai già `KeychainString`),
  rigenerabile da un pulsante con conferma. Confronto **a tempo costante**.
- Intestazione `Authorization: Bearer <token>` obbligatoria su tutto tranne `GET /health` → `{"app":"Lumetto","ai":true}`.
- Qualsiasi richiesta con intestazione `Origin` → **403** (impedisce l'uso da pagine web). Token errato/mancante → **401**.
- Due consensi separati: «Attiva accesso AI» (lettura) e «Consenti modifiche» (scrittura, spento di default). Uno strumento con
  `readOnly=false` con il secondo spento risponde errore: «Modifiche disattivate: l'utente deve attivare «Consenti modifiche» in Impostazioni → Accesso AI.»
- Rispetta Incognito: i fumetti nascosti non sono raggiungibili finché Incognito è spento (usa la libreria visibile, non quella completa).
- Registro delle ultime 60 richieste visibile nella schermata.

**Rotte.**
| Metodo e percorso | Cosa fa |
|---|---|
| `GET /health` | senza token, `{"app":"Lumetto","ai":true}` |
| `POST /mcp` | JSON-RPC MCP. Notifiche (senza `id`) → **202** corpo vuoto. Batch (array) supportato |
| `GET /api/tools` | `{"tools":[{name,description,inputSchema,readOnly}]}` |
| `POST /api/tools/{nome}` | corpo = argomenti JSON; risposta = risultato (sotto); **400** se `isError`, 404 strumento sconosciuto |
| `GET /openapi.json` | OpenAPI 3.0.3 generato dagli stessi strumenti (`/api/tools/{nome}` POST, schema `bearer`) |

**MCP.** Metodi: `initialize` (rispondi con la `protocolVersion` richiesta se è tra 2024-11-05 / 2025-03-26 / 2025-06-18, altrimenti
2025-03-26; `capabilities:{tools:{listChanged:false}}`, `serverInfo:{name:"lumetto",version:"1.0.0"}`, `instructions`), `ping`, `tools/list`
(ogni strumento con `annotations:{readOnlyHint}`), `tools/call`. Errori JSON-RPC: -32700 JSON non valido, -32600, -32601 metodo,
-32602 strumento sconosciuto. Un'eccezione in uno strumento diventa risultato `isError:true`, mai un crash.
**Risultato di uno strumento:** `{"content":[{"type":"text","text":"…"}],"isError":false}`; per le immagini si aggiunge
`{"type":"image","data":"<base64>","mimeType":"image/jpeg"}`.

## Strumenti (26, stessi nomi e argomenti)

Convenzioni: pagine **da 0**; percorsi **relativi alla cartella principale** (gli stessi chiavi di progressi e sync); date in ms dal 1970.
Testo di `instructions`: copialo da `AiTools.INSTRUCTIONS` (italiano).

Sola lettura:
`library_overview`, `list_folder{path}`, `search{query}`, `get_comic{path}`, `continue_reading`, `reading_stats`,
`get_page_image{path,page,max_pixel?}` (JPEG qualità 0.8, lato lungo 400–1600, default 1000; solo CBZ/CBR/PDF/EPUB a layout fisso),
`epub_chapters{path}`, `epub_text{path,chapter,offset?}` (max 30000 caratteri per chiamata, HTML→testo pulito), `list_bookmarks{path?}`,
`list_highlights{path}`, `get_settings`.

Con «Consenti modifiche»: `set_metadata{path,title?,series?,number?,volume?,writer?,penciller?,summary?,year?,language?,manga?}`
(parte dalle modifiche manuali esistenti; campi vuoti non cancellano), `clear_metadata_edit`, `set_progress{path,page}`,
`set_completed{path,completed}`, `reset_progress`, `toggle_bookmark{path,page}`, `remove_highlight{id}`, `recolor_highlight{id,color}`,
`set_setting{key,value}`, `rescan_library`, `open_comic{path}` (apre il lettore sullo schermo; mette il fumetto davanti, alla pagina salvata).

Operazioni sui file, **sempre sotto la cartella principale e con conferma sull'iPad**: `create_folder{path}`, `move_comic{path,to_folder}`
(non cambia nome, non sovrascrive, crea la destinazione), `rename_comic{path,new_name}` (l'estensione resta; senza cartelle nel nome).
Regole: percorsi validi solo se relativi, senza `..`, senza `.`/componenti vuote, senza `/` iniziale, senza caratteri di controllo, meno di 1024 caratteri
(è la stessa `SyncFiles.isSafe` della v2). Prima di agire mostra un alert «Un'AI chiede: …» con da/a e i pulsanti Consenti/Rifiuta; lo strumento
resta in attesa (max 55 s) e senza risposta annulla con «Operazione non confermata sul dispositivo…». Usa `NSFileCoordinator` per spostare/rinominare.
Dopo uno spostamento/rinomina **migra tutto ciò che è legato al percorso**: progresso (nuova chiave + lapide sulla vecchia), segnalibri
(nuovi UUID con lapide sui vecchi), metadati modificati, evidenziazioni (path nuovo e `modifiedAt = adesso`), nascosti in Incognito, preferenze per fumetto;
poi riscansiona. Le sessioni restano com'erano. Tutto passa dagli stessi store dell'interfaccia, quindi si sincronizza con Android.

**Forme JSON delle risposte** (testo = JSON indentato di 2 spazi):
- `library_overview`: `{scanning, readingMetadata, ready, totalFiles, topFolders:[{path,name,fileCount}], filesInRoot}` (+`error` se la scansione è fallita)
- `list_folder`: `{path, folders:[{path,name,fileCount}], files:[riepilogo…], truncated?}` (max 500 file)
- riepilogo fumetto: `{path,title,subtitle,format, progress?:{currentPage,totalPages,completed,fraction,readingSeconds,sessions,lastReadAtMs?,finishedAtMs?}}`
- `get_comic`: riepilogo + `{fileName,folder,sizeBytes,modifiedMs,metadata:{title?,series?,number?,volume?,writer?,penciller?,summary?,year?,pageCount?,language?,manga?},bookmarkedPages:[Int], highlightCount?}`
- `search`: `{folders:[…], files:[riepilogo…]}`; `continue_reading`: array di riepiloghi; `reading_stats`: `{completedCount,totalReadingSeconds,totalReadingText,pagesRead,sessionCount}`
- `epub_chapters`: `{chapters:[{index,file,title?}]}`; `epub_text`: `{chapter,totalChars,offset,hasMore,text}`
- `list_bookmarks`: `[{path,page,createdAtMs}]`; `list_highlights`: `[{id,chapter,text,color}]`
- `get_settings`: le impostazioni dell'iPad con i **loro** valori (es. `transition: horizontalScroll|pageCurl`); `set_setting` accetta gli stessi nomi/valori.
Se una chiave dell'iPad non esiste su Android (o viceversa) tienila: i client leggono comunque `get_settings`.

## Limiti di iOS da gestire (e da dichiarare nella schermata)
- iOS sospende l'app in background: il server risponde **solo con Lumetto in primo piano e lo schermo acceso**. Mentre è attivo e l'app è visibile imposta
  `UIApplication.shared.isIdleTimerDisabled = true`; mostra in schermata «Tieni Lumetto aperto». Niente trucchi in background.
- Mostra gli indirizzi raggiungibili (IPv4 privati delle interfacce attive) e la porta; pulsante «Copia configurazione MCP» (blocco `mcpServers` con `type:"http"`,
  `url` `http://IP:8765/mcp`, `headers.Authorization`), «Verifica funzionamento» (si auto-interroga e scrive «OK: il server funziona (26 strumenti)») e «Rigenera token».
- Un rifiuto o un'assenza di conferma non deve mai lasciare file a metà: operazioni atomiche, in caso di errore niente da annullare.

## Aprire un fumetto da fuori (facoltativo, stesso schema di Android)
`lumetto://apri?titolo=…` (cerca per titolo: identico > inizia così > contiene tutte le parole), `?percorso=…` (esatto), `?ultimo=1` (l'ultimo letto);
se il fumetto non c'è, avviso «Fumetto non trovato nella libreria». Sopra si può costruire un App Intent «Apri fumetto» per Siri/Comandi/Spotlight.

## Test e consegna
1. Scrivi test equivalenti ad `AiAccessTest.kt` (protocollo MCP con un finto host: initialize, tools/list, tools/call, errori, batch, notifiche;
   server HTTP su loopback: /health senza token, 401, 403 con Origin, REST e MCP, 404/400) e al confronto token a tempo costante.
2. Elenca in `SYNC_PROTOCOLLO.md`/documentazione solo ciò che cambia rispetto ad Android (valori delle impostazioni, limiti di iOS).
3. Collauda dal PC: `node tools/lumetto-bridge/bridge.mjs --url http://IP-IPAD:8765 --token lum_… --check` deve dire «token valido, 26 strumenti».
4. Registrazione in Claude Code: `claude mcp add --transport http --scope user lumetto-ipad http://IP-IPAD:8765/mcp --header "Authorization: Bearer lum_…"`.
5. Avvisami (sessione Android) quando è pronto: aggiorno la regola personale «aprimelo su lumetto» perché scelga il dispositivo giusto.

Se qualcosa nel riferimento Android ti sembra sbagliato o troppo legato ad Android, dimmelo prima di divergere: i due server devono restare compatibili.
