// Node test for background.js against a fake `browser` API. Run: node --test app/src/main/assets/extension/test/*.test.mjs
// Covers: cached-persona restore before the port (rules/hostRewrites wait for the app), hello/backoff,
// state/persona/rules/hostRewrites handling, generated rule code parsing, host rewrite loop guard, Eruda
// toggle. NOT shipped in the APK.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const ROOT = new URL('../', import.meta.url);
const read = (p) => readFileSync(new URL(p, ROOT), 'utf8');
const BACKGROUND = read('background.js');
const SHIM = read('shim/persona-shim.js');
const GM = read('gm/gm-shim.js');
const MANIFEST = JSON.parse(read('manifest.json'));

// Objects created inside the vm realm have foreign prototypes: compare by value through JSON.
const eq = (actual, expected, msg) => assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, msg);
const tick = () => new Promise((r) => setTimeout(r, 0));
async function settle(n = 10) { for (let i = 0; i < n; i++) await tick(); }

function listenerSet() {
  const fns = [];
  return { addListener: (f) => fns.push(f), fire: (...a) => fns.map((f) => f(...a)), fns };
}

function makeBrowser({ storedState = null, connectFails = false } = {}) {
  const calls = { register: [], unregister: [], execute: [], storageSet: [], ports: [], timers: [] };
  const storage = { state: storedState };
  const api = {
    runtime: {
      lastError: null,
      getManifest: () => MANIFEST,
      getURL: (p) => `moz-extension://abc/${p}`,
      onMessage: listenerSet(),
      connectNative(name) {
        if (connectFails) throw new Error('No such native application');
        const port = { name, posted: [], onMessage: listenerSet(), onDisconnect: listenerSet(), error: null };
        port.postMessage = (m) => port.posted.push(m);
        calls.ports.push(port);
        return port;
      },
    },
    storage: {
      local: {
        get: async (k) => {
          if (k === null || k === undefined) { const all = {}; for (const [kk, v] of Object.entries(storage)) if (v !== null && v !== undefined) all[kk] = v; return all; }
          return storage[k] !== null && storage[k] !== undefined ? { [k]: storage[k] } : {};
        },
        set: async (o) => { Object.assign(storage, o); calls.storageSet.push(JSON.parse(JSON.stringify(o))); },
        remove: async (k) => { delete storage[k]; },
      },
    },
    contentScripts: {
      async register(opts) {
        const rec = { opts, unregistered: false };
        calls.register.push(rec);
        return { unregister: async () => { rec.unregistered = true; calls.unregister.push(rec); } };
      },
    },
    scripting: { executeScript: async (o) => { calls.execute.push(o); return [{ result: null }]; } },
    webRequest: { onBeforeRequest: listenerSet() },
    webNavigation: { onCommitted: listenerSet() },
    tabs: { onRemoved: listenerSet() },
  };
  return { api, calls, storage };
}

async function boot(opts) {
  const { api, calls, storage } = makeBrowser(opts);
  const timers = [];
  const ctx = vm.createContext({
    browser: api,
    console: { log() {}, warn() {}, error() {} },
    fetch: async (url) => {
      const body = url.endsWith('shim/persona-shim.js') ? SHIM : url.endsWith('gm/gm-shim.js') ? GM : null;
      return { ok: body !== null, status: body !== null ? 200 : 404, text: async () => body };
    },
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimeout: () => {},
    URL, Date, JSON, Map, Set, Promise, Array, Object, String, Number, RegExp, Error, TypeError,
  });
  vm.runInContext(BACKGROUND, ctx, { filename: 'background.js' });
  await settle(20);
  return { api, calls, storage, timers, port: () => calls.ports[calls.ports.length - 1] };
}

const PERSONA = {
  enabled: true, desktop: true, touch: false, userAgent: 'UA', platform: 'Win32', oscpu: null, appVersion: null,
  layoutWidth: 1366, screen: { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, dpr: 1 },
  hardwareConcurrency: 8, webgl: null, chromeLike: false, uaData: null, languages: ['en-US'], locale: 'en-US',
  timeZone: 'UTC', tzShim: false, tzOffsets: null, geolocation: null,
};
const RULE = {
  id: 'r1', name: 'Example', matches: ['*://example.com/*'], excludeMatches: ['*://example.com/skip*'],
  js: "'use strict';\nGM_setValue('k', 1); unsafeWindow.__ran = GM_info.script.name;", css: 'body { color: red }',
  world: 'ISOLATED', runAt: 'document_end', enabled: true, source: null,
};

test('startup: hello on the native port, nothing registered without state', async () => {
  const b = await boot();
  assert.equal(b.calls.ports.length, 1);
  assert.equal(b.port().name, 'hatch');
  eq(b.port().posted, [{ type: 'hello', version: MANIFEST.version }]);
  assert.equal(b.calls.register.length, 0);
});

