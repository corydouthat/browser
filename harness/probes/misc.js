/*
 * Fingerprint tells: plugins, pdfViewer, toString of patched functions (exact text, compared with a
 * never-shimmed reference getter of the same realm), descriptor shape, marker symbols.
 */
export const id = 'misc';

function getter(proto, name) {
  try {
    const d = Object.getOwnPropertyDescriptor(proto, name);
    return d && d.get ? d.get : null;
  } catch (e) { return null; }
}

function method(obj, name) {
  try { return obj && typeof obj[name] === 'function' ? obj[name] : null; } catch (e) { return null; }
}

/** The exact text SpiderMonkey prints for a native function called `printed` (derived from Navigator.prototype.onLine). */
function referenceText() {
  try {
    const ref = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine') || Object.getOwnPropertyDescriptor(Navigator.prototype, 'cookieEnabled');
    if (!ref || !ref.get) return null;
    return { text: Function.prototype.toString.call(ref.get), name: ref.get.name.replace(/^get /, '') };
  } catch (e) { return null; }
}
const REF = referenceText();
const NATIVE_FALLBACK_RE = /^function [^(]*\(\) \{\s*\[native code\]\s*\}$/;

/**
 * `printed` is the bare name a native prints (getters print without the "get " prefix although fn.name
 * carries it). `expectedFnName` is what fn.name must be ("get <name>" for getters).
 */
function toStringCheck(label, fn, printed, expectedFnName) {
  if (!fn) return { name: label, native: null, missing: true };
  let text; let threw = null;
  try { text = Function.prototype.toString.call(fn); } catch (e) { threw = String(e && e.message); text = ''; }
  const expected = REF ? REF.text.replace(REF.name, printed) : null;
  const exact = expected !== null ? text === expected : NATIVE_FALLBACK_RE.test(text);
  const nameOk = expectedFnName === undefined || fn.name === expectedFnName;
  const native = !threw && exact && nameOk && !/hatch|__PERSONA__|CFG\b/.test(text);
  return { name: label, native, text: text.length > 90 ? `${text.slice(0, 87)}...` : text, expected, fnName: fn.name, fnLength: fn.length, ...(threw ? { threw } : {}) };
}

export async function probe() {
  const w = window;
  const n = navigator;
  const NP = w.Navigator && w.Navigator.prototype;
  const SP = w.Screen && w.Screen.prototype;
  const checks = [];
  const getters = [['Navigator', NP, ['userAgent', 'platform', 'oscpu', 'appVersion', 'maxTouchPoints', 'hardwareConcurrency', 'languages', 'language', 'plugins', 'mimeTypes', 'pdfViewerEnabled', 'vendor', 'geolocation', 'userAgentData', 'webdriver']],
    ['Screen', SP, ['width', 'height', 'availWidth', 'availHeight', 'colorDepth', 'pixelDepth', 'orientation']],
    ['MediaQueryList', w.MediaQueryList && w.MediaQueryList.prototype, ['media', 'matches']],
    ['ScreenOrientation', w.ScreenOrientation && w.ScreenOrientation.prototype, ['type', 'angle']],
    ['GeolocationCoordinates', w.GeolocationCoordinates && w.GeolocationCoordinates.prototype, ['latitude', 'longitude', 'accuracy']],
    ['GeolocationPosition', w.GeolocationPosition && w.GeolocationPosition.prototype, ['coords', 'timestamp']]];
  for (const [owner, proto, names] of getters) {
    if (!proto) continue;
    for (const name of names) {
      const g = getter(proto, name);
      if (g) checks.push(toStringCheck(`get ${owner}.prototype.${name}`, g, name, `get ${name}`));
    }
  }
  const eventGetters = [['PointerEvent', w.PointerEvent && w.PointerEvent.prototype, ['pointerType', 'width', 'height', 'pressure']], ['MouseEvent', w.MouseEvent && w.MouseEvent.prototype, ['mozInputSource']]];
  for (const [owner, proto, names] of eventGetters) {
    if (!proto) continue;
    for (const name of names) {
      const g = getter(proto, name);
      if (g) checks.push(toStringCheck(`get ${owner}.prototype.${name}`, g, name, `get ${name}`));
    }
  }
  for (const name of ['devicePixelRatio', 'outerWidth', 'outerHeight']) {
    const d = Object.getOwnPropertyDescriptor(w, name) || (w.Window && Object.getOwnPropertyDescriptor(w.Window.prototype, name));
    if (d && d.get) checks.push(toStringCheck(`get window.${name}`, d.get, name, `get ${name}`));
  }
  const methods = [
    ['window.matchMedia', method(w, 'matchMedia'), 'matchMedia'],
    ['Geolocation.prototype.getCurrentPosition', method(w.Geolocation && w.Geolocation.prototype, 'getCurrentPosition'), 'getCurrentPosition'],
    ['Geolocation.prototype.watchPosition', method(w.Geolocation && w.Geolocation.prototype, 'watchPosition'), 'watchPosition'],
    ['Geolocation.prototype.clearWatch', method(w.Geolocation && w.Geolocation.prototype, 'clearWatch'), 'clearWatch'],
    ['WebGLRenderingContext.prototype.getParameter', method(w.WebGLRenderingContext && w.WebGLRenderingContext.prototype, 'getParameter'), 'getParameter'],
    ['WebGLRenderingContext.prototype.getExtension', method(w.WebGLRenderingContext && w.WebGLRenderingContext.prototype, 'getExtension'), 'getExtension'],
    ['WebGL2RenderingContext.prototype.getParameter', method(w.WebGL2RenderingContext && w.WebGL2RenderingContext.prototype, 'getParameter'), 'getParameter'],
    ['Permissions.prototype.query', method(w.Permissions && w.Permissions.prototype, 'query'), 'query'],
    ['Document.prototype.createEvent', method(w.Document && w.Document.prototype, 'createEvent'), 'createEvent'],
    ['Date', w.Date, 'Date'],
    ['Date.now', method(w.Date, 'now'), 'now'],
    ['Date.prototype.getTimezoneOffset', method(w.Date && w.Date.prototype, 'getTimezoneOffset'), 'getTimezoneOffset'],
    ['Date.prototype.toString', method(w.Date && w.Date.prototype, 'toString'), 'toString'],
    ['Date.prototype.getHours', method(w.Date && w.Date.prototype, 'getHours'), 'getHours'],
    ['Intl.DateTimeFormat', w.Intl && w.Intl.DateTimeFormat, 'DateTimeFormat'],
    ['Function.prototype.toString', Function.prototype.toString, 'toString'],
    ['Object.getOwnPropertyDescriptor', Object.getOwnPropertyDescriptor, 'getOwnPropertyDescriptor'],
    ['NavigatorUAData.prototype.getHighEntropyValues', method(w.NavigatorUAData && w.NavigatorUAData.prototype, 'getHighEntropyValues'), 'getHighEntropyValues'],
  ];
  for (const [label, fn, printed] of methods) if (fn) checks.push(toStringCheck(label, fn, printed, printed));
  // own-property shape of a getter nothing has inspected yet (SpiderMonkey resolves length/name lazily, for
  // natives and script functions alike: fresh -> ['length', 'name'], after `.name` was read -> ['name', 'length']).
  // Screen.prototype.availTop is shimmed when a screen is configured and is not in the toString list above.
  const shapeGetter = getter(SP, 'availTop') || getter(NP, 'cookieEnabled');
  const ownShape = shapeGetter ? Object.getOwnPropertyNames(shapeGetter) : null;
  const prototypeOfToString = (() => { try { return Function.prototype.toString.call(Function.prototype.toString.toString); } catch (e) { return `error: ${e && e.message}`; } })();

  const ownNames = (obj) => { try { return Object.getOwnPropertyNames(obj); } catch (e) { return [`error: ${e && e.message}`]; } };
  const missingAccessors = [];
  for (const name of ['userAgent', 'platform', 'maxTouchPoints', 'hardwareConcurrency', 'languages']) {
    if (!NP || !getter(NP, name)) missingAccessors.push(`Navigator.prototype.${name}`);
  }
  for (const name of ['width', 'height', 'availHeight']) {
    if (!SP || !getter(SP, name)) missingAccessors.push(`Screen.prototype.${name}`);
  }
  let pluginNames = [];
  let pluginsLength = null;
  let mimeTypesLength = null;
  try {
    pluginsLength = n.plugins.length;
    pluginNames = Array.from(n.plugins, (p) => p.name);
    mimeTypesLength = n.mimeTypes.length;
  } catch (e) { pluginsLength = `error: ${e && e.message}`; }

  return {
    misc: {
      pluginsLength,
      pluginNames,
      mimeTypesLength,
      pdfViewerEnabled: 'pdfViewerEnabled' in n ? n.pdfViewerEnabled : null,
      deviceMemory: 'deviceMemory' in n ? n.deviceMemory : null,
      connection: 'connection' in n ? 'present' : null,
      doNotTrack: 'doNotTrack' in n ? n.doNotTrack : null,
      cookieEnabled: n.cookieEnabled,
      toString: { reference: REF, checks, prototypeOfToString, allNative: checks.every((c) => c.native === true) },
      descriptors: {
        navigatorOwnProps: ownNames(n),
        screenOwnProps: ownNames(w.screen),
        navigatorProtoKeys: NP ? ownNames(NP).length : null,
        missingAccessors,
        getterOwnShape: ownShape,
        navigatorSymbolProps: (() => { try { return Object.getOwnPropertySymbols(n).map(String); } catch (e) { return []; } })(),
        windowSymbolProps: (() => { try { return Object.getOwnPropertySymbols(w).map(String); } catch (e) { return []; } })(),
        windowHatchMarker: (() => { try { return Object.prototype.hasOwnProperty.call(w, Symbol.for('hatch.persona')); } catch (e) { return null; } })(),
      },
    },
  };
}
