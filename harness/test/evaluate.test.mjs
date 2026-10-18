import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate, buildResult, formatTable, SIGNALS, SIGNAL_IDS, summarize } from '../evaluate.js';
import { EXPECT_WIN, EXPECT_CHROME, EXPECT_REAL, desktopObserved, phoneObserved, uaOnlySpoofObserved, topOnlySpoofObserved } from './fixtures.mjs';

const byId = (res, id) => res.signals.find((s) => s.id === id);
const statusOf = (res, id) => { const s = byId(res, id); assert.ok(s, `signal ${id} missing`); return s.status; };

test('signal table: unique ids, valid tier/scope, every id has a check', () => {
  assert.equal(new Set(SIGNAL_IDS).size, SIGNAL_IDS.length);
  for (const s of SIGNALS) {
    assert.ok(['hard', 'soft'].includes(s.tier), s.id);
    assert.ok(['both', 'device'].includes(s.scope), s.id);
    assert.equal(typeof s.check, 'function', s.id);
    assert.ok(/^[a-z]+\.[A-Za-z0-9.]+$/.test(s.id), s.id);
  }
  for (const group of ['ua', 'touch', 'media', 'css', 'layout', 'screen', 'intl', 'geo', 'webgl', 'misc', 'contexts', 'server', 'interactive']) {
    assert.ok(SIGNALS.some((s) => s.group === group), `group ${group}`);
  }
});

test('coherent desktop persona in headless -> PASS, device-scoped signals SKIPPED', () => {
  const res = evaluate(desktopObserved(), EXPECT_WIN, 'headless');
  assert.equal(res.summary.overall, 'PASS', JSON.stringify(res.summary));
  assert.deepEqual(res.summary.hardFail, []);
  assert.deepEqual(res.summary.libraryFail, []);
  assert.equal(res.summary.libraryTotal, 10);
  for (const id of ['layout.clientWidth', 'css.bp992', 'css.bp1280', 'touch.orientation', 'interactive.pointerEvents', 'interactive.pointerType', 'css.maxDeviceWidth480', 'css.minResolution2']) {
    assert.equal(statusOf(res, id), 'SKIPPED', id);
    assert.equal(byId(res, id).scope, 'device');
  }
  for (const id of ['ua.userAgent', 'ua.platform', 'ua.oscpu', 'ua.appVersion', 'touch.maxTouchPoints', 'touch.createEvent', 'media.pointerFine',
    'css.pointerFine', 'css.hoverHover', 'css.anyPointerCoarse', 'screen.width', 'screen.dpr', 'intl.languages', 'intl.timeZone', 'geo.position',
    'webgl.renderer', 'server.userAgent', 'server.acceptLanguage', 'server.clientHintsFirst', 'server.clientHintsAfterAcceptCH',
    'contexts.sameOriginFrame', 'contexts.crossOriginFrame', 'contexts.dataFrame', 'contexts.blobFrame', 'contexts.sandboxFrame', 'contexts.crossRealmToString',
    'contexts.worker.userAgent', 'contexts.sharedWorker', 'contexts.sharedWorker.hardwareConcurrency', 'misc.toStringNative', 'misc.descriptorShape', 'ua.userAgentData',
    'media.deviceWidthVsScreen', 'media.deviceHeightVsScreen', 'media.deviceAspectRatio', 'media.resolutionVsDpr', 'media.serialization', 'layout.chromeGeometry']) {
    assert.equal(statusOf(res, id), 'PASS', id);
  }
  assert.equal(statusOf(res, 'ua.webdriver'), 'INFO');
  assert.equal(byId(res, 'intl.locale').tier, 'soft', 'headless: locale is soft');
});

