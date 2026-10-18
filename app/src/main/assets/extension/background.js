/*
 * Hatch bridge — persistent MV2 background script.
 *
 * Native port "hatch" <-> app (GeckoView WebExtension.MessageDelegate).
 *   app -> us : {type:"state"|"persona"|"rules"|"hostRewrites", ...}
 *   us -> app : {type:"hello", version}, {type:"log", level, message}
 * Responsibilities: register the MAIN-world persona shim and per-site rules
 * via contentScripts.register, inject/destroy Eruda on request, rewrite
 * mobile hosts on main_frame requests, cache the last state in storage.local
 * so a cold start is never unshimmed (only the persona is restored from the
 * cache: rules and host rewrites wait for the app's state message).
 */
'use strict';

const NATIVE_APP = 'hatch';
const VERSION = browser.runtime.getManifest().version;
const SHIM_URL = browser.runtime.getURL('shim/persona-shim.js');
const GM_URL = browser.runtime.getURL('gm/gm-shim.js');
// Template tokens are substituted in ONE pass (substituted text is never re-scanned), so persona strings
// and rule metadata can contain token names, '*/' or the userscript marker without changing what runs.
const TOKEN_RX = /__(PERSONA|RULE_ID|RULE_META|GM_VALUES)__|\/\*__USERSCRIPT__\*\//g;
const GM_PREFIX = 'gm:';
const REWRITE_GUARD_MS = 10000;
const BACKOFF_MIN_MS = 500;
const BACKOFF_MAX_MS = 30000;
const RUN_AT = new Set(['document_start', 'document_end', 'document_idle']);

let port = null;
let backoff = BACKOFF_MIN_MS;
let reconnectTimer = null;
let state = { persona: null, rules: [], hostRewrites: [], settings: {} };
let personaScript = null;              // RegisteredContentScript for the shim
const ruleScripts = new Map();         // rule id -> RegisteredContentScript
const erudaTabs = new Set();           // tabs where Eruda is currently injected
const rewriteGuard = new Map();        // tabId -> Map<fromHost, lastRedirectMs>
let templates = null;                  // Promise<{shim, gm}>
let chain = Promise.resolve();         // serialises state changes

// ---- logging -----------------------------------------------------------------

function log(level, message) {
  const line = `[hatch-bridge] ${message}`;
  if (level === 'error') console.error(line);
  else if (level === 'warn') console.warn(line);
  else console.log(line);
  if (port) {
    try { port.postMessage({ type: 'log', level, message }); } catch (e) { /* port gone */ }
  }
}

function errorText(e) {
  return e && e.message ? e.message : String(e);
}

function enqueue(label, fn) {
  chain = chain.then(fn).catch((e) => log('error', `${label}: ${errorText(e)}`));
  return chain;
}

// ---- templates -----------------------------------------------------------------

/** Replaces every template token with map[token] in a single left-to-right pass. */
function substitute(template, map) {
  return template.replace(TOKEN_RX, (token) => (Object.prototype.hasOwnProperty.call(map, token) ? map[token] : token));
}

async function fetchText(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`fetch ${url}: HTTP ${res.status}`);
  return res.text();
}

function loadTemplates() {
  if (!templates) {
    templates = Promise.all([fetchText(SHIM_URL), fetchText(GM_URL)])
      .then(([shim, gm]) => ({ shim, gm }))
      .catch((e) => { templates = null; throw e; });
  }
  return templates;
}

// ---- persona shim ----------------------------------------------------------------

