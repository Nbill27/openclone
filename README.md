# OpenClone

[![Build](https://github.com/Nbill27/openclone/actions/workflows/build.yml/badge.svg)](https://github.com/Nbill27/openclone/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/Nbill27/openclone/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/Nbill27/openclone)](https://github.com/Nbill27/openclone/releases)

An **open source** Android app that clones (duplicates) an already installed
app, so you can run two accounts of the same app on one phone. No ads, no
account, no server. Everything runs on the device.

A clone is produced by copying the original APK, rewriting the package name in
the manifest, and re-signing the result with a key owned by OpenClone. There is
no DEX patching, no signature bypass, and no root.

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
  - [Download a release APK](#download-a-release-apk)
  - [Use the APK already in the repo](#use-the-apk-already-in-the-repo)
  - [Build it yourself](#build-it-yourself)
- [Usage](#usage)
- [Project layout](#project-layout)
- [How it works](#how-it-works)
- [Signing key: read this first](#signing-key-read-this-first)
- [Testing and CI](#testing-and-ci)
- [Limitations](#limitations)
- [FAQ](#faq)
- [Contributing](#contributing)
- [Credits](#credits)
- [License](#license)

---

## Features

- **Pick an app** from the list of installed apps, with a search field. System
  apps are listed and marked too; whether to clone them is up to you.
- **Configure the clone on one screen.** The clone name is filled in
  automatically (for example `Muse 2`), the clone package is filled in
  automatically (for example `com.facebook.aura.klon1`, moving on to `klon2`,
  `klon3` when already taken), and the icon can be replaced from the gallery.
- **Split APKs are supported.** Apps installed as several APKs (App Bundle) are
  merged first, then cloned.
- **One signing key for every clone**, so clones can be updated without
  uninstalling first. The key is created once on first launch and stored on the
  device.
- **Clone history** is kept in the app, so finished clone APKs can be
  re-installed or shared without cloning from scratch again.
- **The app list stays light** on phones with hundreds of apps: the scan skips
  icons, icons load per visible row, and scan results are cached briefly.
- **No ads, no tracking, no internet.** Cloning is a purely local process.

## Requirements

- Android 8.0 (API 26) or newer.
- **Install unknown apps** permission for OpenClone, because clones are
  installed as regular APKs.
- `QUERY_ALL_PACKAGES` permission to see the list of installed apps.
- No storage permission: icon picking goes through the system file picker
  (Storage Access Framework), not full gallery access.

## Installation

### Download a release APK

1. Open the [Releases](https://github.com/Nbill27/openclone/releases) page.
2. Download the APK from the latest release.
3. Open the APK file on your phone, allow **Install unknown apps** when asked,
   then tap **Install**.

Via ADB, if the phone is connected and USB debugging is on:

```bash
adb install -r OpenClone-0.1.0-debug.apk
```

### Use the APK already in the repo

The [`apk/`](apk/) folder contains a prebuilt APK, so you do not need an Android
toolchain at all:

```bash
git clone https://github.com/Nbill27/openclone.git
cd openclone/apk
```

Transfer `OpenClone-0.1.0-debug.apk` to your phone and install it as usual.
Version, size, and checksum of each file are recorded in
[`apk/README.md`](apk/README.md).

### Build it yourself

You need:

- JDK 17 (JDK 21+ is not supported by AGP 8.4.2 yet)
- Android SDK with platform 34 and build-tools 34.0.0
- An internet connection on the first build, to download Gradle and the
  dependencies

```bash
git clone https://github.com/Nbill27/openclone.git
cd openclone
./gradlew assembleDebug
# APK is at app/build/outputs/apk/debug/app-debug.apk
```

On Windows:

```powershell
gradlew.bat assembleDebug
```

Or open this folder in Android Studio (Ladybug or newer) and press Run.

If Gradle cannot find the SDK, set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), or
create `local.properties` containing:

```properties
sdk.dir=C\:\\Users\\NAME\\AppData\\Local\\Android\\Sdk
```

`local.properties` is already in `.gitignore`; do not commit it.

For a release build signed with your own key, add `signingConfigs` to
`app/build.gradle.kts` and use `./gradlew assembleRelease`. CI release builds
use the debug key so the APK on the Releases page can be installed directly.

## Usage

Three steps, three screens.

1. **Pick an app.** Open OpenClone, find the app in the list, tap it.
2. **Configure the clone.** The clone name and package are already filled in.
   Optionally replace the icon from the gallery; leave it empty to keep the
   original icon. Make sure the clone package is not already used by another
   app.
3. **Tap CLONE**, wait for it to finish, then tap **INSTALL**.

Finished clones are stored in the app's internal storage
(`files/clones/<package>.apk`) and can be re-installed or shared from the result
screen.

## Project layout

This is a single-module Android project (`:app`) with no internal libraries, so
it can be read top to bottom.

```
openclone/
├── .github/
│   └── workflows/
│       └── build.yml              # CI: unit test → lint → assemble → artifact/release
├── app/
│   ├── build.gradle.kts           # Module config: SDK, dependencies, viewBinding
│   ├── proguard-rules.pro         # R8 rules (minify is currently disabled)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/dev/bill/openclone/
│       │   │   ├── MainActivity.kt
│       │   │   ├── CloneActivity.kt
│       │   │   ├── CloneEngine.kt
│       │   │   ├── CloneConfig.kt
│       │   │   ├── CloneStore.kt
│       │   │   ├── KeystoreManager.kt
│       │   │   ├── AppRepository.kt
│       │   │   ├── AppIcons.kt
│       │   │   ├── AppListAdapter.kt
│       │   │   ├── InstalledApp.kt
│       │   │   └── ManifestNaming.kt
│       │   └── res/
│       │       ├── layout/        # activity_main, activity_clone, item_app
│       │       ├── values/        # strings, colors, themes
│       │       ├── xml/           # file_paths.xml (FileProvider)
│       │       ├── drawable-xxxhdpi/  # foreground + monochrome adaptive icon
│       │       └── mipmap-*/      # legacy + round launcher icons, all densities
│       └── test/java/dev/bill/openclone/
│           ├── CloneConfigTest.kt
│           └── ManifestNamingTest.kt
├── apk/
│   ├── OpenClone-0.1.0-debug.apk  # ready-to-install APK
│   └── README.md                  # version, checksum, install steps
├── gradle/wrapper/                # Gradle 8.7 wrapper
├── build.gradle.kts               # AGP 8.4.2 + Kotlin 1.9.24 plugin versions
├── settings.gradle.kts            # Project name, repositories, :app module
├── gradle.properties              # Gradle heap, AndroidX, Kotlin style
├── gradlew / gradlew.bat          # Gradle wrapper
├── LICENSE                        # MIT
└── README.md
```

### What each source file does

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Screen 1, "Pick an app". Loads the list in `onResume`, filters as you type, opens the clone screen. |
| `CloneActivity.kt` | Screens 2 and 3, "Configure clone" and the result. Runs the clone in a coroutine, shows progress, installs the APK through `PackageInstaller` (falling back to `ACTION_VIEW`), shares the APK, and restores state after rotation. |
| `CloneEngine.kt` | The core work: copy APK/splits, merge with ARSCLib, rewrite the package name, rewrite the label, replace the icon, strip old signatures, force `resources.arsc` to STORED, write the APK, sign v1/v2/v3. A single `suspend` function with cancellation checkpoints. |
| `CloneConfig.kt` | Data models (`CloneConfig`, `CloneResult`, `CloneRecord`) plus pure helpers: clone name/package suggestions, package name validation. |
| `CloneStore.kt` | Clone history as a single JSON file in private storage. Access is serialized with a process-wide lock, writes are atomic through a `.tmp` file then rename. |
| `KeystoreManager.kt` | Creates and loads the signing key (PKCS12) at `files/openclone-keystore.p12`, with a random password in private `SharedPreferences`. |
| `AppRepository.kt` | Scans installed apps: one bulk `getInstalledPackages` for versions, filters null `sourceDir`, caches for 15 seconds, `invalidate()` after a successful install. |
| `AppIcons.kt` | Lazy per-row icon cache. Drawables are rasterized into small bitmaps and kept in an `LruCache` capped at 8 MB. |
| `AppListAdapter.kt` | RecyclerView adapter with `DiffUtil` (identity `packageName@sourceDir`); icons load on bind. |
| `InstalledApp.kt` | Model for one installed app, without `Drawable`, with lazy `lowerLabel`/`searchKey` for filtering. |
| `ManifestNaming.kt` | Pure text rules for manifest renaming: declared permissions, provider `authorities`, `android:permission`, `android:process`. Split out so it can be tested without an APK. |

### What each resource does

| Path | Contents |
| --- | --- |
| `res/layout/activity_main.xml` | Title, About button, search field, app list. |
| `res/layout/activity_clone.xml` | Icon, clone name, clone package, icon picker, CLONE/INSTALL/SHARE buttons. |
| `res/layout/item_app.xml` | One list row: 48dp icon, name, package, version metadata. |
| `res/values/strings.xml` | All UI text (Indonesian). Add new strings here, never hardcode them in layouts. |
| `res/values/colors.xml` | `openclone_blue` and `ic_launcher_background`. |
| `res/values/themes.xml` | Material 3 DayNight theme without an ActionBar. |
| `res/xml/file_paths.xml` | Paths that may be shared through FileProvider (`clones/` and cache). |
| `res/mipmap-anydpi-v26/` | Adaptive icon XML for API 26+. |
| `res/mipmap-*dpi/` | Legacy and round icons for each density. |
| `res/drawable-xxxhdpi/` | Foreground and monochrome adaptive icon (single raster). |

## How it works

Android has no API to "run an app twice". The trick is to build a second app
that Android considers genuinely different.

### Clone pipeline

`CloneEngine.clone()` runs sequentially, calling `ensureActive()` before every
heavy step:

1. **Copy** `ApplicationInfo.sourceDir` (and all splits) into the cache dir.
2. **Load** the APK with `ApkModule`/`ApkBundle`; splits are merged into one
   module, a single APK is loaded directly.
3. **Rewrite the package name** through `setPackageName`, then `ManifestNaming`
   cleans up identifiers that must be unique per app:
   - `<permission>` / `<permission-tree>` / `<permission-group>` entries that
     use the old package are renamed, including their `uses-permission`
     declarations.
   - Provider `authorities` are split on `;`; ones already under the new package
     are left alone, ones still on the old package get their prefix swapped, and
     bare names (for example `com.facebook.appmanager`) are given the new
     package namespace.
   - `android:permission` and `android:process` on components are adjusted too.
   - Component class names are **never** renamed.
4. **Rewrite the label.**
5. **Replace the icon** when the user picked an image: decode, center-crop,
   rewrite the launcher icon entry inside the APK.
6. **Strip old signatures** selectively: `META-INF/*.SF`, `*.RSA`, `*.DSA`,
   `*.EC`, `MANIFEST.MF`, `SIG-*`. `META-INF/services` and `*.kotlin_module` are
   kept because ServiceLoader and Kotlin need them.
7. **Force `resources.arsc` to STORED** (`isUncompressed = true`). Android 11+
   rejects a compressed or unaligned `resources.arsc`.
8. **Write the APK** to a temporary file.
9. **Sign** v1 + v2 + v3 with apksig, into a `.tmp` file.
10. **Publish atomically**: rename `.tmp` to the final `<package>.apk`.

Cleanup (`module.destroy()`, removing the work dir) always runs in `finally`,
including when the coroutine is cancelled.

### Coroutine and threading rules

- `clone()` is `suspend` and is called from a single scope in `CloneActivity`
  (`Dispatchers.Default`, since the work is CPU + disk).
- Progress is reported through a `suspend (String) -> Unit`; the callback hops to
  `Dispatchers.Main.immediate` itself, so there is no stray coroutine per
  progress line.
- All `PackageManager` lookups, file reads/writes, and icon decoding happen on
  `Dispatchers.IO`/`Default`, never on the main thread.
- `CancellationException` is always rethrown, never swallowed by
  `catch (Exception)`.
- After the scan completes, the activity checks `isFinishing || isDestroyed`
  before touching views.

### Storage and consistency

- Clone history (`clones.json`) is written through a `.tmp` file then renamed,
  under a process-wide lock, so two activities cannot overwrite each other.
- Result APKs are signed into `.tmp` then renamed, so a crash mid-signing cannot
  corrupt an existing clone.
- The app scan is cached for 15 seconds in `AppRepository`; the cache is
  invalidated after every successful clone install.

### App list performance

- The scan only reads package metadata (one bulk call), without `loadIcon` or
  `loadLabel` per package.
- Icons load when a row is actually bound, are rasterized to screen size, and are
  stored in a byte-bounded cache.
- `DiffUtil` is used when the list changes; there is no
  `notifyDataSetChanged`.

## Signing key: read this first

Once the key is lost, installed clones can no longer be updated; they must be
uninstalled and reinstalled, losing their data.

That is why OpenClone creates **one** key on first launch, stores it at
`files/openclone-keystore.p12` in the app's private storage, and uses that same
key for every clone.

**Back up that keystore file.** If you reinstall OpenClone or move to a new phone
without bringing the keystore along, old clones can no longer be updated.

## Testing and CI

Unit tests run on the JVM, with no device and no APK:

```bash
./gradlew testDebugUnitTest
```

What is covered:

- `ManifestNamingTest` — rename rules for `authorities`, permissions, process,
  including already-renamed and bare-name cases.
- `CloneConfigTest` — `klon1..n` package suggestions, package name validation,
  label suggestions.

Lint and build:

```bash
./gradlew lintDebug
./gradlew assembleDebug
```

GitHub Actions (`.github/workflows/build.yml`) runs the same sequence for every
push to `main` and every pull request:

1. `testDebugUnitTest`
2. `lintDebug`
3. `assembleDebug`
4. upload the APK as an artifact

Tags matching `v*.*.*` (for example `v0.1.0`) trigger a release job that creates
a GitHub Release and attaches the APK.

## Limitations

- Apps that check their own signature, use SafetyNet / Play Integrity, or have
  anti-tamper protection (banking, some games) will reject a re-signed copy.
  Working around that needs DEX/native patching, which is out of scope here.
- Only the package name inside `resources.arsc` is changed. Apps that read their
  package name from resources instead of `Context.getPackageName()` may behave
  oddly.
- A clone has to be installed as a regular APK, so the phone must allow
  **Install unknown apps** for OpenClone.
- Clones cannot be updated through the Play Store: their package and signature
  differ.
- No root. Everything uses public Android APIs.
- Do not publish clones to the Play Store, and do not clone apps to pirate,
  defraud, or violate their license. This project is for personal use on your own
  phone.

## FAQ

**Does OpenClone need root?**
No.

**Can a clone update itself from the Play Store?**
No. A clone has a different package and signature. To update, clone the new
version of the app again, or reinstall the new clone APK.

**Why does app X force close after cloning?**
Usually because the app checks its signature or detects modification. See
[Limitations](#limitations).

**Is my account data in the original app safe?**
Yes. OpenClone only copies the APK file, not app data. A clone starts as a fresh
install.

**Why is my clone package rejected?**
The package must have at least two segments, may only contain letters, digits,
and underscores, and each segment may not start with a digit. A valid example is
`com.example.app.klon1`.

**Can the original icon be kept?**
Yes. Leave the icon picker empty; the engine only replaces the icon when an image
was selected.

## Contributing

Contributions are welcome from anyone: bug reports, documentation fixes,
translations, and new features.

### Before you start

- For large changes or new features, open an issue first so the direction is
  clear.
- For bugs, include: Android version, phone brand/model, the cloned app's name,
  reproduction steps, and logs (`adb logcat`) if available.
- Do not attach APKs of paid apps or personal data to issues.

### Workflow

```bash
# 1. Fork the repo on GitHub, then clone your fork
git clone https://github.com/<username>/openclone.git
cd openclone

# 2. Create a branch from main
git checkout -b fix/icon-cache-eviction

# 3. Make your changes, then run the checks
./gradlew testDebugUnitTest lintDebug assembleDebug

# 4. Commit and push
git add -A
git commit -m "fix: cap the icon cache by byte size"
git push origin fix/icon-cache-eviction
```

Then open a pull request against `main`.

### Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: adaptive icon support for clones
fix: prevent the icon cache from growing unbounded
perf: skip loadIcon during app scan
docs: clarify the Windows install steps
test: add authorities cases containing dots
refactor: extract manifest rename rules
chore: bump the Gradle wrapper
```

Commit messages may be in English or Indonesian; just stay consistent within a
single PR.

### Code rules

- **Kotlin, no new Java.** Follow the style of the surrounding files.
- **UI strings always live in `res/values/strings.xml`**, never hardcoded in
  layouts or code. The current UI language is Indonesian.
- **Comments and API names in English.** Comments explain *why*, not restate
  *what* the code already shows.
- **Heavy work never runs on the main thread.** Use `suspend` plus the right
  dispatcher; never swallow `CancellationException`.
- **Write files atomically** (`.tmp` then rename) for data that must not be
  corrupted.
- **Do not add Android permissions** without discussing it in an issue. The
  current permissions are only `QUERY_ALL_PACKAGES` and
  `REQUEST_INSTALL_PACKAGES`.
- **Do not add large dependencies** without a strong reason; explain it in the
  PR.
- **Non-goals that must not be violated:** DEX patching, signature bypass, Play
  Integrity bypass, or any trick that circumvents Android security.
- **Every testable text rule needs a unit test.** New logic in
  `ManifestNaming`/`CloneConfig` must come with tests.

### Pull request checklist

- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lintDebug` adds no new errors/warnings
- [ ] `./gradlew assembleDebug` produces an APK
- [ ] UI changes were tested on a phone/emulator (mention the Android version)
- [ ] New strings are in `strings.xml`
- [ ] No build files, keystores, or `local.properties` are committed
- [ ] The PR description explains the problem, the approach, and how to test

### Contribution ideas

- English (`values-en`) and other locale support.
- Automatic package conflict detection before the CLONE button is pressed.
- Keystore export/import from the history screen, so backups do not need ADB.
- Staged progress indication (a percentage, not just text).
- Instrumented tests for the install flow.
- Better dark theme support and dynamic color.

## Credits

- [ARSCLib](https://github.com/REAndroid/ARSCLib) (Apache-2.0) for reading and
  writing binary `AndroidManifest.xml` and `resources.arsc`.
- [Apktool](https://github.com/iBotPeaches/Apktool) (Apache-2.0), APK format
  reference and inspiration.
- [apksig](https://maven.google.com/com/android/tools/build/apksig) from AOSP for
  v1/v2/v3 signing.
- [BouncyCastle](https://www.bouncycastle.org/) for self-signed certificates.
- [AndroidX](https://developer.android.com/jetpack/androidx) and
  [Material Components](https://github.com/material-components/material-components-android).

## License

MIT License, see the [`LICENSE`](LICENSE) file.
Copyright (c) 2026 Muhammad Nabil Deja A.R.
