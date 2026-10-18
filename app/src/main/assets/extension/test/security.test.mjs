// Security review (M0): node tests for the bridge extension's trust boundaries.
// Run: node --test app/src/main/assets/extension/test/*.test.mjs   (NOT shipped in the APK)
//
// Every test locks in behaviour that was verified safe, including the M0 security findings fixed in the
// M0 fix pass:
//   SEC-1  manifest grants "nativeMessagingFromContent" so GeckoView accepts the per-tab port
//          (mobile/shared/modules/geckoview/GeckoViewWebExtension.sys.mjs exportFlags() at FIREFOX_158_0_RELEASE)
//   SEC-2  ISOLATED-world site rules cannot reach the extension's content-script API (browser/chrome hidden)
//   SEC-3  only the cached persona is restored from storage.local; rules + hostRewrites wait for the app
//   SEC-4  template tokens are substituted in one pass; '*/' and token names in data never become code
//   SEC-8  GM_* values live in the extension's storage.local (gm:<ruleId>:<key>), not in page localStorage
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import { makeRealm } from './fake-env.mjs';

const ROOT = new URL('../', import.meta.url);
const read = (p) => readFileSync(new URL(p, ROOT), 'utf8');
const BACKGROUND = read('background.js');
const SHIM = read('shim/persona-shim.js');
const GM = read('gm/gm-shim.js');
const MANIFEST = JSON.parse(read('manifest.json'));

const eq = (actual, expected, msg) => assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, msg);
const tick = () => new Promise((r) => setTimeout(r, 0));
async function settle(n = 20) { for (let i = 0; i < n; i++) await tick(); }

function listenerSet() {
  const fns = [];
  const addCalls = [];
  return {
    addListener: (f, ...rest) => { fns.push(f); addCalls.push(rest); },
    fire: (...a) => fns.map((f) => f(...a)),
    addCalls,
  };
}

