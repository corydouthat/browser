// Node test for shim/persona-shim.js. Run: node --test app/src/main/assets/extension/test/*.test.mjs
// Loads the shim into a fresh vm realm with a fake window/navigator/screen so the
// core overrides can be asserted without a browser. NOT shipped in the APK.
import { readFileSync, statSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import { makeRealm } from './fake-env.mjs';

const SHIM_PATH = new URL('../shim/persona-shim.js', import.meta.url);
const SHIM = readFileSync(SHIM_PATH, 'utf8');

function inject(ctx, cfg) {
  const code = SHIM.replaceAll('__PERSONA__', JSON.stringify(cfg));
  const t0 = performance.now();
  vm.runInContext(code, ctx, { filename: 'persona-shim.js' });
  return performance.now() - t0;
}

const BASE = {
  enabled: true, desktop: true, touch: false,
  userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0',
  platform: 'Win32', oscpu: 'Windows NT 10.0; Win64; x64', appVersion: '5.0 (Windows)',
  layoutWidth: 1366,
  screen: { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, dpr: 1 },
  hardwareConcurrency: 12,
  webgl: { vendor: 'Google Inc. (NVIDIA)', renderer: 'ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)' },
  chromeLike: false, uaData: null,
  languages: ['en-US', 'en'], locale: 'en-US',
  timeZone: 'America/New_York', tzShim: false, tzOffsets: null,
  geolocation: { latitude: 40.7128, longitude: -74.006, accuracyMeters: 50 },
};

const UA_DATA = {
  brands: [{ brand: 'Chromium', version: '131' }, { brand: 'Not_A Brand', version: '24' }, { brand: 'Google Chrome', version: '131' }],
  platform: 'Windows', platformVersion: '15.0.0', architecture: 'x86', bitness: '64', model: '', mobile: false,
};

const NATIVE_RX = (name) => new RegExp('^function ' + name.replace(/[[\]]/g, '\\$&') + '\\(\\) \\{\\s*\\[native code\\]\\s*\\}$');

function run(code, ctx) { return vm.runInContext(code, ctx); }
function json(code, ctx) { return JSON.parse(vm.runInContext(`JSON.stringify(${code})`, ctx)); }
const TYPE_ERROR = { name: 'TypeError' }; // realm-independent

test('shim file is within the 30 KB budget', () => {
  assert.ok(statSync(SHIM_PATH).size <= 30 * 1024, `size ${statSync(SHIM_PATH).size}`);
});

test('disabled persona changes nothing', () => {
  const ctx = makeRealm();
  const before = run('Function.prototype.toString', ctx);
  inject(ctx, { ...BASE, enabled: false });
  assert.equal(run('navigator.maxTouchPoints', ctx), 5);
  assert.equal(run('Function.prototype.toString', ctx), before);
  assert.equal(run("'ontouchstart' in window", ctx), true);
});

test('desktop persona removes touch surface', () => {
  const ctx = makeRealm();
  const ms = inject(ctx, BASE);
  assert.ok(ms < 20, `shim took ${ms.toFixed(2)} ms`);
  assert.equal(run('navigator.maxTouchPoints', ctx), 0);
  assert.equal(run("'ontouchstart' in window", ctx), false);
  assert.equal(run("'ontouchend' in document", ctx), false, 'Document.prototype still has ontouchend');
  assert.equal(run("'ontouchstart' in HTMLElement.prototype", ctx), false);
  assert.equal(run("'ontouchmove' in SVGElement.prototype", ctx), false);
  assert.equal(run("typeof TouchEvent", ctx), 'undefined');
  assert.equal(run("typeof Touch", ctx), 'undefined');
  assert.equal(run("typeof TouchList", ctx), 'undefined');
  assert.equal(run("'orientation' in window", ctx), false);
  assert.equal(run("'onorientationchange' in window", ctx), false);
  assert.equal(run('screen.orientation.type', ctx), 'landscape-primary');
  assert.equal(run('screen.orientation.angle', ctx), 0);
  assert.equal(run("'vibrate' in navigator", ctx), false);
  assert.equal(run("'share' in navigator", ctx), false);
  assert.equal(run("'canShare' in navigator", ctx), false);
  assert.equal(run('navigator.pdfViewerEnabled', ctx), true);
});

test('matchMedia forces pointer/hover and delegates the rest', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  const cases = {
    '(pointer: coarse)': false, '(pointer: fine)': true, '(pointer: none)': false, '(pointer)': true,
    '(hover: hover)': true, '(hover: none)': false, '(hover)': true,
    '(any-pointer: coarse)': false, '(any-pointer: fine)': true, '(any-hover: none)': false, '(any-hover: hover)': true,
    '(min-width: 1000px)': true, '(min-width: 2000px)': false,
    '(pointer: coarse) and (min-width: 1000px)': false,
    '(hover: hover) and (pointer: fine) and (min-width: 1000px)': true,
    'not all and (pointer: coarse)': true,
    '(HOVER: NONE)': false,
    '(orientation: portrait)': true,
  };
  for (const [q, expected] of Object.entries(cases)) {
    assert.equal(run(`matchMedia(${JSON.stringify(q)}).matches`, ctx), expected, q);
  }
  // .media is the engine's own serialisation of the original query (the fake engine trims + lowercases)
  assert.equal(run("matchMedia('(pointer: coarse)').media", ctx), '(pointer: coarse)');
  assert.equal(run("matchMedia('  (POINTER: FINE) ').media", ctx), '(pointer: fine)');
  assert.equal(run("matchMedia('(min-width: 1000px)').media", ctx), '(min-width: 1000px)');
  assert.equal(run("matchMedia('(pointer: coarse)') instanceof MediaQueryList", ctx), true);
  assert.equal(run("Object.getOwnPropertyNames(matchMedia('(pointer: coarse)')).length", ctx), 0);
});

