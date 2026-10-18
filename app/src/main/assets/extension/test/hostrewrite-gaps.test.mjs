// Web-compat review (M0, HATCH-WC-01 fixed): host-rewrite coverage of the table the APP sends to
// background.js. Components.kt pushes `UrlRewriteRules.extensionHostRewrites`: the 12 exact hosts plus the
// Wikimedia `<lang>.m.<project>.org` rule and the generic `m.` / `mobile.` / `touch.` strip as JS regex rules,
// so a followed link to en.m.wikipedia.org is rewritten like a typed URL (`UrlRewriteRules.desktopHost`).
// APP_HOST_TABLE below is a copy of that Kotlin list; `UrlRewriteRulesTest` locks the regex strings.
// Run: node --test app/src/main/assets/extension/test/*.test.mjs   (NOT shipped in the APK)
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const ROOT = new URL('../', import.meta.url);
const read = (p) => readFileSync(new URL(p, ROOT), 'utf8');
const BACKGROUND = read('background.js');
const MANIFEST = JSON.parse(read('manifest.json'));

// Copy of UrlRewriteRules.extensionHostRewrites (persona/.../UrlRewriteRules.kt): defaultHostRewrites +
// WIKIMEDIA_HOST_RULE + GENERIC_MOBILE_HOST_RULE.
const EXACT_HOST_TABLE = [
  ['m.youtube.com', 'www.youtube.com'], ['mobile.twitter.com', 'x.com'], ['m.twitter.com', 'x.com'],
  ['mobile.x.com', 'x.com'], ['m.facebook.com', 'www.facebook.com'], ['touch.facebook.com', 'www.facebook.com'],
  ['mbasic.facebook.com', 'www.facebook.com'], ['m.reddit.com', 'www.reddit.com'], ['m.imdb.com', 'www.imdb.com'],
  ['m.soundcloud.com', 'soundcloud.com'], ['m.twitch.tv', 'www.twitch.tv'], ['m.aliexpress.com', 'www.aliexpress.com'],
].map(([from, to]) => ({ from, to }));
const APP_HOST_TABLE = [
  ...EXACT_HOST_TABLE,
  { from: '/^([a-z-]+)\\.m\\.(wikipedia|wiktionary|wikimedia|wikibooks|wikiquote|wikinews|wikisource|wikiversity|wikivoyage|mediawiki|wikidata)\\.org$/', to: '$1.$2.org' },
  { from: '/^(?:m|mobile|touch)\\.([^.]+\\.[^.]+.*)$/', to: '$1' },
];

const PERSONA = { enabled: true, desktop: true, touch: false };
const tick = () => new Promise((r) => setTimeout(r, 0));
async function settle(n = 20) { for (let i = 0; i < n; i++) await tick(); }
function listenerSet() { const fns = []; return { addListener: (f) => fns.push(f), fire: (...a) => fns.map((f) => f(...a)) }; }

async function bootWith(hostRewrites) {
  const ports = [];
  const api = {
    runtime: {
      lastError: null, getManifest: () => MANIFEST, getURL: (p) => `moz-extension://abc/${p}`, onMessage: listenerSet(),
      connectNative() { const port = { onMessage: listenerSet(), onDisconnect: listenerSet(), postMessage() {} }; ports.push(port); return port; },
    },
    storage: { local: { get: async () => ({}), set: async () => {} } },
    contentScripts: { register: async () => ({ unregister: async () => {} }) },
    scripting: { executeScript: async () => [] },
    webRequest: { onBeforeRequest: listenerSet() },
    webNavigation: { onCommitted: listenerSet() },
    tabs: { onRemoved: listenerSet() },
  };
  const ctx = vm.createContext({
    browser: api, console: { log() {}, warn() {}, error() {} },
    fetch: async () => ({ ok: true, status: 200, text: async () => '(function(){ /* stub */ })(); /*__USERSCRIPT__*/' }),
    setTimeout: () => 0, clearTimeout: () => {},
    URL, Date, JSON, Map, Set, Promise, Array, Object, String, Number, RegExp, Error, TypeError,
  });
  vm.runInContext(BACKGROUND, ctx, { filename: 'background.js' });
  await settle();
  ports[0].onMessage.fire({ type: 'state', persona: PERSONA, rules: [], hostRewrites, settings: {} });
  await settle(40);
  let tab = 100;
  return (url) => JSON.parse(JSON.stringify(api.webRequest.onBeforeRequest.fire({ url, tabId: tab++, type: 'main_frame' })[0]));
}

test('the exact hosts of the app table rewrite as before', async () => {
  const fire = await bootWith(APP_HOST_TABLE);
  for (const { from, to } of EXACT_HOST_TABLE) {
    assert.deepEqual(fire(`https://${from}/p?q=1`), { redirectUrl: `https://${to}/p?q=1` }, from);
  }
});

const GAPS = [
  ['https://en.m.wikipedia.org/wiki/Android', 'https://en.wikipedia.org/wiki/Android'],
  ['https://de.m.wikipedia.org/wiki/Berlin', 'https://de.wikipedia.org/wiki/Berlin'],
  ['https://en.m.wiktionary.org/wiki/hatch', 'https://en.wiktionary.org/wiki/hatch'],
  ['https://m.wikihow.com/Main-Page', 'https://wikihow.com/Main-Page'],
  ['https://mobile.example.org/a/b', 'https://example.org/a/b'],
  ['https://touch.dailymotion.com/video/x1', 'https://dailymotion.com/video/x1'],
];

for (const [from, to] of GAPS) {
  test(`${new URL(from).hostname} is rewritten by the extension (Wikimedia + generic m./mobile./touch. strip)`, async () => {
    const fire = await bootWith(APP_HOST_TABLE);
    assert.deepEqual(fire(from), { redirectUrl: to });
  });
}

test('the regex rules never over-match and desktop hosts are stable', async () => {
  const fire = await bootWith(APP_HOST_TABLE);
  assert.deepEqual(fire('https://m.me/x'), {}, 'two labels: never stripped');
  assert.deepEqual(fire('https://www.mobile.de/'), {}, 'mobile. only as the first label');
  assert.deepEqual(fire('https://mobile.de/'), {}, 'nothing left after the strip');
  assert.deepEqual(fire('https://en.wikipedia.org/'), {}, 'already desktop');
  assert.deepEqual(fire('https://ZH-YUE.M.WIKIPEDIA.ORG/wiki/X'), { redirectUrl: 'https://zh-yue.wikipedia.org/wiki/X' });
  assert.deepEqual(fire('https://m.wikidata.org/'), { redirectUrl: 'https://wikidata.org/' });
  for (const { to } of EXACT_HOST_TABLE) assert.deepEqual(fire(`https://${to}/`), {}, `${to} is a stable target`);
});
