#!/usr/bin/env node
/*
 * Headless Firefox 158 runner for the Hatch detection harness (make headless).
 *
 *   node tool/headless/run.mjs [--personas all|id,id] [--controls] [--no-ext] [--out <dir>]
 *                              [--page <file.html>] [--base <persona id>] [--timeout <s>] [--verbose]
 *
 * For every persona id (<identityId>__<regionId>, or the built-in "none"):
 *   - prefs + clear + env come from personas/golden/<id>.prefs.json (written by the Kotlin
 *     PersonaToPrefs golden test; this runner never recomputes them and fails loudly when missing)
 *   - Firefox 158.0 from ~/opt/firefox-158.0 (tool/headless/setup.sh), geckodriver 0.37.1, headless,
 *     TZ from the golden env, window sized from the persona screen, memory-trim prefs
 *   - the built-in extension (app/src/main/assets/extension) is copied, given a stand-in for the
 *     Android app on the "hatch" native port (tool/headless/headless-native.js template below), and
 *     installed as a temporary add-on before the first navigation (--no-ext skips it)
 *   - navigates to <server>/index.html?persona=<id>&env=headless, waits for window.__hatchResult,
 *     writes <out>/<id>.json, prints the table
 * --controls adds the negative controls ctl-coarse / ctl-touch / ctl-noext on top of --base
 * (default win-firefox__us-east) and asserts that they FAIL exactly where expected.
 * --page <file> loads that page (served at /page/) with the persona set-up and prints its body text;
 * a body containing "DETECTED" makes the run fail (red-team pages).
 * Exit code: 1 when any persona run has a hard FAIL / library FAIL, any control assertion fails,
 * or a --page prints DETECTED; 2 on usage/setup errors.
 *
 * The whole run executes under `flock /tmp/hatch-heavy.lock` (re-exec) and, when a user systemd
 * instance is reachable, inside `systemd-run --user --scope -p MemoryMax=1200M`.
 */
