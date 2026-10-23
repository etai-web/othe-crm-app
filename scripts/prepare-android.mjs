// Applies the OTHE CRM customisations on top of a freshly generated Capacitor Android project.
// Run after `npx cap add android` (see package.json "android:prepare" and the GitHub workflow).
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const overrides = path.join(root, 'native-overrides');
const appDir = path.join(root, 'android', 'app');
const mainDir = path.join(appDir, 'src', 'main');
const resDir = path.join(mainDir, 'res');

if (!fs.existsSync(mainDir)) {
  console.error('android/ project not found. Run `npx cap add android` first.');
  process.exit(1);
}

function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dst, entry.name);
    if (entry.isDirectory()) copyDir(s, d);
    else fs.copyFileSync(s, d);
  }
}

function patch(file, fn) {
  const before = fs.readFileSync(file, 'utf8');
  const after = fn(before);
  if (after === before) throw new Error(`Patch made no change: ${file}`);
  fs.writeFileSync(file, after);
  console.log(`patched ${path.relative(root, file)}`);
}

// 1. Java sources (MainActivity with the NFC guard, Application, plugins).
copyDir(path.join(overrides, 'java'), path.join(mainDir, 'java'));
console.log('copied Java sources');

// 2. Manifest.
fs.copyFileSync(path.join(overrides, 'AndroidManifest.xml'), path.join(mainDir, 'AndroidManifest.xml'));
console.log('copied AndroidManifest.xml');

// 3. Resources: replace template launcher icons and splash with the OTHE ones.
for (const dir of fs.readdirSync(resDir)) {
  const full = path.join(resDir, dir);
  if (dir.startsWith('mipmap')) {
    fs.rmSync(full, { recursive: true, force: true });
  } else if (dir.startsWith('drawable')) {
    for (const f of fs.readdirSync(full)) {
      if (/^splash\.(png|xml)$/.test(f)) fs.rmSync(path.join(full, f));
    }
    if (fs.readdirSync(full).length === 0) fs.rmdirSync(full);
  }
}
copyDir(path.join(overrides, 'res'), resDir);
console.log('copied resources (icons, splash, Stripe Terminal JS bridge)');

// 4. Stripe Terminal needs Android 8.0+ (API 26).
patch(path.join(root, 'android', 'variables.gradle'), (s) =>
  s.replace(/minSdkVersion\s*=\s*\d+/, 'minSdkVersion = 26')
);

// 5. Version, Stripe dependencies and release signing.
const runNumber = parseInt(process.env.GITHUB_RUN_NUMBER || '0', 10);
const versionCode = 100 + runNumber; // v1.0.0 was versionCode 1
const versionName = `1.1.${runNumber}`;

patch(path.join(appDir, 'build.gradle'), (s) => {
  s = s.replace(/versionCode\s+\d+/, `versionCode ${versionCode}`);
  s = s.replace(/versionName\s+"[^"]*"/, `versionName "${versionName}"`);
  return (
    s +
    `
// ---- OTHE CRM additions (scripts/prepare-android.mjs) ----
dependencies {
    implementation "com.stripe:stripeterminal-taptopay:5.8.1"
    implementation "com.stripe:stripeterminal-core:5.8.1"
    implementation "androidx.webkit:webkit:1.14.0"
}

android {
    signingConfigs {
        release {
            def ks = System.getenv("OTHE_KEYSTORE_PATH")
            if (ks) {
                storeFile file(ks)
                storePassword System.getenv("OTHE_KEYSTORE_PASSWORD")
                keyAlias System.getenv("OTHE_KEY_ALIAS")
                keyPassword System.getenv("OTHE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            minifyEnabled false
            // Real builds use the OTHE release key (GitHub secrets). Without it, sign with the
            // debug key so the APK can still be installed for testing.
            signingConfig System.getenv("OTHE_KEYSTORE_PATH") ? signingConfigs.release : signingConfigs.debug
        }
    }
    packagingOptions {
        resources {
            excludes += ['META-INF/DEPENDENCIES', 'META-INF/INDEX.LIST', 'META-INF/LICENSE.md', 'META-INF/NOTICE.md']
        }
    }
}
`
  );
});

console.log(`OTHE CRM Android project ready (versionCode ${versionCode}, versionName ${versionName}).`);