test('coherent desktop persona in device env -> FAIL until the box is tapped, then PASS', () => {
  const o = desktopObserved();
  let res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(res.summary.overall, 'FAIL');
  assert.deepEqual(res.summary.deviceSkipped, ['interactive.pointerEvents', 'interactive.pointerType']);
  for (const id of ['layout.clientWidth', 'css.bp992', 'css.bp1024', 'css.bp1200', 'css.bp1280', 'touch.orientation', 'css.maxDeviceWidth480']) assert.equal(statusOf(res, id), 'PASS', id);
  assert.equal(byId(res, 'intl.locale').tier, 'hard', 'device: locale is hard');
  o.interactive = { pointerType: 'mouse', mozInputSource: 1, trusted: true, events: [] };
  res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(res.summary.overall, 'PASS', JSON.stringify(res.summary));
  assert.equal(statusOf(res, 'interactive.pointerEvents'), 'PASS');
  assert.equal(statusOf(res, 'interactive.pointerType'), 'PASS');
  // the engine's own value (shim not masking): soft FAIL, overall still PASS
  o.interactive = { pointerType: 'touch', mozInputSource: 5, trusted: true, events: [] };
  res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(statusOf(res, 'interactive.pointerEvents'), 'PASS');
  assert.equal(statusOf(res, 'interactive.pointerType'), 'FAIL');
  assert.equal(byId(res, 'interactive.pointerType').tier, 'soft');
  assert.equal(res.summary.overall, 'PASS');
  // a synthetic (untrusted) pointerdown does not count as a tap
  o.interactive = { pointerType: 'mouse', mozInputSource: 1, trusted: false, events: [] };
  res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(statusOf(res, 'interactive.pointerEvents'), 'FAIL');
  assert.equal(res.summary.overall, 'FAIL');
  // the engine-level stylesheet limits: on the phone device-width is the real display
  o.css.maxDeviceWidth480 = true;
  o.css.minResolution2 = true;
  res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(statusOf(res, 'css.maxDeviceWidth480'), 'FAIL');
  assert.equal(byId(res, 'css.maxDeviceWidth480').tier, 'soft');
  assert.match(byId(res, 'css.maxDeviceWidth480').note, /limitations/);
  assert.equal(statusOf(res, 'css.minResolution2'), 'FAIL');
});

test('real phone against a desktop expectation fails on every mechanism', () => {
  const res = evaluate(phoneObserved(), EXPECT_WIN, 'device');
  assert.equal(res.summary.overall, 'FAIL');
  for (const id of ['ua.userAgent', 'ua.platform', 'touch.maxTouchPoints', 'touch.ontouchstart', 'touch.TouchEvent', 'touch.createEvent', 'touch.orientation',
    'media.pointerFine', 'media.hoverHover', 'media.anyPointerCoarse', 'css.pointerFine', 'css.hoverHover', 'css.anyPointerCoarse',
    'css.bp992', 'css.bp1280', 'layout.clientWidth', 'screen.width', 'screen.dpr', 'intl.timeZone', 'geo.position', 'webgl.renderer',
    'server.userAgent', 'server.acceptLanguage']) {
    assert.equal(statusOf(res, id), 'FAIL', id);
    assert.ok(res.summary.hardFail.includes(id), `${id} should be a hard fail`);
  }
  assert.equal(statusOf(res, 'interactive.pointerType'), 'FAIL');
  assert.ok(res.summary.softFail.includes('interactive.pointerType'));
  assert.equal(res.summary.libraryFail.length, 10);
  // Things a phone does right regardless of persona (its own values are coherent).
  for (const id of ['server.clientHintsFirst', 'contexts.sameOriginFrame', 'contexts.dataFrame', 'contexts.worker.platform', 'contexts.sharedWorker', 'contexts.crossRealmToString', 'misc.toStringNative', 'media.deviceWidthVsScreen', 'media.resolutionVsDpr', 'interactive.pointerEvents']) assert.equal(statusOf(res, id), 'PASS', id);
});

test('UA-only spoof ("request desktop site") passes the UA signals and fails touch/media/libraries', () => {
  const res = evaluate(uaOnlySpoofObserved(), EXPECT_WIN, 'device');
  assert.equal(res.summary.overall, 'FAIL');
  for (const id of ['ua.userAgent', 'server.userAgent', 'contexts.sameOriginFrame', 'contexts.worker.userAgent']) assert.equal(statusOf(res, id), 'PASS', id);
  for (const id of ['ua.platform', 'ua.oscpu', 'touch.maxTouchPoints', 'media.pointerFine', 'css.pointerFine', 'screen.width', 'intl.timeZone']) assert.equal(statusOf(res, id), 'FAIL', id);
  const lib = (name) => res.libraries.find((l) => l.name === name).status;
  assert.equal(lib('mobile-detect'), 'PASS');
  assert.equal(lib('bowser'), 'PASS');
  assert.equal(lib('detect-it'), 'FAIL');
  assert.equal(lib('modernizr'), 'FAIL');
  assert.equal(lib('is-mobile'), 'FAIL');
  assert.deepEqual(res.summary.libraryFail.sort(), ['detect-it', 'is-mobile', 'ismobilejs', 'modernizr']);
});