test('startup: only the cached persona shim is re-registered before the port is opened (SEC-3)', async () => {
  const b = await boot({ storedState: { persona: PERSONA, rules: [RULE], hostRewrites: [{ from: 'm.example.com', to: 'www.example.com' }], settings: {} } });
  assert.equal(b.calls.register.length, 1, 'shim only; cached rules wait for the app');
  assert.equal(b.calls.ports.length, 1);
  const shim = b.calls.register[0].opts;
  eq([shim.matches, shim.allFrames, shim.matchAboutBlank, shim.matchOriginAsFallback, shim.runAt, shim.world], [['<all_urls>'], true, true, true, 'document_start', 'MAIN']);
  assert.ok(shim.js[0].code.includes(JSON.stringify(PERSONA)));
  assert.ok(!shim.js[0].code.includes('__PERSONA__'), 'every token replaced');
  // cached host rewrites are NOT live before the app confirms them
  const res = b.api.webRequest.onBeforeRequest.fire({ url: 'https://m.example.com/x?y=1', tabId: 3, type: 'main_frame' })[0];
  eq(res, {});
  // the app's state then brings rules and rewrites to life
  b.port().onMessage.fire({ type: 'state', persona: PERSONA, rules: [RULE], hostRewrites: [{ from: 'm.example.com', to: 'www.example.com' }], settings: {} });
  await settle(30);
  assert.equal(b.calls.register.filter((r) => !r.unregistered).length, 2, 'shim + one rule');
  eq(b.api.webRequest.onBeforeRequest.fire({ url: 'https://m.example.com/x?y=1', tabId: 3, type: 'main_frame' })[0], { redirectUrl: 'https://www.example.com/x?y=1' });
});

test('startup: a malformed cached persona is ignored', async () => {
  for (const persona of [null, 'x', { enabled: 'true', desktop: true }, { enabled: true }, 42]) {
    const b = await boot({ storedState: { persona, rules: [], hostRewrites: [], settings: {} } });
    assert.equal(b.calls.register.length, 0, JSON.stringify(persona));
  }
});

test('persona shim registration falls back to matchAboutBlank when matchOriginAsFallback is rejected', async () => {
  const b = await boot();
  const orig = b.api.contentScripts.register;
  let calls = 0;
  b.api.contentScripts.register = async (opts) => { calls++; if (opts.matchOriginAsFallback) throw new Error('Unexpected property "matchOriginAsFallback"'); return orig(opts); };
  b.port().onMessage.fire({ type: 'persona', persona: PERSONA });
  await settle(30);
  assert.equal(calls, 2);
  assert.equal(b.calls.register.length, 1);
  assert.equal(b.calls.register[0].opts.matchOriginAsFallback, undefined);
  assert.equal(b.calls.register[0].opts.matchAboutBlank, true);
  assert.ok(b.port().posted.some((m) => m.type === 'log' && m.level === 'warn' && /matchOriginAsFallback/.test(m.message)));
});

test('connectNative failure backs off and retries', async () => {
  const b = await boot({ connectFails: true });
  assert.equal(b.calls.ports.length, 0);
  assert.equal(b.timers.length, 1);
  assert.equal(b.timers[0].ms, 500);
});

test('port disconnect schedules reconnect with growing delay', async () => {
  const b = await boot();
  const p1 = b.port();
  p1.error = { message: 'gone' };
  p1.onDisconnect.fire();
  assert.equal(b.timers[b.timers.length - 1].ms, 500);
  b.timers[b.timers.length - 1].fn();
  await settle();
  assert.equal(b.calls.ports.length, 2);
  b.port().onDisconnect.fire();
  assert.equal(b.timers[b.timers.length - 1].ms, 1000);
});

test('state message registers shim + rules, caches to storage.local, persona=disabled unregisters', async () => {
  const b = await boot();
  b.port().onMessage.fire({ type: 'state', persona: PERSONA, rules: [RULE, { ...RULE, id: 'off', enabled: false }, { id: 'bad', matches: [], js: 'x' }], hostRewrites: [], settings: { a: 1 } });
  await settle(30);
  assert.equal(b.calls.register.length, 2, 'shim + enabled valid rule only');
  const rule = b.calls.register[1].opts;
  eq(rule.matches, RULE.matches);
  eq(rule.excludeMatches, RULE.excludeMatches);
  assert.equal(rule.runAt, 'document_end');
  assert.equal(rule.world, 'ISOLATED');
  assert.equal(rule.allFrames, false);
  assert.equal(rule.cssOrigin, 'user');
  assert.equal(rule.css[0].code, RULE.css);
  const code = rule.js[0].code;
  assert.ok(code.includes(RULE.js));
  assert.ok(!code.includes('__RULE_ID__') && !code.includes('__RULE_META__') && !code.includes('__USERSCRIPT__') && !code.includes('__GM_VALUES__'));
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'rule.js' }), 'generated rule code must parse');
  // run the generated rule in a fake page: GM API + user code work end to end
  const store = new Map();
  const page = vm.createContext({
    console: { log() {} }, Promise, JSON,
    localStorage: { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k), key: (i) => Array.from(store.keys())[i], get length() { return store.size; } },
    document: { head: { appendChild() {} }, createElement: () => ({}) },
  });
  page.window = page;
  vm.runInContext(code, page);
  assert.equal(page.__ran, 'Example');
  assert.equal(store.get('__hatch_gm_r1_k'), '1');
  // the bad rule was logged back to the app
  const logs = b.port().posted.filter((m) => m.type === 'log');
  assert.ok(logs.some((m) => m.level === 'warn' && m.message.includes('rule bad')), JSON.stringify(logs));
  // cached
  const last = b.calls.storageSet[b.calls.storageSet.length - 1].state;
  assert.equal(last.persona.enabled, true);
  assert.equal(last.rules.length, 3);
  eq(last.settings, { a: 1 });
  // persona off => unregister, nothing new
  b.port().onMessage.fire({ type: 'persona', persona: { ...PERSONA, enabled: false } });
  await settle(30);
  assert.equal(b.calls.unregister.length, 1);
  assert.equal(b.calls.register.length, 2);
  assert.equal(b.calls.register[0].unregistered, true);
  // rules replaced
  b.port().onMessage.fire({ type: 'rules', rules: [{ ...RULE, id: 'r2', world: 'MAIN', runAt: 'nonsense', css: null }] });
  await settle(30);
  assert.equal(b.calls.register[1].unregistered, true);
  const r2 = b.calls.register[2].opts;
  assert.equal(r2.world, 'MAIN');
  assert.equal(r2.runAt, 'document_idle');
  assert.equal(r2.css, undefined);
});