test('screen, devicePixelRatio and outer sizes follow the spec', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  assert.deepEqual(json('[screen.width, screen.height, screen.availWidth, screen.availHeight, screen.colorDepth, screen.pixelDepth, screen.availLeft]', ctx), [1920, 1080, 1920, 1040, 24, 24, 0]);
  assert.equal(run('devicePixelRatio', ctx), 1);
  assert.equal(run('outerWidth', ctx), 1366);
  assert.equal(run('outerHeight', ctx), 3030 + 86, 'outerHeight = innerHeight + (mozInnerScreenY - screenY)');
  assert.equal(run('Object.getOwnPropertyNames(screen).length', ctx), 0);
  assert.equal(run("Object.getOwnPropertyDescriptor(Screen.prototype, 'width').enumerable", ctx), true);
  assert.equal(run("Object.getOwnPropertyDescriptor(Screen.prototype, 'width').configurable", ctx), true);
});

test('navigator scalars: hardwareConcurrency, UA/platform only when they differ, languages', () => {
  const ctx = makeRealm();
  const origUA = run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent').get", ctx);
  const origLang = run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'languages').get", ctx);
  // make the fake engine already report the persona UA (prefs did their job)
  run(`__state.ua = ${JSON.stringify(BASE.userAgent)}`, ctx);
  inject(ctx, BASE);
  assert.equal(run('navigator.hardwareConcurrency', ctx), 12);
  assert.equal(run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent').get", ctx), origUA, 'UA getter must stay native when values match');
  assert.notEqual(run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'platform').get", ctx), undefined);
  assert.equal(run('navigator.platform', ctx), 'Win32');
  assert.equal(run('navigator.oscpu', ctx), 'Windows NT 10.0; Win64; x64');
  assert.equal(run('navigator.appVersion', ctx), '5.0 (Windows)');
  assert.equal(run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'languages').get", ctx), origLang, 'languages getter must stay native when values match');

  const ctx2 = makeRealm();
  inject(ctx2, { ...BASE, languages: ['de-DE', 'de', 'en'], locale: 'de-DE' });
  assert.deepEqual(json('Array.from(navigator.languages)', ctx2), ['de-DE', 'de', 'en']);
  assert.equal(run('navigator.language', ctx2), 'de-DE');
  assert.equal(run('Object.isFrozen(navigator.languages)', ctx2), true);
  assert.equal(run('navigator.userAgent', ctx2), BASE.userAgent);
});

test('userAgentData exists only for chromeLike personas with uaData', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  assert.equal(run("'userAgentData' in navigator", ctx), false);
  assert.equal(run("typeof NavigatorUAData", ctx), 'undefined');
});

