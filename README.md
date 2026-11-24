# OpenClone

[![Build](https://github.com/Nbill27/openclone/actions/workflows/build.yml/badge.svg)](https://github.com/Nbill27/openclone/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/Nbill27/openclone/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/Nbill27/openclone)](https://github.com/Nbill27/openclone/releases)

Aplikasi Android **open source** untuk meng-clone (menggandakan) aplikasi
yang sudah terpasang, supaya satu aplikasi bisa dipakai dua akun sekaligus
di satu HP. Tanpa iklan, tanpa akun, tanpa server. Semua proses jalan di HP.

Dibuat karena CloneHI (yang viral di Threads) repo-nya public tapi tidak
ada kode sumbernya, cuma README dan APK rilis. Apktool M juga sama,
repo update-nya cuma berisi APK, bukan source code. Jadi yang ini dibuat
dari nol, dan kodenya dibuka penuh di sini, lisensi MIT.

## Fitur

- **Pilih aplikasi** dari daftar aplikasi terpasang, lengkap dengan kolom
  cari. Aplikasi sistem ikut tampil dan ditandai, terserah kamu mau
  clone atau tidak.
- **Atur clone** dalam satu layar: nama clone terisi otomatis
  (contoh: `Muse 2`), package clone terisi otomatis
  (contoh: `com.facebook.aura.klon1`, naik otomatis ke `klon2`, `klon3`
  kalau sudah ada), dan ikon bisa diganti dari galeri.
- **Split APK didukung.** Aplikasi App Bundle yang terpasang sebagai
  beberapa APK digabung dulu sebelum di-clone.
- **Satu kunci signing untuk semua clone**, jadi clone bisa di-update
  tanpa uninstall dulu. Kuncinya dibuat sekali saat pertama jalan dan
  disimpan di HP.
- **Riwayat clone** tersimpan di aplikasi, jadi APK clone yang sudah jadi
  bisa di-install ulang atau dibagikan tanpa clone dari nol lagi.
- **Tanpa iklan, tanpa pelacakan, tanpa internet.** Clone murni proses
  lokal di HP.

## Syarat

- Android 8.0 (API 26) atau lebih baru.
- Izin "Install unknown apps" untuk OpenClone, karena clone di-install
  sebagai APK biasa.
- Izin `QUERY_ALL_PACKAGES` untuk melihat daftar aplikasi terpasang.

## Install

### Cara cepat: unduh APK rilis

1. Buka halaman
   [Releases](https://github.com/Nbill27/openclone/releases).
2. Unduh APK dari rilis terbaru (`app-debug.apk` untuk versi debug,
   atau APK rilis kalau tersedia).
3. Install di HP, izinkan "Install unknown apps" saat diminta.

### Cara build sendiri

Butuh JDK 17, Android SDK dengan platform 34 dan build-tools 34.0.0.

```bash
git clone https://github.com/Nbill27/openclone.git
cd openclone
./gradlew assembleDebug
# APK ada di app/build/outputs/apk/debug/app-debug.apk
```

Atau buka folder ini di Android Studio dan tekan Run.

Catatan: kalau `./gradlew` gagal karena `gradle-wrapper.jar` belum ada
di clone kamu, jalankan sekali `gradle wrapper --gradle-version 8.7`,
lalu ulangi perintah di atas.

## Cara pakai, 3 langkah

1. **Pilih Aplikasi.** Buka OpenClone, cari aplikasi di daftar, tap.
2. **Atur Clone.** Nama dan package clone sudah terisi otomatis.
   Kalau mau, ganti ikon dari galeri. Kalau dikosongkan, ikon asli
   dipakai. Pastikan package clone belum dipakai aplikasi lain.
3. **Tap CLONE**, tunggu sampai selesai, lalu tap **INSTALL**.

Clone yang sudah jadi tersimpan di penyimpanan internal aplikasi, dan
bisa di-install ulang dari riwayat.

## Cara kerja

Tidak ada API Android untuk "jalankan aplikasi dua kali". Triknya adalah
membuat aplikasi kedua yang menurut Android adalah aplikasi yang
benar-benar beda:

1. Salin APK asli dari `ApplicationInfo.sourceDir`. Kalau aplikasinya
   split APK (App Bundle), semua bagiannya disalin lalu digabung dulu.
2. Buka `AndroidManifest.xml` di dalam APK. File ini disimpan dalam
   format biner (AXML), bukan teks. Package name diganti, provider
   `authorities` yang mengandung package lama diganti juga supaya tidak
   bentrok, dan label aplikasi diganti jadi nama clone. Semua ini pakai
   [ARSCLib](https://github.com/REAndroid/ARSCLib) (Apache-2.0), tanpa
   perlu decompile dan recompile penuh seperti apktool.
3. Ikon launcher diganti dengan gambar pilihanmu, kalau ada.
4. File `META-INF/` lama dibuang, karena signature lama tidak valid
   lagi setelah manifest diubah.
5. APK hasil ditandatangani ulang dengan kunci OpenClone pakai
   [apksig](https://maven.google.com/com/android/tools/build/apksig)
   (library yang sama dipakai `apksigner`), v1 + v2 + v3.
6. APK diserahkan ke installer sistem lewat FileProvider, seperti
   install APK biasa.

## Kunci signing, baca ini dulu

Sekali kunci hilang, clone yang sudah terpasang tidak bisa di-update,
harus uninstall dan install ulang, datanya hilang. Karena itu OpenClone
membuat SATU kunci saat pertama jalan, menyimpannya di
`files/openclone-keystore.p12` di penyimpanan private aplikasi, dan
memakai kunci yang sama untuk semua clone.

**Backup file keystore itu.** Kalau kamu reinstall OpenClone atau pindah
HP tanpa membawa keystore-nya, clone lama tidak bisa di-update lagi.

## Rilis dan CI

Setiap push ke `main` otomatis di-build oleh GitHub Actions, dan APK
debug di-upload sebagai artifact. Tag `v*` (contoh `v0.1.0`) otomatis
membuat GitHub Release dengan APK-nya. Lihat
[workflow build](.github/workflows/build.yml) untuk detailnya.

Riwayat rilis ada di halaman
[Releases](https://github.com/Nbill27/openclone/releases).

## Batasan, jujur saja

- Aplikasi yang mengecek signature-nya sendiri, memakai SafetyNet /
  Play Integrity, atau banking dan game yang anti-tamper, akan menolak
  salinan yang sudah di-sign ulang. Itu bukan bug di manifest, itu
  memang butuh patch DEX / native code, dan itu di luar scope proyek ini.
- `resources.arsc` tidak diutak-atik selain package name di tabel.
  Aplikasi yang membaca package name-nya sendiri dari resource, bukan
  dari `Context.getPackageName()`, bisa berperilaku aneh.
- Clone harus di-install sebagai APK biasa, jadi HP harus mengizinkan
  "Install unknown apps" untuk OpenClone.
- Jangan publish hasil clone ke Play Store, dan jangan clone aplikasi
  untuk membajak, menipu, atau melanggar lisensinya. Proyek ini untuk
  pemakaian pribadi di HP sendiri.

## FAQ

**Apakah OpenClone butuh root?**
Tidak. Semua proses memakai API Android biasa.

**Apakah clone bisa update sendiri dari Play Store?**
Tidak. Clone punya package dan signature yang beda dari aslinya, jadi
Play Store tidak mengenalinya. Untuk update, clone lagi versi baru
aplikasinya, atau install ulang APK clone yang baru.

**Kenapa aplikasi X force close setelah di-clone?**
Biasanya karena aplikasi itu mengecek signature atau mendeteksi
telah dimodifikasi. Lihat bagian Batasan di atas.

**Apakah data akun di aplikasi asli aman?**
Ya. OpenClone hanya menyalin file APK-nya, bukan data aplikasi.
Clone mulai dari kondisi fresh install.

## Berkontribusi

Pull request dan issue dipersilakan. Sebelum kirim PR besar, buka
issue dulu supaya arahnya jelas. Pastikan `./gradlew testDebugUnitTest`
dan `./gradlew lintDebug` lolos sebelum push.

## Kredit

- [ARSCLib](https://github.com/REAndroid/ARSCLib) (Apache-2.0) untuk
  edit AndroidManifest.xml dan resources.arsc.
- [Apktool](https://github.com/iBotPeaches/Apktool) (Apache-2.0),
  inspirasi dan referensi format APK.
- [apksig](https://maven.google.com/com/android/tools/build/apksig)
  dari AOSP untuk signing v1/v2/v3.
- [BouncyCastle](https://www.bouncycastle.org/) untuk sertifikat
  self-signed.

## Lisensi

MIT License, lihat file `LICENSE`. Copyright (c) 2026 Muhammad Nabil
Deja A.R.
