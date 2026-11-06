#!/usr/bin/env node
// Ponte AI per Lumetto (PC): trasforma un client MCP "stdio" (Claude Desktop, Claude Code, Cursor...)
// in chiamate HTTP verso l'app Lumetto sul telefono (Impostazioni -> Accesso AI). Nessuna dipendenza.
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

export function parseArgs(argv, env = {}) {
  const out = { url: env.LUMETTO_URL || '', token: env.LUMETTO_TOKEN || '', check: false, timeoutMs: 60000 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--url') out.url = argv[++i] || '';
    else if (a === '--token') out.token = argv[++i] || '';
    else if (a === '--check') out.check = true;
    else if (a === '--timeout') out.timeoutMs = Number(argv[++i]) * 1000 || out.timeoutMs;
  }
  out.url = out.url.replace(/\/+$/, '').replace(/\/mcp$/, '');
  return out;
}

function errorFor(msg, text) {
  return { jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: text } };
}

/** Inoltra una riga JSON-RPC al telefono. Restituisce la riga di risposta, o null se non serve risposta. */
export async function forward(line, { url, token, fetchImpl = fetch, timeoutMs = 60000 }) {
  let parsed;
  try {
    parsed = JSON.parse(line);
  } catch {
    return JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'JSON non valido' } });
  }
  const requests = Array.isArray(parsed) ? parsed : [parsed];
  const failAll = (text) => {
    const errs = requests.filter((m) => m && m.id !== undefined && m.id !== null).map((m) => errorFor(m, text));
    if (errs.length === 0) return null;
    return JSON.stringify(Array.isArray(parsed) ? errs : errs[0]);
  };
  let res;
  try {
    res = await fetchImpl(`${url}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: line,
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (e) {
    return failAll(`Telefono non raggiungibile (${url}): ${e.cause?.code || e.message}. Controlla che Lumetto sia aperto e l'accesso AI attivo.`);
  }
  if (res.status === 401) return failAll('Token errato: copialo di nuovo da Lumetto (Impostazioni -> Accesso AI).');
  if (res.status === 202) return null;
  const text = await res.text();
  if (!res.ok && !text.trim().startsWith('{')) return failAll(`Errore HTTP ${res.status}`);
  return text.trim() === '' ? null : text.trim();
}

async function check({ url, token, fetchImpl = fetch }) {
  try {
    const h = await fetchImpl(`${url}/health`, { signal: AbortSignal.timeout(5000) });
    const hj = await h.json();
    if (hj.app !== 'Lumetto') throw new Error('risposta inattesa');
    console.log(`OK  Lumetto raggiunto su ${url}`);
  } catch (e) {
    console.error(`ERRORE  nessuna risposta da ${url}: ${e.cause?.code || e.message}`);
    return 1;
  }
  const r = await forward(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }), { url, token, fetchImpl });
  try {
    const j = JSON.parse(r);
    if (j.error) throw new Error(j.error.message);
    console.log(`OK  token valido, ${j.result.tools.length} strumenti disponibili`);
    return 0;
  } catch (e) {
    console.error(`ERRORE  ${e.message}`);
    return 1;
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2), process.env);
  if (!opts.url || !opts.token) {
    console.error('Uso: node bridge.mjs --url http://IP:8765 --token lum_... [--check]\n(oppure variabili LUMETTO_URL e LUMETTO_TOKEN)');
    process.exit(2);
  }
  if (opts.check) process.exit(await check(opts));
  const rl = createInterface({ input: process.stdin });
  const pending = new Set();
  rl.on('line', (line) => {
    if (!line.trim()) return;
    const p = forward(line, opts)
      .then((out) => { if (out !== null) process.stdout.write(out + '\n'); })
      .finally(() => pending.delete(p));
    pending.add(p);
  });
  rl.on('close', async () => {
    await Promise.allSettled([...pending]);
    process.exit(0);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
