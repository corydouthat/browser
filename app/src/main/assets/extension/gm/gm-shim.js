/*
 * Hatch GM shim — prepended to every site rule with JS. background.js substitutes the RULE_ID (JSON
 * string), RULE_META (JSON object) and GM_VALUES (JSON object) tokens and splices the rule's JS at the
 * USERSCRIPT marker, all in one pass (substituted text is never re-scanned).
 *
 * ISOLATED world: unsafeWindow = window.wrappedJSObject; GM values live in the extension's own
 * storage.local under "gm:<ruleId>:<key>" (preloaded at registration, kept fresh through
 * storage.onChanged, never visible to the page); `browser` / `chrome` are hidden from the user script.
 * MAIN world: unsafeWindow = window; values fall back to page localStorage (__hatch_gm_<ruleId>_<key>).
 * No GM_xmlhttpRequest in M1.
 */
(function () {
  var ruleId = String(__RULE_ID__);
  var meta = __RULE_META__ || { id: ruleId, name: ruleId };
  var cache = __GM_VALUES__ || {};
  var unsafeWindow = window.wrappedJSObject || window;
  var tag = '[hatch:' + (meta.name || ruleId) + ']';
  var hasOwn = Object.prototype.hasOwnProperty;

  // The extension API exists only in the ISOLATED world. Capture what the wrapper needs, then hide it
  // from the user script: a rule has userscript privileges, not the bridge extension's.
  var ext = null;
  try { if (typeof browser === 'object' && browser && browser.storage && browser.storage.local) ext = browser; } catch (e) { ext = null; }
  if (ext) {
    ['browser', 'chrome'].forEach(function (name) {
      try { Object.defineProperty(globalThis, name, { value: undefined, writable: false, enumerable: false, configurable: false }); } catch (e) { /* already hidden */ }
    });
  }
  var store = ext ? ext.storage.local : null;
  var PREFIX = 'gm:' + ruleId + ':';
  if (ext && ext.storage.onChanged) {
    try {
      ext.storage.onChanged.addListener(function (changes, area) {
        if (area !== 'local') return;
        for (var k in changes) {
          if (!hasOwn.call(changes, k) || k.indexOf(PREFIX) !== 0) continue;
          var key = k.slice(PREFIX.length);
          if (changes[k] && 'newValue' in changes[k]) cache[key] = changes[k].newValue; else delete cache[key];
        }
      });
    } catch (e) { /* no change events; the preloaded snapshot still works */ }
  }

  var LS_PREFIX = '__hatch_gm_' + ruleId + '_';
  function storage() {
    try { return window.localStorage; } catch (e) { return null; }
  }
  function GM_getValue(key, defaultValue) {
    if (store) return hasOwn.call(cache, key) ? cache[key] : defaultValue;
    var s = storage();
    if (!s) return defaultValue;
    var raw = s.getItem(LS_PREFIX + key);
    if (raw === null) return defaultValue;
    try { return JSON.parse(raw); } catch (e) { return defaultValue; }
  }
  function GM_setValue(key, value) {
    var v = value === undefined ? null : value;
    if (store) {
      cache[key] = v;
      var o = {};
      o[PREFIX + key] = v;
      try { store.set(o).catch(function (e) { console.warn(tag, 'GM_setValue failed:', e && e.message); }); } catch (e) { console.warn(tag, 'GM_setValue failed:', e && e.message); }
      return;
    }
    var s = storage();
    if (s) s.setItem(LS_PREFIX + key, JSON.stringify(v));
  }
  function GM_deleteValue(key) {
    if (store) {
      delete cache[key];
      try { store.remove(PREFIX + key).catch(function () { /* ignore */ }); } catch (e) { /* ignore */ }
      return;
    }
    var s = storage();
    if (s) s.removeItem(LS_PREFIX + key);
  }
  function GM_listValues() {
    if (store) return Object.keys(cache);
    var s = storage(), out = [];
    if (!s) return out;
    for (var i = 0; i < s.length; i++) {
      var k = s.key(i);
      if (k && k.indexOf(LS_PREFIX) === 0) out.push(k.slice(LS_PREFIX.length));
    }
    return out;
  }
  function GM_addStyle(css) {
    var style = document.createElement('style');
    style.textContent = String(css);
    var parent = document.head || document.documentElement;
    if (parent) parent.appendChild(style);
    else document.addEventListener('DOMContentLoaded', function () { (document.head || document.documentElement).appendChild(style); }, { once: true });
    return style;
  }
  function GM_log() {
    var args = Array.prototype.slice.call(arguments);
    args.unshift(tag);
    console.log.apply(console, args);
  }
  function GM_registerMenuCommand(caption) {
    console.log(tag, 'GM_registerMenuCommand is not supported in Hatch; ignored:', caption);
    return 0;
  }
  var GM_info = {
    script: {
      name: meta.name || ruleId,
      namespace: 'hatch',
      description: '',
      version: '',
      matches: meta.matches || [],
      excludes: meta.excludeMatches || [],
      'run-at': meta.runAt || 'document_idle',
      source: meta.source || null,
    },
    scriptHandler: 'Hatch',
    version: meta.extensionVersion || '',
    world: meta.world || 'ISOLATED',
    ruleId: ruleId,
    storage: store ? 'extension' : 'localStorage',
  };
  var GM = {
    info: GM_info,
    getValue: function (key, defaultValue) { return Promise.resolve(GM_getValue(key, defaultValue)); },
    setValue: function (key, value) { return Promise.resolve(GM_setValue(key, value)); },
    deleteValue: function (key) { return Promise.resolve(GM_deleteValue(key)); },
    listValues: function () { return Promise.resolve(GM_listValues()); },
    addStyle: function (css) { return GM_addStyle(css); },
    log: GM_log,
    registerMenuCommand: GM_registerMenuCommand,
  };

  // The user script is the first statement of this function, so its own
  // 'use strict' directive applies to it and only to it.
  (function (unsafeWindow, GM, GM_info, GM_addStyle, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_log, GM_registerMenuCommand) {
/*__USERSCRIPT__*/
  }).call(window, unsafeWindow, GM, GM_info, GM_addStyle, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_log, GM_registerMenuCommand);
})();
