# APK folder

APK binaries are **not** stored in this repository. Every build is published as a
GitHub Release, which keeps the Git history small and makes each download
traceable to a version and a commit.

## Download

| What | Where |
| --- | --- |
| Latest version | [Releases — latest](https://github.com/Nbill27/openclone/releases/latest) |
| Older versions | [All releases](https://github.com/Nbill27/openclone/releases) |
| Just built one yourself | The `OpenClone-apk` artifact of the latest [Actions run](https://github.com/Nbill27/openclone/actions) |

Each release attaches `OpenClone-<version>-release.apk` and records its size and
SHA-256 in the release notes.

## How to install

1. Download the APK from the Releases page onto your phone.
2. Open the file from a file manager.
3. When Android asks, allow **Install unknown apps** for the file manager or
   browser you are using.
4. Tap **Install**, then **Open**.

Or through ADB, if the phone is connected and USB debugging is enabled:

```bash
adb install -r OpenClone-<version>-release.apk
```

## Notes

- Releases are signed with a stable release key, so a newer version installs as
  an update over the previous one without uninstalling.
- The signing key is **not** in this repository. CI reads it from the
  `OPENCLONE_KEYSTORE_BASE64`, `OPENCLONE_KEYSTORE_PASSWORD`,
  `OPENCLONE_KEY_ALIAS`, and `OPENCLONE_KEY_PASSWORD` repository secrets. When
  those secrets are missing, CI falls back to an unsigned-key debug build.
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
