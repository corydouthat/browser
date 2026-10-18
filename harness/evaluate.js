/*
 * Hatch detection harness — pure compare logic (no DOM access).
 *
 * evaluate(observed, expected, env) -> { signals, libraries, summary }
 *   observed : what the probes measured (see probes/*.js and docs/detection-harness.md)
 *   expected : harness/personas/<identity>__<region>.json (null = no expectations, everything is INFO)
 *   env      : "headless" | "device"
 *
 * Signal statuses: PASS, FAIL, SKIPPED (device-scoped in headless, or the probe had nothing to
 * measure), INFO (no expectation for this value, reported for the record).
 * tier: "hard" (any FAIL fails the run) | "soft" (reported, does not fail the run).
 * scope: "both" (evaluated everywhere) | "device" (only meaningful on the phone; SKIPPED in headless).
 *
 * summary.overall === "PASS" iff no hard FAIL, no library FAIL and, in env=device, no device-scoped
 * SKIPPED (result JSON schema v1).
 * touchHost: the expectation derives from `expected.touch` (a real touch screen); when a touch identity is
 * evaluated off-device (headless desktop host) a FAIL on such a signal becomes SKIPPED.
 * tierFor(expected, env): a signal may pick its tier per run (e.g. intl.locale is soft in the en-US-only
 * headless build and hard on the phone).
 */

export const SCHEMA_VERSION = 1;
export const HARNESS_VERSION = '1.1.0';

const PASS = 'PASS';
const FAIL = 'FAIL';
const SKIPPED = 'SKIPPED';
const INFO = 'INFO';

/** Keys a frame / worker snapshot must agree on with the top document. */
export const CONTEXT_KEYS = ['userAgent', 'platform', 'languages', 'hardwareConcurrency', 'timeZone', 'maxTouchPoints', 'appVersion', 'oscpu'];
/** Frames that carry the full window surface must also agree on the shim-only values. */
export const FRAME_KEYS = [...CONTEXT_KEYS, 'screenWidth', 'screenHeight', 'dpr', 'ontouchstart', 'touchEvent', 'pointerFine'];
export const WORKER_KEYS = ['userAgent', 'platform', 'languages', 'hardwareConcurrency', 'timeZone'];
/** Shared-worker keys the engine (prefs / env) controls; hardwareConcurrency is the documented M2 gap. */
export const SHARED_WORKER_KEYS = ['userAgent', 'platform', 'languages', 'timeZone'];

/** Breakpoints the CSS probes cover (Bootstrap lg, Tailwind lg/xl, MUI lg). */
export const BREAKPOINTS = [992, 1024, 1200, 1280];

const CLIENT_HINT_HEADERS = ['sec-ch-ua', 'sec-ch-ua-mobile', 'sec-ch-ua-platform', 'sec-ch-ua-platform-version',
  'sec-ch-ua-arch', 'sec-ch-ua-bitness', 'sec-ch-ua-model', 'sec-ch-ua-full-version', 'sec-ch-ua-full-version-list',
  'sec-ch-ua-wow64', 'sec-ch-prefers-color-scheme', 'sec-ch-prefers-reduced-motion'];
const VIEWPORT_HINT_HEADERS = ['viewport-width', 'sec-ch-viewport-width', 'dpr', 'sec-ch-dpr', 'device-memory',
  'sec-ch-device-memory', 'width', 'sec-ch-width', 'save-data'];

// ---- small helpers ------------------------------------------------------------------------------

function get(obj, path) {
  let cur = obj;
  for (const key of path.split('.')) {
    if (cur === null || cur === undefined) return undefined;
    cur = cur[key];
  }
  return cur;
}

function isNil(v) { return v === null || v === undefined; }