function makeBrowser({ storedState = null } = {}) {
  const calls = { register: [], unregister: [], execute: [], storageSet: [], ports: [] };
  const storage = { state: storedState };
  const api = {
    runtime: {
      lastError: null,
      getManifest: () => MANIFEST,
      getURL: (p) => `moz-extension://abc/${p}`,
      onMessage: listenerSet(),
      connectNative(name) {
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
  const ctx = vm.createContext({
    browser: api,
    console: { log() {}, warn() {}, error() {} },
    fetch: async (url) => {
      const body = url.endsWith('shim/persona-shim.js') ? SHIM : url.endsWith('gm/gm-shim.js') ? GM : null;
      return { ok: body !== null, status: body !== null ? 200 : 404, text: async () => body };
    },
    setTimeout: () => 1,
    clearTimeout: () => {},
    URL, Date, JSON, Map, Set, Promise, Array, Object, String, Number, RegExp, Error, TypeError,
  });
  vm.runInContext(BACKGROUND, ctx, { filename: 'background.js' });
  await settle(20);
  return { api, calls, storage, port: () => calls.ports[calls.ports.length - 1] };
}

const PERSONA = {
  enabled: true, desktop: true, touch: false, userAgent: 'UA', platform: 'Win32', oscpu: null, appVersion: null,
  layoutWidth: 1366, screen: { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, dpr: 1 },
  hardwareConcurrency: 8, webgl: null, chromeLike: false, uaData: null, languages: ['en-US'], locale: 'en-US',
  timeZone: 'UTC', tzShim: false, tzOffsets: null, geolocation: null,
};

// A page realm for running generated rule code: has the globals a Firefox content-script sandbox exposes
// (window, localStorage, document, console) PLUS a fake `browser` object so the test can tell whether the
// userscript can reach the extension API.
function pageRealm({ withBrowser = true } = {}) {
  const store = new Map();
  const ext = { sets: [], removes: [], listeners: [] };
  const g = {
    console: { log() {}, warn() {} }, Promise, JSON, Object, Array, String, TypeError, Error,
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k), key: (i) => Array.from(store.keys())[i], get length() { return store.size; },
    },
    document: { head: { appendChild() {} }, createElement: () => ({}) },
  };
  if (withBrowser) {
    // what a Firefox content-script sandbox exposes (lazy getters, configurable): browser + chrome
    const api = {
      storage: {
        local: { set: async (o) => { ext.sets.push(o); }, remove: async (k) => { ext.removes.push(k); } },
        onChanged: { addListener: (f) => ext.listeners.push(f) },
      },
      runtime: { sendMessage: async () => {}, connectNative: () => ({}) },
    };
    Object.defineProperty(g, 'browser', { get: () => api, enumerable: true, configurable: true });
    Object.defineProperty(g, 'chrome', { get: () => api, enumerable: true, configurable: true });
  }
  const ctx = vm.createContext(g);
  ctx.window = ctx;
  ctx.globalThis = ctx;
  return { ctx, store, ext };
}

// ---- SEC-1: per-tab native port -------------------------------------------------------------------------

test('SEC-1 manifest grants content-script native messaging (nativeMessagingFromContent)', () => {
  // GeckoView 158: WebExtensionController.getDelegate() answers "This NativeApp can't receive messages from
  // Content Scripts." unless Flags.ALLOW_CONTENT_MESSAGING is set, and GeckoViewWebExtension.sys.mjs
  // exportFlags() sets that flag only when the manifest has the "nativeMessagingFromContent" permission.
  // content/bridge.js (Eruda toggle, Self-check hand-over) connects from a content script.
  assert.ok(MANIFEST.permissions.includes('nativeMessagingFromContent'), 'content/bridge.js needs nativeMessagingFromContent');
});

// ---- SEC-2: userscript sandbox ----------------------------------------------------------------------------

test('SEC-2 ISOLATED-world rule code cannot reach the extension API (browser/chrome)', async () => {
  const b = await boot();
  const rule = {
    id: 'probe', name: 'probe', matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED', runAt: 'document_idle',
    js: "unsafeWindow.__seen = [typeof browser, typeof chrome, typeof globalThis.browser, typeof this.browser, typeof window.browser].join(',');" +
      "try { browser = { x: 1 }; } catch (e) {} try { Object.defineProperty(globalThis, 'browser', { value: 1 }); } catch (e) { unsafeWindow.__redefine = e.name; }" +
      "unsafeWindow.__after = typeof browser + ',' + typeof globalThis.browser;",
  };
  b.port().onMessage.fire({ type: 'rules', rules: [rule] });
  await settle(30);
  const code = b.calls.register[0].opts.js[0].code;
  const page = pageRealm({ withBrowser: true });
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__seen, 'undefined,undefined,undefined,undefined,undefined', 'userscript must not see browser.* / chrome.*');
  assert.equal(page.ctx.__redefine, 'TypeError', 'the hidden binding is not configurable');
  assert.equal(page.ctx.__after, 'undefined,undefined');
  // MAIN-world rules never had the API; the shim must not plant a `browser` global on the page there
  const b2 = await boot();
  b2.port().onMessage.fire({ type: 'rules', rules: [{ ...rule, id: 'main', world: 'MAIN', js: "unsafeWindow.__has = 'browser' in window;" }] });
  await settle(30);
  const page2 = pageRealm({ withBrowser: false });
  vm.runInContext(b2.calls.register[0].opts.js[0].code, page2.ctx, { filename: 'rule.js' });
  assert.equal(page2.ctx.__has, false);
});