import { spawnSync, execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
const HARNESS = join(REPO, 'harness');
const GOLDEN_DIR = join(REPO, 'personas', 'golden');
const EXT_DIR = join(REPO, 'app', 'src', 'main', 'assets', 'extension');
const LOCK = '/tmp/hatch-heavy.lock';
const FIREFOX_BIN = process.env.FIREFOX_BIN || join(homedir(), 'opt', 'firefox-158.0', 'firefox');
const GECKODRIVER = process.env.GECKODRIVER || join(homedir(), 'opt', 'bin', 'geckodriver');
const REQUIRED_VERSION_PREFIX = '158.';
const DEFAULT_BASE = 'win-firefox__us-east';
const NONE_ID = 'none';

const MEMORY_PREFS = {
  'dom.ipc.processCount': 1,
  'dom.ipc.processPrelaunch.enabled': false,
  'browser.cache.disk.enable': false,
  'media.gmp-provider.enabled': false,
  'browser.cache.memory.capacity': 8192,
  'browser.sessionstore.resume_from_crash': false,
  'browser.shell.checkDefaultBrowser': false,
  'browser.startup.page': 0,
  'browser.startup.homepage': 'about:blank',
  'browser.newtabpage.enabled': false,
  'app.update.auto': false,
  'app.update.enabled': false,
  'datareporting.policy.dataSubmissionEnabled': false,
  'datareporting.healthreport.uploadEnabled': false,
  'toolkit.telemetry.enabled': false,
  'toolkit.telemetry.unified': false,
  'extensions.update.enabled': false,
  'extensions.getAddons.cache.enabled': false,
  'browser.safebrowsing.malware.enabled': false,
  'browser.safebrowsing.phishing.enabled': false,
  'network.captive-portal-service.enabled': false,
  'network.connectivity-service.enabled': false,
  'dom.push.enabled': false,
  'privacy.resistFingerprinting': false,
  // Geolocation without the shim: auto-answer the prompt and fail fast instead of hanging.
  'geo.prompt.testing': true,
  'geo.prompt.testing.allow': true,
  'geo.provider.use_geoclue': false,
  'dom.w3c_pointer_events.dispatch_by_pointer_messages': false,
};

const CONTROLS = {
  'ctl-coarse': {
    desc: 'pointer prefs = coarse/no-hover; the CSS-level pointer/hover probes must fail (the shim cannot hide them)',
    prefs: { 'ui.primaryPointerCapabilities': 1, 'ui.allPointerCapabilities': 1 },
    ext: true,
    mustFail: ['css.pointerFine', 'css.hoverHover', 'css.anyPointerCoarse'],
    mayFail: ['css.', 'media.', 'contexts.', 'lib:'],
  },
  'ctl-touch': {
    desc: 'touch events re-enabled at engine level; document.createEvent("TouchEvent") must fail even though the shim hides the rest',
    // legacy_apis.enabled (createEvent("TouchEvent"), createTouch, ontouch*) defaults to true on Android and false on
    // desktop (StaticPrefList.yaml @IS_ANDROID@ at FIREFOX_158_0_RELEASE); set it so the host behaves like the phone.
    prefs: { 'dom.w3c_touch_events.enabled': 1, 'dom.w3c_touch_events.legacy_apis.enabled': true, 'dom.maxtouchpoints.testing.value': 5 },
    ext: true,
    mustFail: ['touch.createEvent'],
    mayFail: ['touch.', 'media.', 'css.', 'contexts.', 'lib:'],
  },
  'ctl-noext': {
    desc: 'no extension: everything the JS shim provides must fail (screen, geolocation), prefs still hold',
    prefs: {},
    ext: false,
    mustFail: ['screen.width', 'screen.height', 'screen.availHeight', 'geo.position'],
    mayFail: ['screen.', 'layout.', 'geo.', 'webgl.', 'misc.', 'contexts.', 'media.', 'intl.locale', 'ua.vendor', 'lib:', 'touch.orientation'],
    mustPass: ['ua.userAgent', 'ua.platform', 'ua.oscpu', 'touch.maxTouchPoints', 'touch.createEvent', 'css.pointerFine', 'css.hoverHover', 'intl.languages', 'intl.timeZone', 'server.acceptLanguage'],
  },
};

// ---- CLI ----------------------------------------------------------------------------------------------------

function usage(code) {
  console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0].replace(/^#![^\n]*\n\/\*\n?/, ''));
  process.exit(code);
}

function parseArgs(argv) {
  const a = { personas: null, controls: false, noExt: false, out: join(REPO, 'build', 'harness-reports'), page: null, base: DEFAULT_BASE, timeout: 90, verbose: false, keep: false, list: false };
  for (let i = 0; i < argv.length; i++) {
    const f = argv[i];
    const next = () => { if (i + 1 >= argv.length) { console.error(`${f} needs a value`); process.exit(2); } return argv[++i]; };
    if (f === '--personas') a.personas = next();
    else if (f === '--controls') a.controls = true;
    else if (f === '--no-ext') a.noExt = true;
    else if (f === '--out') a.out = resolve(next());
    else if (f === '--page') a.page = resolve(next());
    else if (f === '--base') a.base = next();
    else if (f === '--timeout') a.timeout = Number(next());
    else if (f === '--verbose' || f === '-v') a.verbose = true;
    else if (f === '--keep') a.keep = true;
    else if (f === '--list') a.list = true;
    else if (f === '-h' || f === '--help') usage(0);
    else { console.error(`unknown flag: ${f}`); usage(2); }
  }
  return a;
}

// ---- heavy-process lock (re-exec) ------------------------------------------------------------------------------

function userSystemdAvailable() {
  try {
    const r = spawnSync('systemd-run', ['--user', '--scope', '-q', '--', 'true'], { stdio: 'ignore', timeout: 5000 });
    return r.status === 0;
  } catch (e) { return false; }
}

function reexecUnderLock(argv) {
  const self = fileURLToPath(import.meta.url);
  const cmd = ['flock', LOCK, process.execPath, self, ...argv];
  if (!process.env.HATCH_NO_SYSTEMD && userSystemdAvailable()) {
    cmd.unshift('systemd-run', '--user', '--scope', '-q', '-p', 'MemoryMax=1200M', '--');
  }
  console.log(`[run] waiting for ${LOCK} (${cmd[0] === 'systemd-run' ? 'MemoryMax=1200M scope, ' : ''}one heavy process at a time)`);
  const r = spawnSync(cmd[0], cmd.slice(1), { stdio: 'inherit', env: { ...process.env, HATCH_HEAVY_LOCKED: '1' } });
  if (r.error) { console.error(`[run] could not re-exec under flock: ${r.error.message}`); process.exit(2); }
  process.exit(r.status === null ? 1 : r.status);
}

// ---- goldens / run plan -------------------------------------------------------------------------------------------

function listGoldens() {
  if (!existsSync(GOLDEN_DIR)) return [];
  return readdirSync(GOLDEN_DIR).filter((f) => f.endsWith('.prefs.json')).map((f) => f.replace(/\.prefs\.json$/, '')).sort();
}

function loadGolden(id) {
  if (id === NONE_ID) {
    return { prefs: {}, clear: [], env: {}, shim: { enabled: false, desktop: false, touch: true, screen: null, languages: [], locale: 'en-US' } };
  }
  const file = join(GOLDEN_DIR, `${id}.prefs.json`);
  if (!existsSync(file)) {
    const have = listGoldens();
    throw new Error(`golden prefs missing for persona "${id}": ${file}\n` +
      `  Goldens are written by the Kotlin :persona golden test (PersonaToPrefs.goldenJson) and are never recomputed here.\n` +
      `  Generate them with:\n    flock ${LOCK} ./gradlew --no-daemon --max-workers=1 --console=plain :persona:test -Dhatch.regenGoldens=true\n` +
      `  Available: ${have.length ? have.join(', ') : '(none)'}`);
  }
  const g = JSON.parse(readFileSync(file, 'utf8'));
  for (const k of ['prefs', 'clear', 'env', 'shim']) {
    if (!(k in g)) throw new Error(`${file}: missing "${k}" (expected {"prefs":{},"clear":[],"env":{},"shim":{}})`);
  }
  return g;
}

function plan(args) {
  const runs = [];
  let ids;
  if (args.personas === null || args.personas === 'all') {
    ids = listGoldens();
    if (ids.length === 0 && args.personas === 'all') {
      throw new Error(`no golden prefs in ${GOLDEN_DIR}; run the :persona golden test first (see --help) or pass --personas none`);
    }
    if (ids.length === 0) ids = [NONE_ID];
  } else {
    ids = args.personas.split(',').map((s) => s.trim()).filter(Boolean);
  }
  for (const id of ids) {
    runs.push({ runId: id, personaId: id, golden: loadGolden(id), ext: !args.noExt, control: null });
  }
  if (args.controls) {
    const base = loadGolden(args.base);
    for (const [name, c] of Object.entries(CONTROLS)) {
      const golden = { ...base, prefs: { ...base.prefs, ...c.prefs } };
      runs.push({ runId: name, personaId: args.base, golden, ext: c.ext && !args.noExt, control: { name, ...c } });
    }
  }
  return runs;
}

// ---- extension copy with the native-port stand-in -----------------------------------------------------------

const NATIVE_STUB = `/* Hatch headless harness: stands in for the Android app on the "hatch" native messaging port.
 * Prepended to background.js by tool/headless/run.mjs; delivers the same {type:"state"} message the app sends
 * on every port connect, so the extension registers the persona shim exactly as on the phone. */
(function () {
  'use strict';
  const STATE = __STATE__;
  function makePort(name) {
    const onMsg = new Set();
    const onDisc = new Set();
    const port = {
      name,
      error: null,
      onMessage: { addListener: (f) => onMsg.add(f), removeListener: (f) => onMsg.delete(f), hasListener: (f) => onMsg.has(f) },
      onDisconnect: { addListener: (f) => onDisc.add(f), removeListener: (f) => onDisc.delete(f), hasListener: (f) => onDisc.has(f) },
      postMessage(msg) { console.log('[hatch-headless] ext -> app: ' + JSON.stringify(msg)); },
      disconnect() { for (const f of onDisc) f(port); },
    };
    setTimeout(() => { for (const f of onMsg) f(STATE, port); }, 0);
    return port;
  }
  const rt = browser.runtime;
  const orig = rt.connectNative.bind(rt);
  const fake = function connectNative(name) { return name === 'hatch' ? makePort(name) : orig(name); };
  let overrode = false;
  try { Object.defineProperty(rt, 'connectNative', { value: fake, configurable: true, writable: true }); overrode = rt.connectNative === fake; } catch (e) { /* fall through */ }
  if (!overrode) { try { rt.connectNative = fake; overrode = rt.connectNative === fake; } catch (e) { /* fall through */ } }
  if (!overrode) console.warn('[hatch-headless] could not override browser.runtime.connectNative; relying on the storage.local seed');
  // Belt and braces: background.js restores storage.local "state" before it opens the port.
  browser.storage.local.set({ state: { persona: STATE.persona, rules: STATE.rules, hostRewrites: STATE.hostRewrites, settings: STATE.settings } }).catch(() => {});
  globalThis.__hatchHeadlessNative = { overrode, state: STATE };
})();
`;

function prepareExtension(run, workDir) {
  if (!existsSync(join(EXT_DIR, 'manifest.json'))) return null;
  const dir = join(workDir, `ext-${run.runId}`);
  cpSync(EXT_DIR, dir, { recursive: true, filter: (src) => !/[\\/]test([\\/]|$)/.test(src) });
  const manifestPath = join(dir, 'manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  if (!manifest.background || !Array.isArray(manifest.background.scripts)) {
    throw new Error(`${manifestPath}: expected background.scripts (MV2) — the headless native stand-in cannot be prepended`);
  }
  const state = { type: 'state', persona: run.golden.shim, rules: [], hostRewrites: [], settings: { headlessHarness: true } };
  writeFileSync(join(dir, 'headless-native.js'), NATIVE_STUB.replace('__STATE__', () => JSON.stringify(state)));
  manifest.background.scripts = ['headless-native.js', ...manifest.background.scripts.filter((s) => s !== 'headless-native.js')];
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  return dir;
}

// ---- selenium ---------------------------------------------------------------------------------------------------------

async function loadSelenium() {
  const base = pathToFileURL(join(HARNESS, 'node_modules', 'selenium-webdriver') + '/').href;
  try {
    const sw = await import(new URL('index.js', base).href);
    const firefox = await import(new URL('firefox.js', base).href);
    return { Builder: sw.Builder, firefox: firefox.default || firefox };
  } catch (e) {
    throw new Error(`selenium-webdriver not installed in harness/node_modules (${e.message}); run tool/headless/setup.sh`);
  }
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

function withDeadline(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${label}: timed out after ${ms} ms`)), ms); });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function killPid(pid, label, log) {
  if (!pid) return;
  try { process.kill(pid, 'SIGKILL'); log(`killed ${label} pid ${pid}`); } catch (e) { /* already gone */ }
}

/**
 * An executeAsyncScript body that resolves true once the shim is active in a fresh document, derived from a
 * shim-only value of the golden config (screen.width, else hardwareConcurrency, else the geolocation
 * permission the shim grants). Null when the config shims nothing observable (then the runner just waits).
 */
function readinessProbe(shim) {
  if (!shim || !shim.enabled) return null;
  if (shim.screen && typeof shim.screen.width === 'number') {
    return `var cb = arguments[arguments.length - 1]; cb(screen.width === ${shim.screen.width} && screen.height === ${shim.screen.height});`;
  }
  if (typeof shim.hardwareConcurrency === 'number') {
    return `var cb = arguments[arguments.length - 1]; cb(navigator.hardwareConcurrency === ${shim.hardwareConcurrency});`;
  }
  if (shim.geolocation && typeof shim.geolocation.latitude === 'number') {
    return 'var cb = arguments[arguments.length - 1]; try { navigator.permissions.query({ name: "geolocation" }).then(function (s) { cb(s.state === "granted"); }, function () { cb(false); }); } catch (e) { cb(false); }';
  }
  return null;
}

function personaWindow(golden) {
  const s = golden.shim && golden.shim.screen;
  const width = s && s.width ? s.width : 1366;
  const height = s && s.height ? s.height : 768;
  return { width, height };
}

async function runOne(run, ctx) {
  const { Builder, firefox } = ctx.selenium;
  const log = (m) => console.log(`[${run.runId}] ${m}`);
  const { width, height } = personaWindow(run.golden);
  const env = { ...process.env, MOZ_HEADLESS: '1', MOZ_CRASHREPORTER_DISABLE: '1', MOZ_DISABLE_AUTO_SAFE_MODE: '1' };
  delete env.TZ;
  if (run.golden.env && run.golden.env.TZ) env.TZ = run.golden.env.TZ;

  const options = new firefox.Options();
  options.setBinary(FIREFOX_BIN);
  options.addArguments('-headless', `-width=${width}`, `-height=${height}`);
  const prefs = { ...MEMORY_PREFS, 'geo.provider.network.url': `${ctx.server.pageOrigin}/no-geo-provider`, ...run.golden.prefs };
  for (const [k, v] of Object.entries(prefs)) options.setPreference(k, v);
  // "clear" entries mean engine defaults: a fresh profile already has them.

  const service = new firefox.ServiceBuilder(GECKODRIVER).setEnvironment(env);
  if (ctx.args.verbose) service.setStdio('inherit'); else service.setStdio('ignore');

  const started = Date.now();
  let driver = null;
  let firefoxPid = null;
  const outcome = { runId: run.runId, personaId: run.personaId, control: run.control ? run.control.name : null, ext: run.ext, tz: env.TZ || null, window: { width, height } };
  const hardDeadline = (ctx.args.timeout + 90) * 1000;
  const watchdog = setTimeout(() => {
    log(`watchdog: run exceeded ${hardDeadline} ms; killing Firefox`);
    killPid(firefoxPid, 'firefox', log);
  }, hardDeadline);

  try {
    log(`starting Firefox (TZ=${env.TZ || '(unset)'} window=${width}x${height} prefs=${Object.keys(run.golden.prefs).length} ext=${run.ext})`);
    driver = await withDeadline(new Builder().forBrowser('firefox').setFirefoxOptions(options).setFirefoxService(service).build(), 90000, 'browser start');
    const caps = await driver.getCapabilities();
    const version = String(caps.get('browserVersion') || '');
    firefoxPid = caps.get('moz:processID') || null;
    outcome.browserVersion = version;
    outcome.firefoxPid = firefoxPid;
    if (!version.startsWith(REQUIRED_VERSION_PREFIX)) {
      throw new Error(`browserVersion is "${version}", expected ${REQUIRED_VERSION_PREFIX}* (FIREFOX_BIN=${FIREFOX_BIN}); run tool/headless/setup.sh`);
    }
    log(`Firefox ${version} pid ${firefoxPid}`);
    await driver.manage().setTimeouts({ pageLoad: 60000, script: 30000 });

    if (run.ext) {
      const extDir = prepareExtension(run, ctx.workDir);
      if (!extDir) {
        log(`extension not found at ${EXT_DIR}; running without it (--no-ext mode)`);
        outcome.ext = false;
      } else {
        const addonId = await withDeadline(driver.installAddon(extDir, true), 30000, 'installAddon');
        outcome.addonId = addonId;
        log(`temporary add-on installed: ${addonId}`);
        const probe = readinessProbe(run.golden.shim);
        if (probe) {
          // The background page registers the shim asynchronously; the shim leaves no marker (RT-07), so wait
          // until a fresh document reports a shim-only value from the golden config.
          let ready = false;
          const until = Date.now() + 15000;
          while (Date.now() < until) {
            await driver.get(`${ctx.server.pageOrigin}/frame.html?tag=ready`);
            ready = await driver.executeAsyncScript(probe) === true;
            if (ready) break;
            await sleep(250);
          }
          outcome.shimReady = ready;
          if (!ready) log('warning: the persona shim never became visible; the extension did not register it');
          else log('persona shim active');
        } else {
          await sleep(run.golden.shim && run.golden.shim.enabled ? 1500 : 500);
        }
      }
    }
    try { await driver.manage().window().setRect({ x: 0, y: 0, width, height }); } catch (e) { log(`setRect: ${e.message}`); }

    if (ctx.args.page) {
      const url = `${ctx.server.pageOrigin}/page/${encodeURIComponent(basename(ctx.args.page))}?persona=${encodeURIComponent(run.personaId)}&env=headless`;
      log(`loading page ${url}`);
      await driver.get(url);
      const until = Date.now() + ctx.args.timeout * 1000;
      let done = false;
      while (Date.now() < until) {
        done = await driver.executeScript('return document.readyState === "complete" && (window.__hatchPageDone === undefined || window.__hatchPageDone === true)');
        if (done) break;
        await sleep(250);
      }
      await sleep(1500); // let async detectors settle
      const text = await driver.executeScript('return document.body ? document.body.innerText : ""');
      const file = join(ctx.args.out, `page-${basename(ctx.args.page).replace(/\.html?$/, '')}-${run.runId}.txt`);
      writeFileSync(file, `${text}\n`);
      outcome.pageText = text;
      outcome.detected = /\bDETECTED\b/.test(text);
      outcome.ok = !outcome.detected;
      console.log(`----- ${basename(ctx.args.page)} [${run.runId}] -----\n${text}\n----- end (${outcome.detected ? 'DETECTED' : 'clean'}) -----`);
      return outcome;
    }

    const url = `${ctx.server.pageOrigin}/index.html?persona=${encodeURIComponent(run.personaId)}&env=headless`;
    log(`loading ${url}`);
    await driver.get(url);
    const until = Date.now() + ctx.args.timeout * 1000;
    let result = null;
    while (Date.now() < until) {
      result = await driver.executeScript('return window.__hatchResult ? JSON.stringify(window.__hatchResult) : null');
      if (result) break;
      await sleep(500);
    }
    if (!result) {
      const status = await driver.executeScript('return (document.getElementById("status") || {}).textContent || document.readyState').catch(() => '?');
      throw new Error(`window.__hatchResult not set within ${ctx.args.timeout} s (page status: ${status})`);
    }
    const parsed = JSON.parse(result);
    parsed.runner = { runId: run.runId, control: outcome.control, ext: outcome.ext, tz: outcome.tz, browserVersion: version, window: outcome.window, prefs: run.golden.prefs, clear: run.golden.clear, durationMs: Date.now() - started };
    const file = join(ctx.args.out, `${run.runId}.json`);
    writeFileSync(file, `${JSON.stringify(parsed, null, 2)}\n`);
    outcome.file = file;
    outcome.summary = parsed.summary;
    console.log(ctx.formatTable(parsed));
    if (run.control) {
      outcome.controlCheck = assertControl(parsed, run.control);
      outcome.ok = outcome.controlCheck.ok;
      log(`control ${run.control.name}: ${outcome.ok ? 'behaves as expected' : 'ASSERTION FAILED'} ${JSON.stringify(outcome.controlCheck)}`);
    } else {
      outcome.ok = parsed.summary.overall === 'PASS';
    }
    log(`overall=${parsed.summary.overall} -> ${file} (${Date.now() - started} ms)`);
    return outcome;
  } catch (e) {
    outcome.ok = false;
    outcome.error = e && e.message ? e.message : String(e);
    log(`ERROR: ${outcome.error}`);
    return outcome;
  } finally {
    clearTimeout(watchdog);
    if (driver) {
      try { await withDeadline(driver.quit(), 20000, 'driver.quit'); } catch (e) { log(`quit: ${e.message}`); }
    }
    await sleep(200);
    killPid(firefoxPid, 'firefox (leftover)', () => {});
  }
}

function assertControl(result, control) {
  const failed = new Set(result.signals.filter((s) => s.status === 'FAIL').map((s) => s.id));
  for (const l of result.libraries) if (l.status === 'FAIL') failed.add(`lib:${l.name}`);
  const allowed = (id) => control.mustFail.includes(id) || (control.mayFail || []).some((p) => id.startsWith(p));
  const missing = control.mustFail.filter((id) => !failed.has(id));
  const unexpected = [...failed].filter((id) => !allowed(id));
  const mustPassBroken = (control.mustPass || []).filter((id) => { const s = result.signals.find((x) => x.id === id); return !s || s.status !== 'PASS'; });
  return {
    ok: missing.length === 0 && unexpected.length === 0 && mustPassBroken.length === 0 && result.summary.overall === 'FAIL',
    overall: result.summary.overall,
    failed: [...failed],
    missing,
    unexpected,
    mustPassBroken,
  };
}

// ---- main ------------------------------------------------------------------------------------------------------------

async function main() {
  const argv = process.argv.slice(2);
  const args = parseArgs(argv);
  if (args.list) {
    console.log(`goldens in ${GOLDEN_DIR}:\n  ${listGoldens().join('\n  ') || '(none)'}\ncontrols: ${Object.keys(CONTROLS).join(', ')}`);
    return 0;
  }
  if (!process.env.HATCH_HEAVY_LOCKED) reexecUnderLock(argv);

  if (!existsSync(FIREFOX_BIN)) throw new Error(`Firefox binary missing: ${FIREFOX_BIN} (run tool/headless/setup.sh or set FIREFOX_BIN)`);
  if (!existsSync(GECKODRIVER)) throw new Error(`geckodriver missing: ${GECKODRIVER} (run tool/headless/setup.sh or set GECKODRIVER)`);
  const ffVersion = execFileSync(FIREFOX_BIN, ['--version'], { encoding: 'utf8', env: { ...process.env, MOZ_HEADLESS: '1' } }).trim();
  if (!/Firefox 158\./.test(ffVersion)) throw new Error(`${FIREFOX_BIN} reports "${ffVersion}", expected Firefox 158.x`);
  if (args.page && !existsSync(args.page)) throw new Error(`--page file not found: ${args.page}`);

  const runs = plan(args);
  const selenium = await loadSelenium();
  const { formatTable } = await import(pathToFileURL(join(HARNESS, 'evaluate.js')).href);
  const { startServer } = await import(pathToFileURL(join(HERE, 'server.mjs')).href);
  mkdirSync(args.out, { recursive: true });
  const workDir = mkdtempSync(join(tmpdir(), 'hatch-headless-'));
  const server = await startServer({ root: HARNESS, outDir: args.out, pageDir: args.page ? dirname(args.page) : null, log: (m) => { if (args.verbose) console.log(`[server] ${m}`); } });
  console.log(`[run] ${ffVersion}; harness at ${server.pageOrigin} (cross-origin ${server.crossOrigin}); reports -> ${args.out}`);
  console.log(`[run] plan: ${runs.map((r) => `${r.runId}${r.ext ? '' : ' (no-ext)'}`).join(', ')}`);

  const outcomes = [];
  try {
    for (const run of runs) {
      outcomes.push(await runOne(run, { args, selenium, server, workDir, formatTable }));
    }
  } finally {
    await server.close();
    if (!args.keep) rmSync(workDir, { recursive: true, force: true }); else console.log(`[run] kept ${workDir}`);
  }

  const summary = { finishedAt: new Date().toISOString(), firefox: ffVersion, runs: outcomes.map((o) => ({ ...o, pageText: undefined })) };
  writeFileSync(join(args.out, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`);
  console.log('\n[run] ===== summary =====');
  for (const o of outcomes) {
    const line = o.error ? `ERROR ${o.error}` : o.control ? `control ${o.controlCheck.ok ? 'OK' : 'ASSERTION FAILED'} (overall=${o.summary.overall}, missing=[${o.controlCheck.missing}], unexpected=[${o.controlCheck.unexpected}], mustPassBroken=[${o.controlCheck.mustPassBroken}])`
      : o.summary ? `overall=${o.summary.overall} hardFail=[${o.summary.hardFail}] libraryFail=[${o.summary.libraryFail}]` : (o.detected ? 'DETECTED' : 'clean');
    console.log(`[run] ${o.ok ? 'ok  ' : 'FAIL'} ${o.runId.padEnd(26)} ${line}`);
  }
  const failed = outcomes.filter((o) => !o.ok);
  console.log(`[run] ${outcomes.length - failed.length}/${outcomes.length} runs ok; summary -> ${join(args.out, 'summary.json')}`);
  return failed.length ? 1 : 0;
}

main().then((code) => process.exit(code), (e) => {
  console.error(`[run] ${e && e.stack ? e.stack : e}`);
  process.exit(2);
});