test('top-document-only JS spoof leaks through contexts, toString, descriptors and CSS', () => {
  const res = evaluate(topOnlySpoofObserved(), EXPECT_WIN, 'headless');
  assert.equal(res.summary.overall, 'FAIL');
  assert.equal(statusOf(res, 'contexts.aboutBlankFrame'), 'FAIL');
  assert.ok(Array.isArray(byId(res, 'contexts.aboutBlankFrame').actual));
  assert.equal(statusOf(res, 'contexts.worker.userAgent'), 'FAIL');
  assert.equal(statusOf(res, 'contexts.worker.hardwareConcurrency'), 'PASS');
  assert.equal(statusOf(res, 'contexts.worker.timeZone'), 'FAIL');
  assert.equal(statusOf(res, 'misc.toStringNative'), 'FAIL');
  assert.equal(statusOf(res, 'misc.descriptorShape'), 'FAIL');
  assert.equal(byId(res, 'misc.descriptorShape').tier, 'soft');
  assert.ok(res.summary.softFail.includes('misc.descriptorShape'));
  assert.ok(!res.summary.hardFail.includes('misc.descriptorShape'));
  assert.ok(JSON.stringify(byId(res, 'misc.descriptorShape').actual).includes('windowHatchMarker'));
  for (const id of ['css.pointerFine', 'css.hoverHover', 'css.anyPointerCoarse']) assert.equal(statusOf(res, id), 'FAIL', id);
  assert.equal(statusOf(res, 'media.pointerFine'), 'PASS');
  assert.equal(statusOf(res, 'contexts.sameOriginFrame'), 'PASS');
  // the red-team vectors promoted to signals: fresh data:/blob:/sandboxed frames, cross-realm toString,
  // device-* / resolution vs screen.*, MediaQueryList.media serialisation, shared worker
  for (const id of ['contexts.dataFrame', 'contexts.blobFrame', 'contexts.sandboxFrame', 'contexts.crossRealmToString', 'media.deviceWidthVsScreen', 'media.deviceHeightVsScreen', 'media.resolutionVsDpr', 'contexts.sharedWorker']) {
    assert.equal(statusOf(res, id), 'FAIL', id);
    assert.ok(res.summary.hardFail.includes(id), `${id} is hard`);
  }
  assert.equal(statusOf(res, 'media.serialization'), 'FAIL');
  assert.equal(byId(res, 'media.serialization').tier, 'soft');
  assert.equal(statusOf(res, 'contexts.sharedWorker.hardwareConcurrency'), 'PASS', 'phone worker and phone top agree on 8');
});

test('chrome geometry: the content offset must fit inside the window chrome', () => {
  const o = desktopObserved();
  assert.equal(statusOf(evaluate(o, EXPECT_WIN, 'headless'), 'layout.chromeGeometry'), 'PASS');
  o.layout = { ...o.layout, outerHeight: o.layout.innerHeight + 85, mozInnerScreenY: 86, screenY: 0 };
  let res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'layout.chromeGeometry'), 'FAIL');
  assert.equal(byId(res, 'layout.chromeGeometry').tier, 'soft');
  o.layout = { ...o.layout, outerHeight: o.layout.innerHeight - 1 };
  assert.equal(statusOf(evaluate(o, EXPECT_WIN, 'headless'), 'layout.chromeGeometry'), 'FAIL');
  delete o.layout.mozInnerScreenY;
  o.layout.outerHeight = o.layout.innerHeight + 86;
  res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'layout.chromeGeometry'), 'PASS', 'no mozInnerScreen: only outer >= inner is checked');
});

test('frames and shared worker: errors FAIL, skipped stays SKIPPED, shim-only values must agree too', () => {
  const o = desktopObserved();
  o.contexts.data = { error: 'data: frame: timeout after 8000 ms' };
  o.contexts.sharedWorker = { skipped: true, reason: 'SharedWorker not supported' };
  let res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'contexts.dataFrame'), 'FAIL');
  assert.equal(statusOf(res, 'contexts.sharedWorker'), 'SKIPPED');
  assert.equal(statusOf(res, 'contexts.sharedWorker.hardwareConcurrency'), 'SKIPPED');
  const o2 = desktopObserved();
  o2.contexts.blob.snapshot.screenWidth = 1366;
  o2.contexts.blob.snapshot.dpr = 2.625;
  res = evaluate(o2, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'contexts.blobFrame'), 'FAIL');
  assert.deepEqual(byId(res, 'contexts.blobFrame').actual.map((d) => d.key), ['screenWidth', 'dpr']);
  const o3 = desktopObserved();
  o3.contexts.crossRealm = { error: 'boom' };
  assert.equal(statusOf(evaluate(o3, EXPECT_WIN, 'headless'), 'contexts.crossRealmToString'), 'FAIL');
  o3.contexts.crossRealm = { referenceText: null, checks: [] };
  assert.equal(statusOf(evaluate(o3, EXPECT_WIN, 'headless'), 'contexts.crossRealmToString'), 'SKIPPED');
});