test('host rewrites: desktop only, main_frame, exact and regex rules, 10 s loop guard per tab', async () => {
  const b = await boot();
  const fire = (url, tabId = 1) => b.api.webRequest.onBeforeRequest.fire({ url, tabId, type: 'main_frame' })[0];
  b.port().onMessage.fire({ type: 'hostRewrites', rules: [{ from: 'M.YouTube.com', to: 'www.youtube.com' }, { from: '/^mobile\\.(.+)$/', to: 'www.$1' }, { from: '/[/', to: 'x' }] });
  await settle();
  eq(fire('https://m.youtube.com/watch?v=1'), {}, 'no persona yet => no rewrite');
  b.port().onMessage.fire({ type: 'persona', persona: { ...PERSONA, desktop: false } });
  await settle(30);
  eq(fire('https://m.youtube.com/watch?v=1'), {}, 'non-desktop persona => no rewrite');
  b.port().onMessage.fire({ type: 'persona', persona: PERSONA });
  await settle(30);
  eq(fire('https://m.youtube.com/watch?v=1'), { redirectUrl: 'https://www.youtube.com/watch?v=1' });
  eq(fire('https://m.youtube.com/watch?v=2'), {}, 'same tab within 10 s: loop guard');
  eq(fire('https://m.youtube.com/watch?v=3', 2), { redirectUrl: 'https://www.youtube.com/watch?v=3' }, 'other tab is independent');
  eq(fire('https://mobile.twitter.com/home', 5), { redirectUrl: 'https://www.twitter.com/home' }, 'regex rule');
  eq(fire('https://www.youtube.com/', 6), {});
  eq(fire('about:blank', 7), {});
  eq(fire('https://m.youtube.com/', -1), {}, 'no tab');
  b.api.tabs.onRemoved.fire(1);
  eq(fire('https://m.youtube.com/again'), { redirectUrl: 'https://www.youtube.com/again' }, 'guard cleared when the tab closes');
});

test('eruda toggle injects, then destroys, and resets on navigation', async () => {
  const b = await boot();
  const send = (tabId) => b.api.runtime.onMessage.fire({ type: 'eruda-toggle' }, { tab: { id: tabId } })[0];
  await send(9); await settle(10);
  assert.equal(b.calls.execute.length, 1);
  eq(b.calls.execute[0], { target: { tabId: 9 }, world: 'MAIN', files: ['vendor/eruda.min.js', 'eruda-init.js'] });
  await send(9); await settle(10);
  assert.equal(b.calls.execute.length, 2);
  assert.equal(b.calls.execute[1].world, 'MAIN');
  assert.equal(typeof b.calls.execute[1].func, 'function');
  await send(9); await settle(10);
  assert.ok(b.calls.execute[2].files, 'third toggle injects again');
  b.api.webNavigation.onCommitted.fire({ tabId: 9, frameId: 0 });
  await send(9); await settle(10);
  assert.ok(b.calls.execute[3].files, 'navigation reset => inject again');
  assert.equal(b.api.runtime.onMessage.fire({ type: 'other' }, { tab: { id: 9 } })[0], undefined);
});

test('manifest matches the spec', () => {
  assert.equal(MANIFEST.manifest_version, 2);
  assert.equal(MANIFEST.browser_specific_settings.gecko.id, 'bridge@hatch.app');
  assert.equal(MANIFEST.browser_specific_settings.gecko.strict_min_version, '128.0');
  eq(MANIFEST.permissions, ['<all_urls>', 'tabs', 'storage', 'nativeMessaging', 'nativeMessagingFromContent', 'scripting', 'webNavigation', 'webRequest', 'webRequestBlocking']);
  assert.equal(MANIFEST.background.persistent, true);
  const cs = MANIFEST.content_scripts[0];
  eq([cs.js, cs.run_at, cs.all_frames, cs.match_about_blank], [['content/bridge.js'], 'document_start', false, true]);
});
