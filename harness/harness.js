/*
 * Hatch detection harness — orchestration. Runs every probe, evaluates against
 * ./personas/<persona>.json, renders the table, POSTs ./report, sets window.__hatchResult and
 * dispatches 'hatch:done' (the headless runner and the in-app bridge wait for exactly that).
 *
 * Query: ?persona=<identity>__<region> (default "none" = no expectations) &env=device|headless
 * Host page may set window.__hatchCrossOrigin = "<url prefix of a second origin serving frame.html>".
 * All URLs are relative so the page works under any base path.
 */
import { buildResult, formatTable, HARNESS_VERSION } from './evaluate.js';
import * as ua from './probes/ua.js';
import * as touch from './probes/touch.js';
import * as media from './probes/media.js';
import * as screen from './probes/screen.js';
import * as intl from './probes/intl.js';
import * as geo from './probes/geo.js';
import * as webgl from './probes/webgl.js';
import * as misc from './probes/misc.js';
import * as contexts from './probes/contexts.js';
import * as server from './probes/server.js';
import * as interactive from './probes/interactive.js';
import * as libraries from './probes/libraries.js';

const PROBES = [ua, touch, media, screen, intl, geo, webgl, misc, contexts, server, interactive, libraries];
const DEFAULT_PROBE_TIMEOUT_MS = 10000;

const params = new URLSearchParams(location.search);
const personaId = params.get('persona') || 'none';
const env = params.get('env') || (navigator.webdriver ? 'headless' : 'device');
const crossOrigin = typeof window.__hatchCrossOrigin === 'string' && window.__hatchCrossOrigin ? window.__hatchCrossOrigin : null;

const $ = (sel) => document.querySelector(sel);
const state = { expected: null, observed: null, result: null, startedAt: null, running: false };

function setStatus(text) { const el = $('#status'); if (el) el.textContent = text; }

