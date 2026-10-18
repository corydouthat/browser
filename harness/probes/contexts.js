/*
 * Context consistency: top document vs same-origin iframe vs cross-origin iframe vs about:blank iframe
 * (read from the parent) vs srcdoc / data: / blob: / sandboxed iframes vs a dedicated and a shared worker,
 * plus the cross-realm Function.prototype.toString trick (a fresh realm's toString applied to the top
 * document's getters and vice versa). A spoof that only patches the top document is caught here.
 */
export const id = 'contexts';
export const timeoutMs = 25000;

/** Self-contained (no closures) so its source can be inlined into srcdoc and frame.html. */
export function hatchSnapshot(w) {
  var n = w.navigator;
  var out = {};
  function t(k, f) { try { out[k] = f(); } catch (e) { out[k] = 'error: ' + (e && e.message); } }
  t('userAgent', function () { return n.userAgent; });
  t('platform', function () { return n.platform; });
  t('oscpu', function () { return 'oscpu' in n ? n.oscpu : null; });
  t('appVersion', function () { return n.appVersion; });
  t('languages', function () { return Array.prototype.slice.call(n.languages); });
  t('hardwareConcurrency', function () { return n.hardwareConcurrency; });
  t('maxTouchPoints', function () { return n.maxTouchPoints; });
  t('timeZone', function () { return new w.Intl.DateTimeFormat().resolvedOptions().timeZone; });
  t('locale', function () { return new w.Intl.DateTimeFormat().resolvedOptions().locale; });
  t('screenWidth', function () { return w.screen.width; });
  t('screenHeight', function () { return w.screen.height; });
  t('dpr', function () { return w.devicePixelRatio; });
  t('ontouchstart', function () { return 'ontouchstart' in w; });
  t('touchEvent', function () { return typeof w.TouchEvent !== 'undefined'; });
  t('dateOffset', function () { return new w.Date().getTimezoneOffset(); });
  t('pointerFine', function () { return w.matchMedia('(pointer: fine)').matches; });
  return out;
}

const SNAPSHOT_SRC = hatchSnapshot.toString();

function waitFor(promise, ms, label) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve({ error: `${label}: timeout after ${ms} ms` }), ms);
    promise.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); resolve({ error: `${label}: ${e && e.message}` }); });
  });
}

function host() {
  let h = document.getElementById('context-frames');
  if (!h) {
    h = document.createElement('div');
    h.id = 'context-frames';
    h.setAttribute('aria-hidden', 'true');
    h.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:1px;overflow:hidden;';
    document.body.appendChild(h);
  }
  return h;
}

/** Loads an iframe that posts {type:'hatch-frame', tag, snapshot} to its parent. */
function messageFrame(tag, setup) {
  return new Promise((resolve, reject) => {
    const iframe = document.createElement('iframe');
    iframe.setAttribute('data-tag', tag);
    const onMsg = (ev) => {
      const d = ev.data;
      if (!d || d.type !== 'hatch-frame' || d.tag !== tag) return;
      window.removeEventListener('message', onMsg);
      resolve({ snapshot: d.snapshot, origin: ev.origin });
    };
    window.addEventListener('message', onMsg);
    iframe.addEventListener('error', () => { window.removeEventListener('message', onMsg); reject(new Error('iframe error')); });
    setup(iframe);
    host().appendChild(iframe);
  });
}

function sameOriginFrame() {
  return messageFrame('sameOrigin', (f) => { f.src = `./frame.html?tag=sameOrigin`; });
}

function crossOriginFrame(prefix) {
  const base = String(prefix).replace(/\/+$/, '');
  return messageFrame('crossOrigin', (f) => { f.src = `${base}/frame.html?tag=crossOrigin`; });
}

function frameHtml(tag) {
  return `<!doctype html><meta charset="utf-8"><script>(function(){var hatchSnapshot=${SNAPSHOT_SRC};parent.postMessage({type:'hatch-frame',tag:'${tag}',snapshot:hatchSnapshot(window)},'*');})();</script>`;
}

function srcdocFrame() {
  return messageFrame('srcdoc', (f) => { f.srcdoc = frameHtml('srcdoc'); });
}

/** data: URL document (null principal with the page as precursor). */
function dataFrame() {
  return messageFrame('data', (f) => { f.src = `data:text/html;charset=utf-8,${encodeURIComponent(frameHtml('data'))}`; });
}

/** blob: URL document (same origin, different URL scheme). */
function blobFrame() {
  const url = URL.createObjectURL(new Blob([frameHtml('blob')], { type: 'text/html' }));
  return messageFrame('blob', (f) => { f.src = url; }).finally(() => { try { URL.revokeObjectURL(url); } catch (e) { /* ignore */ } });
}

/** sandbox="allow-scripts" srcdoc document (opaque origin). */
function sandboxFrame() {
  return messageFrame('sandbox', (f) => { f.setAttribute('sandbox', 'allow-scripts'); f.srcdoc = frameHtml('sandbox'); });
}

/**
 * The "pristine natives" trick: a fresh about:blank realm's Function.prototype.toString applied to the top
 * document's (possibly shimmed) getters, and the top realm's toString applied to the frame's getters. Both must
 * print exactly like the realm's own never-shimmed reference getter (Navigator.prototype.onLine).
 */
