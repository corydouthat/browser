/*
 * Pointer / hover / orientation media queries, measured two ways:
 *   media.* — window.matchMedia (JS; the shim can rewrite these)
 *   css.*   — stylesheet @media rules in breakpoints.css that set a custom property on probe elements
 *             (engine-level; the JS shim cannot touch them)
 */
export const id = 'media';

const QUERIES = {
  pointerFine: '(pointer: fine)',
  pointerCoarse: '(pointer: coarse)',
  pointerNone: '(pointer: none)',
  hoverHover: '(hover: hover)',
  hoverNone: '(hover: none)',
  anyPointerCoarse: '(any-pointer: coarse)',
  anyPointerFine: '(any-pointer: fine)',
  anyHoverHover: '(any-hover: hover)',
  anyHoverNone: '(any-hover: none)',
  orientationPortrait: '(orientation: portrait)',
  minWidth992: '(min-width: 992px)',
  minWidth1024: '(min-width: 1024px)',
  minWidth1200: '(min-width: 1200px)',
  minWidth1280: '(min-width: 1280px)',
  minDeviceWidth1024: '(min-device-width: 1024px)',
  maxDeviceWidth480: '(max-device-width: 480px)',
  minResolution2: '(min-resolution: 2dppx)',
};

/** Probe element ids -> stylesheet rule names (see breakpoints.css). */
export const CSS_PROBES = {
  pointerFine: 'css-pointer-fine',
  hoverHover: 'css-hover-hover',
  anyPointerCoarse: 'css-any-pointer-coarse',
  anyHoverHover: 'css-any-hover-hover',
  bp992: 'css-bp-992',
  bp1024: 'css-bp-1024',
  bp1200: 'css-bp-1200',
  bp1280: 'css-bp-1280',
  minDeviceWidth1024: 'css-min-device-width-1024',
  maxDeviceWidth480: 'css-max-device-width-480',
  minResolution2: 'css-min-resolution-2',
  orientationPortrait: 'css-orientation-portrait',
};

function ensureStylesheet() {
  const existing = Array.from(document.querySelectorAll('link[rel="stylesheet"]')).find((l) => /breakpoints\.css(\?|$)/.test(l.getAttribute('href') || ''));
  if (existing && existing.sheet) return Promise.resolve(existing);
  return new Promise((resolve) => {
    const link = existing || document.createElement('link');
    let done = false;
    const finish = () => { if (!done) { done = true; resolve(link); } };
    link.addEventListener('load', finish, { once: true });
    link.addEventListener('error', finish, { once: true });
    if (!existing) {
      link.rel = 'stylesheet';
      link.href = './breakpoints.css';
      document.head.appendChild(link);
    }
    setTimeout(finish, 3000);
  });
}

function ensureProbeElements() {
  let host = document.getElementById('css-probes');
  if (!host) {
    host = document.createElement('div');
    host.id = 'css-probes';
    host.setAttribute('aria-hidden', 'true');
    host.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:1px;overflow:hidden;';
    document.body.appendChild(host);
  }
  for (const elId of Object.values(CSS_PROBES)) {
    if (!document.getElementById(elId)) {
      const span = document.createElement('span');
      span.id = elId;
      span.className = 'css-probe';
      host.appendChild(span);
    }
  }
}

function hit(elId) {
  const el = document.getElementById(elId);
  if (!el) return null;
  const v = getComputedStyle(el).getPropertyValue('--hit').trim();
  return v === '1';
}

/** JS `matchMedia` device-* / resolution features compared with what `screen.*` / `devicePixelRatio` claim. */
function deviceFeatures() {
  const s = window.screen;
  const dpr = window.devicePixelRatio;
  const mm = (q) => { try { return window.matchMedia(q).matches; } catch (e) { return `error: ${e && e.message}`; } };
  // binary-search the CSS device-width the engine reports (what a stylesheet @media sees)
  let lo = 0; let hi = 8192;
  while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (mm(`(min-device-width: ${mid}px)`) === true) lo = mid; else hi = mid - 1; }
  return {
    screenWidth: s.width, screenHeight: s.height, dpr,
    deviceWidthMatches: mm(`(device-width: ${s.width}px)`),
    deviceHeightMatches: mm(`(device-height: ${s.height}px)`),
    deviceAspectRatioMatches: mm(`(device-aspect-ratio: ${s.width}/${s.height})`),
    resolutionMatches: mm(`(resolution: ${dpr}dppx)`),
    webkitDprMatches: mm(`(-webkit-device-pixel-ratio: ${dpr})`),
    jsDeviceWidth: lo,
  };
}

/** `MediaQueryList.media` must be the engine's normalised serialisation for every spelling. */
function serialization() {
  const cases = ['(pointer:fine)', '(POINTER: FINE)', '(  any-pointer : coarse )', '(hover:hover) and (min-width:100px)', '(pointer: fine)'];
  const out = [];
  for (const q of cases) {
    try { out.push({ query: q, media: window.matchMedia(q).media }); } catch (e) { out.push({ query: q, media: `error: ${e && e.message}` }); }
  }
  let cssom = null;
  try {
    const style = document.createElement('style');
    document.head.appendChild(style);
    cssom = {};
    for (const q of cases) {
      const i = style.sheet.insertRule(`@media ${q} {}`, style.sheet.cssRules.length);
      cssom[q] = style.sheet.cssRules[i].media.mediaText;
    }
    style.remove();
  } catch (e) { cssom = { error: String(e && e.message) }; }
  return { js: out, cssom };
}

export async function probe() {
  const media = {};
  for (const [k, q] of Object.entries(QUERIES)) {
    try {
      const mql = window.matchMedia(q);
      media[k] = mql.matches;
      if (mql.media !== q) media[`${k}MediaText`] = mql.media;
    } catch (e) {
      media[k] = `error: ${e && e.message}`;
    }
  }
  try { media.device = deviceFeatures(); } catch (e) { media.device = { error: String(e && e.message) }; }
  try { media.serialization = serialization(); } catch (e) { media.serialization = { error: String(e && e.message) }; }
  await ensureStylesheet();
  ensureProbeElements();
  // Force style resolution after the elements/stylesheet landed.
  void document.body.offsetWidth;
  const css = {};
  for (const [k, elId] of Object.entries(CSS_PROBES)) css[k] = hit(elId);
  css.stylesheetLoaded = Object.values(css).some((v) => v !== null);
  return { media, css };
}