test('userAgentData: brands, mobile, platform, getHighEntropyValues, toJSON', async () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, chromeLike: true, uaData: UA_DATA, userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36', oscpu: null });
  assert.equal(run('navigator.userAgentData instanceof NavigatorUAData', ctx), true);
  assert.equal(run('navigator.userAgentData === navigator.userAgentData', ctx), true);
  assert.equal(run('navigator.userAgentData.mobile', ctx), false);
  assert.equal(run('navigator.userAgentData.platform', ctx), 'Windows');
  assert.deepEqual(json('navigator.userAgentData.brands', ctx), UA_DATA.brands);
  assert.equal(run('Object.isFrozen(navigator.userAgentData.brands)', ctx), true);
  assert.deepEqual(json('navigator.userAgentData', ctx), { brands: UA_DATA.brands, mobile: false, platform: 'Windows' });
  assert.equal(run('Object.getOwnPropertyNames(navigator.userAgentData).length', ctx), 0);
  assert.equal(run('Object.prototype.toString.call(navigator.userAgentData)', ctx), '[object NavigatorUAData]');
  const hi = await run("navigator.userAgentData.getHighEntropyValues(['platformVersion', 'architecture', 'bitness', 'uaFullVersion', 'fullVersionList', 'bogus'])", ctx);
  assert.equal(hi.platformVersion, '15.0.0');
  assert.equal(hi.architecture, 'x86');
  assert.equal(hi.bitness, '64');
  assert.equal(hi.uaFullVersion, '131.0.0.0');
  assert.equal(hi.fullVersionList.length, 3);
  assert.equal(hi.fullVersionList[0].version, '131.0.0.0');
  assert.equal('bogus' in hi, false);
  assert.equal('model' in hi, false);
  assert.equal(hi.mobile, false);
  await assert.rejects(run('navigator.userAgentData.getHighEntropyValues(5)', ctx), TYPE_ERROR);
  assert.throws(() => run('new NavigatorUAData()', ctx), TYPE_ERROR);
  assert.throws(() => run("Object.getOwnPropertyDescriptor(NavigatorUAData.prototype, 'brands').get.call({})", ctx), TYPE_ERROR);
  assert.equal(run("Object.getOwnPropertyDescriptor(window, 'NavigatorUAData').enumerable", ctx), false);
  // oscpu is null in the config: Gecko's value is left alone
  assert.equal(run('navigator.oscpu', ctx), 'Linux armv81');
});

test('chromeLike plugins are synthesised only when Gecko has none', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, chromeLike: true, uaData: UA_DATA });
  assert.equal(run('navigator.plugins.length', ctx), 5);
  assert.equal(run('navigator.plugins instanceof PluginArray', ctx), true);
  assert.equal(run('navigator.plugins[0].name', ctx), 'PDF Viewer');
  assert.equal(run("navigator.plugins['Chrome PDF Viewer'].name", ctx), 'Chrome PDF Viewer');
  assert.equal(run("navigator.plugins.namedItem('WebKit built-in PDF').filename", ctx), 'internal-pdf-viewer');
  assert.equal(run('navigator.plugins.item(4).name', ctx), 'WebKit built-in PDF');
  assert.equal(run('navigator.plugins.item(9)', ctx), null);
  assert.equal(run('Array.from(navigator.plugins).length', ctx), 5);
  assert.equal(run('navigator.plugins[0] instanceof Plugin', ctx), true);
  assert.equal(run('navigator.plugins[0].length', ctx), 2);
  assert.equal(run('navigator.plugins[0][0].type', ctx), 'application/pdf');
  assert.equal(run('navigator.mimeTypes.length', ctx), 2);
  assert.equal(run("navigator.mimeTypes['application/pdf'].enabledPlugin === navigator.plugins[0]", ctx), true);
  assert.equal(run('navigator.mimeTypes[1].type', ctx), 'text/pdf');
  assert.equal(run('navigator.pdfViewerEnabled', ctx), true);
  assert.deepEqual(json('Object.keys(navigator.plugins)', ctx), ['0', '1', '2', '3', '4']);
});

test('WebGL unmasked vendor/renderer are spoofed after the extension is fetched', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  assert.equal(run('__gl.getParameter(0x9245)', ctx), null, 'not enabled yet');
  assert.equal(run("__gl.getExtension('WEBGL_debug_renderer_info') instanceof WEBGL_debug_renderer_info", ctx), true);
  assert.equal(run('__gl.getParameter(0x9245)', ctx), BASE.webgl.vendor);
  assert.equal(run('__gl.getParameter(0x9246)', ctx), BASE.webgl.renderer);
  assert.equal(run('__gl.getParameter(0x1F00)', ctx), 'Mozilla');
  assert.ok(run("__gl.getSupportedExtensions().includes('WEBGL_debug_renderer_info')", ctx));
  assert.throws(() => run('WebGLRenderingContext.prototype.getParameter.call({}, 0x9245)', ctx), TYPE_ERROR);
  assert.match(run('WebGLRenderingContext.prototype.getParameter.toString()', ctx), NATIVE_RX('getParameter'));
});

