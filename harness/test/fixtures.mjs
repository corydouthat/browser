/* Fixture navigators for evaluate.test.mjs: a coherent desktop persona, a real phone and partial spoofs. */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const EXPECT_WIN = JSON.parse(readFileSync(join(HERE, '..', 'personas', 'win-firefox__us-east.json'), 'utf8'));
export const EXPECT_CHROME = JSON.parse(readFileSync(join(HERE, '..', 'personas', 'win-chrome__uk.json'), 'utf8'));
export const EXPECT_REAL = JSON.parse(readFileSync(join(HERE, '..', 'personas', 'real-device__device.json'), 'utf8'));

const PHONE_UA = 'Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0';

function snapshot(o) {
  return {
    userAgent: o.ua.userAgent, platform: o.ua.platform, oscpu: o.ua.oscpu, appVersion: o.ua.appVersion,
    languages: o.intl.languages, hardwareConcurrency: o.hardwareConcurrency, maxTouchPoints: o.touch.maxTouchPoints,
    timeZone: o.intl.timeZone, locale: o.intl.locale, screenWidth: o.screen.width, screenHeight: o.screen.height, dpr: o.screen.dpr,
    ontouchstart: o.touch.ontouchstartWindow, touchEvent: o.touch.touchEventDefined, dateOffset: -o.intl.dateOffsetMinutes,
    pointerFine: o.media.pointerFine,
  };
}

const REF_TEXT = 'function onLine() {\n    [native code]\n}';
function nativeChecks(names) {
  return names.map((name) => ({ name, native: true, text: REF_TEXT.replace('onLine', name.replace(/^get .*\./, '')), expected: REF_TEXT.replace('onLine', name.replace(/^get .*\./, '')) }));
}

/** The cross-realm toString probe result: every direction prints native. */
export function crossRealmNative(native = true) {
  const names = ['frame toString on top Navigator.hardwareConcurrency', 'top toString on frame Navigator.hardwareConcurrency', 'frame toString on top Screen.width', 'frame toString on top matchMedia'];
  return {
    referenceText: REF_TEXT, frameReferenceText: REF_TEXT,
    checks: names.map((name) => ({ name, text: native ? REF_TEXT.replace('onLine', name.split('.').pop().replace(/.* /, '')) : '[name]() {\n      var ov;\n      if (og) ov = og.call(this);', expected: REF_TEXT.replace('onLine', name.split('.').pop().replace(/.* /, '')), native })),
  };
}

/** What probes/media.js deviceFeatures() reports when the JS device-* features agree with screen.*. */
export function deviceFeatures(screen, dpr, agree = true) {
  return {
    screenWidth: screen.width, screenHeight: screen.height, dpr,
    deviceWidthMatches: agree, deviceHeightMatches: agree, deviceAspectRatioMatches: agree, resolutionMatches: agree, webkitDprMatches: agree,
    jsDeviceWidth: agree ? screen.width : 412,
  };
}

export const SERIALIZATION_OK = {
  js: [{ query: '(pointer:fine)', media: '(pointer: fine)' }, { query: '(POINTER: FINE)', media: '(pointer: fine)' }, { query: '(  any-pointer : coarse )', media: '(any-pointer: coarse)' }, { query: '(hover:hover) and (min-width:100px)', media: '(hover: hover) and (min-width: 100px)' }, { query: '(pointer: fine)', media: '(pointer: fine)' }],
  cssom: { '(pointer:fine)': '(pointer: fine)', '(POINTER: FINE)': '(pointer: fine)', '(  any-pointer : coarse )': '(any-pointer: coarse)', '(hover:hover) and (min-width:100px)': '(hover: hover) and (min-width: 100px)', '(pointer: fine)': '(pointer: fine)' },
};

export function libraryVerdicts(mobile, { uaOnly = false } = {}) {
  const ua = ['ua-parser-js', 'mobile-detect', 'bowser', 'platform', 'react-device-detect', 'device-detector-js'];
  const feature = ['ismobilejs', 'is-mobile', 'detect-it', 'modernizr'];
  return [
    ...ua.map((name) => ({ name, version: '1.0.0', mobile: uaOnly ? false : mobile, detail: {} })),
    ...feature.map((name) => ({ name, version: '1.0.0', mobile, detail: {} })),
  ];
}

