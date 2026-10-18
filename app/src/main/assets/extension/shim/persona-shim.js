/*
 * Hatch persona shim: MAIN world, every frame, document_start (contentScripts.register with
 * matchOriginAsFallback). background.js substitutes the PERSONA token with the config JSON.
 * Prototype accessors only, configurable, one cross-realm-aware Function.prototype.toString patch,
 * no page-visible marker, a second run re-wraps harmlessly (docs/extension.md).
 */
(function () {
  'use strict';
  var CFG = __PERSONA__;
  var W = typeof window !== 'undefined' ? window : globalThis;
  if (!CFG || !CFG.enabled) return;

  // ---- native-looking functions
  var natives = new WeakMap();
  var FP = Function.prototype;
  var origFnToString = FP.toString;
  var TEMPLATE = origFnToString.call(origFnToString);
  var getProto = Object.getPrototypeOf;
  function nat(fn, printed) { natives.set(fn, printed); return fn; }
  var fnToString = nat({ toString() {
    var n = natives.get(this);
    if (n !== undefined) return TEMPLATE.replace('toString', function () { return n; });
    if (typeof this === 'function') {
      var p = getProto(this);
      if (typeof p === 'function' && p !== FP && p.toString !== fnToString) return p.toString.call(this);
    }
    return origFnToString.call(this);
  } }.toString, 'toString');
  Object.defineProperty(FP, 'toString', { value: fnToString, writable: true, enumerable: false, configurable: true });

  // ---- helpers
  var own = Object.getOwnPropertyDescriptor;
  function safe(fn) { try { fn(); } catch (e) { /* a failing section must not kill the rest */ } }
  function del(o, k) { try { if (o && own(o, k)) delete o[k]; } catch (e) { /* non-configurable */ } }
  function prop(holder, name, compute, brand) {
    var d = own(holder, name);
    var og = d && d.get, k = 'get ' + name;
    var g = { [k]() {
      var ov;
      if (og) ov = og.call(this); else if (brand) brand.call(this);
      return compute.call(this, ov);
    } }[k];
    Object.defineProperty(holder, name, {
      get: nat(g, name), set: d && d.set ? d.set : undefined,
      enumerable: d ? d.enumerable : true, configurable: true
    });
  }
  function mapped(holder, name, map, key) {
    var d = own(holder, name);
    var og = d && d.get, k = 'get ' + name;
    var g = { [k]() {
      var data = map.get(this);
      if (data !== undefined) return data[key];
      return og ? og.call(this) : undefined;
    } }[k];
    Object.defineProperty(holder, name, { get: nat(g, name), set: undefined, enumerable: d ? d.enumerable : true, configurable: true });
  }
  function method(holder, name, impl, len) {
    var d = own(holder, name);
    var orig = d && typeof d.value === 'function' ? d.value : null;
    var n = len !== undefined ? len : (orig ? orig.length : 0);
    var f;
    switch (n) {
      case 1: f = { [name](a) { return impl.call(this, orig, arguments); } }[name]; break;
      case 2: f = { [name](a, b) { return impl.call(this, orig, arguments); } }[name]; break;
      case 3: f = { [name](a, b, c) { return impl.call(this, orig, arguments); } }[name]; break;
      case 4: f = { [name](a, b, c, d2) { return impl.call(this, orig, arguments); } }[name]; break;
      default: f = { [name]() { return impl.call(this, orig, arguments); } }[name];
    }
    Object.defineProperty(holder, name, {
      value: nat(f, typeof name === 'symbol' ? '[' + name.description + ']' : name),
      writable: d ? d.writable : true, enumerable: d ? d.enumerable : true, configurable: true
    });
    return f;
  }
  function winHolder(name) {
    if (own(W, name)) return W;
    if (W.Window && W.Window.prototype && own(W.Window.prototype, name)) return W.Window.prototype;
    return null;
  }

  var nav = W.navigator;
  var NP = W.Navigator && W.Navigator.prototype;
  var uaDesc = NP && own(NP, 'userAgent');
  var navBrand = uaDesc && uaDesc.get;
  var setTimeout0 = W.setTimeout, clearTimeout0 = W.clearTimeout;
  var OrigDate = W.Date, dateNow = OrigDate.now;
  var desktopNoTouch = !!CFG.desktop && !CFG.touch;
  var S = CFG.screen;

  // ---- touch removal + pointer/hover/device-* media queries
  if (desktopNoTouch) safe(function () {
    if (NP && own(NP, 'maxTouchPoints')) prop(NP, 'maxTouchPoints', function () { return 0; }, navBrand);
    var evs = ['ontouchstart', 'ontouchmove', 'ontouchend', 'ontouchcancel'];
    var holders = [W, W.Window && W.Window.prototype, W.Document && W.Document.prototype,
      W.HTMLElement && W.HTMLElement.prototype, W.SVGElement && W.SVGElement.prototype,
      W.MathMLElement && W.MathMLElement.prototype];
    holders.forEach(function (h) { if (h) evs.forEach(function (e) { del(h, e); }); });
    ['TouchEvent', 'Touch', 'TouchList'].forEach(function (k) { del(W, k); });
    ['orientation', 'onorientationchange'].forEach(function (k) { del(W, k); if (W.Window) del(W.Window.prototype, k); });
    var SO = W.ScreenOrientation && W.ScreenOrientation.prototype;
    if (SO && S && S.width > S.height) {
      if (own(SO, 'type')) prop(SO, 'type', function () { return 'landscape-primary'; });
      if (own(SO, 'angle')) prop(SO, 'angle', function () { return 0; });
    }
    var PE = W.PointerEvent && W.PointerEvent.prototype, ME = W.MouseEvent && W.MouseEvent.prototype;
    var ptDesc = PE && own(PE, 'pointerType');
    function fromTouch(ev) { return ev.isTrusted === true && ptDesc.get.call(ev) === 'touch'; }
    if (ptDesc && ptDesc.get) {
      prop(PE, 'pointerType', function (ov) { return ov === 'touch' && this.isTrusted === true ? 'mouse' : ov; });
      ['width', 'height'].forEach(function (k) { if (own(PE, k)) prop(PE, k, function (ov) { return fromTouch(this) ? 1 : ov; }); });
      if (own(PE, 'pressure')) prop(PE, 'pressure', function (ov) { return fromTouch(this) ? (this.buttons ? 0.5 : 0) : ov; });
    }
    if (ME && own(ME, 'mozInputSource')) prop(ME, 'mozInputSource', function (ov) { return ov === 5 && this.isTrusted === true ? 1 : ov; });

    var RX = /\(\s*(?:(min|max)-)?(any-pointer|pointer|any-hover|hover|device-width|device-height|device-aspect-ratio|resolution)\s*(?::\s*([^()]*?)\s*)?\)|\(\s*-(?:webkit|moz)-(?:(min|max)-)?device-pixel-ratio\s*(?::\s*([^()]*?)\s*)?\)/gi;
    var T = '(min-width: 0px)', F = '(min-width: 999999999px)';
    function cmp(mm, have, want) { return mm === 'min' ? have >= want : mm === 'max' ? have <= want : Math.abs(have - want) < 1e-6; }
    function dppx(v) {
      var m = /^([+-]?(?:\d+\.?\d*|\.\d+))\s*(dppx|x|dpi|dpcm)$/i.exec(v);
      if (!m) return NaN;
      var n = +m[1], u = m[2].toLowerCase();
      return u === 'dpi' ? n / 96 : u === 'dpcm' ? n * 2.54 / 96 : n;
    }
    function rewrite(q) {
      return q.replace(RX, function (m, mm, feat, val, mm2, val2) {
        if (feat === undefined) {
          if (!S || typeof S.dpr !== 'number') return m;
          if (val2 === undefined) return T;
          var r = +val2;
          return r === r && cmp(mm2 ? mm2.toLowerCase() : undefined, S.dpr, r) ? T : F;
        }
        var f = feat.toLowerCase();
        mm = mm ? mm.toLowerCase() : undefined;
        if (f === 'pointer' || f === 'hover' || f === 'any-pointer' || f === 'any-hover') {
          if (mm) return m;
          if (val === undefined) return T;
          var v = val.toLowerCase();
          return ((f === 'pointer' || f === 'any-pointer') ? v === 'fine' : v === 'hover') ? T : F;
        }
        if (!S) return m;
        if (val === undefined) return T;
        var want;
        if (f === 'device-width' || f === 'device-height') {
          var px = /^([+-]?(?:\d+\.?\d*|\.\d+))\s*px$/i.exec(val);
          if (!px) return m;
          want = +px[1];
          return cmp(mm, f === 'device-width' ? S.width : S.height, want) ? T : F;
        }
        if (f === 'device-aspect-ratio') {
          var ar = /^([+-]?(?:\d+\.?\d*|\.\d+))\s*(?:\/\s*([+-]?(?:\d+\.?\d*|\.\d+)))?$/.exec(val);
          if (!ar) return m;
          want = +ar[1] / (ar[2] !== undefined ? +ar[2] : 1);
          return cmp(mm, S.width / S.height, want) ? T : F;
        }
        if (typeof S.dpr !== 'number') return m;
        want = dppx(val);
        return want === want && cmp(mm, S.dpr, want) ? T : F;
      });
    }
    var mqlMap = new WeakMap();
    var mmHolder = winHolder('matchMedia');
    if (mmHolder) method(mmHolder, 'matchMedia', function (orig, args) {
      var q = String(args[0]);
      var r = rewrite(q);
      var mql = orig.call(this, r);
      if (r !== q && mql && typeof mql === 'object') mqlMap.set(mql, orig.call(this, q).media);
      return mql;
    }, 1);
    var MQL = W.MediaQueryList && W.MediaQueryList.prototype;
    if (MQL && own(MQL, 'media')) prop(MQL, 'media', function (ov) {
      var o = mqlMap.get(this); return o === undefined ? ov : o;
    });
    var MQE = W.MediaQueryListEvent && W.MediaQueryListEvent.prototype;
    var tgtDesc = W.Event && W.Event.prototype && own(W.Event.prototype, 'target');
    if (MQE && own(MQE, 'media') && tgtDesc && tgtDesc.get) prop(MQE, 'media', function (ov) {
      var o = mqlMap.get(tgtDesc.get.call(this)); return o === undefined ? ov : o;
    });
  });

  // ---- screen / dpr / outer sizes
  if (S) safe(function () {
    var SP = W.Screen && W.Screen.prototype;
    if (SP) {
      var vals = { width: S.width, height: S.height, availWidth: S.availWidth, availHeight: S.availHeight,
        colorDepth: S.colorDepth, pixelDepth: S.colorDepth, availLeft: 0, availTop: 0, left: 0, top: 0 };
      Object.keys(vals).forEach(function (k) {
        if (own(SP, k)) prop(SP, k, function () { return vals[k]; });
      });
    }
    var h;
    if (typeof S.dpr === 'number' && (h = winHolder('devicePixelRatio'))) prop(h, 'devicePixelRatio', function () { return S.dpr; });
    var cT = null, cL = null;
    function chromeTop() { if (cT === null) { var t = W.mozInnerScreenY - W.screenY; cT = t > 0 && t < 300 ? Math.round(t) : 85; } return cT; }
    function chromeSide() { if (cL === null) { var l = W.mozInnerScreenX - W.screenX; cL = l > 0 && l < 100 ? Math.round(l) : 0; } return cL; }
    if ((h = winHolder('outerWidth'))) prop(h, 'outerWidth', function () { return W.innerWidth + 2 * chromeSide(); });
    if ((h = winHolder('outerHeight'))) prop(h, 'outerHeight', function () { return W.innerHeight + chromeTop(); });
  });

  // ---- navigator scalars
  if (NP) safe(function () {
    if (typeof CFG.hardwareConcurrency === 'number' && own(NP, 'hardwareConcurrency')) {
      prop(NP, 'hardwareConcurrency', function () { return CFG.hardwareConcurrency; });
    }
    if (CFG.desktop) {
      ['vibrate', 'share', 'canShare'].forEach(function (k) { del(NP, k); });
      if (own(NP, 'pdfViewerEnabled')) prop(NP, 'pdfViewerEnabled', function () { return true; });
    }
    [['userAgent', CFG.userAgent], ['platform', CFG.platform], ['oscpu', CFG.oscpu], ['appVersion', CFG.appVersion]]
      .forEach(function (p) {
        var k = p[0], v = p[1];
        if (typeof v !== 'string' || !own(NP, k)) return;
        var cur; try { cur = nav[k]; } catch (e) { cur = undefined; }
        if (cur !== v) prop(NP, k, function () { return v; });
      });
    if (Array.isArray(CFG.languages) && CFG.languages.length && own(NP, 'languages')) {
      var langs = Object.freeze(CFG.languages.map(String));
      var cur; try { cur = nav.languages; } catch (e) { cur = null; }
      if (!cur || Array.prototype.join.call(cur, ',') !== langs.join(',')) {
        prop(NP, 'languages', function () { return langs; });
        if (own(NP, 'language')) prop(NP, 'language', function () { return langs[0]; });
      }
    }
  });

  // ---- navigator.userAgentData (chromeLike personas)
  if (CFG.uaData && NP) safe(function () {
    var U = CFG.uaData;
    var instances = new WeakSet();
    var brands = Object.freeze((U.brands || []).map(function (b) { return { brand: String(b.brand), version: String(b.version) }; }));
    function full(v) { return v.indexOf('.') >= 0 ? v : v + '.0.0.0'; }
    function brand() { if (!instances.has(this)) throw new TypeError("'NavigatorUAData' called on an object that does not implement interface NavigatorUAData."); }
    var ctor = nat(function NavigatorUAData() { throw new TypeError('Illegal constructor.'); }, 'NavigatorUAData');
    var proto = {};
    Object.defineProperty(ctor, 'prototype', { value: proto, writable: false, enumerable: false, configurable: false });
    Object.defineProperty(proto, 'constructor', { value: ctor, writable: true, enumerable: false, configurable: true });
    Object.defineProperty(proto, Symbol.toStringTag, { value: 'NavigatorUAData', writable: false, enumerable: false, configurable: true });
    prop(proto, 'brands', function () { return brands; }, brand);
    prop(proto, 'mobile', function () { return false; }, brand);
    prop(proto, 'platform', function () { return String(U.platform); }, brand);
    method(proto, 'getHighEntropyValues', function (orig, args) {
      brand.call(this);
      var hints = args[0];
      var out = { brands: brands, mobile: false, platform: String(U.platform) };
      var major = brands.filter(function (b) { return !/not.a.brand/i.test(b.brand); }).map(function (b) { return b.version; })[0] || '0';
      var hi = {
        architecture: String(U.architecture || ''), bitness: String(U.bitness || ''), model: String(U.model || ''),
        platformVersion: String(U.platformVersion || ''), uaFullVersion: full(major),
        fullVersionList: brands.map(function (b) { return { brand: b.brand, version: full(b.version) }; }),
        wow64: false, formFactors: ['Desktop']
      };
      if (hints !== undefined && hints !== null) {
        if (typeof hints !== 'object' || typeof hints[Symbol.iterator] !== 'function') {
          return Promise.reject(new TypeError("NavigatorUAData.getHighEntropyValues: Argument 1 can't be converted to a sequence."));
        }
        var arr = Array.from(hints, String);
        for (var i = 0; i < arr.length; i++) if (Object.prototype.hasOwnProperty.call(hi, arr[i])) out[arr[i]] = hi[arr[i]];
      }
      return Promise.resolve(out);
    }, 1);
    method(proto, 'toJSON', function () { brand.call(this); return { brands: brands, mobile: false, platform: String(U.platform) }; }, 0);
    var inst = Object.create(proto);
    instances.add(inst);
    prop(NP, 'userAgentData', function () { return inst; }, navBrand);
    Object.defineProperty(W, 'NavigatorUAData', { value: ctor, writable: true, enumerable: false, configurable: true });
  });

  // ---- plugins / mimeTypes (only when Gecko's list is not already Chrome-shaped)
  if (CFG.chromeLike && NP) safe(function () {
    var names = ['PDF Viewer', 'Chrome PDF Viewer', 'Chromium PDF Viewer', 'Microsoft Edge PDF Viewer', 'WebKit built-in PDF'];
    var cur; try { cur = nav.plugins; } catch (e) { cur = null; }
    if (cur && cur.length === 5 && cur[0] && cur[0].name === names[0]) return;
    var fakes = new WeakMap();
    function item(ctorName, data) {
      var o = Object.create(W[ctorName].prototype);
      fakes.set(o, { data: data });
      return o;
    }
    function list(ctorName, items, key, data) {
      var p = new Proxy(Object.create(W[ctorName].prototype), {
        get: function (t, k, r) {
          if (typeof k === 'string') {
            if (/^\d+$/.test(k)) return items[+k];
            for (var i = 0; i < items.length; i++) if (fakes.get(items[i]).data[key] === k) return items[i];
          }
          return Reflect.get(t, k, r);
        },
        has: function (t, k) { return (typeof k === 'string' && /^\d+$/.test(k) && +k < items.length) || Reflect.has(t, k); },
        ownKeys: function (t) { return items.map(function (_, i) { return String(i); }).concat(Reflect.ownKeys(t)); },
        getOwnPropertyDescriptor: function (t, k) {
          if (typeof k === 'string' && /^\d+$/.test(k) && +k < items.length) return { value: items[+k], writable: false, enumerable: true, configurable: true };
          return Reflect.getOwnPropertyDescriptor(t, k);
        }
      });
      fakes.set(p, { items: items, key: key, data: data });
      return p;
    }
    function listProto(ctorName, extra) {
      var P = W[ctorName] && W[ctorName].prototype;
      if (!P) return;
      mapped(P, 'length', { get: function (o) { var f = fakes.get(o); return f && f.items ? { length: f.items.length } : undefined; } }, 'length');
      method(P, 'item', function (orig, args) { var f = fakes.get(this); if (!f || !f.items) return orig ? orig.apply(this, args) : null; var i = args[0] >>> 0; return i < f.items.length ? f.items[i] : null; }, 1);
      method(P, 'namedItem', function (orig, args) { var f = fakes.get(this); if (!f || !f.items) return orig ? orig.apply(this, args) : null; var n = String(args[0]); for (var i = 0; i < f.items.length; i++) if (fakes.get(f.items[i]).data[f.key] === n) return f.items[i]; return null; }, 1);
      method(P, Symbol.iterator, function (orig) { var f = fakes.get(this); if (!f || !f.items) return orig ? orig.call(this) : [][Symbol.iterator](); return f.items.slice()[Symbol.iterator](); }, 0);
      (extra || []).forEach(function (k) { if (own(P, k)) mapped(P, k, { get: function (o) { var f = fakes.get(o); return f && f.data ? f.data : undefined; } }, k); });
    }
    function itemProto(ctorName, keys) {
      var P = W[ctorName] && W[ctorName].prototype;
      if (!P) return;
      keys.forEach(function (k) { mapped(P, k, { get: function (o) { var f = fakes.get(o); return f && f.data ? f.data : undefined; } }, k); });
    }
    var mimes = [
      item('MimeType', { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: null }),
      item('MimeType', { type: 'text/pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: null })
    ];
    var plugins = names.map(function (n) { return list('Plugin', mimes, 'type', { name: n, filename: 'internal-pdf-viewer', description: 'Portable Document Format' }); });
    mimes.forEach(function (m) { fakes.get(m).data.enabledPlugin = plugins[0]; });
    itemProto('MimeType', ['type', 'suffixes', 'description', 'enabledPlugin']);
    itemProto('Plugin', ['name', 'filename', 'description']);
    listProto('Plugin');
    listProto('PluginArray');
    listProto('MimeTypeArray');
    if (W.PluginArray && W.PluginArray.prototype && own(W.PluginArray.prototype, 'refresh')) method(W.PluginArray.prototype, 'refresh', function (orig, args) { if (!fakes.get(this) && orig) return orig.apply(this, args); return undefined; }, 0);
    var pluginArray = list('PluginArray', plugins, 'name');
    var mimeArray = list('MimeTypeArray', mimes, 'type');
    if (own(NP, 'plugins')) prop(NP, 'plugins', function () { return pluginArray; });
    if (own(NP, 'mimeTypes')) prop(NP, 'mimeTypes', function () { return mimeArray; });
  });

  // ---- WebGL vendor / renderer
  if (CFG.webgl) safe(function () {
    var UNMASKED_VENDOR = 0x9245, UNMASKED_RENDERER = 0x9246, VENDOR = 0x1F00;
    var EXT = 'WEBGL_debug_renderer_info';
    var enabled = new WeakSet();
    var fakeExt = new WeakMap();
    [W.WebGLRenderingContext, W.WebGL2RenderingContext].forEach(function (C) {
      var P = C && C.prototype;
      if (!P) return;
      method(P, 'getParameter', function (orig, args) {
        var p = args[0];
        if ((p === UNMASKED_VENDOR || p === UNMASKED_RENDERER) && enabled.has(this)) {
          orig.call(this, VENDOR);
          return p === UNMASKED_VENDOR ? String(CFG.webgl.vendor) : String(CFG.webgl.renderer);
        }
        return orig.apply(this, args);
      }, 1);
      method(P, 'getExtension', function (orig, args) {
        var r = orig.apply(this, args);
        if (String(args[0]) === EXT) {
          if (!r && !(this.isContextLost && this.isContextLost())) {
            r = fakeExt.get(this);
            if (!r) {
              var EP = W.WEBGL_debug_renderer_info && W.WEBGL_debug_renderer_info.prototype;
              r = Object.create(EP || null);
              if (!EP) { r.UNMASKED_VENDOR_WEBGL = UNMASKED_VENDOR; r.UNMASKED_RENDERER_WEBGL = UNMASKED_RENDERER; }
              fakeExt.set(this, r);
            }
          }
          if (r) enabled.add(this);
        }
        return r;
      }, 1);
      method(P, 'getSupportedExtensions', function (orig, args) {
        var r = orig.apply(this, args);
        if (r && r.indexOf(EXT) < 0) r.push(EXT);
        return r;
      }, 0);
    });
  });

  // ---- geolocation
  if (CFG.geolocation && typeof CFG.geolocation.latitude === 'number') safe(function () {
    var G = W.Geolocation && W.Geolocation.prototype;
    if (!G) return;
    var geo = CFG.geolocation;
    var fakes = new WeakMap();
    function fake(ctorName, data) {
      var o = Object.create(W[ctorName].prototype);
      fakes.set(o, data);
      return o;
    }
    function install(ctorName, keys) {
      var P = W[ctorName] && W[ctorName].prototype;
      if (!P) return;
      keys.forEach(function (k) { mapped(P, k, fakes, k); });
      method(P, 'toJSON', function (orig) {
        var d = fakes.get(this);
        if (d === undefined) return orig ? orig.call(this) : undefined;
        var out = {};
        Object.keys(d).forEach(function (k) { var v = d[k]; out[k] = v && typeof v === 'object' && fakes.has(v) ? v.toJSON() : v; });
        return out;
      }, 0);
    }
    install('GeolocationCoordinates', ['latitude', 'longitude', 'altitude', 'accuracy', 'altitudeAccuracy', 'heading', 'speed']);
    install('GeolocationPosition', ['coords', 'timestamp']);
    function position() {
      var r = Math.random() * 3, a = Math.random() * 2 * Math.PI;
      var dLat = (r * Math.cos(a)) / 111320;
      var dLon = (r * Math.sin(a)) / (111320 * Math.max(0.01, Math.cos(geo.latitude * Math.PI / 180)));
      var coords = fake('GeolocationCoordinates', {
        latitude: geo.latitude + dLat, longitude: geo.longitude + dLon,
        altitude: null, accuracy: typeof geo.accuracyMeters === 'number' ? geo.accuracyMeters : 50,
        altitudeAccuracy: null, heading: null, speed: null
      });
      return fake('GeolocationPosition', { coords: coords, timestamp: dateNow.call(OrigDate) });
    }
    var brandDesc = own(G, 'clearWatch');
    function brand(o) { if (brandDesc && brandDesc.value) brandDesc.value.call(o, 0); }
    function callback(args, name) {
      if (typeof args[0] !== 'function') throw new TypeError('Geolocation.' + name + ': Argument 1 is not callable.');
      return args[0];
    }
    var watches = new Map(), nextId = 1;
    method(G, 'getCurrentPosition', function (orig, args) {
      brand(this);
      setTimeout0.call(W, callback(args, 'getCurrentPosition'), 30 + Math.random() * 90, position());
      return undefined;
    }, 1);
    method(G, 'watchPosition', function (orig, args) {
      brand(this);
      var cb = callback(args, 'watchPosition');
      var id = nextId++;
      var w = { c: 0, t: 0 };
      function tick(delay) {
        w.c = setTimeout0.call(W, cb, delay, position());
        w.t = setTimeout0.call(W, tick, delay, 10000);
      }
      tick(30 + Math.random() * 90);
      watches.set(id, w);
      return id;
    }, 1);
    method(G, 'clearWatch', function (orig, args) {
      if (orig) orig.call(this, 0);
      var w = watches.get(args[0] >>> 0);
      if (w) { clearTimeout0.call(W, w.c); clearTimeout0.call(W, w.t); watches.delete(args[0] >>> 0); }
      return undefined;
    }, 1);
    var PP = W.Permissions && W.Permissions.prototype;
    var PS = W.PermissionStatus && W.PermissionStatus.prototype;
    if (PP && PS && own(PP, 'query') && own(PS, 'state')) {
      var granted = new WeakSet();
      method(PP, 'query', function (orig, args) {
        var p = orig.apply(this, args);
        var d = args[0];
        if (d && typeof d === 'object' && d.name === 'geolocation' && p && typeof p.then === 'function') {
          p = p.then(function (st) { if (st && typeof st === 'object') granted.add(st); return st; });
        }
        return p;
      }, 1);
      prop(PS, 'state', function (ov) { return granted.has(this) ? 'granted' : ov; });
    }
  });

  // ---- time zone (fallback only; TZ env is the primary mechanism)
  if (CFG.tzShim) safe(function () {
    var D = OrigDate, DP = D.prototype, TZ = typeof CFG.timeZone === 'string' ? CFG.timeZone : null;
    var table = Array.isArray(CFG.tzOffsets) ? CFG.tzOffsets.filter(function (e) {
      return e && typeof e.fromEpochMs === 'number' && typeof e.offsetMinutes === 'number';
    }).sort(function (a, b) { return a.fromEpochMs - b.fromEpochMs; }) : [];
    var IDTF = W.Intl && W.Intl.DateTimeFormat;
    var oGetTime = DP.getTime, oSetTime = DP.setTime, oGetTZO = DP.getTimezoneOffset;
    var offFmt = null, nameFmt = null;
    function intlOffset(t) {
      try {
        if (!offFmt) offFmt = new IDTF('en-US', { timeZone: TZ, timeZoneName: 'longOffset' });
        var parts = offFmt.formatToParts(new D(t)), v = '';
        for (var i = 0; i < parts.length; i++) if (parts[i].type === 'timeZoneName') v = parts[i].value;
        var m = /GMT([+-])(\d{1,2})(?::?(\d{2}))?/.exec(v);
        if (!m) return 0;
        var mins = (+m[2]) * 60 + (m[3] ? +m[3] : 0);
        return m[1] === '-' ? -mins : mins;
      } catch (e) { return 0; }
    }
    function fake(t) {
      if (table.length) {
        var lo = 0, hi = table.length - 1, r = table[0].offsetMinutes;
        while (lo <= hi) {
          var mid = (lo + hi) >> 1;
          if (table[mid].fromEpochMs <= t) { r = table[mid].offsetMinutes; lo = mid + 1; } else hi = mid - 1;
        }
        return r;
      }
      return IDTF && TZ ? intlOffset(t) : 0;
    }
    function real(t) { return -oGetTZO.call(new D(t)); }
    function shift(d) { var t = oGetTime.call(d); if (t !== t) return d; return new D(t + (fake(t) - real(t)) * 60000); }
    function unshift(r) {
      if (r !== r) return r;
      var t = r - (fake(r) - real(r)) * 60000;
      return r - (fake(t) - real(t)) * 60000;
    }
    function fromLocal(u) {
      if (u !== u) return u;
      var t = u - fake(u) * 60000;
      return u - fake(t) * 60000;
    }
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function offStr(t) {
      var o = fake(t), s = o < 0 ? '-' : '+';
      o = Math.abs(o);
      return s + pad(Math.floor(o / 60)) + pad(o % 60);
    }
    function tzName(d) {
      if (IDTF && TZ) {
        try {
          if (!nameFmt) nameFmt = new IDTF(typeof CFG.locale === 'string' ? CFG.locale : 'en-US', { timeZone: TZ, timeZoneName: 'long' });
          var parts = nameFmt.formatToParts(d);
          for (var i = 0; i < parts.length; i++) if (parts[i].type === 'timeZoneName') return parts[i].value;
        } catch (e) { /* fall through */ }
      }
      var s = offStr(oGetTime.call(d));
      return 'GMT' + s.slice(0, 3) + ':' + s.slice(3);
    }
    method(DP, 'getTimezoneOffset', function (orig) {
      var t = oGetTime.call(this);
      return t !== t ? orig.call(this) : -fake(t);
    }, 0);
    ['getFullYear', 'getYear', 'getMonth', 'getDate', 'getDay', 'getHours', 'getMinutes', 'getSeconds', 'getMilliseconds']
      .forEach(function (k) {
        if (!own(DP, k)) return;
        method(DP, k, function (orig) { oGetTime.call(this); return orig.call(shift(this)); }, 0);
      });
    ['setFullYear', 'setYear', 'setMonth', 'setDate', 'setHours', 'setMinutes', 'setSeconds', 'setMilliseconds']
      .forEach(function (k) {
        if (!own(DP, k)) return;
        method(DP, k, function (orig, args) {
          var t0 = oGetTime.call(this);
          var base = t0 === t0 ? this : (k === 'setFullYear' || k === 'setYear' ? new D(0) : null);
          if (!base) return orig.apply(this, args);
          var r = orig.apply(shift(base), args);
          var t = unshift(r);
          oSetTime.call(this, t);
          return t;
        });
      });
    var toDateStr = method(DP, 'toDateString', function (orig) {
      var t = oGetTime.call(this);
      return t !== t ? orig.call(this) : orig.call(shift(this));
    }, 0);
    var toTimeStr = method(DP, 'toTimeString', function (orig) {
      var t = oGetTime.call(this);
      if (t !== t) return orig.call(this);
      return orig.call(shift(this)).slice(0, 8) + ' GMT' + offStr(t) + ' (' + tzName(this) + ')';
    }, 0);
    var toStr = method(DP, 'toString', function (orig) {
      var t = oGetTime.call(this);
      return t !== t ? orig.call(this) : toDateStr.call(this) + ' ' + toTimeStr.call(this);
    }, 0);
    function withTZ(options) {
      if (options === undefined) return { timeZone: TZ };
      if (options === null || (typeof options !== 'object' && typeof options !== 'function')) return options;
      if (options.timeZone !== undefined) return options;
      var o = Object.create(options);
      o.timeZone = TZ;
      return o;
    }
    if (TZ && IDTF) {
      ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (k) {
        method(DP, k, function (orig, args) { return orig.call(this, args[0], withTZ(args[1])); }, 0);
      });
      var DTF = nat(function DateTimeFormat() { return new IDTF(arguments[0], withTZ(arguments[1])); }, 'DateTimeFormat');
      Object.defineProperty(DTF, 'prototype', { value: IDTF.prototype, writable: false, enumerable: false, configurable: false });
      Object.defineProperty(IDTF.prototype, 'constructor', { value: DTF, writable: true, enumerable: false, configurable: true });
      Object.defineProperty(DTF, 'supportedLocalesOf', { value: IDTF.supportedLocalesOf, writable: true, enumerable: false, configurable: true });
      Object.defineProperty(W.Intl, 'DateTimeFormat', { value: DTF, writable: true, enumerable: false, configurable: true });
    }
    var NewDate = nat(function Date(a, b, c, d, e, f, g) {
      if (!new.target) return toStr.call(new D());
      var n = arguments.length;
      if (n >= 2) return new D(fromLocal(D.UTC.apply(D, arguments)));
      return n === 0 ? new D() : new D(a);
    }, 'Date');
    Object.defineProperty(NewDate, 'prototype', { value: DP, writable: false, enumerable: false, configurable: false });
    Object.defineProperty(DP, 'constructor', { value: NewDate, writable: true, enumerable: false, configurable: true });
    ['now', 'parse', 'UTC'].forEach(function (k) {
      Object.defineProperty(NewDate, k, { value: D[k], writable: true, enumerable: false, configurable: true });
    });
    Object.defineProperty(W, 'Date', { value: NewDate, writable: true, enumerable: false, configurable: true });
  });
})();