test('geolocation returns jittered positions shaped like GeolocationPosition', async () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  const pos = await new Promise((resolve, reject) => {
    run('(ok, err) => navigator.geolocation.getCurrentPosition(ok, err)', ctx)(resolve, reject);
  });
  assert.equal(run('(p) => p instanceof GeolocationPosition', ctx)(pos), true);
  assert.equal(run('(p) => p.coords instanceof GeolocationCoordinates', ctx)(pos), true);
  assert.equal(Object.getOwnPropertyNames(pos).length, 0, 'no own properties on the position');
  assert.equal(Object.getOwnPropertyNames(pos.coords).length, 0, 'no own properties on coords');
  const dLat = Math.abs(pos.coords.latitude - BASE.geolocation.latitude) * 111320;
  const dLon = Math.abs(pos.coords.longitude - BASE.geolocation.longitude) * 111320 * Math.cos(BASE.geolocation.latitude * Math.PI / 180);
  assert.ok(Math.hypot(dLat, dLon) <= 3.01, `jitter ${Math.hypot(dLat, dLon)} m`);
  assert.equal(pos.coords.accuracy, 50);
  assert.equal(pos.coords.altitude, null);
  assert.equal(pos.coords.heading, null);
  assert.equal(pos.coords.speed, null);
  assert.ok(typeof pos.timestamp === 'number' && Date.now() - pos.timestamp < 5000);
  const json = pos.toJSON();
  assert.equal(json.coords.latitude, pos.coords.latitude);
  assert.equal(json.coords.accuracy, 50);
  assert.throws(() => run('navigator.geolocation.getCurrentPosition(42)', ctx), TYPE_ERROR);
  assert.throws(() => run("Geolocation.prototype.getCurrentPosition.call({}, function(){})", ctx), TYPE_ERROR);
  const id = run('navigator.geolocation.watchPosition(function () {})', ctx);
  assert.equal(typeof id, 'number');
  run(`navigator.geolocation.clearWatch(${id})`, ctx);
  const state = await run("navigator.permissions.query({ name: 'geolocation' }).then(s => JSON.stringify([s.state, s.name, s instanceof PermissionStatus]))", ctx);
  assert.equal(state, JSON.stringify(['granted', 'geolocation', true]));
  const other = await run("navigator.permissions.query({ name: 'camera' }).then(s => s.state)", ctx);
  assert.equal(other, 'prompt');
});

