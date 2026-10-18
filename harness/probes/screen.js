/* screen.*, devicePixelRatio and the layout viewport. */
export const id = 'screen';

function num(fn) { try { const v = fn(); return typeof v === 'number' ? v : (v === undefined ? null : v); } catch (e) { return `error: ${e && e.message}`; } }

export async function probe() {
  const s = window.screen;
  const o = s && s.orientation;
  const vv = window.visualViewport;
  return {
    screen: {
      width: num(() => s.width),
      height: num(() => s.height),
      availWidth: num(() => s.availWidth),
      availHeight: num(() => s.availHeight),
      availLeft: num(() => s.availLeft),
      availTop: num(() => s.availTop),
      colorDepth: num(() => s.colorDepth),
      pixelDepth: num(() => s.pixelDepth),
      dpr: num(() => window.devicePixelRatio),
      orientationType: o ? o.type : null,
      orientationAngle: o ? o.angle : null,
      mozOrientation: 'mozOrientation' in s ? s.mozOrientation : null,
    },
    layout: {
      clientWidth: num(() => document.documentElement.clientWidth),
      clientHeight: num(() => document.documentElement.clientHeight),
      innerWidth: num(() => window.innerWidth),
      innerHeight: num(() => window.innerHeight),
      outerWidth: num(() => window.outerWidth),
      outerHeight: num(() => window.outerHeight),
      screenX: num(() => window.screenX),
      screenY: num(() => window.screenY),
      mozInnerScreenX: num(() => window.mozInnerScreenX),
      mozInnerScreenY: num(() => window.mozInnerScreenY),
      visualViewportWidth: vv ? num(() => vv.width) : null,
      visualViewportScale: vv ? num(() => vv.scale) : null,
      bodyScrollWidth: num(() => document.body.scrollWidth),
    },
  };
}
