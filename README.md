# Coneckin

Aplikasi Android (Kotlin native) untuk login portal WiFi kampus dengan satu ketukan.
Simpan username dan password sekali, lalu pindah ruangan tanpa mengetik ulang.

- Landing page dan panduan: https://coneckin.github.io/coneckin/
- Unduh APK: https://github.com/coneckin/coneckin/releases/latest/download/coneckin.apk

## Cara kerja

1. Pengguna menyimpan akun (username, password) dan alamat portal. Data dienkripsi
   AES-GCM dengan kunci di Android Keystore dan hanya tersimpan di HP.
2. Saat tombol **Login WiFi** (atau tile Quick Settings) ditekan, aplikasi mengikat
   proses ke jaringan WiFi dan memeriksa konektivitas.
3. Jika diarahkan ke portal yang terdaftar, halaman login dibuka di WebView, kolom
   diisi otomatis, lalu tombol login ditekan. Kredensial hanya diisi jika host halaman
   sama dengan host portal yang tersimpan.
4. Jika gagal, pengguna dapat membuka halaman portal secara manual.

## Struktur proyek

| Bagian | Isi |
|---|---|
| `app/` | Aplikasi Android (Kotlin, Jetpack Compose) |
| `server/` | API kecil (Node.js, Express, MongoDB) untuk daftar kampus, info versi, dan laporan masalah opt-in |
| `docs/` | Landing page (GitHub Pages) |
| `.github/workflows/release.yml` | Build otomatis, tag, dan rilis APK serta AAB |

## Build

Persyaratan: Android Studio terbaru dan JDK 17.

```bash
./gradlew assembleDebug
./gradlew test        # unit test
```

Build rilis bertanda tangan dijalankan oleh GitHub Actions. Secrets yang dibutuhkan:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Server

```bash
cd server
npm install
MONGODB_URI="..." npm start
```

Variabel lingkungan: `MONGODB_URI` (wajib), `DB_NAME`, `PORT`, `IP`.

## Privasi

- Username dan password tidak pernah dikirim ke server Coneckin, hanya ke halaman login portal.
- Laporan masalah bersifat opsional (bawaan mati) dan hanya berisi kode kesalahan,
  host portal, versi aplikasi, versi Android, dan merek perangkat.

Gunakan hanya untuk akun milik Anda sendiri dan pada jaringan yang Anda percayai.

## Lisensi

GNU GPL v3. Lihat berkas `LICENSE`.
