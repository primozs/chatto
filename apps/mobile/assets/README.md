# Mobile brand assets

Source masters live in `apps/frontend/src/lib/assets/`:

- `chatto-icon-maskable.png` → `logo-source.png` (verbatim) and `logo.png`
  (lavender keyed out for Capacitor Assets easy mode)
- `chatto-icon.png` → `icon-rounded.png` (reference only)

Regenerate Android launcher icons, splash screens, and notification icons:

```sh
pnpm --filter chatto-mobile generate:assets
```

Uses `@capacitor/assets` for icons/splash and a monochrome silhouette pass for
`ic_notification` (status bar / OS banners). Brand background `#c5a4d4`
matches the frontend PWA icon generator.
