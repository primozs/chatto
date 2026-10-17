#!/usr/bin/env node
/**
 * Build a sideloadable Android APK after syncing the staged web assets.
 *
 * Usage:
 *   node ./scripts/build-apk.mjs          # debug (default)
 *   node ./scripts/build-apk.mjs release
 *
 * Expects `apps/frontend/build` to already exist (`pnpm run build:frontend`
 * from the repo root). Prints the APK path on success.
 */

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const mobileRoot = path.resolve(__dirname, '..');
const androidRoot = path.join(mobileRoot, 'android');
const frontendBuild = path.resolve(mobileRoot, '../frontend/build');

const variant = (process.argv[2] || 'debug').toLowerCase();
if (variant !== 'debug' && variant !== 'release') {
  console.error(`Unknown variant "${variant}". Use debug or release.`);
  process.exit(1);
}

if (!existsSync(frontendBuild)) {
  console.error(
    `Frontend build missing at ${frontendBuild}.\n` +
      'From the repo root run: pnpm run build:frontend'
  );
  process.exit(1);
}

const gradleTask = variant === 'release' ? 'assembleRelease' : 'assembleDebug';
const apkRel =
  variant === 'release'
    ? 'app/build/outputs/apk/release/app-release.apk'
    : 'app/build/outputs/apk/debug/app-debug.apk';
const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';

console.log(`Building Android ${variant} APK (${gradleTask})…`);
const result = spawnSync(gradlew, [gradleTask, '--quiet'], {
  cwd: androidRoot,
  stdio: 'inherit',
  shell: process.platform === 'win32'
});

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

const apkPath = path.join(androidRoot, apkRel);
if (!existsSync(apkPath)) {
  console.error(`Expected APK missing: ${apkPath}`);
  process.exit(1);
}

console.log(`\nAPK ready:\n  ${apkPath}`);
console.log(
  variant === 'debug'
    ? '\nDebug-signed — fine for sideload testing (enable install from unknown sources).'
    : '\nRelease build (currently signed with the debug keystore until a real upload key is configured).'
);
