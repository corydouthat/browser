/* Dedicated worker: reports the worker-scope navigator / Intl view for the context-consistency probe. */
function hatchSnapshot(w) {
  var n = w.navigator;
  var out = {};
  function t(k, f) { try { out[k] = f(); } catch (e) { out[k] = 'error: ' + (e && e.message); } }
  t('userAgent', function () { return n.userAgent; });
  t('platform', function () { return n.platform; });
  t('oscpu', function () { return 'oscpu' in n ? n.oscpu : null; });
  t('appVersion', function () { return n.appVersion; });
  t('languages', function () { return Array.prototype.slice.call(n.languages); });
  t('hardwareConcurrency', function () { return n.hardwareConcurrency; });
  t('timeZone', function () { return new w.Intl.DateTimeFormat().resolvedOptions().timeZone; });
  t('locale', function () { return new w.Intl.DateTimeFormat().resolvedOptions().locale; });
  t('dateOffset', function () { return new w.Date().getTimezoneOffset(); });
  t('maxTouchPoints', function () { return 'maxTouchPoints' in n ? n.maxTouchPoints : undefined; });
  t('touchEvent', function () { return typeof w.TouchEvent !== 'undefined'; });
  return out;
}
self.onmessage = function () { self.postMessage(hatchSnapshot(self)); };
