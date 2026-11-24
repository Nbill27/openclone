# OpenClone

[![Build](https://github.com/Nbill27/openclone/actions/workflows/build.yml/badge.svg)](https://github.com/Nbill27/openclone/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/Nbill27/openclone/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/Nbill27/openclone)](https://github.com/Nbill27/openclone/releases)

Aplikasi Android **open source** untuk meng-clone (menggandakan) aplikasi yang
sudah terpasang, supaya satu aplikasi bisa dipakai dua akun sekaligus di satu
HP. Tanpa iklan, tanpa akun, tanpa server. Semua proses jalan di HP.

Clone dibuat dengan menyalin APK asli, mengganti package name di manifest,
lalu menandatangani ulang APK hasilnya dengan kunci milik OpenClone. Tidak ada
DEX patching, tidak ada bypass signature, tidak ada root.

---

## Daftar isi

- [Fitur](#fitur)
- [Syarat](#syarat)
- [Instalasi](#instalasi)
  - [Unduh APK rilis](#unduh-apk-rilis)
  - [Pakai APK yang sudah ada di repo](#pakai-apk-yang-sudah-ada-di-repo)
  - [Build sendiri](#build-sendiri)
- [Cara pakai](#cara-pakai)
- [Struktur proyek](#struktur-proyek)
- [Cara kerja internal](#cara-kerja-internal)
- [Kunci signing, baca ini dulu](#kunci-signing-baca-ini-dulu)
- [Testing dan CI](#testing-dan-ci)
- [Batasan](#batasan)
- [FAQ](#faq)
- [Berkontribusi](#berkontribusi)
- [Kredit](#kredit)
- [Lisensi](#lisensi)

---

## Fitur

- **Pilih aplikasi** dari daftar aplikasi terpasang, lengkap dengan kolom cari.
  Aplikasi sistem ikut tampil dan ditandai, terserah kamu mau clone atau tidak.
- **Atur clone dalam satu layar.** Nama clone terisi otomatis (contoh:
  `Muse 2`), package clone terisi otomatis (contoh:
  `com.facebook.aura.klon1`, naik ke `klon2`, `klon3` kalau sudah dipakai), dan
  ikon bisa diganti dari galeri.
- **Split APK didukung.** Aplikasi App Bundle yang terpasang sebagai beberapa
  APK digabung dulu sebelum di-clone.
- **Satu kunci signing untuk semua clone**, jadi clone bisa di-update tanpa
  uninstall dulu. Kuncinya dibuat sekali saat pertama jalan dan disimpan di HP.
- **Riwayat clone** tersimpan di aplikasi, jadi APK clone yang sudah jadi bisa
  di-install ulang atau dibagikan tanpa clone dari nol lagi.
- **Daftar aplikasi tetap ringan** di HP dengan ratusan aplikasi: scan tanpa
  ikon, ikon dimuat per baris yang terlihat, dan hasil scan di-cache sebentar.
- **Tanpa iklan, tanpa pelacakan, tanpa internet.** Clone murni proses lokal.

## Syarat

- Android 8.0 (API 26) atau lebih baru.
- Izin **Install unknown apps** untuk OpenClone, karena clone di-install sebagai
  APK biasa.
- Izin `QUERY_ALL_PACKAGES` untuk melihat daftar aplikasi terpasang.
- Tanpa izin penyimpanan: pemilihan ikon memakai pemilih file/sistem (Storage
  Access Framework), bukan akses ke seluruh galeri.

## Instalasi

### Unduh APK rilis

1. Buka halaman [Releases](https://github.com/Nbill27/openclone/releases).
2. Unduh APK dari rilis terbaru.
3. Buka file APK-nya di HP, izinkan **Install unknown apps** saat diminta, lalu
   tap **Install**.

Lewat ADB, kalau HP sudah tersambung dan USB debugging aktif:

```bash
adb install -r OpenClone-0.1.0-debug.apk
```

### Pakai APK yang sudah ada di repo

Folder [`apk/`](apk/) berisi APK yang sudah dibangun, jadi kamu tidak perlu
memasang toolchain Android sama sekali:

```bash
git clone https://github.com/Nbill27/openclone.git
cd openclone/apk
```

Kirim `OpenClone-0.1.0-debug.apk` ke HP lalu install seperti biasa. Versi,
ukuran, dan checksum tiap file dicatat di [`apk/README.md`](apk/README.md).

### Build sendiri

Butuh:

- JDK 17 (JDK 21+ belum didukung AGP 8.4.2)
- Android SDK dengan platform 34 dan build-tools 34.0.0
- Koneksi internet pada build pertama, untuk mengunduh Gradle dan dependensi

```bash
git clone https://github.com/Nbill27/openclone.git
cd openclone
./gradlew assembleDebug
# APK ada di app/build/outputs/apk/debug/app-debug.apk
```

Di Windows:

```powershell
gradlew.bat assembleDebug
```

Atau buka folder ini di Android Studio (Ladybug atau lebih baru) dan tekan Run.

Kalau Gradle tidak menemukan SDK, set `ANDROID_HOME` (atau
`ANDROID_SDK_ROOT`), atau buat `local.properties` berisi:

```properties
sdk.dir=C\:\\Users\\NAMA\\AppData\\Local\\Android\\Sdk
```

`local.properties` sudah masuk `.gitignore`, jangan di-commit.

Untuk build rilis yang ditandatangani kunci sendiri, tambahkan `signingConfigs`
di `app/build.gradle.kts` dan pakai `./gradlew assembleRelease`. Build rilis dari
CI memakai debug key agar APK di halaman Releases bisa langsung dipasang.

## Cara pakai

Tiga langkah, tiga layar.

1. **Pilih Aplikasi.** Buka OpenClone, cari aplikasi di daftar, tap.
2. **Atur Clone.** Nama dan package clone sudah terisi otomatis. Kalau mau,
   ganti ikon dari galeri; kalau dikosongkan, ikon asli dipakai. Pastikan
   package clone belum dipakai aplikasi lain.
3. **Tap CLONE**, tunggu sampai selesai, lalu tap **INSTALL**.

Clone yang sudah jadi tersimpan di penyimpanan internal aplikasi
(`files/clones/<package>.apk`) dan bisa di-install ulang atau dibagikan dari
layar hasil.

## Struktur proyek

Proyek ini satu modul Android (`:app`), tanpa library internal, supaya mudah
dibaca dari atas ke bawah.

```
openclone/
├── .github/
│   └── workflows/
│       └── build.yml              # CI: unit test → lint → assemble → artifact/release
├── app/
│   ├── build.gradle.kts           # Konfigurasi modul: SDK, dependensi, viewBinding
│   ├── proguard-rules.pro         # Aturan R8 (saat ini minify dimatikan)
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
│       │       └── mipmap-*/      # ikon launcher legacy + round, semua densitas
│       └── test/java/dev/bill/openclone/
│           ├── CloneConfigTest.kt
│           └── ManifestNamingTest.kt
├── apk/
│   ├── OpenClone-0.1.0-debug.apk  # APK siap install
│   └── README.md                  # Versi, checksum, cara install
├── gradle/wrapper/                # Gradle 8.7 wrapper
├── build.gradle.kts               # Versi plugin AGP 8.4.2 + Kotlin 1.9.24
├── settings.gradle.kts            # Nama proyek, repositori, modul :app
├── gradle.properties              # Heap Gradle, AndroidX, gaya Kotlin
├── gradlew / gradlew.bat          # Gradle wrapper
├── LICENSE                        # MIT
└── README.md
```

### Penjelasan tiap file sumber

| File | Tanggung jawab |
| --- | --- |
| `MainActivity.kt` | Layar 1, "Pilih Aplikasi". Memuat daftar di `onResume`, memfilter saat mengetik, membuka layar clone. |
| `CloneActivity.kt` | Layar 2 dan 3, "Atur Clone" dan hasil. Menjalankan clone di coroutine, menampilkan progres, memasang APK lewat `PackageInstaller` (fallback `ACTION_VIEW`), membagikan APK, dan memulihkan state setelah rotasi. |
| `CloneEngine.kt` | Inti pekerjaan: salin APK/split, gabung dengan ARSCLib, ganti package name, ganti label, ganti ikon, buang signature lama, paksa `resources.arsc` STORED, tulis APK, tanda tangani v1/v2/v3. Satu fungsi `suspend` dengan checkpoint pembatalan. |
| `CloneConfig.kt` | Model data (`CloneConfig`, `CloneResult`, `CloneRecord`) plus helper murni: saran nama/package clone, validasi package name. |
| `CloneStore.kt` | Riwayat clone sebagai satu file JSON di private storage. Akses diserialkan dengan lock process-wide, tulisan atomik lewat file `.tmp` lalu rename. |
| `KeystoreManager.kt` | Membuat dan memuat kunci signing (PKCS12) di `files/openclone-keystore.p12`, dengan password acak di `SharedPreferences` privat. |
| `AppRepository.kt` | Scan aplikasi terpasang: satu `getInstalledPackages` bulk untuk versi, filter `sourceDir` null, cache 15 detik, `invalidate()` setelah install sukses. |
| `AppIcons.kt` | Cache ikon lazy per baris. Drawable dirasterisasi ke bitmap kecil, disimpan di `LruCache` berbatas 8 MB. |
| `AppListAdapter.kt` | RecyclerView adapter dengan `DiffUtil` (identity `packageName@sourceDir`), ikon dimuat saat bind. |
| `InstalledApp.kt` | Model satu aplikasi terpasang, tanpa `Drawable`, dengan `lowerLabel`/`searchKey` lazy untuk filter. |
| `ManifestNaming.kt` | Aturan teks murni untuk rename manifest: permission yang dideklarasikan, `authorities` provider, `android:permission`, `android:process`. Dipisah supaya bisa diuji tanpa APK. |

### Penjelasan resource

| Path | Isi |
| --- | --- |
| `res/layout/activity_main.xml` | Judul, tombol Tentang, kolom cari, daftar aplikasi. |
| `res/layout/activity_clone.xml` | Ikon, nama clone, package clone, pemilih ikon, tombol CLONE/INSTALL/BAGIKAN. |
| `res/layout/item_app.xml` | Satu baris daftar: ikon 48dp, nama, package, metadata versi. |
| `res/values/strings.xml` | Semua teks UI (Bahasa Indonesia). Tambah string baru di sini, jangan hardcode di layout. |
| `res/values/colors.xml` | `openclone_blue` dan `ic_launcher_background`. |
| `res/values/themes.xml` | Tema Material 3 DayNight tanpa ActionBar. |
| `res/xml/file_paths.xml` | Path yang boleh dibagikan lewat FileProvider (`clones/` dan cache). |
| `res/mipmap-anydpi-v26/` | Adaptive icon XML untuk API 26+. |
| `res/mipmap-*dpi/` | Ikon legacy dan round untuk tiap densitas. |
| `res/drawable-xxxhdpi/` | Foreground dan monochrome adaptive icon (raster tunggal). |

## Cara kerja internal

Tidak ada API Android untuk "jalankan aplikasi dua kali". Triknya adalah
membuat aplikasi kedua yang menurut Android benar-benar berbeda.

### Pipeline clone

`CloneEngine.clone()` berjalan berurutan, dengan `ensureActive()` sebelum tiap
langkah berat:

1. **Salin** `ApplicationInfo.sourceDir` (dan semua split) ke cache dir.
2. **Muat** APK dengan `ApkModule`/`ApkBundle`; split digabung jadi satu modul,
   APK tunggal dimuat langsung.
3. **Ganti package name** lewat `setPackageName`, lalu `ManifestNaming`
   merapikan identifier yang wajib unik per aplikasi:
   - `<permission>` / `<permission-tree>` / `<permission-group>` yang memakai
     package lama di-rename, termasuk deklarasinya di `uses-permission`.
   - `authorities` provider dipecah per `;`; yang sudah di bawah package baru
     dibiarkan, yang masih package lama di-swap prefix-nya, dan nama telanjang
     (mis. `com.facebook.appmanager`) diberi namespace package baru.
   - `android:permission` dan `android:process` pada komponen ikut disesuaikan.
   - Nama kelas komponen **tidak pernah** di-rename.
4. **Ganti label** aplikasi.
5. **Ganti ikon** kalau user memilih gambar: decode, crop center, tulis ulang
   entry ikon launcher di dalam APK.
6. **Buang signature lama** secara selektif: `META-INF/*.SF`, `*.RSA`, `*.DSA`,
   `*.EC`, `MANIFEST.MF`, `SIG-*`. `META-INF/services` dan `*.kotlin_module`
   tetap ada karena dibutuhkan ServiceLoader dan Kotlin.
7. **Paksa `resources.arsc` STORED** (`isUncompressed = true`). Android 11+
   menolak `resources.arsc` yang terkompresi atau tidak selaras.
8. **Tulis APK** ke file sementara.
9. **Tanda tangani** v1 + v2 + v3 dengan apksig, ke file `.tmp`.
10. **Publish atomik**: rename `.tmp` menjadi `<package>.apk` final.

Cleanup (`module.destroy()`, hapus work dir) selalu jalan di `finally`,
termasuk saat coroutine dibatalkan.

### Aturan coroutine dan thread

- `clone()` adalah `suspend` dan dipanggil dari satu scope di
  `CloneActivity` (`Dispatchers.Default`, karena kerja CPU + disk).
- Progress dikirim lewat `suspend (String) -> Unit`; callback melakukan hop ke
  `Dispatchers.Main.immediate` sendiri, jadi tidak ada coroutine liar per baris
  progres.
- Semua lookup `PackageManager`, baca/tulis file, dan decode ikon terjadi di
  `Dispatchers.IO`/`Default`, tidak pernah di main thread.
- `CancellationException` selalu di-rethrow, tidak ditelan `catch (Exception)`.
- Setelah scan selesai, activity mengecek `isFinishing || isDestroyed` sebelum
  menyentuh view.

### Penyimpanan dan konsistensi

- Riwayat clone (`clones.json`) ditulis lewat file `.tmp` lalu rename, dengan
  lock process-wide, supaya dua activity tidak saling menimpa.
- APK hasil ditandatangani ke `.tmp` lalu di-rename, supaya crash di tengah
  signing tidak merusak clone lama.
- Scan aplikasi di-cache 15 detik di `AppRepository`; cache di-invalidate setiap
  install clone berhasil.

### Performa daftar aplikasi

- Scan hanya membaca metadata paket (satu bulk call), tanpa `loadIcon` atau
  `loadLabel` per paket.
- Ikon dimuat saat baris benar-benar di-bind, dirasterisasi ke ukuran layar,
  dan disimpan di cache berbatas byte.
- `DiffUtil` dipakai saat daftar berubah; tidak ada `notifyDataSetChanged`.

## Kunci signing, baca ini dulu

Sekali kunci hilang, clone yang sudah terpasang tidak bisa di-update; harus
uninstall dan install ulang, datanya hilang.

Karena itu OpenClone membuat **satu** kunci saat pertama jalan, menyimpannya di
`files/openclone-keystore.p12` di penyimpanan privat aplikasi, dan memakai kunci
yang sama untuk semua clone.

**Backup file keystore itu.** Kalau kamu reinstall OpenClone atau pindah HP
tanpa membawa keystore-nya, clone lama tidak bisa di-update lagi.

## Testing dan CI

Unit test berjalan di JVM, tanpa device dan tanpa APK:

```bash
./gradlew testDebugUnitTest
```

Yang diuji:

- `ManifestNamingTest` — aturan rename `authorities`, permission, process,
  termasuk kasus sudah di-rename dan nama telanjang.
- `CloneConfigTest` — saran package `klon1..n`, validasi package name, saran
  label.

Lint dan build:

```bash
./gradlew lintDebug
./gradlew assembleDebug
```

GitHub Actions (`.github/workflows/build.yml`) menjalankan urutan yang sama
untuk setiap push ke `main` dan setiap pull request:

1. `testDebugUnitTest`
2. `lintDebug`
3. `assembleDebug`
4. upload APK sebagai artifact

Tag `v*.*.*` (contoh `v0.1.0`) memicu job release yang membuat GitHub Release
dan melampirkan APK.

## Batasan

- Aplikasi yang mengecek signature-nya sendiri, memakai SafetyNet / Play
  Integrity, atau anti-tamper (banking, game tertentu) akan menolak salinan yang
  sudah di-sign ulang. Itu butuh patch DEX/native, di luar scope proyek ini.
- `resources.arsc` hanya diubah package name-nya. Aplikasi yang membaca package
  name dari resource, bukan dari `Context.getPackageName()`, bisa berperilaku
  aneh.
- Clone harus dipasang sebagai APK biasa, jadi HP harus mengizinkan **Install
  unknown apps** untuk OpenClone.
- Clone tidak bisa update lewat Play Store: package dan signature-nya berbeda.
- Tanpa root. Semua memakai API Android publik.
- Jangan publish hasil clone ke Play Store, dan jangan clone aplikasi untuk
  membajak, menipu, atau melanggar lisensinya. Proyek ini untuk pemakaian
  pribadi di HP sendiri.

## FAQ

**Apakah OpenClone butuh root?**
Tidak.

**Apakah clone bisa update sendiri dari Play Store?**
Tidak. Clone punya package dan signature berbeda. Untuk update, clone lagi versi
baru aplikasinya, atau install ulang APK clone yang baru.

**Kenapa aplikasi X force close setelah di-clone?**
Biasanya karena aplikasi itu mengecek signature atau mendeteksi modifikasi.
Lihat bagian [Batasan](#batasan).

**Apakah data akun di aplikasi asli aman?**
Ya. OpenClone hanya menyalin file APK, bukan data aplikasi. Clone mulai dari
kondisi fresh install.

**Kenapa package clone saya ditolak?**
Package harus minimal dua segmen, hanya huruf/angka/underscore, dan tidak diawali
angka di tiap segmen. Contoh valid: `com.example.app.klon1`.

**Apakah ikon asli bisa dipertahankan?**
Bisa. Kosongkan pilihan ikon; engine hanya mengganti ikon kalau ada gambar yang
dipilih.

## Berkontribusi

Kontribusi terbuka untuk siapa saja: bug report, perbaikan dokumen, terjemahan,
sampai fitur baru.

### Sebelum mulai

- Untuk perubahan besar atau fitur baru, buka issue dulu supaya arahnya jelas.
- Untuk bug, sertakan: versi Android, merk/tipe HP, nama aplikasi yang di-clone,
  langkah reproduksi, dan log (`adb logcat`) kalau ada.
- Jangan sertakan APK aplikasi berbayar atau data pribadi di issue.

### Alur kerja

```bash
# 1. Fork repo di GitHub, lalu clone fork kamu
git clone https://github.com/<username>/openclone.git
cd openclone

# 2. Buat branch dari main
git checkout -b fix/icon-cache-eviction

# 3. Kerjakan perubahan, lalu jalankan pemeriksaan
./gradlew testDebugUnitTest lintDebug assembleDebug

# 4. Commit dan push
git add -A
git commit -m "fix: batasi cache ikon berdasarkan ukuran byte"
git push origin fix/icon-cache-eviction
```

Lalu buka pull request ke `main`.

### Konvensi commit

Pakai [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: dukungan ikon adaptive untuk clone
fix: cegah cache ikon tumbuh tanpa batas
perf: hindari loadIcon saat scan aplikasi
docs: perjelas langkah instalasi di Windows
test: tambah kasus authorities dengan titik
refactor: pisahkan aturan rename manifest
chore: perbarui Gradle wrapper
```

Pesan commit dalam bahasa Inggris atau Indonesia, yang penting konsisten dalam
satu PR.

### Aturan kode

- **Kotlin, tanpa Java baru.** Ikuti gaya yang sudah ada di file sekitarnya.
- **UI string selalu di `res/values/strings.xml`**, bukan hardcode di layout
  atau kode. Bahasa UI saat ini Indonesia.
- **Komentar dan nama API dalam bahasa Inggris.** Komentar menjelaskan *kenapa*,
  bukan mengulang *apa* yang sudah jelas dari kode.
- **Kerja berat tidak boleh di main thread.** Pakai `suspend` + dispatcher yang
  tepat; jangan menelan `CancellationException`.
- **Tulis file secara atomik** (`.tmp` lalu rename) untuk data yang tidak boleh
  korup.
- **Jangan menambah izin Android** tanpa diskusi di issue. Izin yang ada saat ini
  hanya `QUERY_ALL_PACKAGES` dan `REQUEST_INSTALL_PACKAGES`.
- **Jangan menambah dependensi besar** tanpa alasan kuat; jelaskan di PR.
- **Non-goal yang tidak boleh dilanggar:** patch DEX, bypass signature, bypass
  Play Integrity, atau trik yang mengakali sistem keamanan Android.
- **Setiap aturan teks yang bisa diuji harus punya unit test.** Logika baru di
  `ManifestNaming`/`CloneConfig` wajib disertai test.

### Checklist pull request

- [ ] `./gradlew testDebugUnitTest` lulus
- [ ] `./gradlew lintDebug` tidak menambah error/warning baru
- [ ] `./gradlew assembleDebug` menghasilkan APK
- [ ] Perubahan UI diuji di HP/emulator (sebutkan versi Android)
- [ ] String baru masuk `strings.xml`
- [ ] Tidak ada file build, keystore, atau `local.properties` ikut ter-commit
- [ ] Deskripsi PR menjelaskan masalah, pendekatan, dan cara menguji

### Ide kontribusi

- Dukungan bahasa Inggris (locale `values-en`) dan bahasa lain.
- Deteksi otomatis bentrok package sebelum tombol CLONE ditekan.
- Ekspor/impor keystore dari layar riwayat, supaya backup tidak perlu ADB.
- Indikator progres bertahap (persentase, bukan hanya teks).
- Test instrumentasi untuk alur install.
- Dukungan tema gelap yang lebih rapi dan dynamic color.

## Kredit

- [ARSCLib](https://github.com/REAndroid/ARSCLib) (Apache-2.0) untuk membaca dan
  menulis `AndroidManifest.xml` biner serta `resources.arsc`.
- [Apktool](https://github.com/iBotPeaches/Apktool) (Apache-2.0), referensi
  format APK dan inspirasi.
- [apksig](https://maven.google.com/com/android/tools/build/apksig) dari AOSP
  untuk signing v1/v2/v3.
- [BouncyCastle](https://www.bouncycastle.org/) untuk sertifikat self-signed.
- [AndroidX](https://developer.android.com/jetpack/androidx) dan
  [Material Components](https://github.com/material-components/material-components-android).

## Lisensi

MIT License, lihat file [`LICENSE`](LICENSE).
Copyright (c) 2026 Muhammad Nabil Deja A.R.
