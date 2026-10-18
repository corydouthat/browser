/* navigator.geolocation (the shim fakes positions; without it headless Firefox has no provider). */
export const id = 'geo';
export const timeoutMs = 12000;

export async function probe() {
  const g = navigator.geolocation;
  if (!g) return { geo: { status: 'unavailable', permission: null } };
  let permission = null;
  try {
    if (navigator.permissions && navigator.permissions.query) {
      const p = await navigator.permissions.query({ name: 'geolocation' });
      permission = p.state;
    }
  } catch (e) {
    permission = `error: ${e && e.message}`;
  }
  const started = Date.now();
  const pos = await new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    const timer = setTimeout(() => finish({ status: 'timeout' }), 8000);
    try {
      g.getCurrentPosition(
        (p) => {
          clearTimeout(timer);
          const c = p.coords;
          finish({
            status: 'ok', latitude: c.latitude, longitude: c.longitude, accuracy: c.accuracy,
            altitude: c.altitude, heading: c.heading, speed: c.speed, timestamp: p.timestamp,
            positionCtor: Object.prototype.toString.call(p), coordsCtor: Object.prototype.toString.call(c),
          });
        },
        (err) => { clearTimeout(timer); finish({ status: 'error', code: err.code, message: err.message }); },
        { timeout: 7000, maximumAge: 0, enableHighAccuracy: false },
      );
    } catch (e) {
      clearTimeout(timer);
      finish({ status: 'error', code: null, message: String(e && e.message) });
    }
  });
  return {
    geo: {
      ...pos,
      permission,
      elapsedMs: Date.now() - started,
      watchPositionType: typeof g.watchPosition,
    },
  };
}
