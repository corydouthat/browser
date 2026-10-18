/* Verdicts of the vendored detection libraries (harness/vendor/detectors.bundle.js -> window.__detectors). */
export const id = 'libraries';
export const timeoutMs = 15000;

export async function probe() {
  const d = window.__detectors;
  if (!d || typeof d.run !== 'function') {
    return { libraries: [{ name: 'detectors.bundle', version: null, mobile: null, error: 'vendor/detectors.bundle.js not loaded' }] };
  }
  try {
    const list = await d.run();
    return { libraries: list, librariesManifest: d.versions || null };
  } catch (e) {
    return { libraries: [{ name: 'detectors.bundle', version: null, mobile: null, error: String(e && e.message) }] };
  }
}