test('every shimmed function prints as native (bare name, no get/set prefix) and keeps descriptor shape', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, chromeLike: true, uaData: UA_DATA, tzShim: true });
  const template = run('Function.prototype.toString.call(Array.prototype.push)', ctx);
  assert.match(template, NATIVE_RX('push'));
  // SpiderMonkey prints a native getter as "function onLine() {...}" although fn.name is "get onLine" (RT-01).
  const checks = [
    ["Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get", 'get maxTouchPoints', 'maxTouchPoints'],
    ["Object.getOwnPropertyDescriptor(Navigator.prototype, 'platform').get", 'get platform', 'platform'],
    ["Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency').get", 'get hardwareConcurrency', 'hardwareConcurrency'],
    ["Object.getOwnPropertyDescriptor(Screen.prototype, 'width').get", 'get width', 'width'],
    ["Object.getOwnPropertyDescriptor(window, 'devicePixelRatio').get", 'get devicePixelRatio', 'devicePixelRatio'],
    ["Object.getOwnPropertyDescriptor(window, 'outerHeight').get", 'get outerHeight', 'outerHeight'],
    ["Object.getOwnPropertyDescriptor(MediaQueryList.prototype, 'media').get", 'get media', 'media'],
    ["Object.getOwnPropertyDescriptor(PointerEvent.prototype, 'pointerType').get", 'get pointerType', 'pointerType'],
    ["Object.getOwnPropertyDescriptor(GeolocationCoordinates.prototype, 'latitude').get", 'get latitude', 'latitude'],
    ["Object.getOwnPropertyDescriptor(PermissionStatus.prototype, 'state').get", 'get state', 'state'],
    ['window.matchMedia', 'matchMedia', 'matchMedia'],
    ['Function.prototype.toString', 'toString', 'toString'],
    ['Geolocation.prototype.getCurrentPosition', 'getCurrentPosition', 'getCurrentPosition'],
    ['Geolocation.prototype.clearWatch', 'clearWatch', 'clearWatch'],
    ['Permissions.prototype.query', 'query', 'query'],
    ['WebGLRenderingContext.prototype.getExtension', 'getExtension', 'getExtension'],
    ['Date.prototype.getTimezoneOffset', 'getTimezoneOffset', 'getTimezoneOffset'],
    ['Date.prototype.setHours', 'setHours', 'setHours'],
    ['Date', 'Date', 'Date'],
    ['Intl.DateTimeFormat', 'DateTimeFormat', 'DateTimeFormat'],
    ['NavigatorUAData', 'NavigatorUAData', 'NavigatorUAData'],
    ['NavigatorUAData.prototype.getHighEntropyValues', 'getHighEntropyValues', 'getHighEntropyValues'],
  ];
  for (const [expr, name, printed] of checks) {
    const src = run(`(${expr}).toString()`, ctx);
    assert.match(src, NATIVE_RX(printed), `${expr} -> ${src}`);
    assert.equal(run(`Function.prototype.toString.call(${expr})`, ctx), template.replace('push', printed), expr);
    assert.equal(run(`String(${expr})`, ctx), template.replace('push', printed), expr);
    assert.equal(run(`(${expr}).name`, ctx), name, expr);
    assert.ok(!/get \w+\(/.test(src), `${expr} must not print the accessor prefix: ${src}`);
  }
  // native arity survives (declared parameters, never assigned)
  assert.equal(run('Date.length', ctx), 7);
  assert.equal(run('Intl.DateTimeFormat.length', ctx), 0);
  assert.equal(run('Date.prototype.setHours.length', ctx), 4);
  assert.equal(run('Date.prototype.setFullYear.length', ctx), 3);
  assert.equal(run('window.matchMedia.length', ctx), 1);
  assert.equal(run('Geolocation.prototype.getCurrentPosition.length', ctx), 1);
  assert.equal(run("Object.getOwnPropertyDescriptor(Screen.prototype, 'width').get.length", ctx), 0);
  // getters are not constructors and have no .prototype, like native getters
  assert.equal(run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get.hasOwnProperty('prototype')", ctx), false);
  assert.throws(() => run("new (Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get)()", ctx), TYPE_ERROR);
  // brand check still enforced
  assert.throws(() => run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get.call({})", ctx), TYPE_ERROR);
  // descriptor shape
  const d = json("(() => { const d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints'); return [typeof d.get, d.set === undefined, d.enumerable, d.configurable]; })()", ctx);
  assert.deepEqual(d, ['function', true, true, true]);
  assert.equal(run("Object.getOwnPropertyDescriptor(Function.prototype, 'toString').enumerable", ctx), false);
  assert.equal(run("Object.getOwnPropertyDescriptor(window, 'matchMedia').enumerable", ctx), true);
  assert.equal(run('Object.getOwnPropertyNames(navigator).length', ctx), 0);
  // user functions still print their source
  assert.equal(run('(function foo(a) { return a; }).toString()', ctx), 'function foo(a) { return a; }');
  assert.equal(run("Function.prototype.toString.call(class K {})", ctx), 'class K {}');
  assert.throws(() => run('Function.prototype.toString.call({})', ctx), TYPE_ERROR);
});

test('no page-visible marker; a second injection re-wraps but stays coherent (RT-07)', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  assert.equal(run('Object.getOwnPropertySymbols(window).length', ctx), 0, 'no own symbols on window');
  assert.equal(run("Symbol.for('hatch.persona') in window", ctx), false);
  inject(ctx, BASE);
  assert.equal(run('Object.getOwnPropertySymbols(window).length', ctx), 0);
  assert.equal(run('navigator.maxTouchPoints', ctx), 0);
  assert.equal(run('screen.width', ctx), 1920);
  assert.equal(run("matchMedia('(pointer: coarse)').matches", ctx), false);
  const template = run('Function.prototype.toString.call(Array.prototype.push)', ctx);
  assert.equal(run("Function.prototype.toString.call(Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get)", ctx), template.replace('push', 'maxTouchPoints'));
  assert.equal(run('Function.prototype.toString.call(Function.prototype.toString)', ctx), template.replace('push', 'toString'));
  assert.throws(() => run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get.call({})", ctx), TYPE_ERROR);
});

test('shimmed functions have native own-property shape and laziness (RT-08)', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, chromeLike: true, uaData: UA_DATA, tzShim: true });
  // a fresh (never inspected) getter: [length, name] like a native, not [name, length]
  for (const expr of [
    "Object.getOwnPropertyDescriptor(Screen.prototype, 'height').get",
    "Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints').get",
    "Object.getOwnPropertyDescriptor(window, 'outerWidth').get",
    'Geolocation.prototype.watchPosition',
    'Date.prototype.getHours',
    'window.matchMedia',
  ]) {
    assert.deepEqual(json(`Object.getOwnPropertyNames(${expr})`, ctx), ['length', 'name'], expr);
    const d = json(`[Object.getOwnPropertyDescriptor(${expr}, 'name'), Object.getOwnPropertyDescriptor(${expr}, 'length')].map(x => [x.writable, x.enumerable, x.configurable])`, ctx);
    assert.deepEqual(d, [[false, false, true], [false, false, true]], expr);
    assert.equal(run(`Object.getOwnPropertySymbols(${expr}).length`, ctx), 0, expr);
  }
  assert.equal(run("Object.getOwnPropertyDescriptor(Screen.prototype, 'height').get.hasOwnProperty('prototype')", ctx), false);
  // constructors keep prototype like natives
  assert.deepEqual(json('Object.getOwnPropertyNames(Date).slice(0, 3)', ctx), ['length', 'name', 'prototype']);
});