async function registerPersonaShim(cfg) {
  if (personaScript) {
    const old = personaScript;
    personaScript = null;
    try { await old.unregister(); } catch (e) { log('warn', `unregister persona shim: ${errorText(e)}`); }
  }
  if (!cfg || cfg.enabled !== true) {
    log('info', 'persona shim: not registered (persona disabled / real device)');
    return;
  }
  const { shim } = await loadTemplates();
  const code = substitute(shim, { __PERSONA__: JSON.stringify(cfg) });
  // matchOriginAsFallback (Gecko >= 128): also inject into about:, data:, blob: and sandboxed (opaque
  // origin) frames created by a matching page, which matchAboutBlank alone does not cover.
  const opts = { matches: ['<all_urls>'], allFrames: true, matchAboutBlank: true, matchOriginAsFallback: true, runAt: 'document_start', world: 'MAIN', js: [{ code }] };
  try {
    personaScript = await browser.contentScripts.register(opts);
  } catch (e) {
    log('warn', `persona shim: matchOriginAsFallback rejected (${errorText(e)}); data:/blob:/sandboxed frames stay unshimmed`);
    delete opts.matchOriginAsFallback;
    personaScript = await browser.contentScripts.register(opts);
  }
  log('info', `persona shim registered (desktop=${cfg.desktop === true}, touch=${cfg.touch === true}, tzShim=${cfg.tzShim === true})`);
}

// ---- site rules ------------------------------------------------------------------

function hasText(s) {
  return typeof s === 'string' && s.trim().length > 0;
}

function ruleProblem(rule) {
  if (!rule || typeof rule !== 'object') return 'not an object';
  if (!hasText(rule.id)) return 'missing id';
  if (!Array.isArray(rule.matches) || rule.matches.length === 0) return 'missing matches';
  if (!rule.matches.every(hasText)) return 'matches must be non-empty strings';
  if (!hasText(rule.js) && !hasText(rule.css)) return 'needs js or css';
  return null;
}

/** GM values of one rule from storage.local (keys gm:<ruleId>:<key>), given the whole store. */
function gmValuesFor(all, ruleId) {
  const prefix = `${GM_PREFIX}${ruleId}:`;
  const out = {};
  for (const k of Object.keys(all || {})) if (k.startsWith(prefix)) out[k.slice(prefix.length)] = all[k];
  return out;
}

function wrapUserscript(gm, rule, gmValues) {
  const meta = {
    id: rule.id,
    name: hasText(rule.name) ? rule.name : rule.id,
    source: hasText(rule.source) ? rule.source : null,
    matches: rule.matches,
    excludeMatches: Array.isArray(rule.excludeMatches) ? rule.excludeMatches : [],
    runAt: RUN_AT.has(rule.runAt) ? rule.runAt : 'document_idle',
    world: rule.world === 'MAIN' ? 'MAIN' : 'ISOLATED',
    extensionVersion: VERSION,
  };
  return substitute(gm, {
    __RULE_ID__: JSON.stringify(String(rule.id)),
    __RULE_META__: JSON.stringify(meta),
    __GM_VALUES__: JSON.stringify(gmValues && typeof gmValues === 'object' ? gmValues : {}),
    '/*__USERSCRIPT__*/': `\n${rule.js}\n`,
  });
}

async function unregisterRules() {
  const old = Array.from(ruleScripts.entries());
  ruleScripts.clear();
  for (const [id, script] of old) {
    try { await script.unregister(); } catch (e) { log('warn', `unregister rule ${id}: ${errorText(e)}`); }
  }
}

async function registerRules(rules) {
  await unregisterRules();
  const list = Array.isArray(rules) ? rules : [];
  if (list.length === 0) { log('info', 'rules: none'); return; }
  const { gm } = await loadTemplates();
  let stored = {};
  try { stored = await browser.storage.local.get(null); } catch (e) { log('warn', `storage.local.get: ${errorText(e)}`); }
  let enabled = 0;
  for (const rule of list) {
    if (!rule || rule.enabled === false) continue;
    enabled++;
    const problem = ruleProblem(rule);
    if (problem) { log('warn', `rule ${rule && rule.id}: skipped (${problem})`); continue; }
    const opts = {
      matches: rule.matches,
      allFrames: false,
      runAt: RUN_AT.has(rule.runAt) ? rule.runAt : 'document_idle',
    };
    if (Array.isArray(rule.excludeMatches) && rule.excludeMatches.length > 0) {
      opts.excludeMatches = rule.excludeMatches.filter(hasText);
    }
    if (hasText(rule.js)) {
      opts.world = rule.world === 'MAIN' ? 'MAIN' : 'ISOLATED';
      // MAIN-world rules have no extension storage: they keep page localStorage and get no preload.
      opts.js = [{ code: wrapUserscript(gm, rule, opts.world === 'ISOLATED' ? gmValuesFor(stored, rule.id) : {}) }];
    }
    if (hasText(rule.css)) {
      opts.css = [{ code: rule.css }];
      opts.cssOrigin = 'user';
    }
    try {
      ruleScripts.set(rule.id, await browser.contentScripts.register(opts));
    } catch (e) {
      log('error', `rule ${rule.id} (${rule.name || ''}): ${errorText(e)}`);
    }
  }
  log('info', `rules registered: ${ruleScripts.size} of ${enabled} enabled (${list.length} total)`);
}

