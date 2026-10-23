# OTHE CRM — Android app

The technician app for the On Time Home Experts CRM (`com.othe.crm`). It opens the CRM
(https://ontimehomexpertscrm.com) inside a phone app and adds **Stripe Tap to Pay** and the
**Bluetooth M2 card reader** through a native plugin.

## Download (technicians)

Get the latest APK from **[Releases](../../releases/latest)** → `OTHE-CRM-x.y.z.apk`.

**Permanent download link (always the newest version)** — use this on the website:
https://github.com/etai-web/othe-crm-app/releases/latest/download/OTHE-CRM.apk
(This link only works while the repository is public.)

- **Coming from v1.0.0:** delete the old OTHE CRM app first (one time only — v1.0.0 was signed
  with a different key), then install the new APK.
- **Later updates:** install straight over the current app. No need to delete it.

| Version | Date | Notes |
|---|---|---|
| 1.1.1 | Oct 2026 | No more "New tag scanned" after Tap to Pay; phone back button works; opens ontimehomexpertscrm.com. Signed with the permanent OTHE key. |
| 1.0.0 | Sept 2026 | First version (source not saved; rebuilt in 1.1). |

## How a new version is built

Every push to `main` runs **Actions → Build Android APK**, which:

1. installs Capacitor 8 and generates a fresh Android project (`npx cap add android`),
2. applies everything in `native-overrides/` with `scripts/prepare-android.mjs`,
3. builds the APK and signs it with the permanent OTHE key (checked automatically).

A new **Release** (`v1.1.<run number>`) is published only when the commit message contains
`[release]`, or when the workflow is started by hand (**Actions → Build Android APK → Run workflow**).
Other builds keep the APK as a downloadable artifact on the run.

No Android Studio is needed. If the signing-key secrets are missing, the APK is only kept as a
build artifact (signed with a temporary key) and **no release is published**.

`dist/` holds APKs that were signed with the OTHE key outside GitHub; pushing one to `main`
publishes it as a release (**Actions → Publish signed APK**).

## Signing key

Every update must be signed with the **same** key, or phones will refuse to install it.
The key (`othe-crm-release.jks`, alias `othe-crm`) is kept privately by the owner — never commit it.
For automatic builds it must be stored as Actions secrets (**Settings → Secrets and variables →
Actions**):

| Secret | Value |
|---|---|
| `OTHE_KEYSTORE_BASE64` | contents of `OTHE_KEYSTORE_BASE64.txt` |
| `OTHE_KEYSTORE_PASSWORD` | keystore password |
| `OTHE_KEY_PASSWORD` | key password (same as above) |
| `OTHE_KEY_ALIAS` | `othe-crm` |

## Project layout

| Path | Purpose |
|---|---|
| `capacitor.config.json` | App ID, name, CRM address and allowed domains. |
| `native-overrides/java/com/othe/crm/MainActivity.java` | Registers the plugins. Holds the **NFC guard** so Android never shows its "New tag scanned" screen when a card is still touching the phone after a Tap to Pay payment. |
| `native-overrides/java/com/othe/crm/terminal/StripeTerminalPlugin.java` | Stripe Terminal 5.8.1 bridge (Tap to Pay + M2). Same JS interface as v1.0.0. |
| `native-overrides/res/raw/stripe_terminal_shim.js` | Defines `window.StripeTerminal` for the CRM web app. Injected only on CRM pages. |
| `native-overrides/java/com/othe/crm/AuthNavigationPlugin.java` | Explains that Google sign-in doesn't work inside apps. |
| `native-overrides/java/com/othe/crm/OtheApplication.java` | Stripe Terminal application setup. |
| `native-overrides/AndroidManifest.xml`, `native-overrides/res/` | Permissions (NFC, location, Bluetooth), icons, splash. |
| `scripts/prepare-android.mjs` | Applies the overrides, Stripe dependencies, version and signing to the generated project. |
| `.github/workflows/` | `android.yml` builds and releases; `publish-apk.yml` publishes APKs from `dist/`. |
| `dist/` | Signed APKs published as releases. |

The phone back button is handled by `@capacitor/app`, so the CRM web app's own back handling works.