test('SEC-8 ISOLATED-world GM values use extension storage under gm:<ruleId>:<key>, never page localStorage', async () => {
  const b = await boot();
  b.storage['gm:r8:k'] = 'preloaded';
  b.storage['gm:r8:n'] = 7;
  b.storage['gm:other:k'] = 'not mine';
  const rule = {
    id: 'r8', name: 'r8', matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED',
    js: "unsafeWindow.__pre = [GM_getValue('k'), GM_getValue('n'), GM_getValue('missing', 'dflt'), GM_listValues().sort().join(',')].join('|');" +
      "GM_setValue('k', { a: 1 }); GM_deleteValue('n'); unsafeWindow.__post = JSON.stringify(GM_getValue('k')) + '|' + GM_listValues().join(',') + '|' + GM_info.storage;" +
      "unsafeWindow.__read = function () { return GM_getValue('k') + '|' + GM_listValues().sort().join(','); };",
  };
  b.port().onMessage.fire({ type: 'rules', rules: [rule] });
  await settle(30);
  const code = b.calls.register[0].opts.js[0].code;
  assert.ok(code.includes(JSON.stringify({ k: 'preloaded', n: 7 })), 'only this rule\'s values are inlined');
  assert.ok(!code.includes('not mine'));
  const page = pageRealm({ withBrowser: true });
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__pre, 'preloaded|7|dflt|k,n');
  assert.equal(page.ctx.__post, '{"a":1}|k|extension');
  eq(page.ext.sets, [{ 'gm:r8:k': { a: 1 } }]);
  eq(page.ext.removes, ['gm:r8:n']);
  assert.equal(page.store.size, 0, 'page localStorage untouched');
  // another tab's write arrives through storage.onChanged
  assert.equal(page.ext.listeners.length, 1);
  page.ext.listeners[0]({ 'gm:r8:k': { newValue: 'changed' }, 'gm:r8:z': { newValue: 1 }, 'gm:other:k': { newValue: 'x' } }, 'local');
  assert.equal(page.ctx.__read(), 'changed|k,z');
  page.ext.listeners[0]({ 'gm:r8:z': { oldValue: 1 } }, 'local');
  assert.equal(page.ctx.__read(), 'changed|k');
  page.ext.listeners[0]({ 'gm:r8:k': { newValue: 'sync' } }, 'sync');
  assert.equal(page.ctx.__read(), 'changed|k', 'other storage areas are ignored');
  // MAIN world: no extension storage -> page localStorage, like before
  const b2 = await boot();
  b2.port().onMessage.fire({ type: 'rules', rules: [{ ...rule, id: 'm8', world: 'MAIN', js: "GM_setValue('k', 'v'); unsafeWindow.__s = GM_info.storage;" }] });
  await settle(30);
  const page2 = pageRealm({ withBrowser: false });
  vm.runInContext(b2.calls.register[0].opts.js[0].code, page2.ctx, { filename: 'rule.js' });
  assert.equal(page2.store.get('__hatch_gm_m8_k'), '"v"');
  assert.equal(page2.ctx.__s, 'localStorage');
});

