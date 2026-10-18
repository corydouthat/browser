/* navigator identity strings. */
export const id = 'ua';

function read(obj, key) {
  try { return key in obj ? obj[key] : null; } catch (e) { return `error: ${e && e.message}`; }
}

export async function probe() {
  const n = navigator;
  let userAgentData = null;
  try {
    if (n.userAgentData) {
      const d = n.userAgentData;
      userAgentData = {
        brands: Array.from(d.brands || [], (b) => ({ brand: b.brand, version: b.version })),
        mobile: d.mobile,
        platform: d.platform,
      };
      try {
        userAgentData.highEntropy = await d.getHighEntropyValues(['platformVersion', 'architecture', 'bitness', 'model', 'uaFullVersion']);
      } catch (e) {
        userAgentData.highEntropyError = String(e && e.message);
      }
    }
  } catch (e) {
    userAgentData = { error: String(e && e.message) };
  }
  return {
    ua: {
      userAgent: read(n, 'userAgent'),
      platform: read(n, 'platform'),
      oscpu: read(n, 'oscpu'),
      appVersion: read(n, 'appVersion'),
      appName: read(n, 'appName'),
      appCodeName: read(n, 'appCodeName'),
      product: read(n, 'product'),
      productSub: read(n, 'productSub'),
      vendor: read(n, 'vendor'),
      vendorSub: read(n, 'vendorSub'),
      buildID: read(n, 'buildID'),
      webdriver: read(n, 'webdriver'),
      userAgentData,
    },
  };
}
