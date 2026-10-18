/*
 * Source of harness/vendor/detectors.bundle.js (built by harness/tool/build-vendor.mjs with esbuild).
 * Exposes window.__detectors = { versions, run() } where run() returns one verdict per library:
 *   { name, version, mobile: boolean|null, detail }   (mobile === null: no verdict / not applicable)
 * Every library is the exact version pinned in harness/package.json (see vendor/MANIFEST.json).
 * Libraries that evaluate the environment at import time (detect-it, react-device-detect, Modernizr)
 * run after the persona shim (document_start) because this bundle is a normal page script.
 */
import { UAParser } from 'ua-parser-js';
import MobileDetect from 'mobile-detect';
import isMobileJs from 'ismobilejs';
import isMobile from 'is-mobile';
import * as detectIt from 'detect-it';
import Bowser from 'bowser';
import platform from 'platform';
import * as rdd from 'react-device-detect';
import DeviceDetector from 'device-detector-js';
import '../build/vendor/modernizr-custom.js';

// Injected by the build script from package.json / node_modules.
const VERSIONS = __VERSIONS__;

function verdict(name, fn) {
  try {
    const r = fn();
    return Promise.resolve(r).then(
      (v) => ({ name, version: VERSIONS[name] || null, mobile: typeof v.mobile === 'boolean' ? v.mobile : null, detail: v.detail === undefined ? null : v.detail }),
      (e) => ({ name, version: VERSIONS[name] || null, mobile: null, error: String(e && e.message) }),
    );
  } catch (e) {
    return Promise.resolve({ name, version: VERSIONS[name] || null, mobile: null, error: String(e && e.message) });
  }
}

const MOBILE_TYPES = new Set(['mobile', 'tablet', 'smartphone', 'phablet', 'feature phone', 'wearable']);

async function run() {
  const ua = navigator.userAgent;
  return Promise.all([
    verdict('ua-parser-js', async () => {
      const parser = new UAParser();
      const plain = parser.getResult();
      const feature = parser.getResult().withFeatureCheck();
      let hints;
      try { hints = await parser.getResult().withClientHints(); } catch (e) { hints = { error: String(e && e.message) }; }
      const type = feature.device && feature.device.type;
      const hintType = hints && hints.device ? hints.device.type : undefined;
      return {
        mobile: MOBILE_TYPES.has(type) || MOBILE_TYPES.has(hintType),
        detail: {
          browser: `${plain.browser.name || ''} ${plain.browser.version || ''}`.trim(),
          os: `${plain.os.name || ''} ${plain.os.version || ''}`.trim(),
          deviceType: type || 'desktop(undefined)',
          withClientHintsDeviceType: hintType || 'desktop(undefined)',
          withClientHintsOs: hints && hints.os ? `${hints.os.name || ''} ${hints.os.version || ''}`.trim() : null,
          engine: plain.engine.name || null,
        },
      };
    }),
    verdict('mobile-detect', () => {
      const md = new MobileDetect(ua);
      return { mobile: md.mobile() !== null, detail: { mobile: md.mobile(), phone: md.phone(), tablet: md.tablet(), os: md.os() } };
    }),
    verdict('ismobilejs', () => {
      const r = isMobileJs(navigator);
      return { mobile: !!r.any, detail: { any: r.any, phone: r.phone, tablet: r.tablet, apple: r.apple.device, android: r.android.device } };
    }),
    verdict('is-mobile', () => {
      const m = isMobile({ ua: navigator, tablet: true, featureDetect: true });
      return { mobile: !!m, detail: { phoneOnly: isMobile({ ua: navigator, tablet: false, featureDetect: true }) } };
    }),
    verdict('detect-it', () => ({
      mobile: detectIt.deviceType === 'touchOnly' || detectIt.primaryInput === 'touch',
      detail: { deviceType: detectIt.deviceType, primaryInput: detectIt.primaryInput, supportsTouchEvents: detectIt.supportsTouchEvents, supportsPointerEvents: detectIt.supportsPointerEvents, supportsPassiveEvents: detectIt.supportsPassiveEvents },
    })),
    verdict('bowser', () => {
      const p = Bowser.getParser(ua);
      const type = p.getPlatformType(true);
      return { mobile: type === 'mobile' || type === 'tablet', detail: { platformType: type || 'desktop(empty)', browser: p.getBrowserName(), os: p.getOSName(), engine: p.getEngineName() } };
    }),
    verdict('platform', () => {
      const p = platform.parse(ua);
      const family = (p.os && p.os.family) || '';
      const mobileOs = /Android|iOS|Windows Phone|BlackBerry|Firefox OS|webOS|Symbian/i.test(family);
      return { mobile: !!p.product || mobileOs, detail: { name: p.name, version: p.version, os: String(p.os), product: p.product || null, manufacturer: p.manufacturer || null, layout: p.layout } };
    }),
    verdict('react-device-detect', () => ({
      mobile: !!rdd.isMobile,
      detail: { isMobile: rdd.isMobile, isMobileOnly: rdd.isMobileOnly, isTablet: rdd.isTablet, isDesktop: rdd.isDesktop, deviceType: rdd.deviceType, osName: rdd.osName, browserName: rdd.browserName, isAndroid: rdd.isAndroid, isIOS: rdd.isIOS },
    })),
    verdict('device-detector-js', () => {
      const r = new DeviceDetector().parse(ua);
      const type = r.device && r.device.type ? r.device.type : '';
      return { mobile: MOBILE_TYPES.has(type), detail: { deviceType: type || 'desktop(empty)', os: r.os ? `${r.os.name || ''} ${r.os.version || ''}`.trim() : null, client: r.client ? `${r.client.name || ''} ${r.client.version || ''}`.trim() : null, bot: r.bot || null } };
    }),
    verdict('modernizr', () => {
      const M = window.Modernizr;
      if (!M) throw new Error('Modernizr global missing');
      return { mobile: !!M.touchevents, detail: { touchevents: M.touchevents, pointerevents: M.pointerevents, version: M._version } };
    }),
  ]);
}

window.__detectors = { versions: VERSIONS, run };