test('SEC-8 two rules cannot read each other\'s values', async () => {
  const b = await boot();
  b.storage['gm:a:secret'] = 's';
  b.port().onMessage.fire({ type: 'rules', rules: [
    { id: 'a', name: 'a', matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED', js: "unsafeWindow.__a = GM_getValue('secret');" },
    { id: 'b', name: 'b', matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED', js: "unsafeWindow.__b = GM_getValue('secret', 'none') + '|' + GM_listValues().length;" },
  ] });
  await settle(30);
  const page = pageRealm({ withBrowser: true });
  vm.runInContext(b.calls.register[0].opts.js[0].code, page.ctx, { filename: 'a.js' });
  vm.runInContext(b.calls.register[1].opts.js[0].code, page.ctx, { filename: 'b.js' });
  assert.equal(page.ctx.__a, 's');
  assert.equal(page.ctx.__b, 'none|0');
  assert.ok(!b.calls.register[1].opts.js[0].code.includes('"secret":"s"'), 'rule b\'s code carries nothing of rule a');
  assert.ok(b.calls.register[0].opts.js[0].code.includes('"secret":"s"'));
});

test('SEC-3 startup restore registers only the persona shim; rules and host rewrites wait for the app', async () => {
  // A rule in storage.local can be planted by any ISOLATED-world rule (browser.storage.local is a
  // content-script API). Until the native port delivers the real state, whatever is cached is live.
  const poisoned = {
    persona: PERSONA,
    rules: [{ id: 'evil', name: 'evil', matches: ['<all_urls>'], js: 'void 0', world: 'MAIN', enabled: true, runAt: 'document_start' }],
    hostRewrites: [{ from: 'www.bank.example', to: 'evil.example' }],
    settings: {},
  };
  const b = await boot({ storedState: poisoned });
  const registered = b.calls.register.filter((r) => !r.unregistered).map((r) => r.opts);
  assert.equal(registered.length, 1, 'only the persona shim is registered from cache');
  assert.equal(registered[0].world, 'MAIN');
  assert.ok(registered[0].js[0].code.includes(JSON.stringify(PERSONA)));
  const res = b.api.webRequest.onBeforeRequest.fire({ url: 'https://www.bank.example/login', tabId: 1, type: 'main_frame' })[0];
  eq(res, {}, 'cached host rewrites must not redirect before the app confirms them');
});

test('the app state fully replaces whatever was cached (poison window closes on connect)', async () => {
  const poisoned = {
    persona: PERSONA,
    rules: [{ id: 'evil', name: 'evil', matches: ['<all_urls>'], js: 'void 0', world: 'MAIN', enabled: true, runAt: 'document_start' }],
    hostRewrites: [{ from: 'www.bank.example', to: 'evil.example' }],
    settings: {},
  };
  const b = await boot({ storedState: poisoned });
  b.port().onMessage.fire({ type: 'state', persona: PERSONA, rules: [], hostRewrites: [{ from: 'm.youtube.com', to: 'www.youtube.com' }], settings: {} });
  await settle(30);
  const live = b.calls.register.filter((r) => !r.unregistered).map((r) => r.opts);
  assert.equal(live.length, 1, 'only the (re-registered) persona shim stays');
  assert.equal(live[0].world, 'MAIN');
  assert.ok(!live[0].js[0].code.includes('evil'));
  eq(b.api.webRequest.onBeforeRequest.fire({ url: 'https://www.bank.example/login', tabId: 1, type: 'main_frame' })[0], {});
  eq(b.api.webRequest.onBeforeRequest.fire({ url: 'https://m.youtube.com/w', tabId: 1, type: 'main_frame' })[0], { redirectUrl: 'https://www.youtube.com/w' });
  const last = b.calls.storageSet[b.calls.storageSet.length - 1].state;
  eq(last.rules, []);
  eq(last.hostRewrites, [{ from: 'm.youtube.com', to: 'www.youtube.com' }]);
});

// ---- template injection (persona JSON / rule metadata) ----------------------------------------------------

// No "*/" here: that case is SEC-4 below.
const HOSTILE = '); alert(1); (function(){ </script><script>x</script> `${1}` "\'\\    __USERSCRIPT__ __RULE_META__ __RULE_ID__ __PERSONA__ \u0000';
// SEC-4 (fixed): the templates mention no token inside their header comments and background.js substitutes
// every token in one pass, so a "*/" or a token name inside data stays inside its JSON string literal.
const COMMENT_BREAK = 'x */ globalThis.__pwned = 1; /* y';

test('SEC-4 templates carry each token exactly once and none inside a comment', () => {
  const count = (src, re) => (src.match(re) || []).length;
  assert.equal(count(SHIM, /__PERSONA__/g), 1);
  assert.equal(count(GM, /__RULE_ID__/g), 1);
  assert.equal(count(GM, /__RULE_META__/g), 1);
  assert.equal(count(GM, /__GM_VALUES__/g), 1);
  assert.equal(count(GM, /\/\*__USERSCRIPT__\*\//g), 1);
  for (const [name, src] of [['shim', SHIM], ['gm', GM]]) {
    for (const c of src.match(/\/\*[\s\S]*?\*\//g) || []) {
      if (c === '/*__USERSCRIPT__*/') continue;
      assert.ok(!/__(PERSONA|RULE_ID|RULE_META|GM_VALUES)__/.test(c), `${name}: token inside a block comment: ${c.slice(0, 80)}`);
    }
  }
});

async function registeredRuleCode(rule) {
  const b = await boot();
  b.port().onMessage.fire({ type: 'rules', rules: [rule] });
  await settle(30);
  assert.equal(b.calls.register.length, 1, 'rule registered');
  return b.calls.register[0].opts.js[0].code;
}

const META_PROBE = "unsafeWindow.__name = GM_info.script.name; unsafeWindow.__src = GM_info.script.source; unsafeWindow.__rid = GM_info.ruleId; GM_setValue('k', 'v');";

test('hostile rule metadata (quotes, backslashes, U+2028, tokens) cannot break out of the GM wrapper', async () => {
  // the id carries the hostile characters but no token names (token-in-id is the SEC-4 facet below)
  const rule = {
    id: 'r-' + HOSTILE.replace(/__[A-Z_]+__/g, ''), name: HOSTILE, source: HOSTILE, matches: ['*://example.com/*'], excludeMatches: [HOSTILE],
    enabled: true, world: 'ISOLATED', runAt: 'document_idle', js: META_PROBE,
  };
  const code = await registeredRuleCode(rule);
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'rule.js' }), 'generated code must still parse');
  const page = pageRealm();
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__name, HOSTILE);
  assert.equal(page.ctx.__src, HOSTILE);
  assert.equal(page.ctx.__rid, rule.id);
  eq(page.ext.sets, [{ ['gm:' + rule.id + ':k']: 'v' }], 'GM storage keyed by the exact rule id');
});

test('SEC-4 a "*/" in rule metadata neither breaks the wrapper nor runs as code', async () => {
  const rule = { id: 'r4', name: COMMENT_BREAK, matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED', js: META_PROBE };
  const code = await registeredRuleCode(rule);
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'rule.js' }), 'generated code must still parse');
  const page = pageRealm();
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__pwned, undefined, 'metadata text must never execute');
  assert.equal(page.ctx.__name, COMMENT_BREAK);
});

