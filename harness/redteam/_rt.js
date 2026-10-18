/*
 * Hatch red-team page helper. Pages print one line per check:
 *   "DETECTED <id>: <reason>"   the lie is visible (tool/headless/run.mjs --page fails on the word)
 *   "clean <id>: <detail>"      consistent with the persona
 *   "info <id>: <detail>"       recorded, not judged
 *   "needs-device <id>: <why>"  cannot be judged in headless Firefox
 * The word "DETECTED" must appear in the body text only when a check fires.
 * Pages set window.__hatchPageDone = true when every (async) check has finished.
 */
(function () {
  'use strict';
  window.__hatchPageDone = false;
  var lines = [];
  var pre = null;
  var counts = { fired: 0, clean: 0, info: 0, device: 0 };
  function render() {
    if (!pre) {
      if (!document.body) return;
      pre = document.createElement('pre');
      pre.id = 'rt-out';
      pre.style.cssText = 'white-space:pre-wrap;word-break:break-all;font:12px/1.4 ui-monospace,Menlo,monospace;';
      document.body.appendChild(pre);
    }
    pre.textContent = lines.join('\n');
  }
  function str(v) {
    if (v === undefined) return 'undefined';
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }
  function line(status, id, detail) {
    lines.push(status + ' ' + id + ': ' + str(detail));
    render();
  }
  var RT = {
    fired: function (id, reason) { counts.fired++; line('DETECTED', id, reason); },
    clean: function (id, detail) { counts.clean++; line('clean', id, detail); },
    info: function (id, detail) { counts.info++; line('info', id, detail); },
    device: function (id, why) { counts.device++; line('needs-device', id, why); },
    section: function (title) { lines.push('', '## ' + title); render(); },
    /** check(id, ok, reasonIfNot, detailIfOk) */
    check: function (id, ok, reason, detail) { if (ok) RT.clean(id, detail === undefined ? 'ok' : detail); else RT.fired(id, reason); },
    persona: function () {
      try { return new URLSearchParams(location.search).get('persona') || ''; } catch (e) { return ''; }
    },
    identity: function () { return RT.persona().split('__')[0] || ''; },
    region: function () { return RT.persona().split('__')[1] || ''; },
    /** Persona expectations derived from the id (keeps the pages independent of the harness JSON). */
    expected: function () {
      var id = RT.identity();
      var desktop = id !== 'real-device' && id !== '';
      var platform = /^win-/.test(id) ? 'windows' : /^mac-/.test(id) ? 'mac' : /^linux-/.test(id) ? 'linux' : 'device';
      var engine = /chrome/.test(id) ? 'chrome' : 'firefox';
      return { id: id, desktop: desktop, platform: platform, engine: engine, region: RT.region() };
    },
    wait: function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); },
    withTimeout: function (p, ms, label) {
      return new Promise(function (resolve) {
        var t = setTimeout(function () { resolve({ timeout: true, label: label }); }, ms);
        Promise.resolve(p).then(function (v) { clearTimeout(t); resolve(v); }, function (e) { clearTimeout(t); resolve({ error: String(e && e.message || e) }); });
      });
    },
    /** Runs the page body; errors become a line (never a silent blank page). */
    run: function (fn) {
      var start = function () {
        try {
          RT.info('page', document.title + ' persona=' + (RT.persona() || '(none)') + ' ua=' + navigator.userAgent);
          Promise.resolve(fn()).catch(function (e) { line('info', 'page-error', String(e && e.stack || e)); }).then(function () {
            lines.push('', 'summary: fired=' + counts.fired + ' clean=' + counts.clean + ' info=' + counts.info + ' needs-device=' + counts.device);
            render();
            window.__hatchPageDone = true;
          });
        } catch (e) {
          line('info', 'page-error', String(e && e.stack || e));
          window.__hatchPageDone = true;
        }
      };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
    },
    /** Frame count of an Error stack (Firefox: one "fn@url:line:col" line per frame). */
    frames: function (stack) {
      return String(stack || '').split('\n').filter(function (l) { return l.trim().length > 0; });
    },
    nativeRe: function (name) {
      return new RegExp('^function ' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\(\\) \\{\\n    \\[native code\\]\\n\\}$');
    }
  };
  window.RT = RT;
})();