test('device-* media signals are INFO without a screen expectation and FAIL when the engine disagrees', () => {
  const o = desktopObserved();
  o.media.device.deviceWidthMatches = false;
  o.media.device.jsDeviceWidth = 1366;
  let res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'media.deviceWidthVsScreen'), 'FAIL');
  assert.match(byId(res, 'media.deviceWidthVsScreen').note, /CSS device-width 1366/);
  assert.equal(statusOf(res, 'media.deviceHeightVsScreen'), 'PASS');
  res = evaluate(o, EXPECT_REAL, 'headless');
  assert.equal(statusOf(res, 'media.deviceWidthVsScreen'), 'INFO');
  delete o.media.device;
  res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'media.resolutionVsDpr'), 'SKIPPED');
});

test('chromeLike persona requires userAgentData brands, mobile=false and vendor', () => {
  const good = desktopObserved(EXPECT_CHROME);
  good.intl.dateOffsetMinutes = 60; good.intl.intlOffsetMinutes = 60;
  let res = evaluate(good, EXPECT_CHROME, 'headless');
  assert.equal(statusOf(res, 'ua.userAgentData'), 'PASS');
  assert.equal(statusOf(res, 'ua.uaDataBrands'), 'PASS');
  assert.equal(statusOf(res, 'ua.uaDataMobile'), 'PASS');
  assert.equal(statusOf(res, 'ua.vendor'), 'PASS');
  assert.equal(statusOf(res, 'server.acceptLanguage'), 'PASS');
  assert.equal(byId(res, 'server.acceptLanguage').expected, 'en-GB,en;q=0.9');
  good.ua.userAgentData = null;
  good.ua.vendor = '';
  res = evaluate(good, EXPECT_CHROME, 'headless');
  assert.equal(statusOf(res, 'ua.userAgentData'), 'FAIL');
  assert.equal(statusOf(res, 'ua.uaDataBrands'), 'FAIL');
  assert.equal(statusOf(res, 'ua.vendor'), 'FAIL');
  assert.equal(byId(res, 'ua.vendor').tier, 'soft');
  // A Gecko persona must NOT expose userAgentData.
  const win = desktopObserved(EXPECT_WIN);
  win.ua.userAgentData = { brands: [], mobile: false, platform: 'Windows' };
  res = evaluate(win, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'ua.userAgentData'), 'FAIL');
  assert.equal(statusOf(res, 'ua.uaDataBrands'), 'INFO');
});

test('real-device expectation: unspoofed values are INFO, touch must be present', () => {
  const phone = phoneObserved();
  const res = evaluate(phone, EXPECT_REAL, 'device');
  for (const id of ['ua.userAgent', 'ua.platform', 'screen.width', 'intl.languages', 'intl.timeZone', 'geo.position', 'webgl.renderer', 'server.userAgent', 'server.acceptLanguage', 'interactive.pointerType', 'media.deviceWidthVsScreen']) {
    assert.equal(statusOf(res, id), 'INFO', id);
  }
  assert.equal(statusOf(res, 'interactive.pointerEvents'), 'PASS');
  assert.equal(statusOf(res, 'touch.maxTouchPoints'), 'PASS');
  assert.equal(byId(res, 'touch.maxTouchPoints').expected, '> 0');
  assert.equal(statusOf(res, 'touch.TouchEvent'), 'PASS');
  assert.equal(statusOf(res, 'media.pointerFine'), 'PASS');
  assert.equal(statusOf(res, 'css.anyPointerCoarse'), 'PASS');
  assert.equal(statusOf(res, 'ua.userAgentData'), 'PASS');
  assert.ok(res.libraries.every((l) => l.status === 'INFO'));
  assert.equal(res.summary.overall, 'PASS', JSON.stringify(res.summary));
});