/** An observation fully consistent with a desktop expectation file. */
export function desktopObserved(e = EXPECT_WIN, { crossOrigin = true } = {}) {
  const chromeLike = !!e.chromeLike;
  const o = {
    ua: {
      userAgent: e.userAgent, platform: e.platform, oscpu: e.oscpu, appVersion: e.appVersion, vendor: chromeLike ? 'Google Inc.' : '',
      productSub: '20100101', webdriver: true,
      userAgentData: chromeLike ? { brands: e.uaData.brands.map((b) => ({ ...b })), mobile: false, platform: e.uaData.platform } : null,
    },
    touch: {
      maxTouchPoints: 0, ontouchstartWindow: false, ontouchstartElement: false, touchEventDefined: false, createEventTouch: false,
      orientationInWindow: false, pointerEventDefined: true,
    },
    media: { pointerFine: true, hoverHover: true, anyPointerCoarse: false, anyHoverHover: true, orientationPortrait: false, device: deviceFeatures(e.screen, e.screen.dpr), serialization: SERIALIZATION_OK },
    css: { pointerFine: true, hoverHover: true, anyPointerCoarse: false, anyHoverHover: true, bp992: true, bp1024: true, bp1200: true, bp1280: true, minDeviceWidth1024: true, maxDeviceWidth480: false, minResolution2: e.screen.dpr >= 2, orientationPortrait: false, stylesheetLoaded: true },
    layout: { clientWidth: 1920, innerWidth: 1920, innerHeight: 994, outerWidth: 1920, outerHeight: 1080, screenX: 0, screenY: 0, mozInnerScreenX: 0, mozInnerScreenY: 86 },
    screen: { ...e.screen, pixelDepth: e.screen.colorDepth, orientationType: 'landscape-primary', orientationAngle: 0 },
    hardwareConcurrency: e.hardwareConcurrency,
    intl: { languages: [...e.languages], language: e.languages[0], timeZone: e.timeZone, locale: e.locale, dateOffsetMinutes: -300, intlOffsetMinutes: -300 },
    geo: { status: 'ok', latitude: e.geolocation.latitude + 0.00001, longitude: e.geolocation.longitude - 0.00001, accuracy: e.geolocation.accuracyMeters, permission: 'granted' },
    webgl: { available: true, vendor: 'Mozilla', renderer: 'Mozilla', unmaskedVendor: e.webgl.vendor, unmaskedRenderer: e.webgl.renderer },
    misc: {
      pluginsLength: 5, pluginNames: ['PDF Viewer'], pdfViewerEnabled: true, deviceMemory: null,
      toString: { checks: nativeChecks(['get Navigator.prototype.userAgent', 'window.matchMedia', 'Date']), allNative: true },
      descriptors: { navigatorOwnProps: [], screenOwnProps: [], missingAccessors: [], getterOwnShape: ['length', 'name'], windowSymbolProps: [], windowHatchMarker: false },
    },
    server: {
      first: { headers: { 'user-agent': e.userAgent, 'accept-language': e.acceptLanguage, accept: '*/*' } },
      second: { headers: { 'user-agent': e.userAgent, 'accept-language': e.acceptLanguage, accept: '*/*' } },
    },
    interactive: { pointerType: null, mozInputSource: null, trusted: null, events: [] },
    libraries: libraryVerdicts(false),
  };
  const snap = snapshot(o);
  const workerSnap = { userAgent: snap.userAgent, platform: snap.platform, languages: [...snap.languages], hardwareConcurrency: snap.hardwareConcurrency, timeZone: snap.timeZone };
  o.contexts = {
    top: snap,
    sameOrigin: { snapshot: { ...snap } },
    crossOrigin: crossOrigin ? { snapshot: { ...snap } } : { skipped: true, reason: 'window.__hatchCrossOrigin not set by the host' },
    aboutBlank: { snapshot: { ...snap } },
    srcdoc: { snapshot: { ...snap } },
    data: { snapshot: { ...snap } },
    blob: { snapshot: { ...snap } },
    sandbox: { snapshot: { ...snap } },
    worker: { snapshot: workerSnap },
    sharedWorker: { snapshot: { ...workerSnap } },
    crossRealm: crossRealmNative(true),
  };
  return o;
}

