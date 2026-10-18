// Micro-benchmark for shim/persona-shim.js: the synchronous work every document (top frame AND every
// iframe, incl. about:blank) pays at document_start. Run: node --test app/src/main/assets/extension/test/*.test.mjs
//   HATCH_SHIM_BENCH_ITER=<n>   iterations per configuration (default 1000)
// Reports median / p95 / max ms for (a) cold = compile + execute of a never-seen source (what Gecko pays for
// an inline `code` content script in a fresh frame), (b) exec = execute only (pre-compiled vm.Script), plus the
// per-call cost of the hottest shimmed getters. Fails when the median cold time exceeds the 5 ms budget from
// the spec ("shim <= 30 KB / < 5 ms"). The budget is enforced on this box (2 vCPU VPS); a phone's JS engine
// is slower than a server core, so the numbers are a lower bound and docs/limitations.md carries the device
// figure once measured. NOT shipped in the APK.
import { readFileSync, statSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import { makeRealm } from './fake-env.mjs';

const SHIM_PATH = new URL('../shim/persona-shim.js', import.meta.url);
const SHIM = readFileSync(SHIM_PATH, 'utf8');
const GOLDEN_DIR = new URL('../../../../../../personas/golden/', import.meta.url);
const ITER = Math.max(10, Number(process.env.HATCH_SHIM_BENCH_ITER) || 1000);
const WARMUP = 20;
const BUDGET_MS = 5;
const SIZE_BUDGET = 30 * 1024;

function golden(id) {
  const g = JSON.parse(readFileSync(new URL(`${id}.prefs.json`, GOLDEN_DIR), 'utf8'));
  assert.ok(g.shim && typeof g.shim === 'object', `${id}: golden has no shim config`);
  return g.shim;
}

// The real goldens (the runner and the app inject exactly these objects), plus the heaviest optional path.
const CONFIGS = {
  'win-firefox__us-east': golden('win-firefox__us-east'),
  'win-chrome__uk': golden('win-chrome__uk'), // chromeLike: + userAgentData + plugin/mimeType proxies
  'win-firefox__us-east+tzShim': { ...golden('win-firefox__us-east'), tzShim: true }, // Date/Intl patching
  'real-device__device': golden('real-device__device'), // enabled:false -> early return (never registered)
};

function stats(samples) {
  const s = samples.slice().sort((a, b) => a - b);
  const at = (q) => s[Math.min(s.length - 1, Math.floor(q * s.length))];
  const mean = s.reduce((a, b) => a + b, 0) / s.length;
  return { n: s.length, median: at(0.5), p95: at(0.95), max: s[s.length - 1], mean };
}

function fmt(st) {
  return `median ${st.median.toFixed(3)} ms, p95 ${st.p95.toFixed(3)} ms, max ${st.max.toFixed(3)} ms, mean ${st.mean.toFixed(3)} ms (n=${st.n})`;
}

function bench(cfg) {
  const base = SHIM.replaceAll('__PERSONA__', JSON.stringify(cfg));
  const bytes = Buffer.byteLength(base, 'utf8');
  const compiled = new vm.Script(base, { filename: 'persona-shim.js' });
  const cold = [];
  const exec = [];
  for (let i = 0; i < WARMUP + ITER; i++) {
    // cold: a unique trailing comment defeats V8's in-isolate compilation cache, so every iteration parses
    // the 30 KB source again, like a brand-new frame does.
    const ctx = makeRealm();
    const src = `${base}\n//${i}`;
    const t0 = performance.now();
    vm.runInContext(src, ctx, { filename: `persona-shim-${i}.js` });
    const dt = performance.now() - t0;

    const ctx2 = makeRealm();
    const t1 = performance.now();
    compiled.runInContext(ctx2);
    const dt2 = performance.now() - t1;

    if (i >= WARMUP) { cold.push(dt); exec.push(dt2); }
  }
  return { bytes, cold: stats(cold), exec: stats(exec) };
}

// Hot-path cost after the shim: pages poll these in scroll/resize handlers and on every React render.
function getterCost(cfg) {
  const ctx = makeRealm();
  vm.runInContext(SHIM.replaceAll('__PERSONA__', JSON.stringify(cfg)), ctx, { filename: 'persona-shim.js' });
  const N = 20000;
  const probes = {
    'navigator.platform': 'navigator.platform',
    'navigator.maxTouchPoints': 'navigator.maxTouchPoints',
    'screen.width': 'screen.width',
    'devicePixelRatio': 'devicePixelRatio',
    "matchMedia('(pointer: fine)').matches": "matchMedia('(pointer: fine)').matches",
    "matchMedia('(min-width: 1024px)').matches": "matchMedia('(min-width: 1024px)').matches",
    'Function.prototype.toString (shimmed getter)': "Object.getOwnPropertyDescriptor(Screen.prototype, 'width').get.toString()",
    'new Date().getHours()': 'new Date().getHours()',
  };
  const out = {};
  for (const [label, expr] of Object.entries(probes)) {
    const fn = vm.runInContext(`(function () { var x; for (var i = 0; i < ${N}; i++) x = ${expr}; return x; })`, ctx);
    fn(); // warm
    const t0 = performance.now();
    fn();
    out[label] = ((performance.now() - t0) * 1e6 / N).toFixed(0) + ' ns/call';
  }
  return out;
}

test('shim template size and injected size stay within the 30 KB budget', (t) => {
  const template = statSync(SHIM_PATH).size;
  t.diagnostic(`shim template: ${template} bytes (budget ${SIZE_BUDGET})`);
  assert.ok(template <= SIZE_BUDGET, `template ${template} > ${SIZE_BUDGET}`);
  for (const [id, cfg] of Object.entries(CONFIGS)) {
    const injected = Buffer.byteLength(SHIM.replaceAll('__PERSONA__', JSON.stringify(cfg)), 'utf8');
    t.diagnostic(`${id}: injected code ${injected} bytes (config JSON ${JSON.stringify(cfg).length} bytes)`);
    assert.ok(injected <= SIZE_BUDGET + 4096, `${id}: injected ${injected} bytes is more than 4 KB over the template budget`);
  }
});

test(`shim synchronous work: median cold compile+execute < ${BUDGET_MS} ms over ${ITER} fresh realms per config`, (t) => {
  const results = {};
  for (const [id, cfg] of Object.entries(CONFIGS)) {
    const r = bench(cfg);
    results[id] = r;
    t.diagnostic(`${id} [${r.bytes} B] cold: ${fmt(r.cold)}`);
    t.diagnostic(`${id} [${r.bytes} B] exec: ${fmt(r.exec)}`);
  }
  // realm construction itself, so the reader can tell the harness overhead from the shim cost
  const realm = [];
  for (let i = 0; i < 200; i++) { const t0 = performance.now(); makeRealm(); realm.push(performance.now() - t0); }
  t.diagnostic(`fake realm construction (not included above): ${fmt(stats(realm))}`);
  console.log(JSON.stringify({ iterations: ITER, node: process.version, results }, (k, v) => (typeof v === 'number' ? +v.toFixed(4) : v), 2));
  for (const [id, r] of Object.entries(results)) {
    assert.ok(r.cold.median < BUDGET_MS, `${id}: median cold ${r.cold.median.toFixed(3)} ms >= ${BUDGET_MS} ms budget`);
  }
  // the disabled config must be essentially free: it returns before patching anything
  assert.ok(results['real-device__device'].exec.median < results['win-firefox__us-east'].exec.median,
    'enabled:false must cost less than an enabled persona');
});

test('shimmed getters stay cheap on the hot path', (t) => {
  for (const id of ['win-firefox__us-east', 'win-chrome__uk', 'win-firefox__us-east+tzShim']) {
    const c = getterCost(CONFIGS[id]);
    t.diagnostic(`${id}: ${Object.entries(c).map(([k, v]) => `${k} = ${v}`).join('; ')}`);
    for (const [k, v] of Object.entries(c)) {
      // 50 µs per call would be ~ a frame budget burned by 300 matchMedia polls; anything near that is a bug
      assert.ok(parseInt(v, 10) < 50000, `${id}: ${k} costs ${v}`);
    }
  }
});
