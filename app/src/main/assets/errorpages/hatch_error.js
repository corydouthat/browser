/* Hatch error page script. Reads the query string the way AC's errorPageScripts.js does (document.documentURI:
   the error page keeps the failed URL as its location) and fills the page with textContent only. Parameters:
   AC (browser-errorpages 158 ErrorPages.kt): title, button, description, errorCode, showSSL, showHSTS,
   badCertAdvanced, badCertTechInfo, badCertAcceptTemporary, showContinueHttp, continueHttpButton;
   Hatch (ErrorPageParams): u (failed URL), n (one-time token) + po=1 (persona is on), kind (net|lock|warn),
   mode (system|light|dark), l_* / d_* palette colours. */
(function () {
  "use strict";
  var uri = document.documentURI;
  var q = new URLSearchParams(uri.indexOf("?") >= 0 ? uri.substring(uri.indexOf("?") + 1) : "");
  var root = document.documentElement;
  var HEX = /^[0-9A-Fa-f]{6}$/;

  ["l", "d"].forEach(function (m) {
    ["ac", "oa", "bg", "s1", "ink", "mu"].forEach(function (k) {
      var v = q.get(m + "_" + k);
      if (v && HEX.test(v)) root.style.setProperty("--" + m + "-" + k, "#" + v);
    });
  });
  var mode = q.get("mode");
  if (mode === "light" || mode === "dark") root.setAttribute("data-theme", mode);

  /** AC's bad-certificate strings may carry markup; only their text is shown. */
  function plain(s) {
    if (!s) return "";
    return new DOMParser().parseFromString(s, "text/html").body.textContent || "";
  }
  function el(id) { return document.getElementById(id); }

  var title = q.get("title") || "";
  document.title = title;
  el("title").textContent = title;
  el("description").textContent = q.get("description") || "";
  el("code").textContent = q.get("errorCode") || "";

  var kind = q.get("kind");
  var icon = el("icon-" + (kind === "net" || kind === "lock" ? kind : "warn"));
  icon.classList.add("shown");

  var retry = el("retry");
  retry.textContent = q.get("button") || "Try again";
  retry.addEventListener("click", function () { window.location.reload(); });

  // HTTPS-Only: "Try again" would only show this page again (same rule as AC's script).
  if (q.get("showContinueHttp") === "true" && typeof document.reloadWithHttpsOnlyException === "function") {
    retry.hidden = true;
    var cont = el("continueHttp");
    cont.textContent = q.get("continueHttpButton") || "";
    cont.hidden = false;
    cont.addEventListener("click", function () { document.reloadWithHttpsOnlyException(); });
  }

  // "Try with persona off": a plain link the interceptor honours only with this page's token.
  var u = q.get("u");
  var n = q.get("n");
  if (q.get("po") === "1" && u && n && /^https?:\/\//i.test(u)) {
    var off = el("personaOff");
    off.href = "hatch-internal://persona-off?u=" + encodeURIComponent(u) + "&n=" + encodeURIComponent(n);
    off.hidden = false;
  }

  // Bad certificate / HSTS: the advanced panel, as in AC's page (accepting needs document.addCertException).
  var canAccept = typeof document.addCertException !== "undefined";
  var showSSL = q.get("showSSL") === "true" && canAccept;
  var showHSTS = q.get("showHSTS") === "true";
  if (showSSL || showHSTS) {
    var adv = el("advanced");
    adv.textContent = plain(q.get("badCertAdvanced"));
    adv.hidden = false;
    el("techInfo").textContent = plain(q.get("badCertTechInfo"));
    var accept = el("acceptRisk");
    if (showSSL && !showHSTS) {
      accept.textContent = plain(q.get("badCertAcceptTemporary"));
      accept.hidden = false;
      accept.addEventListener("click", function () {
        document.addCertException(true).then(function () { window.location.reload(); }, function (e) { console.error(e); });
      });
    }
    adv.addEventListener("click", function () { el("advancedPanel").hidden = !el("advancedPanel").hidden; });
  }

  // Desktop personas run GeckoView's desktop viewport (meta viewport ignored, ~980 px layout zoomed out to fit), so
  // the page would render at a third of its size. Undo that zoom once, from the visual viewport's scale at load.
  function fitToScreen() {
    var vv = window.visualViewport;
    if (!vv || !vv.scale || vv.scale >= 0.9) return;
    root.style.zoom = String(1 / vv.scale);
  }
  window.addEventListener("load", fitToScreen, { once: true });
})();
