/* WebGL vendor / renderer (unmasked via WEBGL_debug_renderer_info). */
export const id = 'webgl';

export async function probe() {
  const out = { available: false, webgl2: false };
  try {
    const canvas = document.createElement('canvas');
    canvas.width = 8; canvas.height = 8;
    const gl = canvas.getContext('webgl', { failIfMajorPerformanceCaveat: false }) || canvas.getContext('experimental-webgl');
    if (gl) {
      out.available = true;
      out.vendor = gl.getParameter(gl.VENDOR);
      out.renderer = gl.getParameter(gl.RENDERER);
      out.version = gl.getParameter(gl.VERSION);
      out.shadingLanguageVersion = gl.getParameter(gl.SHADING_LANGUAGE_VERSION);
      const ext = gl.getExtension('WEBGL_debug_renderer_info');
      out.debugExtension = !!ext;
      if (ext) {
        out.unmaskedVendor = gl.getParameter(ext.UNMASKED_VENDOR_WEBGL);
        out.unmaskedRenderer = gl.getParameter(ext.UNMASKED_RENDERER_WEBGL);
      } else {
        out.unmaskedVendor = null;
        out.unmaskedRenderer = null;
      }
      const ext2 = gl.getSupportedExtensions();
      out.extensionCount = ext2 ? ext2.length : null;
      const lose = gl.getExtension('WEBGL_lose_context');
      if (lose) lose.loseContext();
    }
    const c2 = document.createElement('canvas');
    out.webgl2 = !!c2.getContext('webgl2');
  } catch (e) {
    out.error = String(e && e.message);
  }
  return { webgl: out };
}
