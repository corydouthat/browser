/*
 * Server-side view: what request headers the engine actually sends. ./echo returns {"headers":{...}}
 * (lowercase names) and carries Accept-CH; the second request proves whether the engine honours it.
 */
export const id = 'server';
export const timeoutMs = 15000;

async function echo(url) {
  const res = await fetch(url, { cache: 'no-store', credentials: 'same-origin', headers: { 'X-Hatch-Probe': '1' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  const headers = {};
  for (const [k, v] of Object.entries(json.headers || {})) headers[String(k).toLowerCase()] = v;
  return { headers, acceptCH: res.headers.get('accept-ch'), status: res.status };
}

export async function probe() {
  const out = {};
  try {
    out.first = await echo('./echo');
    // Give the engine a chance to persist Accept-CH before the second request.
    await new Promise((r) => setTimeout(r, 150));
    out.second = await echo('./echo?second=1');
  } catch (e) {
    out.error = String(e && e.message);
  }
  return { server: out };
}