test('cross-realm Function.prototype.toString prints native for shimmed functions in both directions (RT-02)', () => {
  const a = makeRealm();
  const b = makeRealm();
  inject(a, BASE);
  inject(b, BASE);
  const template = run('Function.prototype.toString.call(Array.prototype.push)', a);
  const getterA = run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency').get", a);
  const getterB = run("Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency').get", b);
  const tsA = run('Function.prototype.toString', a);
  const tsB = run('Function.prototype.toString', b);
  assert.equal(tsB.call(getterA), template.replace('push', 'hardwareConcurrency'), 'other realm toString on our getter');
  assert.equal(tsA.call(getterB), template.replace('push', 'hardwareConcurrency'), 'our toString on the other realm getter');
  assert.equal(tsB.call(run('window.matchMedia', a)), template.replace('push', 'matchMedia'));
  assert.equal(tsA.call(run('Function.prototype.toString', b)), template.replace('push', 'toString'));
  // an unshimmed realm's toString still prints the (page) source of ordinary functions and natives of its own
  const c = makeRealm();
  const tsC = run('Function.prototype.toString', c);
  assert.equal(tsA.call(run('(function foo() { return 1; })', c)), 'function foo() { return 1; }');
  assert.equal(tsA.call(run('Array.prototype.push', c)), template.replace('push', 'push'));
  assert.equal(tsC.call(run('(function bar() { return 2; })', a)), 'function bar() { return 2; }');
  // and a page function whose prototype was replaced by a cross-realm function prints its own source
  assert.equal(run('(() => { const f = function baz() {}; Object.setPrototypeOf(f, b_fn); return Function.prototype.toString.call(f); })()', Object.assign(a, { b_fn: run('(function q() {})', b) })), 'function baz() {}');
  assert.throws(() => tsA.call({}), TYPE_ERROR);
});

test('matchMedia evaluates device-width/height/aspect-ratio/resolution/device-pixel-ratio from the persona screen (RT-04)', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, screen: { ...BASE.screen, dpr: 2 } });
  const cases = {
    '(device-width: 1920px)': true, '(device-width: 1366px)': false, '(min-device-width: 1024px)': true, '(max-device-width: 480px)': false,
    '(max-device-width: 1920px)': true, '(MIN-DEVICE-WIDTH: 1921px)': false, '(device-width)': true,
    '(device-height: 1080px)': true, '(min-device-height: 1081px)': false, '(max-device-height: 1080px)': true,
    '(device-aspect-ratio: 16/9)': true, '(device-aspect-ratio: 1920/1080)': true, '(device-aspect-ratio: 4/3)': false,
    '(min-device-aspect-ratio: 1/1)': true, '(max-device-aspect-ratio: 1/1)': false,
    '(resolution: 2dppx)': true, '(resolution: 192dpi)': true, '(resolution: 2x)': true, '(resolution: 1dppx)': false,
    '(min-resolution: 1.5dppx)': true, '(min-resolution: 2.5dppx)': false, '(max-resolution: 2dppx)': true, '(max-resolution: 96dpi)': false,
    '(-webkit-device-pixel-ratio: 2)': true, '(-webkit-min-device-pixel-ratio: 2)': true, '(-webkit-min-device-pixel-ratio: 3)': false,
    '(-webkit-max-device-pixel-ratio: 1.5)': false, '(-moz-device-pixel-ratio: 2)': true, '(-webkit-device-pixel-ratio)': true,
    '(pointer: fine) and (min-device-width: 1200px)': true, '(pointer: coarse), (max-device-width: 600px)': false,
    'not (max-device-width: 600px)': true,
  };
  for (const [q, expected] of Object.entries(cases)) assert.equal(run(`matchMedia(${JSON.stringify(q)}).matches`, ctx), expected, q);
  // .media stays the engine serialisation of what the page asked for
  assert.equal(run("matchMedia('(device-width: 1920px)').media", ctx), '(device-width: 1920px)');
  // unparseable values are left to the engine (invalid for the fake: false), not forced
  assert.equal(run("matchMedia('(device-width: 10em)').matches", ctx), false);
  // no screen in the persona: device-* queries are untouched
  const ctx2 = makeRealm();
  inject(ctx2, { ...BASE, screen: null });
  assert.equal(run("matchMedia('(device-width: 412px)').matches", ctx2), false, 'fake engine default');
  assert.equal(run("matchMedia('(pointer: fine)').matches", ctx2), true, 'pointer still forced');
});

