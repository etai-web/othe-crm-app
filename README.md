# OTHE CRM — Android app

The technician app for the On Time Home Experts CRM (`com.othe.crm`). It opens the CRM
(https://ontimehomexpertscrm.com) inside a phone app and adds **Stripe Tap to Pay** and the
**Bluetooth M2 card reader** through a native plugin.

## How the APK is built

Every push to `main` (or `android-rebuild`) runs **GitHub Actions → Build Android APK**, which:

1. installs Capacitor 8 and generates a fresh Android project (`npx cap add android`),
2. applies everything in `native-overrides/` with `scripts/prepare-android.mjs`,
3. builds and signs the APK, and publishes it under **Releases** (download it from there).

No Android Studio is needed.

## What's in `native-overrides/`

| File | Purpose |
|---|---|
| `java/com/othe/crm/MainActivity.java` | Registers the plugins. Holds an **NFC guard** so Android never shows its "New tag scanned" screen when a card is still touching the phone after a Tap to Pay payment. |
| `java/com/othe/crm/terminal/StripeTerminalPlugin.java` | Stripe Terminal 5.8.1 bridge (Tap to Pay + M2). Same JS interface as v1.0.0. |
| `res/raw/stripe_terminal_shim.js` | Defines `window.StripeTerminal` for the CRM web app. Injected only on CRM pages. |
| `java/com/othe/crm/AuthNavigationPlugin.java` | Explains that Google sign-in doesn't work inside apps. |
| `java/com/othe/crm/OtheApplication.java` | Stripe Terminal application setup. |
| `AndroidManifest.xml`, `res/` | Permissions (NFC, location, Bluetooth), icons, splash. |

The phone back button is handled by `@capacitor/app`, so the CRM web app's own back handling works.

## Signing

Release builds are signed with the OTHE release key, stored as GitHub Actions secrets:
`OTHE_KEYSTORE_BASE64`, `OTHE_KEYSTORE_PASSWORD`, `OTHE_KEY_ALIAS`, `OTHE_KEY_PASSWORD`.
Without them the APK is signed with a temporary test key. **Keep the .jks file and password
safe — every future update must be signed with the same key.**

## History

v1.0.0 (Sept 2026) was built outside this repository; its source was not saved. This project
was rebuilt from that APK in Oct 2026 (v1.1), keeping the same behaviour and JS interface.