// ---- host rewrites ---------------------------------------------------------------

function normalizeHostRewrites(rules) {
  const out = [];
  for (const r of Array.isArray(rules) ? rules : []) {
    if (!r || !hasText(r.from) || !hasText(r.to)) continue;
    const from = r.from.trim();
    const to = r.to.trim();
    if (from.length > 2 && from.startsWith('/') && from.endsWith('/')) {
      try { out.push({ re: new RegExp(from.slice(1, -1), 'i'), to }); } catch (e) { log('warn', `hostRewrite ${from}: bad regex`); }
    } else {
      out.push({ from: from.toLowerCase(), to: to.toLowerCase() });
    }
  }
  return out;
}

function rewriteHost(host) {
  for (const r of state.hostRewrites) {
    if (r.re) {
      if (r.re.test(host)) {
        const to = host.replace(r.re, r.to).toLowerCase();
        if (to && to !== host) return to;
      }
    } else if (r.from === host && r.to !== host) {
      return r.to;
    }
  }
  return null;
}

function onBeforeRequest(details) {
  const p = state.persona;
  if (!p || p.enabled !== true || p.desktop !== true) return {};
  if (state.hostRewrites.length === 0 || details.tabId < 0) return {};
  let url;
  try { url = new URL(details.url); } catch (e) { return {}; }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return {};
  const host = url.hostname.toLowerCase();
  const to = rewriteHost(host);
  if (!to) return {};
  const now = Date.now();
  let guard = rewriteGuard.get(details.tabId);
  if (!guard) { guard = new Map(); rewriteGuard.set(details.tabId, guard); }
  const last = guard.get(host);
  if (last !== undefined && now - last < REWRITE_GUARD_MS) {
    log('warn', `host rewrite loop guard: ${host} -> ${to} skipped in tab ${details.tabId}`);
    return {};
  }
  guard.set(host, now);
  url.hostname = to;
  log('info', `host rewrite: ${host} -> ${to} (tab ${details.tabId})`);
  return { redirectUrl: url.href };
}

browser.webRequest.onBeforeRequest.addListener(
  onBeforeRequest,
  { urls: ['<all_urls>'], types: ['main_frame'] },
  ['blocking'],
);

// ---- Eruda ----------------------------------------------------------------------------

function destroyErudaInPage() {
  try {
    if (window.eruda && typeof window.eruda.destroy === 'function') window.eruda.destroy();
  } catch (e) { /* ignore */ }
}

async function toggleEruda(tabId) {
  if (erudaTabs.has(tabId)) {
    erudaTabs.delete(tabId);
    await browser.scripting.executeScript({ target: { tabId }, world: 'MAIN', func: destroyErudaInPage });
    log('info', `eruda destroyed in tab ${tabId}`);
  } else {
    await browser.scripting.executeScript({
      target: { tabId },
      world: 'MAIN',
      files: ['vendor/eruda.min.js', 'eruda-init.js'],
    });
    erudaTabs.add(tabId);
    log('info', `eruda injected in tab ${tabId}`);
  }
}

browser.runtime.onMessage.addListener((msg, sender) => {
  if (!msg || typeof msg !== 'object' || !sender || !sender.tab) return undefined;
  if (msg.type === 'eruda-toggle') {
    const tabId = sender.tab.id;
    return enqueue(`eruda tab ${tabId}`, () => toggleEruda(tabId)).then(() => ({ ok: true }));
  }
  return undefined;
});

browser.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId === 0) erudaTabs.delete(details.tabId);
});

