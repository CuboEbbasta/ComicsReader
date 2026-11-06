# Ponte AI per Lumetto (PC)

Collega un'AI che gira sul PC (Claude Desktop, Claude Code, Cursor, qualsiasi client MCP "stdio") a Lumetto sul telefono.
Il ponte non contiene dati: inoltra le richieste MCP all'app tramite la rete di casa. Nessuna dipendenza, serve solo **Node 18+**.

## Prima volta
1. Su Lumetto: Impostazioni → Accesso AI → attiva (e, se vuoi che l'AI possa modificare, «Consenti modifiche»).
2. Annota indirizzo (es. `http://192.168.1.20:8765`) e copia il token.
3. Prova il collegamento:

```bash
node bridge.mjs --url http://192.168.1.20:8765 --token lum_XXXX --check
```

## Configurazione nel client MCP
```json
{
  "mcpServers": {
    "lumetto": {
      "command": "node",
      "args": ["C:/percorso/ComicsReader/tools/lumetto-bridge/bridge.mjs"],
      "env": { "LUMETTO_URL": "http://192.168.1.20:8765", "LUMETTO_TOKEN": "lum_XXXX" }
    }
  }
}
```

## Limiti
- Lumetto deve essere in esecuzione sul telefono (Android può chiudere le app in background) e il PC sulla stessa rete.
- Non è un dispositivo della stanza di sincronizzazione: per leggere o modificare i dati con l'app chiusa servirebbe un client del protocollo `SYNC_PROTOCOLLO.md` con mDNS, non incluso.
- Il traffico è HTTP senza cifratura: usalo solo su rete fidata.

Test: `node --test` in questa cartella.
