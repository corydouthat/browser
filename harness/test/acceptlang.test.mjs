import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { acceptLanguageHeader, navigatorLanguages, canonicalizeLanguageTag, prefValue, expectationFor, generate } from '../../tool/headless/gen-expectations.mjs';

// rust_prepare_accept_languages (netwerk/base/rust-helper/src/lib.rs @ FIREFOX_158_0_RELEASE)
test('Accept-Language: Gecko q-value algorithm', () => {
  assert.equal(acceptLanguageHeader('en, ja'), 'en,ja;q=0.9');
  assert.equal(acceptLanguageHeader('en-US, en'), 'en-US,en;q=0.9');
  assert.equal(acceptLanguageHeader('de-DE, de, en'), 'de-DE,de;q=0.9,en;q=0.8');
  assert.equal(acceptLanguageHeader('ja-JP, ja, en'), 'ja-JP,ja;q=0.9,en;q=0.8');
  assert.equal(acceptLanguageHeader('en'), 'en');
  assert.equal(acceptLanguageHeader(''), '');
});

test('Accept-Language: trimming, old q-values and empty tokens', () => {
  assert.equal(acceptLanguageHeader(' en-US ,\ten'), 'en-US,en;q=0.9');
  assert.equal(acceptLanguageHeader('en-US;q=0.5, en;q=0.3'), 'en-US,en;q=0.9');
  assert.equal(acceptLanguageHeader('en,,ja'), 'en,ja;q=0.9');
  assert.equal(acceptLanguageHeader(',en'), 'en');
  assert.equal(acceptLanguageHeader('en-US en'), 'en-US'); // inner space ends the token
});

test('Accept-Language: canonicalization', () => {
  assert.equal(canonicalizeLanguageTag('EN-us'), 'en-US');
  assert.equal(canonicalizeLanguageTag('zh-hant-tw'), 'zh-Hant-TW');
  assert.equal(canonicalizeLanguageTag('ZH-HANT'), 'zh-Hant');
  assert.equal(canonicalizeLanguageTag('es-419'), 'es-419');
  assert.equal(canonicalizeLanguageTag('x-pig-latin'), 'x-pig-latin');
  assert.equal(canonicalizeLanguageTag('de-DE-x-GOETHE'), 'de-DE-x-goethe');
  assert.equal(canonicalizeLanguageTag('man-nkoo-gn'), 'man-Nkoo-GN');
  assert.equal(canonicalizeLanguageTag('fr_CA'), 'fr_ca'); // underscore is not a subtag separator
  assert.equal(canonicalizeLanguageTag('*'), '*');
});

test('Accept-Language: more than 9 entries floor at q=0.1', () => {
  const langs = ['en', 'de', 'fr', 'es', 'it', 'pt', 'nl', 'sv', 'da', 'fi', 'pl', 'cs'];
  const header = acceptLanguageHeader(langs.join(', '));
  assert.equal(header, 'en,de;q=0.9,fr;q=0.8,es;q=0.7,it;q=0.6,pt;q=0.5,nl;q=0.4,sv;q=0.3,da;q=0.2,fi;q=0.1,pl;q=0.1,cs;q=0.1');
  const ten = acceptLanguageHeader(langs.slice(0, 10).join(','));
  assert.ok(ten.endsWith('fi;q=0.1'));
  const eleven = acceptLanguageHeader(langs.slice(0, 11).join(','));
  assert.ok(eleven.endsWith('fi;q=0.1,pl;q=0.1'));
});

// Navigator::GetAcceptLanguages (dom/base/Navigator.cpp @ FIREFOX_158_0_RELEASE)
test('navigator.languages derivation', () => {
  assert.deepEqual(navigatorLanguages('en-US, en'), ['en-US', 'en']);
  assert.deepEqual(navigatorLanguages('de-de, en-us,en'), ['de-DE', 'en-US', 'en']);
  assert.deepEqual(navigatorLanguages('en_us'), ['en-US']);
  assert.deepEqual(navigatorLanguages('zh-hant'), ['zh-hant']);
  assert.deepEqual(navigatorLanguages('de-DE-x-goethe'), ['de-DE-x-goethe']);
  assert.deepEqual(navigatorLanguages('EN-us'), ['EN-US']);
  assert.deepEqual(navigatorLanguages('en,,ja'), ['en', 'ja']);
  assert.deepEqual(navigatorLanguages(''), []);
});

test('prefValue matches the Kotlin GeckoAcceptLanguage.prefValue format', () => {
  assert.equal(prefValue(['en-US', 'en']), 'en-US, en');
});

test('expectationFor mirrors Persona.expectedReport()', () => {
  const identity = { id: 'win-firefox', name: 'Win', desktop: true, userAgent: 'UA', platform: 'Win32', oscpu: 'Windows NT 10.0; Win64; x64', appVersion: '5.0 (Windows)', layoutWidth: 1366, screen: { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, dpr: 1 }, hardwareConcurrency: 8, webgl: { vendor: 'v', renderer: 'r' }, chromeLike: false, uaData: null, touch: false };
  const region = { id: 'de', name: 'DE', languages: ['de-DE', 'de', 'en'], locale: 'de-DE', timeZone: 'Europe/Berlin', geolocation: { latitude: 52.52, longitude: 13.405, accuracyMeters: 50 } };
  const e = expectationFor(identity, region);
  assert.equal(e.id, 'win-firefox__de');
  assert.equal(e.maxTouchPoints, 0);
  assert.equal(e.hasTouchEvent, false);
  assert.equal(e.pointerFine, true);
  assert.equal(e.minLayoutWidth, 1366);
  assert.deepEqual(e.languages, ['de-DE', 'de', 'en']);
  assert.equal(e.acceptLanguage, 'de-DE,de;q=0.9,en;q=0.8');
  assert.equal(e.locale, 'de-DE');
  assert.equal(e.librariesMobile, false);
  assert.equal(e.userAgentData, false);
  const dev = expectationFor(identity, { id: 'device', name: 'Device', languages: ['en-US', 'en'], locale: 'en-US', timeZone: null, geolocation: null });
  assert.equal(dev.languages, null);
  assert.equal(dev.acceptLanguage, null);
  assert.equal(dev.locale, null);
  assert.equal(dev.timeZone, null);
  const real = expectationFor({ id: 'real-device', name: 'Real', desktop: false, userAgent: null, platform: null, oscpu: null, appVersion: null, layoutWidth: 0, screen: null, hardwareConcurrency: null, webgl: null, chromeLike: false, uaData: null, touch: true }, region);
  assert.equal(real.maxTouchPoints, null);
  assert.equal(real.hasTouchEvent, true);
  assert.equal(real.minLayoutWidth, null);
  assert.equal(real.librariesMobile, null);
});

test('generate writes one file per identity x region plus index.json and is idempotent', () => {
  const out = mkdtempSync(join(tmpdir(), 'hatch-expect-'));
  try {
    const first = generate({ outDir: out });
    assert.equal(first.written.length, 5 * 5 + 1);
    assert.equal(first.changed.length, first.written.length);
    const second = generate({ outDir: out, check: true });
    assert.deepEqual(second.changed, []);
    const index = JSON.parse(readFileSync(join(out, 'index.json'), 'utf8'));
    assert.ok(index.personas.includes('win-firefox__us-east'));
    assert.ok(index.personas.includes('real-device__device'));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test('committed expectation files are current', () => {
  const res = generate({ check: true });
  assert.deepEqual(res.changed, [], 'run node tool/headless/gen-expectations.mjs');
});