function sameList(a, b) {
  if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function approx(a, b, tol) {
  return typeof a === 'number' && typeof b === 'number' && Number.isFinite(a) && Number.isFinite(b) && Math.abs(a - b) <= tol;
}

function r(status, expected, actual, note) {
  const out = { status, expected: expected === undefined ? null : expected, actual: actual === undefined ? null : actual };
  if (note) out.note = note;
  return out;
}

/** expected === null -> INFO; otherwise strict equality. */
function eq(expected, actual, note) {
  if (isNil(expected)) return r(INFO, null, actual, note);
  if (actual === undefined) return r(FAIL, expected, null, note || 'not measured');
  return r(actual === expected ? PASS : FAIL, expected, actual, note);
}

function eqBool(expected, actual, note) {
  if (isNil(expected)) return r(INFO, null, actual, note);
  if (typeof actual !== 'boolean') return r(FAIL, expected, actual === undefined ? null : actual, note || 'not measured');
  return r(actual === expected ? PASS : FAIL, expected, actual, note);
}

function eqList(expected, actual, note) {
  if (isNil(expected)) return r(INFO, null, actual, note);
  if (!Array.isArray(actual)) return r(FAIL, expected, actual === undefined ? null : actual, note || 'not measured');
  return r(sameList(expected, actual) ? PASS : FAIL, expected, actual, note);
}

function gte(min, actual, note) {
  if (isNil(min)) return r(INFO, null, actual, note);
  if (typeof actual !== 'number') return r(FAIL, `>= ${min}`, actual === undefined ? null : actual, note || 'not measured');
  return r(actual >= min ? PASS : FAIL, `>= ${min}`, actual, note);
}

function snapshotDiff(top, other, keys) {
  const diffs = [];
  for (const k of keys) {
    const a = top ? top[k] : undefined;
    const b = other ? other[k] : undefined;
    const same = Array.isArray(a) || Array.isArray(b) ? sameList(a, b) : a === b;
    if (!same) diffs.push({ key: k, top: a === undefined ? null : a, other: b === undefined ? null : b });
  }
  return diffs;
}

function contextSignal(observed, name, keys) {
  const top = get(observed, 'contexts.top');
  const ctx = get(observed, `contexts.${name}`);
  if (!top) return r(SKIPPED, 'agrees with top', null, 'no top snapshot');
  if (!ctx) return r(SKIPPED, 'agrees with top', null, 'context not available');
  if (ctx.skipped) return r(SKIPPED, 'agrees with top', null, ctx.reason || 'skipped');
  if (ctx.error) return r(FAIL, 'agrees with top', null, `context error: ${ctx.error}`);
  const diffs = snapshotDiff(top, ctx.snapshot, keys);
  return r(diffs.length === 0 ? PASS : FAIL, 'agrees with top', diffs.length === 0 ? 'agrees' : diffs);
}

function workerSignal(observed, key, tierNote, name = 'worker') {
  const top = get(observed, 'contexts.top');
  const w = get(observed, `contexts.${name}`);
  if (!top || !w) return r(SKIPPED, 'agrees with top', null, `no ${name} snapshot`);
  if (w.skipped) return r(SKIPPED, 'agrees with top', null, w.reason || 'skipped');
  if (w.error) return r(FAIL, 'agrees with top', null, `${name} error: ${w.error}`);
  const diffs = snapshotDiff(top, w.snapshot, [key]);
  const actual = w.snapshot ? w.snapshot[key] : null;
  return r(diffs.length === 0 ? PASS : FAIL, top[key] === undefined ? null : top[key], actual === undefined ? null : actual, tierNote);
}

function crossRealmSignal(observed) {
  const c = get(observed, 'contexts.crossRealm');
  if (!c) return r(SKIPPED, 'native text in both directions', null, 'not measured');
  if (c.error) return r(FAIL, 'native text in both directions', null, `probe error: ${c.error}`);
  if (!Array.isArray(c.checks) || c.checks.length === 0 || !c.referenceText) return r(SKIPPED, 'native text in both directions', null, 'no reference getter');
  const bad = c.checks.filter((x) => x.native !== true).map((x) => ({ name: x.name, text: x.text }));
  return r(bad.length === 0 ? PASS : FAIL, 'native text in both directions', bad.length === 0 ? `${c.checks.length} native` : bad);
}

function deviceFeatureSignal(observed, expected, key, label) {
  const d = get(observed, 'media.device');
  if (!d) return r(SKIPPED, label, null, 'not measured');
  if (d.error) return r(FAIL, label, null, `probe error: ${d.error}`);
  const v = d[key];
  if (!expected || !expected.screen) return r(INFO, null, v);
  if (typeof v !== 'boolean') return r(FAIL, label, v === undefined ? null : v, 'not measured');
  return r(v ? PASS : FAIL, label, v, v ? undefined : `JS device-* / resolution disagree with screen.* (screen ${d.screenWidth}x${d.screenHeight} dpr ${d.dpr}, CSS device-width ${d.jsDeviceWidth})`);
}

function headerPresence(headers, names) {
  if (!headers || typeof headers !== 'object') return null;
  const found = {};
  for (const n of names) {
    for (const k of Object.keys(headers)) {
      if (k.toLowerCase() === n) found[n] = headers[k];
    }
  }
  return found;
}

function serverHeaders(observed, which) {
  const s = observed.server;
  if (!s || s.error) return { skipped: s && s.error ? `echo failed: ${s.error}` : 'no server probe' };
  const resp = s[which];
  if (!resp || !resp.headers) return { skipped: `no ${which} echo response` };
  return { headers: resp.headers };
}

function noHeaders(observed, which, names, expectedLabel) {
  const h = serverHeaders(observed, which);
  if (h.skipped) return r(SKIPPED, expectedLabel, null, h.skipped);
  const found = headerPresence(h.headers, names);
  const keys = Object.keys(found);
  return r(keys.length === 0 ? PASS : FAIL, expectedLabel, keys.length === 0 ? 'none' : found);
}

function expectedMobile(expected) {
  if (!expected) return null;
  if (expected.librariesMobile === undefined) return expected.desktop ? false : null;
  return expected.librariesMobile;
}

// ---- the signal table ----------------------------------------------------------------------------------
// Each entry: id, group, tier, scope, desc, check(observed, expected, env) -> {status, expected, actual, note}
// `expected` may be null (no expectations: persona "none" or missing file).

const X = (expected, key) => (expected ? expected[key] : null);

export const SIGNALS = [
  // ---- ua ----------------------------------------------------------------------------------
  { id: 'ua.userAgent', group: 'ua', tier: 'hard', scope: 'both', desc: 'navigator.userAgent',
    check: (o, e) => eq(X(e, 'userAgent'), get(o, 'ua.userAgent')) },
  { id: 'ua.platform', group: 'ua', tier: 'hard', scope: 'both', desc: 'navigator.platform',
    check: (o, e) => eq(X(e, 'platform'), get(o, 'ua.platform')) },
  { id: 'ua.oscpu', group: 'ua', tier: 'hard', scope: 'both', desc: 'navigator.oscpu',
    check: (o, e) => eq(X(e, 'oscpu'), get(o, 'ua.oscpu')) },
  { id: 'ua.appVersion', group: 'ua', tier: 'hard', scope: 'both', desc: 'navigator.appVersion',
    check: (o, e) => eq(X(e, 'appVersion'), get(o, 'ua.appVersion')) },
  { id: 'ua.userAgentData', group: 'ua', tier: 'hard', scope: 'both', desc: 'navigator.userAgentData present iff chromeLike',
    check: (o, e) => {
      const present = !isNil(get(o, 'ua.userAgentData'));
      if (!e) return r(INFO, null, present);
      return r(present === !!e.userAgentData ? PASS : FAIL, !!e.userAgentData, present);
    } },
  { id: 'ua.uaDataBrands', group: 'ua', tier: 'hard', scope: 'both', desc: 'userAgentData.brands match the persona',
    check: (o, e) => {
      const uad = get(o, 'ua.userAgentData');
      if (!e || !e.userAgentData || !e.uaData) return r(INFO, null, uad ? uad.brands : null);
      if (!uad) return r(FAIL, e.uaData.brands, null, 'userAgentData missing');
      const exp = e.uaData.brands.map((b) => `${b.brand}/${b.version}`);
      const act = Array.isArray(uad.brands) ? uad.brands.map((b) => `${b.brand}/${b.version}`) : null;
      return r(act && sameList(exp, act) ? PASS : FAIL, exp, act);
    } },
  { id: 'ua.uaDataMobile', group: 'ua', tier: 'hard', scope: 'both', desc: 'userAgentData.mobile === false and platform matches',
    check: (o, e) => {
      const uad = get(o, 'ua.userAgentData');
      if (!e || !e.userAgentData || !e.uaData) return r(INFO, null, uad ? { mobile: uad.mobile, platform: uad.platform } : null);
      if (!uad) return r(FAIL, { mobile: false, platform: e.uaData.platform }, null, 'userAgentData missing');
      const ok = uad.mobile === false && uad.platform === e.uaData.platform;
      return r(ok ? PASS : FAIL, { mobile: false, platform: e.uaData.platform }, { mobile: uad.mobile, platform: uad.platform });
    } },
  { id: 'ua.vendor', group: 'ua', tier: 'soft', scope: 'both', desc: 'navigator.vendor ("" on Gecko, "Google Inc." on Chrome)',
    check: (o, e) => {
      const v = get(o, 'ua.vendor');
      if (!e || !e.desktop) return r(INFO, null, v);
      const exp = e.chromeLike ? 'Google Inc.' : '';
      return r(v === exp ? PASS : FAIL, exp, v, e.chromeLike ? 'win-chrome is UA-only; vendor is a known tell' : undefined);
    } },
  { id: 'ua.webdriver', group: 'ua', tier: 'soft', scope: 'both', desc: 'navigator.webdriver (true under selenium, false on device)',
    check: (o) => r(INFO, null, get(o, 'ua.webdriver')) },

  // ---- touch -------------------------------------------------------------------------------
  { id: 'touch.maxTouchPoints', group: 'touch', touchHost: true, tier: 'hard', scope: 'both', desc: 'navigator.maxTouchPoints',
    check: (o, e) => {
      const v = get(o, 'touch.maxTouchPoints');
      if (!e) return r(INFO, null, v);
      if (e.maxTouchPoints === null) return r(typeof v === 'number' && v > 0 ? PASS : FAIL, '> 0', v);
      return eq(e.maxTouchPoints, v);
    } },
  { id: 'touch.ontouchstart', group: 'touch', touchHost: true, tier: 'hard', scope: 'both', desc: '"ontouchstart" in window',
    check: (o, e) => eqBool(e ? e.hasTouchEvent : null, get(o, 'touch.ontouchstartWindow')) },
  { id: 'touch.ontouchstartElement', group: 'touch', touchHost: true, tier: 'hard', scope: 'both', desc: '"ontouchstart" in document.documentElement',
    check: (o, e) => eqBool(e ? e.hasTouchEvent : null, get(o, 'touch.ontouchstartElement')) },
  { id: 'touch.TouchEvent', group: 'touch', touchHost: true, tier: 'hard', scope: 'both', desc: 'typeof TouchEvent !== "undefined"',
    check: (o, e) => eqBool(e ? e.hasTouchEvent : null, get(o, 'touch.touchEventDefined')) },
  { id: 'touch.createEvent', group: 'touch', touchHost: true, tier: 'hard', scope: 'both', desc: 'document.createEvent("TouchEvent") succeeds (engine-level, not shimmable)',
    check: (o, e) => eqBool(e ? e.hasTouchEvent : null, get(o, 'touch.createEventTouch')) },
  { id: 'touch.orientation', group: 'touch', tier: 'hard', scope: 'device', desc: '"orientation" in window (mobile-only property)',
    check: (o, e) => eqBool(e ? e.hasTouchEvent : null, get(o, 'touch.orientationInWindow')) },
  { id: 'touch.pointerEvents', group: 'touch', tier: 'soft', scope: 'both', desc: 'PointerEvent supported',
    check: (o) => r(INFO, null, get(o, 'touch.pointerEventDefined')) },

  // ---- media (JS matchMedia) ------------------------------------------------------------------
  { id: 'media.pointerFine', group: 'media', touchHost: true, tier: 'hard', scope: 'both', desc: 'matchMedia("(pointer: fine)")',
    check: (o, e) => eqBool(e ? e.pointerFine : null, get(o, 'media.pointerFine')) },
  { id: 'media.hoverHover', group: 'media', touchHost: true, tier: 'hard', scope: 'both', desc: 'matchMedia("(hover: hover)")',
    check: (o, e) => eqBool(e ? e.hoverHover : null, get(o, 'media.hoverHover')) },
  { id: 'media.anyPointerCoarse', group: 'media', touchHost: true, tier: 'hard', scope: 'both', desc: 'matchMedia("(any-pointer: coarse)")',
    check: (o, e) => eqBool(e ? !e.pointerFine : null, get(o, 'media.anyPointerCoarse')) },
  { id: 'media.anyHoverHover', group: 'media', touchHost: true, tier: 'soft', scope: 'both', desc: 'matchMedia("(any-hover: hover)")',
    check: (o, e) => eqBool(e ? e.hoverHover : null, get(o, 'media.anyHoverHover')) },
  { id: 'media.orientation', group: 'media', tier: 'soft', scope: 'both', desc: 'matchMedia("(orientation: portrait)") — known limitation on a phone held upright',
    check: (o) => r(INFO, null, get(o, 'media.orientationPortrait') ? 'portrait' : 'landscape') },
  { id: 'media.deviceWidthVsScreen', group: 'media', tier: 'hard', scope: 'both', desc: 'matchMedia("(device-width: <screen.width>px)") agrees with screen.width',
    check: (o, e) => deviceFeatureSignal(o, e, 'deviceWidthMatches', 'device-width == screen.width') },
  { id: 'media.deviceHeightVsScreen', group: 'media', tier: 'hard', scope: 'both', desc: 'matchMedia("(device-height: <screen.height>px)") agrees with screen.height',
    check: (o, e) => deviceFeatureSignal(o, e, 'deviceHeightMatches', 'device-height == screen.height') },
  { id: 'media.deviceAspectRatio', group: 'media', tier: 'soft', scope: 'both', desc: 'matchMedia("(device-aspect-ratio: w/h)") agrees with screen.width/height',
    check: (o, e) => deviceFeatureSignal(o, e, 'deviceAspectRatioMatches', 'device-aspect-ratio == screen aspect') },
  { id: 'media.resolutionVsDpr', group: 'media', tier: 'hard', scope: 'both', desc: 'matchMedia("(resolution: <dpr>dppx)") agrees with devicePixelRatio',
    check: (o, e) => deviceFeatureSignal(o, e, 'resolutionMatches', 'resolution == devicePixelRatio') },
  { id: 'media.serialization', group: 'media', tier: 'soft', scope: 'both', desc: 'MediaQueryList.media is the CSSOM-normalised serialisation for every spelling',
    check: (o) => {
      const s = get(o, 'media.serialization');
      if (!s || !Array.isArray(s.js)) return r(SKIPPED, 'normalised', null, 'not measured');
      if (!s.cssom || s.cssom.error) return r(INFO, null, s.js, s.cssom && s.cssom.error ? `no CSSOM reference: ${s.cssom.error}` : 'no CSSOM reference');
      const bad = s.js.filter((x) => s.cssom[x.query] !== undefined && x.media !== s.cssom[x.query]).map((x) => ({ query: x.query, js: x.media, cssom: s.cssom[x.query] }));
      return r(bad.length === 0 ? PASS : FAIL, 'normalised', bad.length === 0 ? `${s.js.length} spellings agree` : bad);
    } },

  // ---- css (stylesheet-level @media, independent of the JS shim) --------------------------------------
  { id: 'css.pointerFine', group: 'css', touchHost: true, tier: 'hard', scope: 'both', desc: '@media (pointer: fine) applies',
    check: (o, e) => eqBool(e ? e.pointerFine : null, get(o, 'css.pointerFine')) },
  { id: 'css.hoverHover', group: 'css', touchHost: true, tier: 'hard', scope: 'both', desc: '@media (hover: hover) applies',
    check: (o, e) => eqBool(e ? e.hoverHover : null, get(o, 'css.hoverHover')) },
  { id: 'css.anyPointerCoarse', group: 'css', touchHost: true, tier: 'hard', scope: 'both', desc: '@media (any-pointer: coarse) applies',
    check: (o, e) => eqBool(e ? !e.pointerFine : null, get(o, 'css.anyPointerCoarse')) },
  { id: 'css.anyHoverHover', group: 'css', touchHost: true, tier: 'soft', scope: 'both', desc: '@media (any-hover: hover) applies',
    check: (o, e) => eqBool(e ? e.hoverHover : null, get(o, 'css.anyHoverHover')) },
  ...BREAKPOINTS.map((bp) => ({
    id: `css.bp${bp}`, group: 'css', tier: 'hard', scope: 'device', desc: `@media (min-width: ${bp}px) applies`,
    check: (o, e) => {
      const min = e ? e.minLayoutWidth : null;
      const v = get(o, `css.bp${bp}`);
      if (isNil(min)) return r(INFO, null, v);
      return eqBool(min >= bp, v, min >= bp ? undefined : `persona layout width ${min} is below ${bp}`);
    },
  })),
  { id: 'css.minDeviceWidth1024', group: 'css', tier: 'soft', scope: 'device', desc: '@media (min-device-width: 1024px) applies',
    check: (o, e) => {
      const w = e && e.screen ? e.screen.width : null;
      if (isNil(w)) return r(INFO, null, get(o, 'css.minDeviceWidth1024'));
      return eqBool(w >= 1024, get(o, 'css.minDeviceWidth1024'));
    } },
  { id: 'css.maxDeviceWidth480', group: 'css', tier: 'soft', scope: 'device', desc: 'stylesheet @media (max-device-width: 480px) does not apply (engine evaluates device-* from the real display: documented limitation)',
    check: (o, e) => {
      const w = e && e.screen ? e.screen.width : null;
      const v = get(o, 'css.maxDeviceWidth480');
      if (isNil(w)) return r(INFO, null, v);
      return eqBool(w <= 480, v, v === !(w <= 480) ? 'engine-level device-width (docs/limitations.md)' : undefined);
    } },
  { id: 'css.minResolution2', group: 'css', tier: 'soft', scope: 'device', desc: 'stylesheet @media (min-resolution: 2dppx) follows the persona dpr (engine evaluates it from the real display: documented limitation)',
    check: (o, e) => {
      const dpr = e ? e.dpr : null;
      const v = get(o, 'css.minResolution2');
      if (isNil(dpr)) return r(INFO, null, v);
      return eqBool(dpr >= 2, v, v === !(dpr >= 2) ? 'engine-level resolution (docs/limitations.md)' : undefined);
    } },
  { id: 'css.orientation', group: 'css', tier: 'soft', scope: 'both', desc: '@media (orientation: portrait) applies',
    check: (o) => r(INFO, null, get(o, 'css.orientationPortrait') ? 'portrait' : 'landscape') },

  // ---- layout -------------------------------------------------------------------------------
  { id: 'layout.clientWidth', group: 'layout', tier: 'hard', scope: 'device', desc: 'documentElement.clientWidth >= persona layout width',
    check: (o, e) => gte(e ? e.minLayoutWidth : null, get(o, 'layout.clientWidth')) },
  { id: 'layout.innerWidth', group: 'layout', tier: 'soft', scope: 'both', desc: 'window.innerWidth / innerHeight',
    check: (o) => r(INFO, null, { innerWidth: get(o, 'layout.innerWidth'), innerHeight: get(o, 'layout.innerHeight') }) },
  { id: 'layout.outerVsScreen', group: 'layout', tier: 'soft', scope: 'both', desc: 'outerWidth/outerHeight fit inside screen.width/height',
    check: (o, e) => {
      const s = e ? e.screen : null;
      const ow = get(o, 'layout.outerWidth'); const oh = get(o, 'layout.outerHeight');
      if (!s) return r(INFO, null, { outerWidth: ow, outerHeight: oh });
      const ok = typeof ow === 'number' && typeof oh === 'number' && ow <= s.width && oh <= s.height && ow > 0 && oh > 0;
      return r(ok ? PASS : FAIL, `<= ${s.width}x${s.height}`, { outerWidth: ow, outerHeight: oh });
    } },
  { id: 'layout.chromeGeometry', group: 'layout', tier: 'soft', scope: 'both', desc: 'outer >= inner and the content offset inside the window (mozInnerScreen* - screen*) fits in outer - inner',
    check: (o) => {
      const l = o.layout || {};
      const n = (k) => (typeof l[k] === 'number' ? l[k] : null);
      const iw = n('innerWidth'); const ih = n('innerHeight'); const ow = n('outerWidth'); const oh = n('outerHeight');
      if ([iw, ih, ow, oh].some((v) => v === null)) return r(SKIPPED, 'coherent', null, 'not measured');
      const top = n('mozInnerScreenY') !== null && n('screenY') !== null ? l.mozInnerScreenY - l.screenY : null;
      const left = n('mozInnerScreenX') !== null && n('screenX') !== null ? l.mozInnerScreenX - l.screenX : null;
      const problems = [];
      if (ow < iw || oh < ih) problems.push('outer smaller than inner');
      if (top !== null && (top < 0 || top > oh - ih)) problems.push(`top chrome ${top} vs outerHeight-innerHeight ${oh - ih}`);
      if (left !== null && (left < 0 || left > ow - iw)) problems.push(`left chrome ${left} vs outerWidth-innerWidth ${ow - iw}`);
      return r(problems.length === 0 ? PASS : FAIL, 'coherent', problems.length === 0 ? { top, left, chromeH: oh - ih, chromeW: ow - iw } : problems);
    } },

  // ---- screen ------------------------------------------------------------------------------
  { id: 'screen.width', group: 'screen', tier: 'hard', scope: 'both', desc: 'screen.width',
    check: (o, e) => eq(get(e, 'screen.width'), get(o, 'screen.width')) },
  { id: 'screen.height', group: 'screen', tier: 'hard', scope: 'both', desc: 'screen.height',
    check: (o, e) => eq(get(e, 'screen.height'), get(o, 'screen.height')) },
  { id: 'screen.availWidth', group: 'screen', tier: 'hard', scope: 'both', desc: 'screen.availWidth',
    check: (o, e) => eq(get(e, 'screen.availWidth'), get(o, 'screen.availWidth')) },
  { id: 'screen.availHeight', group: 'screen', tier: 'hard', scope: 'both', desc: 'screen.availHeight',
    check: (o, e) => eq(get(e, 'screen.availHeight'), get(o, 'screen.availHeight')) },
  { id: 'screen.colorDepth', group: 'screen', tier: 'hard', scope: 'both', desc: 'screen.colorDepth',
    check: (o, e) => eq(get(e, 'screen.colorDepth'), get(o, 'screen.colorDepth')) },
  { id: 'screen.pixelDepth', group: 'screen', tier: 'soft', scope: 'both', desc: 'screen.pixelDepth === colorDepth',
    check: (o, e) => eq(get(e, 'screen.colorDepth'), get(o, 'screen.pixelDepth')) },
  { id: 'screen.dpr', group: 'screen', tier: 'hard', scope: 'both', desc: 'window.devicePixelRatio',
    check: (o, e) => {
      const exp = e ? e.dpr : null; const v = get(o, 'screen.dpr');
      if (isNil(exp)) return r(INFO, null, v);
      return r(approx(exp, v, 0.001) ? PASS : FAIL, exp, v);
    } },
  { id: 'screen.orientationType', group: 'screen', tier: 'soft', scope: 'both', desc: 'screen.orientation.type agrees with screen aspect',
    check: (o, e) => {
      const s = e ? e.screen : null; const t = get(o, 'screen.orientationType');
      if (!s) return r(INFO, null, t);
      const exp = s.width >= s.height ? 'landscape' : 'portrait';
      return r(typeof t === 'string' && t.startsWith(exp) ? PASS : FAIL, `${exp}-*`, t);
    } },

  // ---- intl --------------------------------------------------------------------------------
  { id: 'intl.languages', group: 'intl', tier: 'hard', scope: 'both', desc: 'navigator.languages',
    check: (o, e) => eqList(e ? e.languages : null, get(o, 'intl.languages')) },
  { id: 'intl.language', group: 'intl', tier: 'hard', scope: 'both', desc: 'navigator.language === languages[0]',
    check: (o, e) => eq(e && e.languages ? e.languages[0] : null, get(o, 'intl.language')) },
  { id: 'intl.timeZone', group: 'intl', tier: 'hard', scope: 'both', desc: 'Intl.DateTimeFormat().resolvedOptions().timeZone',
    check: (o, e) => eq(e ? e.timeZone : null, get(o, 'intl.timeZone')) },
  { id: 'intl.locale', group: 'intl', tier: 'soft', scope: 'both', desc: 'Intl default locale == persona locale (soft in the en-US-only headless build, hard on the phone)',
    tierFor: (e, env) => (env === 'device' ? 'hard' : 'soft'),
    check: (o, e) => {
      const exp = e ? e.locale : null; const v = get(o, 'intl.locale');
      if (isNil(exp)) return r(INFO, null, v);
      const ok = typeof v === 'string' && (v === exp || v.split('-')[0] === exp.split('-')[0]);
      return r(ok ? PASS : FAIL, exp, v);
    } },
  { id: 'intl.dateOffsetCoherence', group: 'intl', tier: 'soft', scope: 'both', desc: 'Date.getTimezoneOffset() agrees with the Intl time zone',
    check: (o) => {
      const d = get(o, 'intl.dateOffsetMinutes'); const i = get(o, 'intl.intlOffsetMinutes');
      if (typeof d !== 'number' || typeof i !== 'number') return r(SKIPPED, 'equal', { date: d, intl: i }, 'not measured');
      return r(d === i ? PASS : FAIL, 'equal', { date: d, intl: i });
    } },

  // ---- geo ---------------------------------------------------------------------------------
  { id: 'geo.position', group: 'geo', tier: 'hard', scope: 'both', desc: 'getCurrentPosition() returns the persona location',
    check: (o, e) => {
      const g = o.geo || {};
      const exp = e ? e.geolocation : null;
      const act = g.status === 'ok' ? { latitude: g.latitude, longitude: g.longitude, accuracy: g.accuracy } : { status: g.status, code: g.code, message: g.message };
      if (!exp) return r(INFO, null, act);
      if (g.status !== 'ok') return r(FAIL, { latitude: exp.latitude, longitude: exp.longitude }, act);
      const ok = approx(g.latitude, exp.latitude, 0.01) && approx(g.longitude, exp.longitude, 0.01);
      return r(ok ? PASS : FAIL, { latitude: exp.latitude, longitude: exp.longitude }, act, ok ? undefined : 'more than ~1 km away');
    } },
  { id: 'geo.accuracy', group: 'geo', tier: 'soft', scope: 'both', desc: 'position accuracy is plausible',
    check: (o, e) => {
      const g = o.geo || {}; const exp = e && e.geolocation ? e.geolocation.accuracyMeters : null;
      if (isNil(exp)) return r(INFO, null, g.status === 'ok' ? g.accuracy : null);
      if (g.status !== 'ok') return r(FAIL, exp, null, 'no position');
      const ok = typeof g.accuracy === 'number' && g.accuracy > 0 && g.accuracy <= exp * 2 + 1;
      return r(ok ? PASS : FAIL, `~${exp}`, g.accuracy);
    } },
  { id: 'geo.permission', group: 'geo', tier: 'soft', scope: 'both', desc: 'permissions.query({name:"geolocation"}) is "granted" when a location is spoofed',
    check: (o, e) => {
      const p = get(o, 'geo.permission');
      if (!e || !e.geolocation) return r(INFO, null, p);
      return r(p === 'granted' ? PASS : FAIL, 'granted', p);
    } },

  // ---- webgl ----------------------------------------------------------------------------------
  { id: 'webgl.available', group: 'webgl', tier: 'soft', scope: 'both', desc: 'a WebGL context could be created',
    check: (o) => r(INFO, null, get(o, 'webgl.available')) },
  { id: 'webgl.vendor', group: 'webgl', tier: 'hard', scope: 'both', desc: 'UNMASKED_VENDOR_WEBGL',
    check: (o, e) => {
      const exp = e && e.webgl ? e.webgl.vendor : null;
      if (!get(o, 'webgl.available')) return r(SKIPPED, exp, null, 'no WebGL context in this environment');
      return eq(exp, get(o, 'webgl.unmaskedVendor'));
    } },
  { id: 'webgl.renderer', group: 'webgl', tier: 'hard', scope: 'both', desc: 'UNMASKED_RENDERER_WEBGL',
    check: (o, e) => {
      const exp = e && e.webgl ? e.webgl.renderer : null;
      if (!get(o, 'webgl.available')) return r(SKIPPED, exp, null, 'no WebGL context in this environment');
      return eq(exp, get(o, 'webgl.unmaskedRenderer'));
    } },

  // ---- misc / fingerprint tells ---------------------------------------------------------------
  { id: 'misc.toStringNative', group: 'misc', tier: 'hard', scope: 'both', desc: 'Function.prototype.toString of every shimmed getter/method prints [native code]',
    check: (o) => {
      const t = get(o, 'misc.toString');
      if (!t || !Array.isArray(t.checks)) return r(SKIPPED, 'all native', null, 'not measured');
      const bad = t.checks.filter((c) => !c.native);
      return r(bad.length === 0 ? PASS : FAIL, 'all native', bad.length === 0 ? `${t.checks.length} native` : bad);
    } },
  { id: 'misc.descriptorShape', group: 'misc', tier: 'soft', scope: 'both', desc: 'no own data properties on navigator/screen; accessors live on the prototypes',
    check: (o) => {
      const d = get(o, 'misc.descriptors');
      if (!d) return r(SKIPPED, 'prototype accessors only', null, 'not measured');
      const problems = [];
      if (Array.isArray(d.navigatorOwnProps) && d.navigatorOwnProps.length) problems.push({ navigatorOwnProps: d.navigatorOwnProps });
      if (Array.isArray(d.screenOwnProps) && d.screenOwnProps.length) problems.push({ screenOwnProps: d.screenOwnProps });
      if (Array.isArray(d.missingAccessors) && d.missingAccessors.length) problems.push({ missingAccessors: d.missingAccessors });
      if (d.windowHatchMarker === true) problems.push({ windowHatchMarker: true });
      if (Array.isArray(d.windowSymbolProps) && d.windowSymbolProps.some((x) => /hatch|persona|shim/i.test(x))) problems.push({ windowSymbolProps: d.windowSymbolProps });
      if (Array.isArray(d.getterOwnShape) && !sameList(d.getterOwnShape, ['length', 'name'])) problems.push({ getterOwnShape: d.getterOwnShape });
      return r(problems.length === 0 ? PASS : FAIL, 'prototype accessors only', problems.length === 0 ? 'clean' : problems);
    } },
  { id: 'misc.plugins', group: 'misc', tier: 'soft', scope: 'both', desc: 'navigator.plugins non-empty on a desktop persona (desktop Firefox lists its PDF viewer)',
    check: (o, e) => {
      const n = get(o, 'misc.pluginsLength');
      if (!e || !e.desktop) return r(INFO, null, n);
      return r(typeof n === 'number' && n > 0 ? PASS : FAIL, '> 0', n);
    } },
  { id: 'misc.pdfViewerEnabled', group: 'misc', tier: 'soft', scope: 'both', desc: 'navigator.pdfViewerEnabled true on a desktop persona',
    check: (o, e) => {
      const v = get(o, 'misc.pdfViewerEnabled');
      if (!e || !e.desktop) return r(INFO, null, v);
      return r(v === true ? PASS : FAIL, true, v);
    } },
  { id: 'misc.deviceMemory', group: 'misc', tier: 'soft', scope: 'both', desc: 'navigator.deviceMemory (absent on Gecko)',
    check: (o) => r(INFO, null, get(o, 'misc.deviceMemory')) },
  { id: 'misc.oscpuCoherence', group: 'misc', tier: 'soft', scope: 'both', desc: 'navigator.oscpu is a substring of the UA (coherent spoof)',
    check: (o, e) => {
      const ua = get(o, 'ua.userAgent'); const os = get(o, 'ua.oscpu');
      if (!e || !e.desktop) return r(INFO, null, { oscpu: os });
      if (typeof ua !== 'string' || typeof os !== 'string') return r(FAIL, 'oscpu in UA', { oscpu: os }, 'not measured');
      return r(ua.includes(os) ? PASS : FAIL, 'oscpu in UA', { oscpu: os });
    } },

  // ---- contexts -------------------------------------------------------------------------------
  { id: 'contexts.sameOriginFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'same-origin iframe agrees with top',
    check: (o) => contextSignal(o, 'sameOrigin', FRAME_KEYS) },
  { id: 'contexts.crossOriginFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'cross-origin iframe agrees with top',
    check: (o) => contextSignal(o, 'crossOrigin', FRAME_KEYS) },
  { id: 'contexts.aboutBlankFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'about:blank iframe (read from the parent) agrees with top',
    check: (o) => contextSignal(o, 'aboutBlank', FRAME_KEYS) },
  { id: 'contexts.srcdocFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'srcdoc iframe agrees with top',
    check: (o) => contextSignal(o, 'srcdoc', FRAME_KEYS) },
  { id: 'contexts.dataFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'data: URL iframe agrees with top (matchOriginAsFallback)',
    check: (o) => contextSignal(o, 'data', FRAME_KEYS) },
  { id: 'contexts.blobFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'blob: URL iframe agrees with top',
    check: (o) => contextSignal(o, 'blob', FRAME_KEYS) },
  { id: 'contexts.sandboxFrame', group: 'contexts', tier: 'hard', scope: 'both', desc: 'sandbox="allow-scripts" srcdoc iframe (opaque origin) agrees with top',
    check: (o) => contextSignal(o, 'sandbox', FRAME_KEYS) },
  { id: 'contexts.crossRealmToString', group: 'contexts', tier: 'hard', scope: 'both', desc: 'a fresh realm\'s Function.prototype.toString prints native for the top document\'s shimmed functions and vice versa',
    check: (o) => crossRealmSignal(o) },
  { id: 'contexts.worker.userAgent', group: 'contexts', tier: 'hard', scope: 'both', desc: 'worker navigator.userAgent agrees with top',
    check: (o) => workerSignal(o, 'userAgent') },
  { id: 'contexts.worker.platform', group: 'contexts', tier: 'hard', scope: 'both', desc: 'worker navigator.platform agrees with top',
    check: (o) => workerSignal(o, 'platform') },
  { id: 'contexts.worker.languages', group: 'contexts', tier: 'hard', scope: 'both', desc: 'worker navigator.languages agrees with top',
    check: (o) => workerSignal(o, 'languages') },
  { id: 'contexts.worker.hardwareConcurrency', group: 'contexts', tier: 'soft', scope: 'both', desc: 'worker hardwareConcurrency agrees with top (worker-scope shim is M2)',
    check: (o) => workerSignal(o, 'hardwareConcurrency', 'worker-scope shim is an M2 item') },
  { id: 'contexts.worker.timeZone', group: 'contexts', tier: 'hard', scope: 'both', desc: 'worker Intl time zone agrees with top',
    tierFor: (e) => (e && e.tzShim ? 'soft' : 'hard'),
    check: (o, e) => workerSignal(o, 'timeZone', e && e.tzShim ? 'Date/Intl shim does not reach workers' : undefined) },
  { id: 'contexts.sharedWorker', group: 'contexts', tier: 'hard', scope: 'both', desc: 'SharedWorker navigator UA/platform/languages and Intl time zone agree with top',
    tierFor: (e) => (e && e.tzShim ? 'soft' : 'hard'),
    check: (o) => {
      const top = get(o, 'contexts.top'); const w = get(o, 'contexts.sharedWorker');
      if (!top || !w) return r(SKIPPED, 'agrees with top', null, 'no shared worker snapshot');
      if (w.skipped) return r(SKIPPED, 'agrees with top', null, w.reason || 'skipped');
      if (w.error) return r(FAIL, 'agrees with top', null, `shared worker error: ${w.error}`);
      const diffs = snapshotDiff(top, w.snapshot, SHARED_WORKER_KEYS);
      return r(diffs.length === 0 ? PASS : FAIL, 'agrees with top', diffs.length === 0 ? 'agrees' : diffs);
    } },
  { id: 'contexts.sharedWorker.hardwareConcurrency', group: 'contexts', tier: 'soft', scope: 'both', desc: 'SharedWorker hardwareConcurrency agrees with top (worker-scope shim is M2)',
    check: (o) => workerSignal(o, 'hardwareConcurrency', 'worker-scope shim is an M2 item', 'sharedWorker') },

  // ---- server ------------------------------------------------------------------------------
  { id: 'server.reachable', group: 'server', tier: 'soft', scope: 'both', desc: './echo responded',
    check: (o) => {
      const s = o.server;
      if (!s) return r(SKIPPED, 'reachable', null, 'no server probe');
      return r(INFO, null, s.error ? `error: ${s.error}` : 'ok');
    } },
  { id: 'server.userAgent', group: 'server', tier: 'hard', scope: 'both', desc: 'echoed user-agent request header',
    check: (o, e) => {
      const h = serverHeaders(o, 'first');
      if (h.skipped) return r(SKIPPED, e ? e.userAgent : null, null, h.skipped);
      return eq(e ? e.userAgent : null, h.headers['user-agent']);
    } },
  { id: 'server.acceptLanguage', group: 'server', tier: 'hard', scope: 'both', desc: 'echoed accept-language header (Gecko q-value algorithm)',
    check: (o, e) => {
      const h = serverHeaders(o, 'first');
      if (h.skipped) return r(SKIPPED, e ? e.acceptLanguage : null, null, h.skipped);
      return eq(e ? e.acceptLanguage : null, h.headers['accept-language']);
    } },
  { id: 'server.clientHintsFirst', group: 'server', tier: 'hard', scope: 'both', desc: 'no Sec-CH-UA* headers on the first request (Gecko sends none)',
    check: (o) => noHeaders(o, 'first', CLIENT_HINT_HEADERS, 'none') },
  { id: 'server.clientHintsAfterAcceptCH', group: 'server', tier: 'hard', scope: 'both', desc: 'still no Sec-CH-UA* headers after the server sent Accept-CH',
    check: (o) => noHeaders(o, 'second', CLIENT_HINT_HEADERS, 'none') },
  { id: 'server.viewportHints', group: 'server', tier: 'hard', scope: 'both', desc: 'no Viewport-Width / DPR / Device-Memory / Save-Data headers after Accept-CH',
    check: (o) => noHeaders(o, 'second', VIEWPORT_HINT_HEADERS, 'none') },

  // ---- interactive -------------------------------------------------------------------------------
  // Gecko keeps the real input source on pointer/mouse events even with dom.w3c_touch_events.enabled=0
  // (PointerEventHandler::InitPointerEventFromTouch at FIREFOX_158_0_RELEASE), so "mouse" is only reachable
  // through the shim's trusted-event masking. The hard device gate is therefore "a trusted tap arrived";
  // the pointerType value is a soft signal.
  { id: 'interactive.pointerEvents', group: 'interactive', tier: 'hard', scope: 'device', desc: 'a trusted pointerdown reached the box (tap it)',
    check: (o) => {
      const t = get(o, 'interactive.pointerType');
      if (isNil(t)) return r(SKIPPED, 'trusted pointerdown', null, 'tap the box to measure');
      const trusted = get(o, 'interactive.trusted');
      return r(trusted === false ? FAIL : PASS, 'trusted pointerdown', { pointerType: t, trusted: trusted === undefined ? null : trusted });
    } },
  { id: 'interactive.pointerType', group: 'interactive', tier: 'soft', scope: 'device', desc: 'pointerdown on the box reports pointerType "mouse" (shim masks trusted touch input; the engine itself says "touch")',
    check: (o, e) => {
      const t = get(o, 'interactive.pointerType');
      const src = get(o, 'interactive.mozInputSource');
      const actual = isNil(t) ? null : { pointerType: t, mozInputSource: isNil(src) ? null : src };
      if (!e) return r(isNil(t) ? SKIPPED : INFO, null, actual, isNil(t) ? 'tap the box' : undefined);
      if (e.touch) return r(INFO, null, actual, isNil(t) ? 'tap the box' : undefined);
      if (isNil(t)) return r(SKIPPED, 'mouse', null, 'tap the box to measure');
      const ok = t === 'mouse' && (isNil(src) || src === 1);
      return r(ok ? PASS : FAIL, 'mouse', actual, ok ? undefined : 'engine reports the real input source; the shim did not mask it (docs/limitations.md)');
    } },
];

export const SIGNAL_IDS = SIGNALS.map((s) => s.id);

// ---- libraries -----------------------------------------------------------------------------------

export function evaluateLibraries(observed, expected) {
  const list = Array.isArray(observed.libraries) ? observed.libraries : [];
  const expMobile = expectedMobile(expected);
  return list.map((lib) => {
    const out = { name: lib.name, version: lib.version || null, mobile: typeof lib.mobile === 'boolean' ? lib.mobile : null, detail: lib.detail === undefined ? null : lib.detail };
    if (lib.error) { out.status = FAIL; out.note = `library threw: ${lib.error}`; return out; }
    if (isNil(expMobile)) { out.status = INFO; return out; }
    if (out.mobile === null) { out.status = INFO; out.note = 'library gave no mobile/desktop verdict'; return out; }
    out.status = out.mobile === expMobile ? PASS : FAIL;
    out.expectedMobile = expMobile;
    return out;
  });
}

// ---- main entry ------------------------------------------------------------------------------------

export function evaluate(observed, expected, env) {
  const o = observed || {};
  const e = expected || null;
  const environment = env === 'device' ? 'device' : 'headless';
  const signals = SIGNALS.map((s) => {
    const tier = typeof s.tierFor === 'function' ? s.tierFor(e, environment) : s.tier;
    let res;
    try {
      res = s.check(o, e, environment);
    } catch (err) {
      res = r(FAIL, null, null, `check threw: ${err && err.message ? err.message : String(err)}`);
    }
    if (s.scope === 'device' && environment !== 'device') {
      res = { ...res, status: SKIPPED, note: `device-scoped signal (${res.note || 'not evaluated in headless'})` };
    } else if (s.touchHost && e && e.touch === true && environment !== 'device' && res.status === FAIL) {
      // A touch identity (real-device) expects a real touch screen; headless Firefox on a desktop host has none.
      // The engine-default values are still recorded, but they cannot fail the run off-device.
      res = { ...res, status: SKIPPED, note: `expects a real touch device; the headless host is a desktop (${res.note || 'host-dependent'})` };
    }
    return { id: s.id, group: s.group, tier, scope: s.scope, desc: s.desc, ...res };
  });
  const libraries = evaluateLibraries(o, e);
  const summary = summarize(signals, libraries, environment);
  return { signals, libraries, summary };
}

export function summarize(signals, libraries, env) {
  const count = (st) => signals.filter((s) => s.status === st).length;
  const hardFail = signals.filter((s) => s.status === FAIL && s.tier === 'hard').map((s) => s.id);
  const softFail = signals.filter((s) => s.status === FAIL && s.tier === 'soft').map((s) => s.id);
  const deviceSkipped = signals.filter((s) => s.status === SKIPPED && s.scope === 'device').map((s) => s.id);
  const libraryFail = libraries.filter((l) => l.status === FAIL).map((l) => l.name);
  const overall = hardFail.length === 0 && libraryFail.length === 0 && (env !== 'device' || deviceSkipped.length === 0) ? PASS : FAIL;
  return {
    overall,
    pass: count(PASS), fail: count(FAIL), skipped: count(SKIPPED), info: count(INFO),
    hardFail, softFail, deviceSkipped, libraryFail,
    libraryTotal: libraries.length,
    libraryPass: libraries.filter((l) => l.status === PASS).length,
  };
}

/** Builds the result JSON (schema v1) around an evaluation. */
export function buildResult({ persona, env, observed, expected, startedAt, finishedAt, url, crossOrigin, harness }) {
  const ev = evaluate(observed, expected, env);
  return {
    schema: SCHEMA_VERSION,
    harnessVersion: HARNESS_VERSION,
    persona: persona || 'none',
    env: env === 'device' ? 'device' : 'headless',
    url: url || null,
    crossOrigin: crossOrigin || null,
    startedAt: startedAt || null,
    finishedAt: finishedAt || null,
    durationMs: startedAt && finishedAt ? Date.parse(finishedAt) - Date.parse(startedAt) : null,
    harness: harness || null,
    expected: expected || null,
    observed,
    signals: ev.signals,
    libraries: ev.libraries,
    summary: ev.summary,
  };
}

/** Plain-text table used by the runner and the page's "Copy" text fallback. */
export function formatTable(result) {
  const lines = [];
  const pad = (s, n) => String(s).padEnd(n);
  lines.push(`persona=${result.persona} env=${result.env} overall=${result.summary.overall}`);
  lines.push(`${pad('status', 8)}${pad('tier', 5)}${pad('scope', 7)}${pad('id', 40)}expected -> actual`);
  const short = (v) => {
    const s = typeof v === 'string' ? v : JSON.stringify(v);
    return s === undefined ? 'null' : (s.length > 70 ? `${s.slice(0, 67)}...` : s);
  };
  for (const s of result.signals) {
    lines.push(`${pad(s.status, 8)}${pad(s.tier, 5)}${pad(s.scope, 7)}${pad(s.id, 40)}${short(s.expected)} -> ${short(s.actual)}${s.note ? `  (${s.note})` : ''}`);
  }
  for (const l of result.libraries) {
    lines.push(`${pad(l.status, 8)}${pad('lib', 5)}${pad('', 7)}${pad(`${l.name}@${l.version}`, 40)}mobile=${l.mobile}${l.expectedMobile !== undefined ? ` (expected ${l.expectedMobile})` : ''}${l.note ? `  (${l.note})` : ''}`);
  }
  const s = result.summary;
  lines.push(`pass=${s.pass} fail=${s.fail} skipped=${s.skipped} info=${s.info} libraries=${s.libraryPass}/${s.libraryTotal} hardFail=[${s.hardFail.join(', ')}] softFail=[${s.softFail.join(', ')}] libraryFail=[${s.libraryFail.join(', ')}]`);
  return lines.join('\n');
}
