#!/usr/bin/env node
/*
 * Builds harness/vendor/detectors.bundle.js (esbuild IIFE) from vendor/entry.js + a Modernizr custom
 * build (touchevents + pointerevents, via the modernizr CLI), and writes vendor/MANIFEST.json with
 * name@version + sha256 for every vendored library and the bundle itself.
 *
 *   node tool/build-vendor.mjs            # rebuild + write bundle and manifest
 *   node tool/build-vendor.mjs --check    # rebuild in memory and fail if bundle/manifest differ (make vendor-check)
 *
 * The bundle must stay <= MAX_BUNDLE_BYTES (1.5 MB).
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const HARNESS = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const VENDOR = join(HARNESS, 'vendor');
const BUILD_DIR = join(HARNESS, 'build', 'vendor');
const BUNDLE = join(VENDOR, 'detectors.bundle.js');
const MANIFEST = join(VENDOR, 'MANIFEST.json');
const MODERNIZR_CONFIG = join(VENDOR, 'modernizr-config.json');
const MODERNIZR_OUT = join(BUILD_DIR, 'modernizr-custom.js');
const MAX_BUNDLE_BYTES = 1.5 * 1024 * 1024;

const LIBRARIES = ['ua-parser-js', 'mobile-detect', 'ismobilejs', 'is-mobile', 'detect-it', 'bowser', 'platform',
  'react-device-detect', 'device-detector-js', 'modernizr'];
const PEERS = ['react', 'react-dom'];

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

function installedVersion(name) {
  const p = join(HARNESS, 'node_modules', name, 'package.json');
  if (!existsSync(p)) throw new Error(`${name} is not installed; run npm ci in harness/`);
  return readJson(p).version;
}

function lockIntegrity(lock, name) {
  const entry = lock.packages && lock.packages[`node_modules/${name}`];
  return entry ? { integrity: entry.integrity || null, resolved: entry.resolved || null } : { integrity: null, resolved: null };
}

function buildModernizr() {
  mkdirSync(BUILD_DIR, { recursive: true });
  const cli = join(HARNESS, 'node_modules', 'modernizr', 'bin', 'modernizr');
  execFileSync(process.execPath, [cli, '-c', MODERNIZR_CONFIG, '-d', MODERNIZR_OUT], { stdio: ['ignore', 'pipe', 'inherit'] });
  if (!existsSync(MODERNIZR_OUT)) throw new Error('modernizr CLI produced no output');
  return readFileSync(MODERNIZR_OUT);
}

async function bundle(versions) {
  const result = await esbuild.build({
    entryPoints: [join(VENDOR, 'entry.js')],
    bundle: true,
    minify: true,
    format: 'iife',
    platform: 'browser',
    target: ['firefox128', 'chrome120'],
    legalComments: 'none',
    sourcemap: false,
    write: false,
    logLevel: 'warning',
    define: {
      'process.env.NODE_ENV': '"production"',
      __VERSIONS__: JSON.stringify(versions),
    },
    banner: { js: `/* Hatch detectors bundle: ${LIBRARIES.map((n) => `${n}@${versions[n]}`).join(', ')} (+ ${PEERS.map((n) => `${n}@${versions[n]}`).join(', ')}). Built by harness/tool/build-vendor.mjs; do not edit. */` },
  });
  return Buffer.from(result.outputFiles[0].contents);
}

async function main() {
  const check = process.argv.includes('--check');
  const pkg = readJson(join(HARNESS, 'package.json'));
  const lock = existsSync(join(HARNESS, 'package-lock.json')) ? readJson(join(HARNESS, 'package-lock.json')) : { packages: {} };
  const versions = {};
  const problems = [];
  for (const name of [...LIBRARIES, ...PEERS]) {
    const pinned = pkg.dependencies[name] || pkg.devDependencies[name];
    const installed = installedVersion(name);
    if (pinned !== installed) problems.push(`${name}: package.json pins ${pinned} but node_modules has ${installed}`);
    versions[name] = installed;
  }
  if (problems.length) throw new Error(problems.join('\n'));

  const modernizr = buildModernizr();
  const bundleBytes = await bundle(versions);
  if (bundleBytes.length > MAX_BUNDLE_BYTES) {
    throw new Error(`bundle is ${bundleBytes.length} bytes, over the ${MAX_BUNDLE_BYTES} byte budget`);
  }
  const manifest = {
    generatedBy: 'harness/tool/build-vendor.mjs',
    esbuild: installedVersion('esbuild'),
    bundle: { file: 'detectors.bundle.js', bytes: bundleBytes.length, sha256: sha256(bundleBytes) },
    modernizr: { version: versions.modernizr, config: readJson(MODERNIZR_CONFIG)['feature-detects'], sha256: sha256(modernizr) },
    libraries: [...LIBRARIES, ...PEERS].map((name) => {
      const main = resolveMain(name);
      return {
        name, version: versions[name], id: `${name}@${versions[name]}`,
        role: PEERS.includes(name) ? 'peer' : 'detector',
        ...lockIntegrity(lock, name),
        entrySha256: main ? sha256(readFileSync(main)) : null,
      };
    }),
  };
  const manifestText = `${JSON.stringify(manifest, null, 2)}\n`;

  if (check) {
    const diffs = [];
    if (!existsSync(BUNDLE)) diffs.push('vendor/detectors.bundle.js is missing');
    else if (sha256(readFileSync(BUNDLE)) !== manifest.bundle.sha256) diffs.push(`vendor/detectors.bundle.js differs (committed ${sha256(readFileSync(BUNDLE)).slice(0, 12)}, rebuilt ${manifest.bundle.sha256.slice(0, 12)})`);
    if (!existsSync(MANIFEST)) diffs.push('vendor/MANIFEST.json is missing');
    else {
      const current = readJson(MANIFEST);
      const strip = (m) => JSON.stringify({ ...m, generatedBy: undefined });
      if (strip(current) !== strip(manifest)) diffs.push('vendor/MANIFEST.json differs from a fresh build');
    }
    if (diffs.length) {
      console.error(`vendor-check FAILED:\n  ${diffs.join('\n  ')}\nrun: npm run vendor-build (in harness/) and commit the result`);
      process.exit(1);
    }
    console.log(`vendor-check ok: ${manifest.bundle.file} ${manifest.bundle.bytes} bytes sha256 ${manifest.bundle.sha256.slice(0, 12)}… (${LIBRARIES.length} libraries)`);
    return;
  }
  writeFileSync(BUNDLE, bundleBytes);
  writeFileSync(MANIFEST, manifestText);
  console.log(`wrote ${BUNDLE} (${bundleBytes.length} bytes, sha256 ${manifest.bundle.sha256.slice(0, 12)}…) and MANIFEST.json`);
}

function resolveMain(name) {
  const dir = join(HARNESS, 'node_modules', name);
  const pkg = readJson(join(dir, 'package.json'));
  const candidates = [];
  if (typeof pkg.exports === 'string') candidates.push(pkg.exports);
  if (pkg.exports && typeof pkg.exports === 'object' && pkg.exports['.']) {
    const dot = pkg.exports['.'];
    if (typeof dot === 'string') candidates.push(dot);
    else candidates.push(dot.import, dot.require, dot.default, dot.browser);
  }
  candidates.push(pkg.module, pkg.main, 'index.js');
  for (const c of candidates) {
    if (!c) continue;
    const p = join(dir, c);
    if (existsSync(p)) return p;
  }
  return null;
}

main().catch((e) => { console.error(e && e.stack ? e.stack : String(e)); process.exit(1); });