test('SEC-4 a rule id containing a later token name does not break the wrapper', async () => {
  const id = 'r-__RULE_META__ __GM_VALUES__ __PERSONA__';
  const code = await registeredRuleCode({ id, name: 'n', matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED', js: 'unsafeWindow.__rid = GM_info.ruleId;' });
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'rule.js' }));
  const page = pageRealm();
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__rid, id);
});

test('SEC-4 a rule name containing the userscript marker does not swallow the user script', async () => {
  const name = 'odd /*__USERSCRIPT__*/ name';
  const rule = { id: 'r5', name, matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED', js: 'unsafeWindow.__ran = GM_info.script.name;' };
  const code = await registeredRuleCode(rule);
  assert.ok(!code.includes('/*__USERSCRIPT__*/\n  }).call('), 'real marker must be replaced');
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'rule.js' }));
  const page = pageRealm();
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__ran, name);
});

test('a userscript body containing template tokens is not re-substituted', async () => {
  const b = await boot();
  const rule = {
    id: 'tok', name: 'tok', matches: ['*://example.com/*'], enabled: true, world: 'ISOLATED',
    js: "unsafeWindow.__t = '__RULE_ID__ __RULE_META__ /*__USERSCRIPT__*/ __PERSONA__';",
  };
  b.port().onMessage.fire({ type: 'rules', rules: [rule] });
  await settle(30);
  const code = b.calls.register[0].opts.js[0].code;
  const page = pageRealm();
  vm.runInContext(code, page.ctx, { filename: 'rule.js' });
  assert.equal(page.ctx.__t, '__RULE_ID__ __RULE_META__ /*__USERSCRIPT__*/ __PERSONA__');
});

async function registeredShimCode(cfg) {
  const b = await boot();
  b.port().onMessage.fire({ type: 'persona', persona: cfg });
  await settle(30);
  assert.equal(b.calls.register.length, 1, 'shim registered');
  return b.calls.register[0].opts.js[0].code;
}

test('hostile persona strings (quotes, backslashes, U+2028, tokens) cannot break out of the shim template', async () => {
  const cfg = { ...PERSONA, userAgent: HOSTILE, platform: HOSTILE, oscpu: HOSTILE, appVersion: HOSTILE, languages: [HOSTILE], timeZone: HOSTILE, locale: HOSTILE };
  const code = await registeredShimCode(cfg);
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'persona-shim.js' }));
  const ctx = makeRealm();
  vm.runInContext(code, ctx, { filename: 'persona-shim.js' });
  assert.equal(vm.runInContext('navigator.userAgent', ctx), HOSTILE);
  assert.equal(vm.runInContext('navigator.platform', ctx), HOSTILE);
  assert.equal(vm.runInContext('navigator.languages[0]', ctx), HOSTILE);
});

test('SEC-4 a "*/" in a persona string neither disables the shim nor runs as code', async () => {
  const code = await registeredShimCode({ ...PERSONA, userAgent: COMMENT_BREAK });
  assert.doesNotThrow(() => new vm.Script(code, { filename: 'persona-shim.js' }), 'shim must still parse (otherwise every page is unshimmed)');
  const ctx = makeRealm();
  vm.runInContext(code, ctx, { filename: 'persona-shim.js' });
  assert.equal(vm.runInContext('globalThis.__pwned', ctx), undefined, 'persona text must never execute');
  assert.equal(vm.runInContext('navigator.userAgent', ctx), COMMENT_BREAK);
  assert.equal(vm.runInContext('navigator.maxTouchPoints', ctx), 0, 'shim still applied');
});