test('trusted touch-origin pointer/mouse events report a mouse; synthetic events are untouched (HATCH-WC-02)', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  const trusted = run("__mkEvent('pointer', { type: 'pointerdown', pointerType: 'touch', width: 23, height: 23, pressure: 0.5, buttons: 1, mozInputSource: 5, pointerId: 2 }, true)", ctx);
  assert.equal(trusted.pointerType, 'mouse');
  assert.equal(trusted.width, 1);
  assert.equal(trusted.height, 1);
  assert.equal(trusted.pressure, 0.5);
  assert.equal(trusted.mozInputSource, 1);
  const up = run("__mkEvent('pointer', { type: 'pointerup', pointerType: 'touch', width: 23, height: 23, pressure: 0, buttons: 0, mozInputSource: 5 }, true)", ctx);
  assert.equal(up.pressure, 0);
  const mouse = run("__mkEvent('mouse', { type: 'click', mozInputSource: 5, buttons: 0 }, true)", ctx);
  assert.equal(mouse.mozInputSource, 1);
  const pen = run("__mkEvent('pointer', { type: 'pointerdown', pointerType: 'pen', width: 1, height: 1, pressure: 0.3, mozInputSource: 2 }, true)", ctx);
  assert.equal(pen.pointerType, 'pen');
  assert.equal(pen.pressure, 0.3);
  assert.equal(pen.mozInputSource, 2);
  const synthetic = run("__mkEvent('pointer', { type: 'pointerdown', pointerType: 'touch', width: 40, height: 40, pressure: 1, mozInputSource: 5 }, false)", ctx);
  assert.equal(synthetic.pointerType, 'touch', 'new PointerEvent({pointerType: "touch"}) keeps its value');
  assert.equal(synthetic.width, 40);
  assert.equal(synthetic.mozInputSource, 5);
  assert.throws(() => run("Object.getOwnPropertyDescriptor(PointerEvent.prototype, 'pointerType').get.call({})", ctx), TYPE_ERROR);
  // real-device persona: nothing masked
  const ctx2 = makeRealm();
  inject(ctx2, { ...BASE, desktop: false, touch: true });
  assert.equal(run("__mkEvent('pointer', { type: 'pointerdown', pointerType: 'touch', mozInputSource: 5 }, true).pointerType", ctx2), 'touch');
});

test('geolocation callbacks run as the timer callback itself (no shim frame on the stack) (RT-06)', async () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  const stack = await new Promise((resolve) => {
    run('(ok) => navigator.geolocation.getCurrentPosition(ok)', ctx)(() => resolve(new Error().stack));
  });
  assert.ok(!/persona-shim\.js/.test(stack), `shim frames on the callback stack:\n${stack}`);
  // watchPosition fires repeatedly with fresh positions and clearWatch stops it
  const seen = [];
  const id = run('(ok) => navigator.geolocation.watchPosition(ok)', ctx)((p) => seen.push(p.coords.latitude));
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(seen.length, 1);
  run(`navigator.geolocation.clearWatch(${id})`, ctx);
});

test('outer sizes follow the engine chrome geometry (RT-09)', () => {
  const ctx = makeRealm();
  inject(ctx, BASE);
  assert.equal(run('outerHeight - innerHeight', ctx), run('mozInnerScreenY - screenY', ctx));
  assert.equal(run('outerWidth - innerWidth', ctx), 0);
  assert.ok(run('outerHeight >= innerHeight && outerWidth >= innerWidth', ctx));
});

test('real-device style persona (touch) keeps touch and pointer answers', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, desktop: false, touch: true, screen: null, geolocation: null, webgl: null, hardwareConcurrency: null, userAgent: null, platform: null, oscpu: null, appVersion: null });
  assert.equal(run('navigator.maxTouchPoints', ctx), 5);
  assert.equal(run("'ontouchstart' in window", ctx), true);
  assert.equal(run("matchMedia('(pointer: coarse)').matches", ctx), true);
  assert.equal(run('screen.width', ctx), 412);
  assert.equal(run("'vibrate' in navigator", ctx), true);
});

