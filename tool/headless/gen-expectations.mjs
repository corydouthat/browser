#!/usr/bin/env node
/*
 * Generates harness/personas/<identityId>__<regionId>.json (+ index.json) from the preset source of
 * truth personas/identities.json + personas/regions.json. The files mirror the Kotlin
 * `Persona.expectedReport()` contract (persona/src/main/kotlin/app/hatch/persona/Report.kt) so the harness,
 * the in-app Self-check and a future iOS port compare against the same values.
 *
 *   node tool/headless/gen-expectations.mjs [--check]     (--check: fail if files would change)
 *
 * Accept-Language and navigator.languages are derived with Gecko 158's own algorithms (verified against
 * tag FIREFOX_158_0_RELEASE):
 *   - header: netwerk/protocol/http/nsHttpHandler.cpp PrepareAcceptLanguages ->
 *     netwerk/base/rust-helper/src/lib.rs rust_prepare_accept_languages (split on ',', trim HTTP LWS and
 *     anything from ';', drop empties, canonicalize_language_tag, q = max(10 - min(10, i), 1) / 10,
 *     written as ";q=0.N" for i > 0 while q < 10 -> entries 10+ all carry q=0.1).
 *   - navigator.languages: dom/base/Navigator.cpp Navigator::GetAcceptLanguages (split on ',', tokenizer
 *     trims whitespace, "xx_YY" -> "xx-YY", uppercase every 2-letter subtag after the first; no lowercasing).
 */
import { readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO = resolve(HERE, '..', '..');
export const PRESETS_DIR = join(REPO, 'personas');
export const OUT_DIR = join(REPO, 'harness', 'personas');

const REAL_DEVICE_ID = 'real-device';
const DEVICE_REGION_ID = 'device';

// ---- Gecko Accept-Language (rust_prepare_accept_languages) ---------------------------------------------

const HTTP_LWS = [' ', '\t'];

function trimToken(token) {
  let start = 0;
  while (start < token.length && HTTP_LWS.includes(token[start])) start++;
  let end = start;
  while (end < token.length && token[end] !== ';' && !HTTP_LWS.includes(token[end])) end++;
  return token.slice(start, end);
}

function asciiLower(s) { return s.replace(/[A-Z]/g, (c) => c.toLowerCase()); }
function asciiUpper(s) { return s.replace(/[a-z]/g, (c) => c.toUpperCase()); }

/** canonicalize_language_tag: lowercase all, then per subtag after the first: 1 -> stop, 2 -> UPPER, 4 -> Title. */
export function canonicalizeLanguageTag(token) {
  const parts = asciiLower(token).split('-');
  for (let i = 1; i < parts.length; i++) {
    const sub = parts[i];
    if (sub.length === 1) break;
    if (sub.length === 2) parts[i] = asciiUpper(sub);
    else if (sub.length === 4) parts[i] = asciiUpper(sub[0]) + sub.slice(1);
  }
  return parts.join('-');
}

/** The exact Accept-Language header Gecko 158 emits for an `intl.accept_languages` pref value. */
export function acceptLanguageHeader(prefValue) {
  if (!prefValue) return '';
  const tokens = prefValue.split(',').map(trimToken).filter((t) => t.length > 0);
  let out = '';
  tokens.forEach((token, i) => {
    if (i !== 0) out += ',';
    out += canonicalizeLanguageTag(token);
    const qMax = 10;
    const step = Math.min(10, i);
    const q = Math.max(qMax - step, 1);
    if (i > 0 && q < 10) out += `;q=0.${q}`;
  });
  return out;
}

// ---- navigator.languages (Navigator::GetAcceptLanguages) --------------------------------------------------

/** What navigator.languages reports for an `intl.accept_languages` pref value. */
export function navigatorLanguages(prefValue) {
  if (!prefValue) return [];
  const out = [];
  for (let lang of prefValue.split(',')) {
    lang = lang.trim(); // nsCCharSeparatedTokenizer trims whitespace around tokens
    if (lang.length === 0) continue; // default tokenizer flags drop empty tokens
    if (lang.length > 2 && lang[2] === '_') lang = `${lang.slice(0, 2)}-${lang.slice(3)}`;
    if (lang.length > 2) {
      const codes = lang.split('-');
      lang = codes.map((code, i) => (i > 0 && code.length === 2 ? code.toUpperCase() : code)).join('-');
    }
    out.push(lang);
  }
  return out;
}

/** The pref value the app writes for a region (Kotlin GeckoAcceptLanguage.prefValue: `languages.joinToString(", ")`). */
export function prefValue(languages) { return languages.join(', '); }

// ---- expectation files ------------------------------------------------------------------------------------

export function expectationFor(identity, region) {
  const deviceRegion = region.id === DEVICE_REGION_ID;
  const pref = prefValue(region.languages);
  const chromeLike = !!identity.chromeLike && !!identity.uaData;
  return {
    id: `${identity.id}__${region.id}`,
    identity: identity.id,
    region: region.id,
    name: `${identity.name} / ${region.name}`,
    desktop: !!identity.desktop,
    touch: !!identity.touch,
    chromeLike,
    tzShim: false,
    userAgent: identity.userAgent ?? null,
    platform: identity.platform ?? null,
    oscpu: identity.oscpu ?? null,
    appVersion: identity.appVersion ?? null,
    maxTouchPoints: identity.touch ? null : 0,
    hasTouchEvent: !!identity.touch,
    pointerFine: !identity.touch,
    hoverHover: !identity.touch,
    minLayoutWidth: identity.desktop ? identity.layoutWidth : null,
    screen: identity.screen ?? null,
    dpr: identity.screen ? identity.screen.dpr : null,
    hardwareConcurrency: identity.hardwareConcurrency ?? null,
    languages: deviceRegion ? null : navigatorLanguages(pref),
    acceptLanguage: deviceRegion ? null : acceptLanguageHeader(pref),
    timeZone: region.timeZone ?? null,
    geolocation: region.geolocation ?? null,
    webgl: identity.webgl ?? null,
    userAgentData: chromeLike,
    uaData: chromeLike ? identity.uaData : null,
    locale: deviceRegion ? null : region.locale,
    librariesMobile: identity.id === REAL_DEVICE_ID || !identity.desktop ? null : false,
  };
}

function stableJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

export function generate({ check = false, outDir = OUT_DIR, presetsDir = PRESETS_DIR } = {}) {
  const identities = JSON.parse(readFileSync(join(presetsDir, 'identities.json'), 'utf8'));
  const regions = JSON.parse(readFileSync(join(presetsDir, 'regions.json'), 'utf8'));
  mkdirSync(outDir, { recursive: true });
  const files = new Map();
  for (const identity of identities) {
    for (const region of regions) {
      const exp = expectationFor(identity, region);
      files.set(`${exp.id}.json`, stableJson(exp));
    }
  }
  const index = {
    generatedFrom: ['personas/identities.json', 'personas/regions.json'],
    identities: identities.map((i) => i.id),
    regions: regions.map((r) => r.id),
    personas: [...files.keys()].map((f) => f.replace(/\.json$/, '')),
  };
  files.set('index.json', stableJson(index));
  const changed = [];
  for (const [name, content] of files) {
    const path = join(outDir, name);
    const current = existsSync(path) ? readFileSync(path, 'utf8') : null;
    if (current !== content) {
      changed.push(name);
      if (!check) writeFileSync(path, content);
    }
  }
  const stale = existsSync(outDir)
    ? readdirSync(outDir).filter((f) => f.endsWith('.json') && !files.has(f))
    : [];
  return { written: [...files.keys()], changed, stale };
}

const invokedDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  const check = process.argv.includes('--check');
  const res = generate({ check });
  if (check) {
    if (res.changed.length || res.stale.length) {
      console.error(`expectations out of date: changed=[${res.changed.join(', ')}] stale=[${res.stale.join(', ')}]`);
      console.error('run: node tool/headless/gen-expectations.mjs');
      process.exit(1);
    }
    console.log(`expectations up to date (${res.written.length} files)`);
  } else {
    console.log(`wrote ${res.written.length} files to harness/personas (${res.changed.length} changed${res.stale.length ? `, stale: ${res.stale.join(', ')}` : ''})`);
  }
}
