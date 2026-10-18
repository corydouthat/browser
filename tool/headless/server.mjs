#!/usr/bin/env node
/*
 * Static server for the detection harness, on two ports so the page can embed a cross-origin iframe.
 *
 *   node tool/headless/server.mjs [--port 8765] [--xport 8766] [--root harness] [--out build/harness-reports]
 *                                 [--page-dir <dir served at /page/>] [--host 127.0.0.1]
 *
 * Routes (relative to any base path, so the page can use ./echo and ./report):
 *   GET  .../echo[?second=1]  -> {"headers":{lowercase request headers},"method","url","second"}
 *                                with Accept-CH: Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR
 *   POST .../report           -> stores the JSON body under <out>/posted/<persona>__<env>.json
 *   GET  /page/<file>         -> files from --page-dir (red-team pages etc.)
 *   GET  /index.html (or /)   -> harness/index.html with an inline script prepended to <head> that sets
 *                                window.__hatchCrossOrigin to the other port's origin
 *   anything else             -> static file from --root
 *
 * Also importable: startServer({...}) -> { pageOrigin, crossOrigin, close() }.
 */
import http from 'node:http';
import { createReadStream, existsSync, mkdirSync, statSync, writeFileSync, readFileSync } from 'node:fs';
import { extname, join, normalize, resolve, sep, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO = resolve(HERE, '..', '..');

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.map': 'application/json; charset=utf-8',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2', '.wasm': 'application/wasm',
};
const ACCEPT_CH = 'Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR';
const MAX_BODY = 8 * 1024 * 1024;

function safeJoin(root, urlPath) {
  const decoded = decodeURIComponent(urlPath.split('?')[0]);
  const rel = normalize(decoded).replace(/^(\.\.(\/|\\|$))+/, '');
  const full = resolve(root, `.${sep}${rel.replace(/^[/\\]+/, '')}`);
  if (full !== root && !full.startsWith(root + sep)) return null;
  return full;
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Cache-Control': 'no-store', ...headers });
  res.end(body);
}

function readBody(req, limit = MAX_BODY) {
  return new Promise((resolvePromise, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolvePromise(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sanitizeName(s, fallback) {
  const v = String(s || '').replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 80);
  return v || fallback;
}

export function createHandler({ root, outDir, crossOriginFor, pageDir, log = () => {} }) {
  const absRoot = resolve(root);
  const absPageDir = pageDir ? resolve(pageDir) : null;
  return async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const path = url.pathname;
    try {
      if (path === '/echo' || path.endsWith('/echo')) {
        const headers = {};
        for (const [k, v] of Object.entries(req.headers)) headers[k.toLowerCase()] = v;
        const body = JSON.stringify({ headers, method: req.method, url: req.url, second: url.searchParams.get('second') === '1' });
        send(res, 200, body, { 'Content-Type': 'application/json; charset=utf-8', 'Accept-CH': ACCEPT_CH, 'Critical-CH': 'Sec-CH-UA-Mobile', Vary: 'Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR' });
        return;
      }
      if (path === '/report' || path.endsWith('/report')) {
        if (req.method !== 'POST') { send(res, 405, 'POST only'); return; }
        const raw = await readBody(req);
        let json;
        try { json = JSON.parse(raw.toString('utf8')); } catch (e) { send(res, 400, 'invalid JSON'); return; }
        const dir = join(resolve(outDir), 'posted');
        mkdirSync(dir, { recursive: true });
        const name = `${sanitizeName(json.persona, 'unknown')}__${sanitizeName(json.env, 'unknown')}.json`;
        const file = join(dir, name);
        writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
        log(`report stored: ${file} (overall=${json.summary && json.summary.overall})`);
        send(res, 200, JSON.stringify({ ok: true, file }), { 'Content-Type': 'application/json; charset=utf-8' });
        return;
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') { send(res, 405, 'method not allowed'); return; }
      let fsRoot = absRoot;
      let rel = path;
      if (path.startsWith('/page/')) {
        if (!absPageDir) { send(res, 404, 'no --page-dir'); return; }
        fsRoot = absPageDir;
        rel = path.slice('/page'.length);
      }
      if (rel === '/' || rel === '') rel = '/index.html';
      const file = safeJoin(fsRoot, rel);
      if (!file || !existsSync(file) || !statSync(file).isFile()) { send(res, 404, `not found: ${path}`); return; }
      const type = MIME[extname(file).toLowerCase()] || 'application/octet-stream';
      if (extname(file).toLowerCase() === '.html') {
        let html = readFileSync(file, 'utf8');
        const inject = `<script>window.__hatchCrossOrigin=${JSON.stringify(crossOriginFor())};</script>`;
        html = /<head[^>]*>/i.test(html) ? html.replace(/<head[^>]*>/i, (m) => `${m}\n${inject}`) : `${inject}\n${html}`;
        send(res, 200, html, { 'Content-Type': type });
        return;
      }
      res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store', 'Content-Length': statSync(file).size });
      if (req.method === 'HEAD') { res.end(); return; }
      createReadStream(file).pipe(res);
    } catch (e) {
      log(`error ${req.method} ${req.url}: ${e && e.message}`);
      send(res, 500, `server error: ${e && e.message}`);
    }
  };
}

function listen(server, host, port) {
  return new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolvePromise(server.address().port));
  });
}

