import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { parseArgs, forward } from './bridge.mjs';

function fakePhone(handler) {
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => handler(req, body, res));
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, url: `http://127.0.0.1:${server.address().port}` })));
}

test('parseArgs: argomenti, ambiente e pulizia dell url', () => {
  const o = parseArgs(['--url', 'http://1.2.3.4:8765/mcp/', '--token', 'abc'], {});
  assert.equal(o.url, 'http://1.2.3.4:8765');
  assert.equal(o.token, 'abc');
  assert.equal(parseArgs([], { LUMETTO_URL: 'http://x:1', LUMETTO_TOKEN: 't' }).token, 't');
});

test('forward: inoltra con il token e restituisce la risposta', async () => {
  const { server, url } = await fakePhone((req, body, res) => {
    assert.equal(req.headers.authorization, 'Bearer segreto');
    assert.equal(req.url, '/mcp');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: JSON.parse(body).id, result: {} }));
  });
  const out = await forward('{"jsonrpc":"2.0","id":7,"method":"ping"}', { url, token: 'segreto' });
  assert.equal(JSON.parse(out).id, 7);
  server.close();
});

test('forward: notifica (202) senza risposta', async () => {
  const { server, url } = await fakePhone((req, body, res) => {
    res.writeHead(202);
    res.end();
  });
  assert.equal(await forward('{"jsonrpc":"2.0","method":"notifications/initialized"}', { url, token: 't' }), null);
  server.close();
});

test('forward: token errato -> errore JSON-RPC con l id giusto', async () => {
  const { server, url } = await fakePhone((req, body, res) => {
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end('{"error":"x"}');
  });
  const out = JSON.parse(await forward('{"jsonrpc":"2.0","id":3,"method":"ping"}', { url, token: 'no' }));
  assert.equal(out.id, 3);
  assert.match(out.error.message, /Token errato/);
  server.close();
});

test('forward: telefono spento -> errore chiaro, nessun errore per le notifiche', async () => {
  const dead = { url: 'http://127.0.0.1:1', token: 't', timeoutMs: 2000 };
  const out = JSON.parse(await forward('{"jsonrpc":"2.0","id":1,"method":"ping"}', dead));
  assert.match(out.error.message, /non raggiungibile/);
  assert.equal(await forward('{"jsonrpc":"2.0","method":"notifications/x"}', dead), null);
});

test('forward: JSON non valido', async () => {
  const out = JSON.parse(await forward('{rotto', { url: 'http://127.0.0.1:1', token: 't' }));
  assert.equal(out.error.code, -32700);
});
