#!/usr/bin/env node
/**
 * Sync brand sources from apps/frontend, run @capacitor/assets for Android
 * launcher icons + splash screens, then build monochrome notification icons
 * (Capacitor Assets does not generate those).
 *
 * Brand colors match apps/frontend/scripts/generate-icons.mjs.
 */

import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, unlinkSync, existsSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const mobileRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(mobileRoot, '../..');
const assetsDir = path.join(mobileRoot, 'assets');
const androidRes = path.join(mobileRoot, 'android/app/src/main/res');

const ICON_BG = '#c5a4d4';
const SPLASH_BG = '#c5a4d4';
const ICON_BG_DARK = '#2a1f33';
const SPLASH_BG_DARK = '#2a1f33';

/** Frontend brand masters (tracked in apps/frontend). */
const FRONTEND_MASKABLE = path.join(
  repoRoot,
  'apps/frontend/src/lib/assets/chatto-icon-maskable.png'
);
const FRONTEND_ROUNDED = path.join(
  repoRoot,
  'apps/frontend/src/lib/assets/chatto-icon.png'
);

/** Android status-bar / notification icon sizes (white silhouette). */
const NOTIFICATION_DENSITIES = [
  { dir: 'drawable-mdpi', size: 24 },
  { dir: 'drawable-hdpi', size: 36 },
  { dir: 'drawable-xhdpi', size: 48 },
  { dir: 'drawable-xxhdpi', size: 72 },
  { dir: 'drawable-xxxhdpi', size: 96 }
];

/**
 * Key out the brand lavender fill so @capacitor/assets can composite the mark
 * onto icon/splash background colors (avoids a nested purple square on splash).
 */
async function makeTransparentLogo(sourcePath, destPath) {
  const bg = { r: 0xc5, g: 0xa4, b: 0xd4 };
  const { data, info } = await sharp(sourcePath)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const out = Buffer.alloc(data.length);
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const a = data[i + 3];
    const dr = r - bg.r;
    const dg = g - bg.g;
    const db = b - bg.b;
    const dist = Math.sqrt(dr * dr + dg * dg + db * db);
    const isBg = dist < 28 || a < 16;
    out[i] = r;
    out[i + 1] = g;
    out[i + 2] = b;
    out[i + 3] = isBg ? 0 : a;
  }

  await sharp(out, {
    raw: { width: info.width, height: info.height, channels: 4 }
  })
    .png()
    .toFile(destPath);
}

async function syncSources() {
  mkdirSync(assetsDir, { recursive: true });
  copyFileSync(FRONTEND_MASKABLE, path.join(assetsDir, 'logo-source.png'));
  copyFileSync(FRONTEND_ROUNDED, path.join(assetsDir, 'icon-rounded.png'));
  await makeTransparentLogo(
    FRONTEND_MASKABLE,
    path.join(assetsDir, 'logo.png')
  );
  console.log('Synced logo.png (transparent) from frontend maskable asset');
}

function runCapacitorAssets() {
  const result = spawnSync(
    'pnpm',
    [
      'exec',
      'capacitor-assets',
      'generate',
      '--android',
      '--assetPath',
      'assets',
      '--androidProject',
      'android',
      '--iconBackgroundColor',
      ICON_BG,
      '--iconBackgroundColorDark',
      ICON_BG_DARK,
      '--splashBackgroundColor',
      SPLASH_BG,
      '--splashBackgroundColorDark',
      SPLASH_BG_DARK,
      '--logoSplashScale',
      '0.35'
    ],
    { cwd: mobileRoot, stdio: 'inherit', shell: false }
  );
  if (result.status !== 0) {
    throw new Error(`capacitor-assets exited with ${result.status}`);
  }
}

/**
 * Build a white-on-transparent silhouette from the brand mark so Android can
 * tint it in the status bar / notification shade.
 */
async function generateNotificationIcons() {
  // logo.png is already background-keyed; paint remaining opaque pixels white.
  const source = path.join(assetsDir, 'logo.png');
  const { data, info } = await sharp(source)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const out = Buffer.alloc(data.length);
  for (let i = 0; i < data.length; i += 4) {
    const a = data[i + 3];
    out[i] = 255;
    out[i + 1] = 255;
    out[i + 2] = 255;
    out[i + 3] = a;
  }

  const master = await sharp(out, {
    raw: { width: info.width, height: info.height, channels: 4 }
  })
    .png()
    .toBuffer();

  for (const { dir, size } of NOTIFICATION_DENSITIES) {
    const destDir = path.join(androidRes, dir);
    mkdirSync(destDir, { recursive: true });
    const dest = path.join(destDir, 'ic_notification.png');
    await sharp(master).resize(size, size, { fit: 'contain' }).png().toFile(dest);
    console.log(`Wrote ${path.relative(mobileRoot, dest)} (${size}x${size})`);
  }

  // Prefer density PNGs over the old generic chat-bubble vector.
  const legacyVector = path.join(androidRes, 'drawable/ic_notification.xml');
  if (existsSync(legacyVector)) {
    unlinkSync(legacyVector);
    console.log('Removed drawable/ic_notification.xml (replaced by density PNGs)');
  }

  // Keep a vector-free default: point any leftover references at mdpi via
  // documenting in README. Service already uses R.drawable.ic_notification.
}

function writeAssetsReadme() {
  const readme = path.join(assetsDir, 'README.md');
  writeFileSync(
    readme,
    `# Mobile brand assets

Source masters live in \`apps/frontend/src/lib/assets/\`:

- \`chatto-icon-maskable.png\` → \`logo-source.png\` (verbatim) and \`logo.png\`
  (lavender keyed out for Capacitor Assets easy mode)
- \`chatto-icon.png\` → \`icon-rounded.png\` (reference only)

Regenerate Android launcher icons, splash screens, and notification icons:

\`\`\`sh
pnpm --filter chatto-mobile generate:assets
\`\`\`

Uses \`@capacitor/assets\` for icons/splash and a monochrome silhouette pass for
\`ic_notification\` (status bar / OS banners). Brand background \`#c5a4d4\`
matches the frontend PWA icon generator.
`
  );
}

function cleanupLegacyDrawables() {
  for (const rel of [
    'drawable/ic_launcher_background.xml',
    'drawable-v24/ic_launcher_foreground.xml'
  ]) {
    const p = path.join(androidRes, rel);
    if (existsSync(p)) {
      unlinkSync(p);
      console.log(`Removed legacy ${rel}`);
    }
  }
  // Keep the color resource aligned with generated mipmap backgrounds.
  const colorXml = path.join(androidRes, 'values/ic_launcher_background.xml');
  writeFileSync(
    colorXml,
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">${ICON_BG}</color>
</resources>
`
  );
}

async function main() {
  await syncSources();
  writeAssetsReadme();
  runCapacitorAssets();
  await generateNotificationIcons();
  cleanupLegacyDrawables();
  console.log('Mobile assets generated.');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
