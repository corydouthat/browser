/*
 * Hatch — runs right after vendor/eruda.min.js in the MAIN world via
 * scripting.executeScript (CSP-exempt). Initialises Eruda once and shows it.
 */
(function () {
  'use strict';
  try {
    var eruda = window.eruda;
    if (!eruda || typeof eruda.init !== 'function') {
      console.error('[hatch] eruda is not loaded');
      return;
    }
    if (!eruda._isInit) {
      eruda.init({
        tool: ['console', 'elements', 'network', 'resources', 'sources', 'info'],
        useShadowDom: true,
        autoScale: true,
        defaults: {
          displaySize: 45,
          transparency: 1,
          theme: 'Dark'
        }
      });
    }
    eruda.show();
  } catch (e) {
    console.error('[hatch] eruda init failed', e);
  }
})();
