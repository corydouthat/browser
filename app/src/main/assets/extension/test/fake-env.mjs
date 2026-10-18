// Fake browser environment shared by the extension node tests (shim.test.mjs, shim-bench.test.mjs).
// Built inside the vm realm so every intrinsic belongs to it. NOT shipped in the APK (assets/extension/test is
// excluded by app/build.gradle.kts ignoreAssetsPatterns).
import vm from 'node:vm';

// Fake browser environment, built inside the realm so all intrinsics belong to it.
export const ENV = String.raw`
(function () {
  const g = globalThis;
  g.window = g;
  const brand = new WeakMap(); // instance -> interface name
  function iface(name, parentProto) {
    const C = function () { throw new TypeError('Illegal constructor.'); };
    Object.defineProperty(C, 'name', { value: name });
    if (parentProto) Object.setPrototypeOf(C.prototype, parentProto);
    Object.defineProperty(C.prototype, Symbol.toStringTag, { value: name, configurable: true });
    Object.defineProperty(g, name, { value: C, writable: true, enumerable: false, configurable: true });
    return C;
  }
  function instance(C, data) {
    const o = Object.create(C.prototype);
    brand.set(o, data || {});
    return o;
  }
  function check(o, C) {
    if (!brand.has(o) || !(o instanceof C)) throw new TypeError("'" + C.name + "' called on an object that does not implement interface " + C.name + '.');
    return brand.get(o);
  }
  function attr(C, name, impl) {
    Object.defineProperty(C.prototype, name, {
      get: function () { return impl.call(this, check(this, C)); }, set: undefined, enumerable: true, configurable: true,
    });
  }
  function op(C, name, impl, len) {
    const f = function () { return impl.apply(this, [check(this, C), ...arguments]); };
    Object.defineProperty(f, 'name', { value: name });
    Object.defineProperty(f, 'length', { value: len || 0 });
    Object.defineProperty(C.prototype, name, { value: f, writable: true, enumerable: true, configurable: true });
  }
  function winAttr(name, impl) {
    Object.defineProperty(g, name, { get: function () { return impl(); }, set: undefined, enumerable: true, configurable: true });
  }
  function winHandler(name) {
    let v = null;
    Object.defineProperty(g, name, { get: () => v, set: (x) => { v = x; }, enumerable: true, configurable: true });
  }

  g.__state = {
    ua: 'Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0',
    platform: 'Linux armv81', oscpu: 'Linux armv81', appVersion: '5.0 (Android 15)',
    languages: Object.freeze(['en-US', 'en']), maxTouchPoints: 5, hardwareConcurrency: 8,
    screen: { width: 412, height: 915, availWidth: 412, availHeight: 915, colorDepth: 24 },
    dpr: 2.625, innerWidth: 1366, innerHeight: 3030, orientation: 'portrait-primary',
    pluginsLength: 0,
  };
  const st = g.__state;

  // Window / Document / HTMLElement with touch handlers
  const Window = iface('Window');
  const Document = iface('Document');
  const HTMLElement = iface('HTMLElement');
  const SVGElement = iface('SVGElement');
  ['ontouchstart', 'ontouchmove', 'ontouchend', 'ontouchcancel'].forEach((n) => {
    winHandler(n);
    [Document, HTMLElement, SVGElement].forEach((C) => {
      Object.defineProperty(C.prototype, n, { get() { return null; }, set() {}, enumerable: true, configurable: true });
    });
  });
  g.document = instance(Document);
  winHandler('onorientationchange');
  winAttr('orientation', () => 0);
  iface('TouchEvent'); iface('Touch'); iface('TouchList');
  winAttr('innerWidth', () => st.innerWidth);
  winAttr('innerHeight', () => st.innerHeight);
  winAttr('outerWidth', () => st.innerWidth);
  winAttr('outerHeight', () => st.innerHeight);
  winAttr('devicePixelRatio', () => st.dpr);
  // Gecko window geometry: the content area starts 86 CSS px below the window's top edge (headless 158).
  winAttr('screenX', () => 0);
  winAttr('screenY', () => 0);
  winAttr('mozInnerScreenX', () => 0);
  winAttr('mozInnerScreenY', () => 86);

  // Events: trusted pointer/mouse events from a finger carry pointerType 'touch' / mozInputSource 5.
  const Event = iface('Event');
  const UIEvent = iface('UIEvent', Event.prototype);
  const MouseEvent = iface('MouseEvent', UIEvent.prototype);
  const PointerEvent = iface('PointerEvent', MouseEvent.prototype);
  attr(Event, 'type', (d) => d.type);
  attr(Event, 'target', (d) => d.target);
  attr(MouseEvent, 'mozInputSource', (d) => d.mozInputSource);
  attr(MouseEvent, 'buttons', (d) => d.buttons);
  ['pointerType', 'width', 'height', 'pressure', 'pointerId'].forEach((k) => attr(PointerEvent, k, (d) => d[k]));
  // isTrusted is an own unforgeable property on every event instance (WebIDL [LegacyUnforgeable]).
  g.__mkEvent = function (kind, data, trusted) {
    const o = instance(kind === 'pointer' ? PointerEvent : MouseEvent, data);
    Object.defineProperty(o, 'isTrusted', { value: trusted === true, writable: false, enumerable: true, configurable: false });
    return o;
  };

  // Navigator
  const Navigator = iface('Navigator');
  attr(Navigator, 'onLine', () => true); // never shimmed: the toString reference
  attr(Navigator, 'userAgent', () => st.ua);
  attr(Navigator, 'platform', () => st.platform);
  attr(Navigator, 'oscpu', () => st.oscpu);
  attr(Navigator, 'appVersion', () => st.appVersion);
  attr(Navigator, 'language', () => st.languages[0]);
  attr(Navigator, 'languages', () => st.languages);
  attr(Navigator, 'maxTouchPoints', () => st.maxTouchPoints);
  attr(Navigator, 'hardwareConcurrency', () => st.hardwareConcurrency);
  attr(Navigator, 'pdfViewerEnabled', () => st.pluginsLength > 0);
  op(Navigator, 'vibrate', () => true, 1);
  op(Navigator, 'share', () => Promise.resolve(), 0);
  op(Navigator, 'canShare', () => true, 0);

  // Plugins (pdf.js disabled -> empty, like Gecko with pdfjs.disabled=true)
  const PluginArray = iface('PluginArray'); const Plugin = iface('Plugin');
  const MimeTypeArray = iface('MimeTypeArray'); const MimeType = iface('MimeType');
  attr(PluginArray, 'length', () => 0); op(PluginArray, 'item', () => null, 1); op(PluginArray, 'namedItem', () => null, 1); op(PluginArray, 'refresh', () => undefined, 0);
  Object.defineProperty(PluginArray.prototype, Symbol.iterator, { value: function () { check(this, PluginArray); return [][Symbol.iterator](); }, writable: true, configurable: true });
  attr(MimeTypeArray, 'length', () => 0); op(MimeTypeArray, 'item', () => null, 1); op(MimeTypeArray, 'namedItem', () => null, 1);
  Object.defineProperty(MimeTypeArray.prototype, Symbol.iterator, { value: function () { check(this, MimeTypeArray); return [][Symbol.iterator](); }, writable: true, configurable: true });
  attr(Plugin, 'name', (d) => d.name); attr(Plugin, 'filename', (d) => d.filename); attr(Plugin, 'description', (d) => d.description); attr(Plugin, 'length', () => 0);
  op(Plugin, 'item', () => null, 1); op(Plugin, 'namedItem', () => null, 1);
  Object.defineProperty(Plugin.prototype, Symbol.iterator, { value: function () { check(this, Plugin); return [][Symbol.iterator](); }, writable: true, configurable: true });
  attr(MimeType, 'type', (d) => d.type); attr(MimeType, 'suffixes', (d) => d.suffixes); attr(MimeType, 'description', (d) => d.description); attr(MimeType, 'enabledPlugin', (d) => d.enabledPlugin);
  const plugins = instance(PluginArray); const mimeTypes = instance(MimeTypeArray);
  attr(Navigator, 'plugins', () => plugins);
  attr(Navigator, 'mimeTypes', () => mimeTypes);

  // Geolocation + Permissions
  const Geolocation = iface('Geolocation');
  const GeolocationPosition = iface('GeolocationPosition');
  const GeolocationCoordinates = iface('GeolocationCoordinates');
  ['latitude', 'longitude', 'altitude', 'accuracy', 'altitudeAccuracy', 'heading', 'speed'].forEach((k) => attr(GeolocationCoordinates, k, (d) => d[k]));
  op(GeolocationCoordinates, 'toJSON', (d) => ({ ...d }), 0);
  attr(GeolocationPosition, 'coords', (d) => d.coords); attr(GeolocationPosition, 'timestamp', (d) => d.timestamp);
  op(GeolocationPosition, 'toJSON', (d) => ({ ...d }), 0);
  op(Geolocation, 'getCurrentPosition', (d, ok, err) => { if (typeof ok !== 'function') throw new TypeError('Geolocation.getCurrentPosition: Argument 1 is not callable.'); if (err) setTimeout(() => err({ code: 1 }), 0); }, 1);
  op(Geolocation, 'watchPosition', (d, ok) => { if (typeof ok !== 'function') throw new TypeError('not callable'); return 7; }, 1);
  op(Geolocation, 'clearWatch', () => undefined, 1);
  const geolocation = instance(Geolocation);
  attr(Navigator, 'geolocation', () => geolocation);
  const Permissions = iface('Permissions'); const PermissionStatus = iface('PermissionStatus');
  attr(PermissionStatus, 'state', (d) => d.state); attr(PermissionStatus, 'name', (d) => d.name);
  op(Permissions, 'query', (d, desc) => Promise.resolve(instance(PermissionStatus, { name: desc.name, state: 'prompt' })), 1);
  const permissions = instance(Permissions);
  attr(Navigator, 'permissions', () => permissions);

  g.navigator = instance(Navigator);

  // Screen + orientation
  const Screen = iface('Screen'); const ScreenOrientation = iface('ScreenOrientation');
  ['width', 'height', 'availWidth', 'availHeight', 'colorDepth'].forEach((k) => attr(Screen, k, () => st.screen[k]));
  attr(Screen, 'pixelDepth', () => st.screen.colorDepth);
  attr(Screen, 'availLeft', () => 0); attr(Screen, 'availTop', () => 0);
  attr(ScreenOrientation, 'type', () => st.orientation); attr(ScreenOrientation, 'angle', () => 0);
  const orientation = instance(ScreenOrientation);
  attr(Screen, 'orientation', () => orientation);
  g.screen = instance(Screen);

  // matchMedia: evaluates a tiny subset the way a touch phone would.
  const MediaQueryList = iface('MediaQueryList');
  function feature(f) {
    const m = /^\(\s*([a-z-]+)\s*(?::\s*([^)]+?)\s*)?\)$/.exec(f.trim());
    if (!m) return false;
    const name = m[1], val = m[2];
    switch (name) {
      case 'pointer': case 'any-pointer': return val === undefined || val === 'coarse';
      case 'hover': case 'any-hover': return val === 'none';
      case 'min-width': return st.innerWidth >= parseFloat(val);
      case 'max-width': return st.innerWidth <= parseFloat(val);
      case 'orientation': return val === 'portrait';
      default: return false;
    }
  }
  function evaluate(q) {
    let s = q.trim().toLowerCase(), neg = false;
    if (s.startsWith('not ')) { neg = true; s = s.slice(4); }
    if (s.startsWith('all and ')) s = s.slice(8);
    const r = s.split(/\s+and\s+/).every(feature);
    return neg ? !r : r;
  }
  attr(MediaQueryList, 'media', (d) => d.query.trim().toLowerCase());
  attr(MediaQueryList, 'matches', (d) => evaluate(d.query));
  op(MediaQueryList, 'addEventListener', () => undefined, 2);
  const matchMedia = function matchMedia(query) { return instance(MediaQueryList, { query: String(query) }); };
  Object.defineProperty(g, 'matchMedia', { value: matchMedia, writable: true, enumerable: true, configurable: true });

  // WebGL
  const WebGLRenderingContext = iface('WebGLRenderingContext');
  iface('WEBGL_debug_renderer_info');
  Object.assign(WEBGL_debug_renderer_info.prototype, { UNMASKED_VENDOR_WEBGL: 0x9245, UNMASKED_RENDERER_WEBGL: 0x9246 });
  op(WebGLRenderingContext, 'getParameter', (d, p) => {
    if (p === 0x1F00 || p === 0x1F01) return 'Mozilla';
    if (p === 0x9245) return d.ext ? 'Qualcomm' : null;
    if (p === 0x9246) return d.ext ? 'Adreno (TM) 740' : null;
    return null;
  }, 1);
  op(WebGLRenderingContext, 'getExtension', (d, name) => { if (name === 'WEBGL_debug_renderer_info') { d.ext = true; return instance(WEBGL_debug_renderer_info); } return null; }, 1);
  op(WebGLRenderingContext, 'getSupportedExtensions', () => ['OES_texture_float', 'WEBGL_debug_renderer_info'], 0);
  op(WebGLRenderingContext, 'isContextLost', () => false, 0);
  g.__gl = instance(WebGLRenderingContext);
})();
`;

export function makeRealm() {
  const ctx = vm.createContext({ setTimeout, setInterval, clearTimeout, clearInterval, console });
  vm.runInContext(ENV, ctx, { filename: 'fake-env.js' });
  return ctx;
}