/** A real Android phone running Firefox 158 with nothing spoofed. */
export function phoneObserved() {
  const o = {
    ua: { userAgent: PHONE_UA, platform: 'Linux armv81', oscpu: 'Linux armv81', appVersion: '5.0 (Android 15)', vendor: '', webdriver: false, userAgentData: null },
    touch: { maxTouchPoints: 5, ontouchstartWindow: true, ontouchstartElement: true, touchEventDefined: true, createEventTouch: true, orientationInWindow: true, pointerEventDefined: true },
    media: { pointerFine: false, hoverHover: false, anyPointerCoarse: true, anyHoverHover: false, orientationPortrait: true, device: deviceFeatures({ width: 412, height: 915 }, 2.625), serialization: SERIALIZATION_OK },
    css: { pointerFine: false, hoverHover: false, anyPointerCoarse: true, anyHoverHover: false, bp992: false, bp1024: false, bp1200: false, bp1280: false, minDeviceWidth1024: false, maxDeviceWidth480: true, minResolution2: true, orientationPortrait: true, stylesheetLoaded: true },
    layout: { clientWidth: 412, innerWidth: 412, innerHeight: 830, outerWidth: 412, outerHeight: 915, screenX: 0, screenY: 0, mozInnerScreenX: 0, mozInnerScreenY: 85 },
    screen: { width: 412, height: 915, availWidth: 412, availHeight: 915, colorDepth: 24, pixelDepth: 24, dpr: 2.625, orientationType: 'portrait-primary', orientationAngle: 0 },
    hardwareConcurrency: 8,
    intl: { languages: ['en-US', 'en'], language: 'en-US', timeZone: 'America/Los_Angeles', locale: 'en-US', dateOffsetMinutes: -420, intlOffsetMinutes: -420 },
    geo: { status: 'error', code: 1, message: 'User denied geolocation prompt', permission: 'prompt' },
    webgl: { available: true, unmaskedVendor: 'Qualcomm', unmaskedRenderer: 'Adreno (TM) 750' },
    misc: {
      pluginsLength: 0, pluginNames: [], pdfViewerEnabled: false, deviceMemory: null,
      toString: { checks: nativeChecks(['get Navigator.prototype.userAgent']), allNative: true },
      descriptors: { navigatorOwnProps: [], screenOwnProps: [], missingAccessors: [], getterOwnShape: ['length', 'name'], windowSymbolProps: [], windowHatchMarker: false },
    },
    server: {
      first: { headers: { 'user-agent': PHONE_UA, 'accept-language': 'en-US,en;q=0.5' } },
      second: { headers: { 'user-agent': PHONE_UA, 'accept-language': 'en-US,en;q=0.5' } },
    },
    interactive: { pointerType: 'touch', mozInputSource: 5, trusted: true, events: [] },
    libraries: libraryVerdicts(true),
  };
  const snap = snapshot(o);
  o.contexts = {
    top: snap, sameOrigin: { snapshot: { ...snap } }, crossOrigin: { snapshot: { ...snap } }, aboutBlank: { snapshot: { ...snap } }, srcdoc: { snapshot: { ...snap } },
    data: { snapshot: { ...snap } }, blob: { snapshot: { ...snap } }, sandbox: { snapshot: { ...snap } },
    worker: { snapshot: { ...snap } }, sharedWorker: { snapshot: { ...snap } }, crossRealm: crossRealmNative(true),
  };
  return o;
}

/** "Request desktop site" style spoof: UA string + header only, everything else is still the phone. */
export function uaOnlySpoofObserved(e = EXPECT_WIN) {
  const o = phoneObserved();
  o.ua = { ...o.ua, userAgent: e.userAgent };
  o.server.first.headers['user-agent'] = e.userAgent;
  o.server.second.headers['user-agent'] = e.userAgent;
  o.libraries = libraryVerdicts(true, { uaOnly: true });
  o.contexts.top.userAgent = e.userAgent;
  for (const k of ['sameOrigin', 'crossOrigin', 'aboutBlank', 'srcdoc', 'data', 'blob', 'sandbox', 'worker', 'sharedWorker']) o.contexts[k].snapshot.userAgent = e.userAgent;
  return o;
}

/** A JS-only spoof: the top document is patched, iframes/worker are not, and the patched getters leak. */
export function topOnlySpoofObserved(e = EXPECT_WIN) {
  const good = desktopObserved(e);
  const phone = phoneObserved();
  good.contexts.aboutBlank = { snapshot: snapshot(phone) };
  good.contexts.data = { snapshot: snapshot(phone) };
  good.contexts.blob = { snapshot: snapshot(phone) };
  good.contexts.sandbox = { snapshot: snapshot(phone) };
  good.contexts.crossRealm = crossRealmNative(false);
  good.contexts.worker = { snapshot: { userAgent: PHONE_UA, platform: 'Linux armv81', languages: ['en-US', 'en'], hardwareConcurrency: 8, timeZone: 'America/Los_Angeles' } };
  good.contexts.sharedWorker = { snapshot: { userAgent: PHONE_UA, platform: 'Linux armv81', languages: ['en-US', 'en'], hardwareConcurrency: 8, timeZone: 'America/Los_Angeles' } };
  good.misc.toString = { checks: [{ name: 'get Navigator.prototype.userAgent', native: false, text: 'function () { return CFG.userAgent; }' }], allNative: false };
  good.misc.descriptors = { navigatorOwnProps: ['userAgent'], screenOwnProps: [], missingAccessors: [], getterOwnShape: ['name', 'length'], windowSymbolProps: ['Symbol(hatch.persona)'], windowHatchMarker: true };
  good.css = { ...good.css, pointerFine: false, hoverHover: false, anyPointerCoarse: true };
  good.media.device = deviceFeatures(e.screen, e.screen.dpr, false);
  good.media.serialization = { ...SERIALIZATION_OK, js: SERIALIZATION_OK.js.map((x) => ({ ...x, media: x.query })) };
  return good;
}