function withTimeout(promise, ms, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms} ms`)), ms);
    promise.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}

async function loadExpectations(id) {
  if (!id || id === 'none') return null;
  const res = await fetch(`./personas/${encodeURIComponent(id)}.json`, { cache: 'no-store' });
  if (!res.ok) throw new Error(`no expectations for persona "${id}" (HTTP ${res.status}); run tool/headless/gen-expectations.mjs`);
  return res.json();
}

function fmt(v) {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'string') return v;
  try { return JSON.stringify(v); } catch (e) { return String(v); }
}

function render(result) {
  const chip = $('#persona-chip');
  if (chip) chip.textContent = `persona: ${result.persona}${result.expected ? '' : ' (no expectations)'}`;
  const envChip = $('#env-chip');
  if (envChip) envChip.textContent = `env: ${result.env}`;
  const vChip = $('#version-chip');
  if (vChip) vChip.textContent = `schema v${result.schema} · harness ${HARNESS_VERSION}`;
  const banner = $('#banner');
  if (banner) {
    const s = result.summary;
    banner.className = s.overall;
    banner.textContent = `${s.overall} — pass ${s.pass}, fail ${s.fail} (hard: ${s.hardFail.length}), skipped ${s.skipped}, info ${s.info}; libraries ${s.libraryPass}/${s.libraryTotal}` +
      (s.hardFail.length ? ` — hard: ${s.hardFail.join(', ')}` : '') +
      (s.libraryFail.length ? ` — libraries: ${s.libraryFail.join(', ')}` : '') +
      (result.env === 'device' && s.deviceSkipped.length ? ` — device-scoped skipped: ${s.deviceSkipped.join(', ')}` : '');
  }
  const tbody = $('#signals tbody');
  if (tbody) {
    tbody.textContent = '';
    for (const s of result.signals) {
      const tr = document.createElement('tr');
      tr.className = `${s.status} ${s.tier}`;
      const cells = [
        ['status', s.status], ['tier', s.tier], ['scope', s.scope],
        ['id', null], ['val', fmt(s.expected)], ['val', fmt(s.actual)],
      ];
      cells.forEach(([cls, text], i) => {
        const td = document.createElement('td');
        td.className = cls;
        if (i === 3) {
          td.textContent = s.id;
          const d = document.createElement('div');
          d.className = 'note';
          d.textContent = s.desc + (s.note ? ` — ${s.note}` : '');
          td.appendChild(d);
        } else {
          td.textContent = text;
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    }
  }
  const lbody = $('#libraries tbody');
  if (lbody) {
    lbody.textContent = '';
    for (const l of result.libraries) {
      const tr = document.createElement('tr');
      tr.className = l.status;
      const mk = (cls, text) => { const td = document.createElement('td'); td.className = cls; td.textContent = text; return td; };
      tr.appendChild(mk('status', l.status));
      tr.appendChild(mk('id', `${l.name}@${l.version || '?'}`));
      tr.appendChild(mk('val', `${fmt(l.mobile)}${l.expectedMobile !== undefined ? ` (expected ${l.expectedMobile})` : ''}`));
      tr.appendChild(mk('val', (l.note ? `${l.note} ` : '') + fmt(l.detail)));
      lbody.appendChild(tr);
    }
  }
  const sum = $('#summary');
  if (sum) sum.textContent = JSON.stringify(result.summary, null, 2);
}

async function postReport(result) {
  try {
    const res = await fetch('./report', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(result), cache: 'no-store',
    });
    setStatus(res.ok ? `report sent (${res.status})` : `report rejected (HTTP ${res.status})`);
    return res.ok;
  } catch (e) {
    setStatus(`report not sent: ${e && e.message}`);
    return false;
  }
}

function publish(result, { post = true } = {}) {
  state.result = result;
  window.__hatchResult = result;
  render(result);
  window.dispatchEvent(new CustomEvent('hatch:done', { detail: result }));
  if (post) postReport(result);
}

function build() {
  return buildResult({
    persona: personaId, env, observed: state.observed, expected: state.expected,
    startedAt: state.startedAt, finishedAt: new Date().toISOString(), url: location.href, crossOrigin,
    harness: { version: HARNESS_VERSION, probes: PROBES.map((p) => p.id), libraryManifest: state.observed && state.observed.librariesManifest ? state.observed.librariesManifest : null },
  });
}

export async function run() {
  if (state.running) return state.result;
  state.running = true;
  state.startedAt = new Date().toISOString();
  window.__hatchResult = undefined;
  const banner = $('#banner');
  if (banner) { banner.className = 'RUNNING'; banner.textContent = 'Running probes…'; }
  const observed = { probeErrors: {} };
  state.observed = observed;
  try {
    state.expected = await loadExpectations(personaId);
  } catch (e) {
    observed.probeErrors.expectations = String(e && e.message);
    state.expected = null;
    setStatus(String(e && e.message));
  }
  const ctx = { expected: state.expected, env, crossOrigin, persona: personaId };
  for (const p of PROBES) {
    setStatus(`probing ${p.id}…`);
    try {
      const part = await withTimeout(p.probe(ctx), p.timeoutMs || DEFAULT_PROBE_TIMEOUT_MS, `probe ${p.id}`);
      Object.assign(observed, part);
    } catch (e) {
      observed.probeErrors[p.id] = String(e && e.message);
    }
  }
  if (Object.keys(observed.probeErrors).length === 0) delete observed.probeErrors;
  state.running = false;
  const result = build();
  setStatus('done');
  publish(result);
  return result;
}

// Interactive pointer probe: re-evaluate when the user taps the box.
interactive.onPointer((st) => {
  if (!state.observed) return;
  state.observed.interactive = st;
  if (!state.running) publish(build(), { post: false });
});

function wire() {
  const copy = async (text) => {
    try { await navigator.clipboard.writeText(text); setStatus('copied'); } catch (e) {
      const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); setStatus('copied'); } catch (e2) { setStatus('copy failed'); }
      ta.remove();
    }
  };
  const on = (sel, fn) => { const el = $(sel); if (el) el.addEventListener('click', fn); };
  on('#btn-copy', () => state.result && copy(JSON.stringify(state.result, null, 2)));
  on('#btn-copy-text', () => state.result && copy(formatTable(state.result)));
  on('#btn-send', () => state.result && postReport(state.result));
  on('#btn-rerun', () => run());
}

wire();
run().catch((e) => {
  const banner = $('#banner');
  if (banner) { banner.className = 'ERROR'; banner.textContent = `harness error: ${e && e.message}`; }
  window.__hatchResult = { schema: 1, persona: personaId, env, error: String(e && e.message), summary: { overall: 'FAIL', hardFail: ['harness.error'], softFail: [], libraryFail: [], deviceSkipped: [], pass: 0, fail: 1, skipped: 0, info: 0, libraryTotal: 0, libraryPass: 0 }, signals: [], libraries: [] };
  window.dispatchEvent(new CustomEvent('hatch:done', { detail: window.__hatchResult }));
});