/** Starts the page server and the cross-origin server. port/xport 0 = ephemeral. */
export async function startServer({ root = join(REPO, 'harness'), outDir = join(REPO, 'build', 'harness-reports'), port = 0, xport = 0, host = '127.0.0.1', pageDir = null, log = () => {} } = {}) {
  mkdirSync(resolve(outDir), { recursive: true });
  const state = { pageOrigin: null, crossOrigin: null };
  const page = http.createServer(createHandler({ root, outDir, pageDir, log, crossOriginFor: () => state.crossOrigin }));
  const cross = http.createServer(createHandler({ root, outDir, pageDir, log, crossOriginFor: () => state.pageOrigin }));
  const p1 = await listen(page, host, port);
  const p2 = await listen(cross, host, xport);
  state.pageOrigin = `http://${host}:${p1}`;
  state.crossOrigin = `http://${host}:${p2}`;
  page.keepAliveTimeout = 1000;
  cross.keepAliveTimeout = 1000;
  return {
    pageOrigin: state.pageOrigin,
    crossOrigin: state.crossOrigin,
    close: () => new Promise((r) => { let n = 2; const done = () => { if (--n === 0) r(); }; page.close(done); cross.close(done); page.closeAllConnections(); cross.closeAllConnections(); }),
  };
}

function parseArgs(argv) {
  const out = { port: 8765, xport: 8766, host: '127.0.0.1', root: join(REPO, 'harness'), out: join(REPO, 'build', 'harness-reports'), pageDir: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--port') out.port = Number(next());
    else if (a === '--xport') out.xport = Number(next());
    else if (a === '--host') out.host = next();
    else if (a === '--root') out.root = resolve(next());
    else if (a === '--out') out.out = resolve(next());
    else if (a === '--page-dir') out.pageDir = resolve(next());
    else if (a === '-h' || a === '--help') { console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0]); process.exit(0); }
    else { console.error(`unknown flag ${a}`); process.exit(2); }
  }
  return out;
}

const invokedDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  const a = parseArgs(process.argv.slice(2));
  const s = await startServer({ root: a.root, outDir: a.out, port: a.port, xport: a.xport, host: a.host, pageDir: a.pageDir, log: (m) => console.log(`[server] ${m}`) });
  console.log(`[server] harness: ${s.pageOrigin}/index.html?persona=win-firefox__us-east&env=headless`);
  console.log(`[server] cross-origin frames: ${s.crossOrigin}/frame.html`);
  console.log(`[server] reports: ${resolve(a.out)}/posted/`);
  const stop = () => s.close().then(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