function crossRealmToString() {
  const iframe = document.createElement('iframe');
  iframe.setAttribute('data-tag', 'crossRealm');
  host().appendChild(iframe);
  const cw = iframe.contentWindow;
  const getter = (w, proto, name) => { try { const d = w.Object.getOwnPropertyDescriptor(proto, name); return d && d.get ? d.get : null; } catch (e) { return null; } };
  const text = (ts, fn) => { try { return ts.call(fn); } catch (e) { return `error: ${e && e.message}`; } };
  const topTs = Function.prototype.toString;
  const frameTs = cw.Function.prototype.toString;
  const ref = getter(window, Navigator.prototype, 'onLine');
  const frameRef = getter(cw, cw.Navigator.prototype, 'onLine');
  const refText = ref ? text(topTs, ref) : null;
  const expectedFor = (name) => (refText ? refText.replace('onLine', name) : null);
  const checks = [];
  for (const [owner, proto, frameProto, names] of [
    ['Navigator', Navigator.prototype, cw.Navigator.prototype, ['hardwareConcurrency', 'maxTouchPoints', 'userAgent', 'platform', 'languages']],
    ['Screen', Screen.prototype, cw.Screen.prototype, ['width', 'height', 'availHeight']],
  ]) {
    for (const name of names) {
      const topGetter = getter(window, proto, name);
      const frameGetter = getter(cw, frameProto, name);
      if (topGetter) checks.push({ name: `frame toString on top ${owner}.${name}`, text: text(frameTs, topGetter), expected: expectedFor(name) });
      if (frameGetter) checks.push({ name: `top toString on frame ${owner}.${name}`, text: text(topTs, frameGetter), expected: expectedFor(name) });
    }
  }
  const dpr = getter(window, window, 'devicePixelRatio') || getter(window, Window.prototype, 'devicePixelRatio');
  if (dpr) checks.push({ name: 'frame toString on top window.devicePixelRatio', text: text(frameTs, dpr), expected: expectedFor('devicePixelRatio') });
  checks.push({ name: 'frame toString on top matchMedia', text: text(frameTs, window.matchMedia), expected: expectedFor('matchMedia') });
  checks.push({ name: 'top toString on frame Function.prototype.toString', text: text(topTs, frameTs), expected: expectedFor('toString') });
  const frameRefText = frameRef ? text(frameTs, frameRef) : null;
  return { referenceText: refText, frameReferenceText: frameRefText, checks: checks.map((c) => ({ ...c, native: c.expected !== null && c.text === c.expected })) };
}

async function aboutBlankFrame() {
  const iframe = document.createElement('iframe');
  iframe.setAttribute('data-tag', 'aboutBlank');
  host().appendChild(iframe);
  const immediate = hatchSnapshot(iframe.contentWindow);
  await new Promise((r) => setTimeout(r, 50));
  const later = hatchSnapshot(iframe.contentWindow);
  return { snapshot: immediate, later, url: iframe.contentWindow.location.href };
}

function sharedWorkerSnapshot() {
  return new Promise((resolve, reject) => {
    if (typeof SharedWorker === 'undefined') { resolve({ skipped: true, reason: 'SharedWorker not supported' }); return; }
    try {
      const src = `var hatchSnapshot=${SNAPSHOT_SRC};onconnect=function(e){var p=e.ports[0];p.onmessage=function(){p.postMessage(hatchSnapshot(self));};};`;
      const url = URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
      const w = new SharedWorker(url, 'hatch-harness');
      w.onerror = (ev) => reject(new Error(`shared worker: ${ev.message || 'error'}`));
      w.port.onmessage = (ev) => { resolve({ snapshot: ev.data, kind: 'blob' }); try { w.port.close(); } catch (e) { /* ignore */ } };
      w.port.start();
      w.port.postMessage('snapshot');
    } catch (e) {
      reject(new Error(`shared worker: ${e && e.message}`));
    }
  });
}

function workerSnapshot() {
  return new Promise((resolve, reject) => {
    let worker;
    const start = (w, kind) => {
      worker = w;
      w.onmessage = (ev) => { resolve({ snapshot: ev.data, kind }); w.terminate(); };
      w.onerror = (ev) => { reject(new Error(`${kind} worker: ${ev.message || 'error'}`)); try { w.terminate(); } catch (e) { /* ignore */ } };
      w.postMessage('snapshot');
    };
    try {
      start(new Worker('./worker.js'), 'file');
    } catch (e) {
      try {
        const src = `self.onmessage=function(){var hatchSnapshot=${SNAPSHOT_SRC};postMessage(hatchSnapshot(self));};`;
        const url = URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
        start(new Worker(url), 'blob');
      } catch (e2) {
        reject(new Error(`worker: ${e2 && e2.message}`));
      }
    }
  });
}

export async function probe(ctx) {
  const top = hatchSnapshot(window);
  const crossOriginPrefix = ctx && ctx.crossOrigin ? ctx.crossOrigin : null;
  let crossRealm;
  try { crossRealm = crossRealmToString(); } catch (e) { crossRealm = { error: String(e && e.message) }; }
  const [sameOrigin, crossOrigin, srcdoc, data, blob, sandbox, aboutBlank, worker, sharedWorker] = await Promise.all([
    waitFor(sameOriginFrame(), 8000, 'same-origin frame'),
    crossOriginPrefix ? waitFor(crossOriginFrame(crossOriginPrefix), 8000, 'cross-origin frame') : Promise.resolve({ skipped: true, reason: 'window.__hatchCrossOrigin not set by the host' }),
    waitFor(srcdocFrame(), 8000, 'srcdoc frame'),
    waitFor(dataFrame(), 8000, 'data: frame'),
    waitFor(blobFrame(), 8000, 'blob: frame'),
    waitFor(sandboxFrame(), 8000, 'sandboxed frame'),
    waitFor(aboutBlankFrame(), 2000, 'about:blank frame'),
    waitFor(workerSnapshot(), 8000, 'worker'),
    waitFor(sharedWorkerSnapshot(), 8000, 'shared worker'),
  ]);
  return { contexts: { top, sameOrigin, crossOrigin, srcdoc, data, blob, sandbox, aboutBlank, worker, sharedWorker, crossRealm } };
}
