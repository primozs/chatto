import { access, cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

/**
 * Assemble apps/mobile/www for Capacitor:
 * 1. Copy the frontend static SPA build
 * 2. Ensure index.html exists (Capacitor entry; SPA shell is 200.html)
 * 3. Bundle and inject capacitor-glue.js before the SPA boots
 */
const mobileRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const require = createRequire(path.join(mobileRoot, 'package.json'));
const frontendBuild = path.resolve(mobileRoot, '../frontend/build');
const wwwDir = path.join(mobileRoot, 'www');
const spaShell = path.join(frontendBuild, '200.html');
const glueEntry = path.join(mobileRoot, 'src/glue.ts');
const glueOut = path.join(wwwDir, 'capacitor-glue.js');

try {
  await access(spaShell, constants.R_OK);
} catch {
  console.error(
    `Missing ${spaShell}. Build the frontend first:\n  pnpm run build:frontend`
  );
  process.exit(1);
}

await rm(wwwDir, { recursive: true, force: true });
await mkdir(wwwDir, { recursive: true });
await cp(frontendBuild, wwwDir, { recursive: true });

const indexPath = path.join(wwwDir, 'index.html');
const spaShellOut = path.join(wwwDir, '200.html');
let html = await readFile(spaShellOut, 'utf8');
// Root-absolute URL: after OAuth the WebView lands on deep paths such as
// /servers/callback or /chat/<host>, and a relative ./capacitor-glue.js would
// 404 as /chat/<host>/capacitor-glue.js.
const glueScriptTag =
  '<script type="module" src="/capacitor-glue.js"></script>';
if (html.includes('capacitor-glue.js')) {
  html = html.replace(
    /src=["'](?:\.\/)?capacitor-glue\.js["']/g,
    'src="/capacitor-glue.js"'
  );
} else {
  html = html.replace(/<head([^>]*)>/i, `<head$1>\n    ${glueScriptTag}`);
}
await writeFile(indexPath, html, 'utf8');
await writeFile(spaShellOut, html, 'utf8');

// Resolve Capacitor packages via this package's node_modules. A Yarn PnP
// manifest in $HOME otherwise makes esbuild refuse workspace deps.
const capacitorAlias = {
  '@capacitor/core': require.resolve('@capacitor/core'),
  '@capacitor/keyboard': require.resolve('@capacitor/keyboard'),
  '@capacitor/preferences': require.resolve('@capacitor/preferences'),
  '@capacitor/status-bar': require.resolve('@capacitor/status-bar'),
  '@capawesome/capacitor-file-picker': require.resolve(
    '@capawesome/capacitor-file-picker'
  )
};

await build({
  entryPoints: [glueEntry],
  bundle: true,
  format: 'esm',
  outfile: glueOut,
  platform: 'browser',
  target: ['es2022'],
  logLevel: 'warning',
  absWorkingDir: mobileRoot,
  alias: capacitorAlias
});

console.log(`Prepared Capacitor www at ${wwwDir}`);
