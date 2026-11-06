# Accesso AI a Lumetto (Android)

Lumetto può esporre un piccolo server locale con cui qualsiasi AI legge e usa l'app. È **spento di default**.

## Attivazione
Impostazioni → Accesso AI:
1. «Attiva accesso AI». Compare l'indirizzo (es. `http://192.168.1.20:8765`) e il token.
2. «Consenti modifiche» è un secondo consenso: senza, l'AI può solo leggere.
3. «Consenti dalla rete di casa» spento = solo da questo telefono (`adb reverse tcp:8765 tcp:8765`, poi `http://127.0.0.1:8765`).
4. «Copia configurazione MCP» copia un blocco pronto per i client MCP.

L'app deve essere in esecuzione (Android può chiudere le app in background).

## MCP (consigliato)
Trasporto HTTP, endpoint `POST /mcp`, intestazione `Authorization: Bearer <token>`:

```json
{ "mcpServers": { "lumetto": { "type": "http", "url": "http://IP:8765/mcp", "headers": { "Authorization": "Bearer lum_..." } } } }
```

## REST / OpenAPI
- `GET /openapi.json` – descrizione di tutti gli strumenti
- `GET /api/tools` – elenco
- `POST /api/tools/{nome}` – corpo JSON = argomenti; risposta `{ content: [...], isError }`
- `GET /health` – senza token, serve solo a verificare l'indirizzo

## Strumenti
Sola lettura: `library_overview`, `list_folder`, `search`, `get_comic`, `continue_reading`, `reading_stats`, `get_page_image` (JPEG), `epub_chapters`, `epub_text`, `list_bookmarks`, `list_highlights`, `get_settings`.

Con «Consenti modifiche»: `set_metadata`, `clear_metadata_edit`, `set_progress`, `set_completed`, `reset_progress`, `toggle_bookmark`, `remove_highlight`, `recolor_highlight`, `set_setting`, `rescan_library`, `open_comic`.

Operazioni sui file, sempre dentro la cartella principale e **con conferma sul telefono** (compare una finestra «Un'AI chiede…» che si deve accettare entro un minuto, con Lumetto aperto): `create_folder`, `move_comic` (non cambia il nome, non sovrascrive), `rename_comic` (l'estensione resta). Servono «Consenti modifiche» e il permesso di scrittura sulla cartella. Dopo uno spostamento progressi, segnalibri, metadati ed evidenziazioni seguono il file; sull'altro dispositivo il fumetto risulterà con il percorso vecchio finché non lo si sposta anche lì.

Le pagine si contano da 0. I percorsi sono relativi alla cartella principale (come nel sync). Le modifiche passano dagli stessi store dell'interfaccia, quindi si sincronizzano con l'iPad secondo `SYNC_PROTOCOLLO.md`.

## Sicurezza
- Token casuale (192 bit), confronto a tempo costante; rigenerabile.
- Rifiuta le richieste con intestazione `Origin` (nessuna pagina web può usarlo).
- Corpo massimo 1 MB, timeout 15 s, nessun TLS: **usalo solo in rete fidata**, mai esposto su Internet.
- Rispetta Incognito: i file nascosti non sono raggiungibili finché Incognito è spento.
- Le ultime richieste sono visibili nella schermata.

Codice: `app/src/main/java/com/cubo/lumetto/ai/`. Test: `app/src/test/.../ai/AiAccessTest.kt`.

Dal PC: vedi `../tools/lumetto-bridge/` (ponte stdio per Claude Desktop, Claude Code e altri client MCP).

## Come verificare che funzioni
1. **Sul telefono**: Impostazioni → Accesso AI → attiva, poi «Verifica funzionamento». Deve scrivere «OK: il server funziona (26 strumenti)».
2. **Dal PC** (stessa rete Wi-Fi), con indirizzo e token mostrati nella schermata:
   - `curl http://IP:8765/health` → `{"app":"Lumetto","ai":true}`
   - `node tools/lumetto-bridge/bridge.mjs --url http://IP:8765 --token lum_... --check` → «token valido, 26 strumenti disponibili»
   - `curl -X POST http://IP:8765/api/tools/library_overview -H "Authorization: Bearer lum_..."` → panoramica della libreria
3. **Con un'AI**: aggiungi il server e chiedi «elenca le cartelle della mia libreria Lumetto».
   - Claude Code: `claude mcp add --transport http lumetto http://IP:8765/mcp --header "Authorization: Bearer lum_..."`
   - Claude Desktop e altri client stdio: usa il ponte in `tools/lumetto-bridge/` (vedi il suo README).
4. **Modifiche**: con «Consenti modifiche» spento, uno strumento di scrittura (es. `set_progress`) deve rispondere «Modifiche disattivate»; acceso, la modifica compare nell'app e nell'elenco «Ultime richieste».
