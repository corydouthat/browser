// Web-compat review (M0, HATCH-WC-02 fixed): Gecko 158 keeps the real input source when
// dom.w3c_touch_events.enabled=0: pointerdown/pointerup/click from a finger carry pointerType "touch"
// (mozInputSource 5). Verified in headless Firefox 158.0 with a WebDriver touch pointer and the
// win-firefox__us-east golden prefs (PointerEventHandler::InitPointerEventFromTouch copies mInputSource;
// APZCCallbackHelper::DispatchSynthesizedMouseEvent sets MOZ_SOURCE_TOUCH at FIREFOX_158_0_RELEASE).
// The harness therefore gates the device run on "a trusted pointerdown arrived" (interactive.pointerEvents,
// hard) and keeps the value as a soft signal (interactive.pointerType: "mouse" through the shim's trusted-event
// masking; "touch" is the engine truth and only a soft FAIL).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate } from '../evaluate.js';
import { EXPECT_WIN, desktopObserved } from './fixtures.mjs';

const byId = (res, id) => res.signals.find((s) => s.id === id);

test('a trusted "touch" tap on a desktop persona is accepted by the hard gate and only soft-fails the value', () => {
  const o = desktopObserved();
  o.interactive = { pointerType: 'touch', mozInputSource: 5, trusted: true, events: [{ kind: 'pointerdown', type: 'pointerdown', pointerType: 'touch', isPrimary: true, width: 23, pressure: 0.5, mozInputSource: 5, isTrusted: true, at: 0 }] };
  const res = evaluate(o, EXPECT_WIN, 'device');
  const gate = byId(res, 'interactive.pointerEvents');
  assert.equal(gate.scope, 'device');
  assert.equal(gate.tier, 'hard');
  assert.equal(gate.status, 'PASS');
  const value = byId(res, 'interactive.pointerType');
  assert.equal(value.tier, 'soft');
  assert.equal(value.status, 'FAIL');
  assert.match(value.note, /engine reports the real input source/);
  assert.equal(res.summary.overall, 'PASS');
  assert.deepEqual(res.summary.hardFail, []);
});

test('the shim-masked tap ("mouse", mozInputSource 1) passes both signals', () => {
  const o = desktopObserved();
  o.interactive = { pointerType: 'mouse', mozInputSource: 1, trusted: true, events: [] };
  const res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(byId(res, 'interactive.pointerEvents').status, 'PASS');
  assert.equal(byId(res, 'interactive.pointerType').status, 'PASS');
  assert.equal(res.summary.overall, 'PASS');
});

test('"mouse" with a touch mozInputSource is still a soft FAIL (half-masked)', () => {
  const o = desktopObserved();
  o.interactive = { pointerType: 'mouse', mozInputSource: 5, trusted: true, events: [] };
  const res = evaluate(o, EXPECT_WIN, 'device');
  assert.equal(byId(res, 'interactive.pointerType').status, 'FAIL');
  assert.equal(res.summary.overall, 'PASS');
});