// ---- MAIN-world shim exposes nothing privileged -------------------------------------------------------------

test('shim adds no named globals and no handle to the config or extension', () => {
  const ctx = makeRealm();
  const before = new Set(vm.runInContext('Object.getOwnPropertyNames(window)', ctx));
  vm.runInContext(SHIM.replaceAll('__PERSONA__', JSON.stringify({ ...PERSONA, geolocation: { latitude: 1, longitude: 2, accuracyMeters: 5 }, webgl: { vendor: 'v', renderer: 'r' } })), ctx, { filename: 'persona-shim.js' });
  const added = vm.runInContext('Object.getOwnPropertyNames(window)', ctx).filter((k) => !before.has(k));
  eq(added, [], 'no new named window properties');
  assert.equal(vm.runInContext('Object.getOwnPropertySymbols(window).length', ctx), 0, 'no marker symbol either (RT-07)');
  assert.equal(vm.runInContext('typeof browser', ctx), 'undefined');
  assert.equal(vm.runInContext('typeof chrome', ctx), 'undefined');
  assert.equal(vm.runInContext('typeof __PERSONA__', ctx), 'undefined');
  // chromeLike adds exactly NavigatorUAData, non-enumerable, like Chrome
  const ctx2 = makeRealm();
  const before2 = new Set(vm.runInContext('Object.getOwnPropertyNames(window)', ctx2));
  vm.runInContext(SHIM.replaceAll('__PERSONA__', JSON.stringify({ ...PERSONA, chromeLike: true, uaData: { brands: [{ brand: 'Chromium', version: '131' }], platform: 'Windows' } })), ctx2, { filename: 'persona-shim.js' });
  const added2 = vm.runInContext('Object.getOwnPropertyNames(window)', ctx2).filter((k) => !before2.has(k));
  eq(added2, ['NavigatorUAData']);
  assert.equal(vm.runInContext("Object.getOwnPropertyDescriptor(window, 'NavigatorUAData').enumerable", ctx2), false);
});

// ---- registration gates ------------------------------------------------------------------------------------------

test('persona shim is registered only for enabled === true (no truthy coercion)', async () => {
  const b = await boot();
  for (const enabled of ['true', 1, {}, [], 'yes']) {
    b.port().onMessage.fire({ type: 'persona', persona: { ...PERSONA, enabled } });
    await settle(30);
  }
  assert.equal(b.calls.register.length, 0);
  b.port().onMessage.fire({ type: 'persona', persona: 'not an object' });
  await settle(30);
  assert.equal(b.calls.register.length, 0);
});

test('rules: malformed entries are rejected, world is MAIN only on exact match, CSS is user-origin', async () => {
  const b = await boot();
  const base = { id: 'x', name: 'x', matches: ['*://example.com/*'], enabled: true, js: 'void 0' };
  b.port().onMessage.fire({
    type: 'rules',
    rules: [
      { ...base, id: 'ok-main', world: 'MAIN' },
      { ...base, id: 'ok-iso', world: 'main' },
      { ...base, id: 'ok-iso2', world: ['MAIN'] },
      { ...base, id: 'css-only', js: null, css: 'body{}' },
      { ...base, id: 'no-match', matches: 'string' },
      { ...base, id: 'empty-match', matches: [''] },
      { ...base, id: 'obj-match', matches: [{}] },
      { ...base, id: 'nothing', js: '   ', css: '' },
      { ...base, id: '' },
      'garbage', null, 42,
    ],
  });
  await settle(30);
  const ids = b.calls.register.map((r) => r.opts);
  assert.equal(ids.length, 4, 'four valid rules');
  assert.equal(ids[0].world, 'MAIN');
  assert.equal(ids[1].world, 'ISOLATED');
  assert.equal(ids[2].world, 'ISOLATED');
  assert.equal(ids[3].world, undefined, 'css-only rule registers no js world');
  assert.equal(ids[3].cssOrigin, 'user');
  assert.equal(ids[3].js, undefined);
  for (const o of ids) assert.equal(o.allFrames, false, 'rules never run in subframes');
});

// ---- host rewrite ------------------------------------------------------------------------------------------------

