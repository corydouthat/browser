/* Red-team 04: service worker scope snapshot. */
function rtWorkerSnap(s) {
  var o = {};
  function t(k, f) { try { o[k] = f(); } catch (e) { o[k] = 'error:' + (e && e.message); } }
  t('hwc', function () { return s.navigator.hardwareConcurrency; });
  t('ua', function () { return s.navigator.userAgent; });
  t('platform', function () { return s.navigator.platform; });
  t('oscpu', function () { return s.navigator.oscpu; });
  t('appVersion', function () { return s.navigator.appVersion; });
  t('languages', function () { return Array.prototype.slice.call(s.navigator.languages); });
  t('language', function () { return s.navigator.language; });
  t('tz', function () { return new s.Intl.DateTimeFormat().resolvedOptions().timeZone; });
  t('locale', function () { return new s.Intl.DateTimeFormat().resolvedOptions().locale; });
  t('tzo', function () { return new s.Date().getTimezoneOffset(); });
  t('webgl', function () { return 'n/a'; });
  return o;
}
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('message', function (e) { var port = e.ports && e.ports[0]; if (port) port.postMessage(rtWorkerSnap(self)); });