test('timezone shim: offsets, local getters, toString, Intl and local-component constructor', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, tzShim: true, timeZone: 'Europe/Berlin', tzOffsets: null });
  const jan = 'new Date(Date.UTC(2026, 0, 15, 12, 0, 0))';
  const jul = 'new Date(Date.UTC(2026, 6, 15, 12, 0, 0))';
  assert.equal(run(`${jan}.getTimezoneOffset()`, ctx), -60);
  assert.equal(run(`${jul}.getTimezoneOffset()`, ctx), -120);
  assert.equal(run(`${jan}.getHours()`, ctx), 13);
  assert.equal(run(`${jul}.getHours()`, ctx), 14);
  assert.equal(run(`${jan}.getDate()`, ctx), 15);
  assert.equal(run('new Date(Date.UTC(2026, 0, 15, 23, 30, 0)).getDate()', ctx), 16, 'day rolls over in the fake zone');
  assert.equal(run(`${jan}.toString()`, ctx), 'Thu Jan 15 2026 13:00:00 GMT+0100 (Central European Standard Time)');
  assert.equal(run(`${jul}.toTimeString()`, ctx), '14:00:00 GMT+0200 (Central European Summer Time)');
  assert.equal(run(`${jan}.toDateString()`, ctx), 'Thu Jan 15 2026');
  assert.equal(run("Intl.DateTimeFormat().resolvedOptions().timeZone", ctx), 'Europe/Berlin');
  assert.equal(run("new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Tokyo' }).resolvedOptions().timeZone", ctx), 'Asia/Tokyo', 'explicit timeZone wins');
  assert.equal(run("Intl.DateTimeFormat.prototype.constructor === Intl.DateTimeFormat", ctx), true);
  assert.equal(run("new Intl.DateTimeFormat() instanceof Intl.DateTimeFormat", ctx), true);
  assert.equal(run("typeof Intl.DateTimeFormat.supportedLocalesOf", ctx), 'function');
  assert.match(run(`${jan}.toLocaleTimeString('en-US', { hour12: false })`, ctx), /^13:00:00$/);
  assert.match(run(`${jan}.toLocaleString('en-US', { timeZone: 'UTC', hour12: false })`, ctx), /12:00:00/);
  assert.equal(run('new Date(2026, 6, 1, 12, 0, 0).getTime()', ctx), Date.UTC(2026, 6, 1, 10, 0, 0));
  assert.equal(run('new Date(2026, 0, 1, 12, 0, 0).getTime()', ctx), Date.UTC(2026, 0, 1, 11, 0, 0));
  assert.equal(run('new Date(2026, 0, 1, 12, 0, 0).getHours()', ctx), 12);
  assert.equal(run('new Date(1700000000000).getTime()', ctx), 1700000000000);
  assert.equal(run('Date.prototype.constructor === Date', ctx), true);
  assert.equal(run('new Date() instanceof Date', ctx), true);
  assert.equal(run('typeof Date.now() === "number" && typeof Date.UTC === "function" && typeof Date.parse === "function"', ctx), true);
  assert.match(run('Date()', ctx), /GMT[+-]\d{4} \(Central European/);
  assert.equal(run('Date.length', ctx), 7);
  assert.equal(run('new Date(NaN).toString()', ctx), 'Invalid Date');
  assert.ok(Number.isNaN(run('new Date(NaN).getTimezoneOffset()', ctx)));
  // setters round-trip through the fake zone
  assert.equal(run(`(() => { const d = ${jan}; d.setHours(0, 0, 0, 0); return d.getTime(); })()`, ctx), Date.UTC(2026, 0, 14, 23, 0, 0));
  assert.throws(() => run('Date.prototype.getHours.call({})', ctx), TYPE_ERROR);
});

test('timezone shim honours a precomputed tzOffsets table', () => {
  const ctx = makeRealm();
  inject(ctx, { ...BASE, tzShim: true, timeZone: 'Asia/Tokyo', tzOffsets: [{ fromEpochMs: 0, offsetMinutes: 540 }] });
  assert.equal(run('new Date(Date.UTC(2026, 0, 15, 12, 0, 0)).getTimezoneOffset()', ctx), -540);
  assert.equal(run('new Date(Date.UTC(2026, 0, 15, 12, 0, 0)).getHours()', ctx), 21);
  assert.equal(run('new Date(Date.UTC(2026, 0, 15, 12, 0, 0)).toString()', ctx), 'Thu Jan 15 2026 21:00:00 GMT+0900 (Japan Standard Time)');
  const ctx2 = makeRealm();
  inject(ctx2, { ...BASE, tzShim: true, timeZone: 'America/New_York', tzOffsets: [
    { fromEpochMs: Date.UTC(2025, 10, 2, 6), offsetMinutes: -300 },
    { fromEpochMs: Date.UTC(2026, 2, 8, 7), offsetMinutes: -240 },
    { fromEpochMs: Date.UTC(2026, 10, 1, 6), offsetMinutes: -300 },
  ] });
  assert.equal(run('new Date(Date.UTC(2026, 0, 15, 12)).getTimezoneOffset()', ctx2), 300);
  assert.equal(run('new Date(Date.UTC(2026, 6, 15, 12)).getTimezoneOffset()', ctx2), 240);
  assert.equal(run('new Date(Date.UTC(2026, 6, 15, 12)).getHours()', ctx2), 8);
  assert.equal(run('new Date(Date.UTC(2026, 6, 15, 12)).toTimeString()', ctx2), '08:00:00 GMT-0400 (Eastern Daylight Time)');
});

test('tzShim=false leaves Date alone', () => {
  const ctx = makeRealm();
  const before = run('[Date, Date.prototype.getTimezoneOffset, Intl.DateTimeFormat]', ctx);
  inject(ctx, BASE);
  const after = run('[Date, Date.prototype.getTimezoneOffset, Intl.DateTimeFormat]', ctx);
  for (let i = 0; i < before.length; i++) assert.equal(after[i], before[i]);
});