browser.tabs.onRemoved.addListener((tabId) => {
  erudaTabs.delete(tabId);
  rewriteGuard.delete(tabId);
});

// ---- state ------------------------------------------------------------------------------

function persist() {
  const snapshot = {
    persona: state.persona,
    rules: state.rules,
    hostRewrites: state.rawHostRewrites || [],
    settings: state.settings,
  };
  return browser.storage.local.set({ state: snapshot })
    .catch((e) => log('warn', `storage.local.set: ${errorText(e)}`));
}

async function applyPersona(persona, save = true) {
  state.persona = persona && typeof persona === 'object' ? persona : null;
  await registerPersonaShim(state.persona);
  if (save) await persist();
}

async function applyRules(rules, save = true) {
  state.rules = Array.isArray(rules) ? rules : [];
  await registerRules(state.rules);
  if (save) await persist();
}

async function applyHostRewrites(rules, save = true) {
  state.rawHostRewrites = Array.isArray(rules) ? rules : [];
  state.hostRewrites = normalizeHostRewrites(state.rawHostRewrites);
  log('info', `host rewrites: ${state.hostRewrites.length}`);
  if (save) await persist();
}

async function applyState(s, save = true) {
  const st = s && typeof s === 'object' ? s : {};
  state.settings = st.settings && typeof st.settings === 'object' ? st.settings : {};
  await applyHostRewrites(st.hostRewrites, false);
  await applyPersona(st.persona, false);
  await applyRules(st.rules, false);
  if (save) await persist();
}

function onAppMessage(msg) {
  backoff = BACKOFF_MIN_MS; // traffic proves the port is alive
  if (!msg || typeof msg !== 'object') return;
  switch (msg.type) {
    case 'state':
      enqueue('state', () => applyState(msg));
      break;
    case 'persona':
      enqueue('persona', () => applyPersona(msg.persona));
      break;
    case 'rules':
      enqueue('rules', () => applyRules(msg.rules));
      break;
    case 'hostRewrites':
      enqueue('hostRewrites', () => applyHostRewrites(msg.rules));
      break;
    default:
      log('warn', `unknown message type from app: ${String(msg.type)}`);
  }
}

// ---- native port with reconnect/backoff -------------------------------------------

function scheduleReconnect(reason) {
  if (reconnectTimer !== null) return;
  const delay = backoff;
  backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
  console.warn(`[hatch-bridge] native port "${NATIVE_APP}" down (${reason}); retry in ${delay} ms`);
  reconnectTimer = setTimeout(connect, delay);
}

function connect() {
  reconnectTimer = null;
  let p;
  try {
    p = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    scheduleReconnect(errorText(e));
    return;
  }
  port = p;
  p.onMessage.addListener(onAppMessage);
  p.onDisconnect.addListener(() => {
    const err = p.error || browser.runtime.lastError;
    if (port === p) port = null;
    scheduleReconnect(err ? errorText(err) : 'disconnected');
  });
  try {
    p.postMessage({ type: 'hello', version: VERSION });
  } catch (e) {
    console.warn(`[hatch-bridge] hello failed: ${errorText(e)}`);
  }
}

// ---- startup: restore the cached persona first, then open the port ----------------------

/** The cached persona config is pure data; anything else is ignored until the app confirms it. */
function cachedPersona(stored) {
  const p = stored && typeof stored === 'object' ? stored.persona : null;
  if (!p || typeof p !== 'object' || typeof p.enabled !== 'boolean' || typeof p.desktop !== 'boolean') return null;
  return p;
}

enqueue('restore', async () => {
  let stored = null;
  try {
    const got = await browser.storage.local.get('state');
    stored = got && got.state ? got.state : null;
  } catch (e) {
    console.warn(`[hatch-bridge] storage.local.get: ${errorText(e)}`);
  }
  const persona = cachedPersona(stored);
  if (persona) {
    // Only the persona shim: cached rules (code) and host rewrites (navigation) are code-bearing /
    // navigation-altering state and take effect only after the app's state message (SEC-3).
    await applyPersona(persona, false);
    console.log('[hatch-bridge] restored cached persona before the native port is up');
  }
}).then(connect);
