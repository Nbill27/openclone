# APK folder

This folder holds ready-to-install OpenClone APKs, so release files do not get
mixed up with source code.

## Contents

| File | Description |
| --- | --- |
| `OpenClone-0.1.0-debug.apk` | OpenClone 0.1.0 (debug), ready to install on a phone. |

File details:

- Size: 14,194,519 bytes
- Built: November 24, 2026
- SHA-256: `2311E8B944B503AF7F2093A88A1B65E448806C2D526046471BD6C7A1CB461C12`

## How to install

1. Transfer `OpenClone-0.1.0-debug.apk` to your phone (USB cable, Google Drive,
   Telegram, anything works).
2. Open the APK file from a file manager on the phone.
3. When Android asks, allow **Install unknown apps** for the file manager or
   browser you are using.
4. Tap **Install**, then **Open**.

Or through ADB, if the phone is connected and USB debugging is enabled:

```bash
adb install -r OpenClone-0.1.0-debug.apk
```

## Notes

- This is a **debug** build, signed with a debug key. It is fine for personal
  use; do not upload it to the Play Store.
- After installing, OpenClone itself needs the **Install unknown apps**
  permission, because the clones it produces are also installed as APKs.
- This APK contains no ads, no tracking, and no network access.
- The APK in this folder is committed to Git for convenience, which is not ideal
  practice. When you build a new one, do not pile APKs up here: upload it to the
  [Releases](https://github.com/Nbill27/openclone/releases) page or use the
  GitHub Actions artifact, then update just this one file.

## Building a new APK

From the `openclone` folder:

```bash
./gradlew assembleDebug
```

Then copy the result here:

```powershell
Copy-Item app\build\outputs\apk\debug\app-debug.apk apk\OpenClone-0.1.0-debug.apk -Force
```