test('host rewrite listener is main_frame + blocking only and changes nothing but the hostname', async () => {
  const b = await boot();
  const add = b.api.webRequest.onBeforeRequest.addCalls;
  assert.equal(add.length, 1);
  eq(add[0], [{ urls: ['<all_urls>'], types: ['main_frame'] }, ['blocking']]);
  b.port().onMessage.fire({ type: 'state', persona: PERSONA, rules: [], hostRewrites: [{ from: 'm.example.com', to: 'www.example.com' }], settings: {} });
  await settle(30);
  const fire = (url, tabId = 1) => b.api.webRequest.onBeforeRequest.fire({ url, tabId, type: 'main_frame' })[0];
  eq(fire('https://user:pw@m.example.com:8443/a/b?c=1#frag'), { redirectUrl: 'https://user:pw@www.example.com:8443/a/b?c=1#frag' });
  eq(fire('http://m.example.com/', 2), { redirectUrl: 'http://www.example.com/' }, 'scheme preserved (no http->https upgrade and no downgrade)');
  eq(fire('ftp://m.example.com/', 3), {});
  eq(fire('moz-extension://abc/m.example.com', 4), {});
  eq(fire('https://m.example.com.evil.test/', 5), {}, 'exact host match only');
  eq(fire('https://xm.example.com/', 6), {});
  eq(fire('https://m.example.com/', -1), {}, 'requests without a tab are never redirected');
});

test('a host rewrite target cannot smuggle scheme, userinfo, port or path', async () => {
  const b = await boot();
  b.port().onMessage.fire({
    type: 'state', persona: PERSONA, rules: [], settings: {},
    hostRewrites: [
      { from: 'a.example', to: 'evil.example/phish' },
      { from: 'b.example', to: 'evil.example:9' },
      { from: 'c.example', to: 'x@evil.example' },
      { from: 'd.example', to: 'javascript:alert(1)' },
    ],
  });
  await settle(30);
  let tab = 10;
  for (const host of ['a', 'b', 'c', 'd']) {
    const r = b.api.webRequest.onBeforeRequest.fire({ url: `https://${host}.example:4443/p?q#f`, tabId: tab++, type: 'main_frame' })[0];
    if (r.redirectUrl === undefined) continue; // hostname setter rejected the value: fine
    const u = new URL(r.redirectUrl);
    assert.equal(u.protocol, 'https:');
    assert.equal(u.username, '');
    assert.equal(u.port, '4443');
    assert.equal(u.pathname, '/p');
    assert.equal(u.search, '?q');
    assert.equal(u.hash, '#f');
  }
});

// ---- Eruda --------------------------------------------------------------------------------------------------------

test('eruda-toggle needs a tab sender and an object message; the page world never gets a handle', async () => {
  const b = await boot();
  const fire = (msg, sender) => b.api.runtime.onMessage.fire(msg, sender)[0];
  assert.equal(fire({ type: 'eruda-toggle' }, {}), undefined);
  assert.equal(fire({ type: 'eruda-toggle' }, undefined), undefined);
  assert.equal(fire('eruda-toggle', { tab: { id: 1 } }), undefined);
  assert.equal(fire(null, { tab: { id: 1 } }), undefined);
  await settle(10);
  assert.equal(b.calls.execute.length, 0);
  await fire({ type: 'eruda-toggle' }, { tab: { id: 1 } });
  await settle(10);
  assert.equal(b.calls.execute.length, 1);
  assert.equal(b.calls.execute[0].world, 'MAIN', 'Eruda runs unprivileged in the page world');
  eq(b.calls.execute[0].files, ['vendor/eruda.min.js', 'eruda-init.js']);
});

// ---- native port names: only the app's two names exist ----------------------------------------------------------------

test('background connects only to the "hatch" native app and answers only hello/log', async () => {
  const b = await boot();
  assert.equal(b.calls.ports.length, 1);
  assert.equal(b.port().name, 'hatch');
  const types = new Set(b.port().posted.map((m) => m.type));
  for (const t of types) assert.ok(['hello', 'log'].includes(t), `unexpected message type ${t}`);
  // unknown message types from the app are logged, not executed
  b.port().onMessage.fire({ type: 'eval', code: 'x' });
  await settle(10);
  assert.ok(b.port().posted.some((m) => m.type === 'log' && m.message.includes('unknown message type')));
  assert.equal(b.calls.execute.length, 0);
});
