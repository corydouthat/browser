/* Touch / pointer API surface (the JS half; CSS-level pointer queries live in media.js). */
export const id = 'touch';

export async function probe() {
  const w = window;
  const n = navigator;
  let createEventTouch = false;
  let createEventError = null;
  try {
    const ev = document.createEvent('TouchEvent');
    createEventTouch = !!ev;
  } catch (e) {
    createEventTouch = false;
    createEventError = e && e.name ? e.name : String(e);
  }
  let maxTouchPoints = null;
  try { maxTouchPoints = n.maxTouchPoints; } catch (e) { maxTouchPoints = `error: ${e && e.message}`; }
  return {
    touch: {
      maxTouchPoints,
      msMaxTouchPoints: 'msMaxTouchPoints' in n ? n.msMaxTouchPoints : null,
      ontouchstartWindow: 'ontouchstart' in w,
      ontouchstartElement: 'ontouchstart' in document.documentElement,
      ontouchstartDocument: 'ontouchstart' in document,
      touchEventDefined: typeof w.TouchEvent !== 'undefined',
      touchDefined: typeof w.Touch !== 'undefined',
      touchListDefined: typeof w.TouchList !== 'undefined',
      documentTouch: 'DocumentTouch' in w,
      createTouch: typeof document.createTouch === 'function',
      createEventTouch,
      createEventError,
      orientationInWindow: 'orientation' in w,
      onorientationchange: 'onorientationchange' in w,
      pointerEventDefined: typeof w.PointerEvent !== 'undefined',
      onpointerdown: 'onpointerdown' in w,
    },
  };
}