test('real-device expectation in headless: touch-host signals are SKIPPED, not FAIL (desktop host has no touch screen)', () => {
  const o = desktopObserved(EXPECT_WIN); // a desktop host: no TouchEvent, pointer fine, hover hover
  const res = evaluate(o, EXPECT_REAL, 'headless');
  for (const id of ['touch.maxTouchPoints', 'touch.ontouchstart', 'touch.TouchEvent', 'touch.createEvent', 'media.anyPointerCoarse', 'css.anyPointerCoarse']) {
    assert.equal(statusOf(res, id), 'SKIPPED', id);
    assert.match(byId(res, id).note, /real touch device/);
  }
  assert.deepEqual(res.summary.hardFail, []);
  assert.equal(res.summary.overall, 'PASS');
  // on device the same values must fail: the phone really has a touch screen
  const dev = evaluate(o, EXPECT_REAL, 'device');
  assert.equal(statusOf(dev, 'touch.createEvent'), 'FAIL');
  assert.equal(statusOf(dev, 'css.anyPointerCoarse'), 'FAIL');
  assert.equal(dev.summary.overall, 'FAIL');
  // real-device on the phone: a touch pointerType is the truth and only INFO
  const phoneDev = evaluate(phoneObserved(), EXPECT_REAL, 'device');
  assert.equal(statusOf(phoneDev, 'interactive.pointerType'), 'INFO');
  assert.equal(statusOf(phoneDev, 'interactive.pointerEvents'), 'PASS');
  // a desktop expectation is never relaxed: the phone fixture still fails in headless
  const phone = evaluate(phoneObserved(), EXPECT_WIN, 'headless');
  assert.equal(statusOf(phone, 'touch.createEvent'), 'FAIL');
});

test('no expectations (persona "none"): nothing is PASS/FAIL except structural checks', () => {
  const res = evaluate(desktopObserved(), null, 'headless');
  assert.equal(res.summary.overall, 'PASS');
  for (const id of ['ua.userAgent', 'screen.width', 'intl.timeZone', 'geo.position', 'media.deviceWidthVsScreen']) assert.equal(statusOf(res, id), 'INFO', id);
  for (const id of ['server.clientHintsFirst', 'contexts.sameOriginFrame', 'contexts.dataFrame', 'contexts.crossRealmToString', 'misc.toStringNative', 'media.serialization', 'layout.chromeGeometry']) assert.equal(statusOf(res, id), 'PASS', id);
  assert.ok(res.libraries.every((l) => l.status === 'INFO'));
});

test('missing probes: structural signals are SKIPPED not PASS; library bundle errors FAIL', () => {
  const res = evaluate({ libraries: [{ name: 'detectors.bundle', version: null, mobile: null, error: 'not loaded' }] }, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'misc.toStringNative'), 'SKIPPED');
  assert.equal(statusOf(res, 'server.clientHintsFirst'), 'SKIPPED');
  assert.equal(statusOf(res, 'contexts.sameOriginFrame'), 'SKIPPED');
  assert.equal(statusOf(res, 'contexts.crossRealmToString'), 'SKIPPED');
  assert.equal(statusOf(res, 'media.serialization'), 'SKIPPED');
  assert.equal(statusOf(res, 'layout.chromeGeometry'), 'SKIPPED');
  assert.equal(statusOf(res, 'ua.userAgent'), 'FAIL');
  assert.equal(byId(res, 'ua.userAgent').note, 'not measured');
  assert.deepEqual(res.summary.libraryFail, ['detectors.bundle']);
  assert.equal(res.summary.overall, 'FAIL');
});

test('client hints after Accept-CH are a hard FAIL when present', () => {
  const o = desktopObserved();
  o.server.second.headers['sec-ch-ua-mobile'] = '?0';
  o.server.second.headers['Viewport-Width'] = '1920';
  const res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'server.clientHintsFirst'), 'PASS');
  assert.equal(statusOf(res, 'server.clientHintsAfterAcceptCH'), 'FAIL');
  assert.equal(statusOf(res, 'server.viewportHints'), 'FAIL');
  assert.deepEqual(byId(res, 'server.viewportHints').actual, { 'viewport-width': '1920' });
});

