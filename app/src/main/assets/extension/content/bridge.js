/*
 * Hatch bridge — ISOLATED-world content script, top frame, document_start.
 * Opens the per-tab native port "hatch-tab" (GeckoView
 * SessionController.setMessageDelegate on the app side) so the app has a
 * per-tab channel without tab-id mapping.
 *   content -> app : {type:"ready", url}, {type:"selfcheck-report", report}
 *   app -> content : {type:"eruda"}, {type:"selfcheck-run"}
 */
(function () {
  'use strict';
  const PORT_NAME = 'hatch-tab';
  const SELFCHECK_TIMEOUT_MS = 120000;
  let port = null;
  let latestResult = null; // set when the harness fires 'hatch:done'

  function post(msg) {
    if (!port) return;
    try { port.postMessage(msg); } catch (e) { port = null; }
  }

  // Convert the page-world harness result into a plain JSON value.
  function cloneResult(value) {
    if (value === undefined || value === null) return null;
    try { return JSON.parse(JSON.stringify(value)); } catch (e) { /* xray edge case */ }
    try {
      const pageJSON = window.wrappedJSObject && window.wrappedJSObject.JSON;
      if (pageJSON) return JSON.parse(pageJSON.stringify(value));
    } catch (e) { /* fall through */ }
    return null;
  }

  function pageResult() {
    try {
      const w = window.wrappedJSObject || window;
      return w.__hatchResult;
    } catch (e) {
      return undefined;
    }
  }

  // Keep the latest result even if 'hatch:done' fires before the app asks.
  window.addEventListener('hatch:done', () => {
    const r = cloneResult(pageResult());
    if (r) latestResult = r;
  }, true);

  function waitForResult() {
    return new Promise((resolve) => {
      const existing = cloneResult(pageResult()) || latestResult;
      if (existing) { resolve({ report: existing }); return; }
      let timer = 0;
      const onDone = () => {
        window.removeEventListener('hatch:done', onDone, true);
        clearTimeout(timer);
        resolve({ report: cloneResult(pageResult()) || latestResult });
      };
      window.addEventListener('hatch:done', onDone, true);
      timer = setTimeout(() => {
        window.removeEventListener('hatch:done', onDone, true);
        resolve({ report: cloneResult(pageResult()) || latestResult, error: 'timeout' });
      }, SELFCHECK_TIMEOUT_MS);
    });
  }

  function onMessage(msg) {
    if (!msg || typeof msg !== 'object') return;
    if (msg.type === 'eruda') {
      try {
        browser.runtime.sendMessage({ type: 'eruda-toggle' }).catch(() => { /* background busy */ });
      } catch (e) { /* extension context gone */ }
    } else if (msg.type === 'selfcheck-run') {
      waitForResult().then((r) => {
        const out = { type: 'selfcheck-report', report: r.report, url: location.href };
        if (r.error) out.error = r.error;
        if (!r.report && !r.error) out.error = 'no result';
        post(out);
      });
    }
  }

  try {
    port = browser.runtime.connectNative(PORT_NAME);
  } catch (e) {
    return; // no app delegate for this session; nothing to do
  }
  port.onMessage.addListener(onMessage);
  port.onDisconnect.addListener(() => { port = null; });
  post({ type: 'ready', url: location.href });
})();
