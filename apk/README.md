# Folder APK

Folder ini khusus menampung APK OpenClone yang siap install, supaya file rilis
tidak tercampur dengan source code.

## Isi

| File | Keterangan |
| --- | --- |
| `OpenClone-0.1.0-debug.apk` | APK OpenClone versi 0.1.0 (debug), siap install di HP. |

Detail file:

- Ukuran: 14.194.519 byte
- Dibangun: 24 November 2026
- SHA-256: `2311E8B944B503AF7F2093A88A1B65E448806C2D526046471BD6C7A1CB461C12`

## Cara install

1. Pindahkan `OpenClone-0.1.0-debug.apk` ke HP (kabel USB, Google Drive,
   Telegram, atau apa saja).
2. Buka file APK-nya dari file manager di HP.
3. Saat Android bertanya, izinkan **Install unknown apps** untuk aplikasi
   file manager / browser yang kamu pakai.
4. Tap **Install**, lalu **Open**.

Atau lewat ADB, kalau HP sudah tersambung dan USB debugging aktif:

```bash
adb install -r OpenClone-0.1.0-debug.apk
```

## Catatan

- Ini build **debug**, ditandatangani dengan debug key. Untuk pemakaian sendiri
  aman; jangan diunggah ke Play Store.
- Setelah install, OpenClone perlu izin **Install unknown apps** untuk dirinya
  sendiri, karena clone yang dihasilkan juga dipasang sebagai APK.
- APK ini tidak memuat iklan, pelacakan, atau koneksi internet.
- APK di folder ini ikut ter-commit ke Git sebagai kemudahan, bukan praktik
  ideal. Kalau kamu membuat build baru, jangan menumpuk APK di sini: unggah ke
  halaman [Releases](https://github.com/Nbill27/openclone/releases) atau pakai
  artifact dari GitHub Actions, lalu perbarui hanya satu file di folder ini.

## Membuat APK baru

Dari folder `openclone`:

```bash
./gradlew assembleDebug
```

Lalu salin hasilnya ke sini:

```powershell
Copy-Item app\build\outputs\apk\debug\app-debug.apk apk\OpenClone-0.1.0-debug.apk -Force
```
