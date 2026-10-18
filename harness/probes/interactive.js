/*
 * Device-only: the user taps a box. Gecko keeps the real input source on pointer/mouse events even with
 * dom.w3c_touch_events.enabled=0 (pointerType "touch", mozInputSource 5); the shim masks trusted events to
 * "mouse" / 1 for desktop personas. `pointerEvents` (a trusted pointerdown arrived) is the hard device gate,
 * `pointerType` the soft one. The probe returns immediately; harness.js re-evaluates when onPointer fires.
 */
export const id = 'interactive';

const listeners = new Set();
let state = { pointerType: null, mozInputSource: null, trusted: null, events: [] };
let installed = false;

export function current() { return state; }

export function onPointer(fn) { listeners.add(fn); return () => listeners.delete(fn); }

function record(kind, ev) {
  const entry = { kind, type: ev.type, pointerType: ev.pointerType === undefined ? null : ev.pointerType, isPrimary: ev.isPrimary === undefined ? null : ev.isPrimary, width: ev.width === undefined ? null : ev.width, pressure: ev.pressure === undefined ? null : ev.pressure, mozInputSource: ev.mozInputSource === undefined ? null : ev.mozInputSource, isTrusted: ev.isTrusted === true, at: Date.now() };
  state = { ...state, events: [...state.events.slice(-19), entry] };
  if (kind === 'pointerdown') state = { ...state, pointerType: entry.pointerType || 'unknown', mozInputSource: entry.mozInputSource, trusted: entry.isTrusted };
  else if (kind === 'touchstart' && state.pointerType === null) state = { ...state, pointerType: 'touch' };
  else if (kind === 'mousedown' && state.pointerType === null && typeof window.PointerEvent === 'undefined') state = { ...state, pointerType: 'mouse' };
  for (const fn of listeners) { try { fn(state); } catch (e) { /* listener error */ } }
}

export function install(box) {
  if (installed || !box) return;
  installed = true;
  box.addEventListener('pointerdown', (ev) => record('pointerdown', ev));
  box.addEventListener('pointerup', (ev) => record('pointerup', ev));
  box.addEventListener('touchstart', (ev) => record('touchstart', ev), { passive: true });
  box.addEventListener('mousedown', (ev) => record('mousedown', ev));
  box.addEventListener('contextmenu', (ev) => { record('contextmenu', ev); ev.preventDefault(); });
}

export async function probe() {
  install(document.getElementById('tap-box'));
  return { interactive: state };
}