test('geolocation tolerance and failure modes', () => {
  const o = desktopObserved();
  o.geo = { status: 'ok', latitude: 40.7128 + 0.005, longitude: -74.006, accuracy: 50, permission: 'granted' };
  assert.equal(statusOf(evaluate(o, EXPECT_WIN, 'headless'), 'geo.position'), 'PASS');
  o.geo = { status: 'ok', latitude: 41.5, longitude: -74.006, accuracy: 50, permission: 'granted' };
  assert.equal(statusOf(evaluate(o, EXPECT_WIN, 'headless'), 'geo.position'), 'FAIL');
  o.geo = { status: 'timeout', permission: 'prompt' };
  const res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'geo.position'), 'FAIL');
  assert.equal(statusOf(res, 'geo.permission'), 'FAIL');
  assert.equal(byId(res, 'geo.permission').tier, 'soft');
});

test('webgl without a context is SKIPPED, with a wrong renderer FAIL', () => {
  const o = desktopObserved();
  o.webgl = { available: false };
  let res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'webgl.renderer'), 'SKIPPED');
  assert.equal(res.summary.overall, 'PASS');
  o.webgl = { available: true, unmaskedVendor: 'Mesa', unmaskedRenderer: 'llvmpipe (LLVM 18.1.8, 256 bits)' };
  res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'webgl.renderer'), 'FAIL');
  assert.equal(res.summary.overall, 'FAIL');
});

test('tzShim makes the worker time-zone signal soft', () => {
  const o = desktopObserved();
  o.contexts.worker.snapshot.timeZone = 'Etc/UTC';
  let res = evaluate(o, EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'contexts.worker.timeZone'), 'FAIL');
  assert.equal(byId(res, 'contexts.worker.timeZone').tier, 'hard');
  res = evaluate(o, { ...EXPECT_WIN, tzShim: true }, 'headless');
  assert.equal(statusOf(res, 'contexts.worker.timeZone'), 'FAIL');
  assert.equal(byId(res, 'contexts.worker.timeZone').tier, 'soft');
  assert.equal(res.summary.overall, 'PASS');
});

test('cross-origin frame is SKIPPED when the host provides no second origin', () => {
  const res = evaluate(desktopObserved(EXPECT_WIN, { crossOrigin: false }), EXPECT_WIN, 'headless');
  assert.equal(statusOf(res, 'contexts.crossOriginFrame'), 'SKIPPED');
  assert.equal(res.summary.overall, 'PASS');
});

test('a throwing check is reported as FAIL instead of crashing the evaluation', () => {
  const res = evaluate({ ua: null, contexts: { top: 1 } }, EXPECT_WIN, 'headless');
  assert.ok(res.signals.length === SIGNALS.length);
  assert.equal(res.summary.overall, 'FAIL');
});

test('buildResult produces schema v1 and formatTable renders every row', () => {
  const result = buildResult({ persona: 'win-firefox__us-east', env: 'headless', observed: desktopObserved(), expected: EXPECT_WIN, startedAt: '2026-10-18T10:00:00.000Z', finishedAt: '2026-10-18T10:00:05.000Z', url: 'http://127.0.0.1/index.html' });
  assert.equal(result.schema, 1);
  assert.equal(result.durationMs, 5000);
  assert.equal(result.summary.overall, 'PASS');
  assert.equal(result.signals.length, SIGNALS.length);
  const table = formatTable(result);
  const lines = table.split('\n');
  assert.equal(lines.length, 2 + SIGNALS.length + result.libraries.length + 1);
  assert.ok(lines[0].includes('overall=PASS'));
  assert.ok(lines.some((l) => l.includes('ua.userAgent')));
  assert.ok(JSON.stringify(result).length > 1000);
});

test('summarize counts', () => {
  const sig = [
    { id: 'a', status: 'FAIL', tier: 'hard', scope: 'both' },
    { id: 'b', status: 'FAIL', tier: 'soft', scope: 'both' },
    { id: 'c', status: 'SKIPPED', tier: 'hard', scope: 'device' },
    { id: 'd', status: 'PASS', tier: 'hard', scope: 'both' },
    { id: 'e', status: 'INFO', tier: 'soft', scope: 'both' },
  ];
  const s = summarize(sig, [{ name: 'x', status: 'PASS' }, { name: 'y', status: 'FAIL' }], 'headless');
  assert.deepEqual({ ...s }, { overall: 'FAIL', pass: 1, fail: 2, skipped: 1, info: 1, hardFail: ['a'], softFail: ['b'], deviceSkipped: ['c'], libraryFail: ['y'], libraryTotal: 2, libraryPass: 1 });
  assert.equal(summarize([sig[3]], [], 'device').overall, 'PASS');
  assert.equal(summarize([sig[2]], [], 'device').overall, 'FAIL');
  assert.equal(summarize([sig[2]], [], 'headless').overall, 'PASS');
});
