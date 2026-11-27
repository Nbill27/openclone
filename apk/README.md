# APK folder

APK binaries are **not** stored in this repository. Every build is published as a
GitHub Release, which keeps the Git history small and makes each download
traceable to a version and a commit.

## Download

| What | Where |
| --- | --- |
| Latest version | [Releases — latest](https://github.com/Nbill27/openclone/releases/latest) |
| Older versions | [All releases](https://github.com/Nbill27/openclone/releases) |
| Just built one yourself | The `OpenClone-debug-apk` artifact of the latest [Actions run](https://github.com/Nbill27/openclone/actions) |

Each release attaches `OpenClone-<version>-debug.apk` and records its size and
SHA-256 in the release notes.

## How to install

1. Download the APK from the Releases page onto your phone.
2. Open the file from a file manager.
3. When Android asks, allow **Install unknown apps** for the file manager or
   browser you are using.
4. Tap **Install**, then **Open**.

Or through ADB, if the phone is connected and USB debugging is enabled:

```bash
adb install -r OpenClone-<version>-debug.apk
```

## Notes

- These are **debug** builds, signed with a debug key. They are fine for personal
  use; do not upload them to the Play Store.
- Because each CI build uses a fresh debug key, a newer release cannot be
  installed over an older one. Uninstall the old app first.
- After installing, OpenClone itself needs the **Install unknown apps**
  permission, because the clones it produces are also installed as APKs.
- These builds contain no ads, no tracking, and no network access.

## Publishing a new version

1. Bump `appVersionCode` and `appVersionName` in `app/build.gradle.kts`.
2. Push to `main` and let CI pass.
3. Tag and push:

```bash
git tag v0.4.2
git push origin v0.4.2
```

The `build.yml` workflow builds the APK and attaches it to the release.
