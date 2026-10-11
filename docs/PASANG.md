# Cara pasang Beka

Panduan ini untuk kamu yang baru pertama kali memasang Beka. Butuh HP Android 11 atau lebih baru,
dan sekitar 10 menit.

## 1. Unduh dan pasang
1. Buka halaman [Releases](https://github.com/bnjyar/Beka/releases/latest) di HP, lalu tap `beka.apk`.
2. Kalau browser bertanya, izinkan mengunduh file ini.
3. Buka file yang sudah terunduh. Android akan minta izin "Instal aplikasi tidak dikenal" untuk browser
   atau app file kamu: nyalakan, lalu kembali dan tap **Instal**.
4. Kalau muncul peringatan Play Protect ("aplikasi tidak dikenal"), pilih **Detail lainnya → Tetap instal**.
   Beka tidak ada di Play Store, jadi peringatan ini wajar.

## 2. Setup awal
Buka Beka. Setup akan memandu langkah demi langkah:
1. **Tampil di atas app lain**: supaya bubble Beka bisa melayang di atas app lain.
2. **Aksesibilitas**: supaya Beka bisa membaca layar dan menekan tombol saat kamu suruh.
   Kalau tombolnya abu-abu atau muncul "Setelan dibatasi", buka **Info aplikasi → ⋮ (pojok kanan atas)
   → Izinkan setelan terbatas**, lalu coba lagi. Ini terjadi untuk semua app yang dipasang di luar Play Store.
3. **Notifikasi**: boleh dilewati, tapi tanpa ini Beka tidak bisa mengabari kamu dari rule proaktif.
   "Akses notifikasi" juga bisa kena "Setelan dibatasi"; caranya sama seperti di atas.
4. **Tetap jalan di background**: izinkan Beka tidak dibatasi baterai, supaya bubble dan rule tidak dimatikan HP.
   Di Xiaomi, Oppo, Vivo dan Realme ada tombol **Buka setelan HP** untuk izin tambahan (lihat bagian 4).
5. **Mikrofon**: kalau mau bicara dengan Beka. Boleh dilewati.

## 3. Pilih model AI
Beka butuh model AI untuk menjawab. Beka tidak punya server sendiri; kamu memakai akun sendiri.
- **Paling mudah: Login OpenRouter.** Tap **Login OpenRouter**, masuk atau daftar, lalu izinkan. Akun baru
  perlu saldo dulu: isi di [openrouter.ai/settings/credits](https://openrouter.ai/settings/credits).
  Tanpa saldo Beka akan bilang "Saldo di provider habis".
- **Punya API key sendiri** (OpenAI, Anthropic, atau provider lain): Setelan → Model AI → **Tambah provider**,
  tempel key-nya, lalu **Simpan**.

Key disimpan terenkripsi di HP dan hanya dikirim ke provider yang kamu pilih.

## 4. HP Xiaomi, Oppo, Vivo, Realme, Samsung
HP merek ini suka mematikan app di background. Langkah "Tetap jalan di background" di setup (juga ada di
Setelan → Izin) sudah mengurus baterai. Kalau bubble Beka masih sering hilang atau Beka tidak bisa membuka app:
- **Baterai**: Info aplikasi Beka → Baterai → pilih **Tidak dibatasi** (atau "Tanpa batasan").
- **Mulai otomatis** (Xiaomi, Oppo, Vivo, Realme): Info aplikasi Beka → nyalakan **Mulai otomatis / Autostart**.
- **Xiaomi**: Info aplikasi Beka → Izin lainnya → nyalakan **Tampilkan jendela pop-up saat berjalan di latar
  belakang**. Tanpa ini Beka tidak bisa membuka app lain.
- **Samsung**: Setelan → Perawatan perangkat → Baterai → Batas penggunaan latar belakang → pastikan Beka
  tidak ada di "Aplikasi tidur".

Nama menunya bisa sedikit beda tergantung versi HP.

## 5. Mulai pakai
Tap **Mulai Beka**, lalu tap bubble untuk membuka panel. Coba tanya "ini layar apa?" di app apa saja.
Beka minta izin dulu sebelum melakukan hal berisiko (mengirim, membayar, menghapus, menelepon), dan tidak
pernah menyentuh app bank, dompet digital, atau pengelola kata sandi.

## Pembaruan
Beka mengecek versi baru sendiri. Pasang lewat **Setelan → Pembaruan**. Data kamu tetap ada.

## Cadangan
Sebelum ganti HP atau pasang ulang, buka **Setelan → Data → Ekspor**. Simpan file-nya, lalu di HP baru
pakai **Impor**. Memori, rule, izin app dan setelan ikut; API key tidak ikut, jadi login atau tempel lagi.
Menghapus Beka (uninstall) menghapus semua datanya.

## Ada masalah?
Di panel Beka: **⋮ → Laporkan**. Ceritakan apa yang terjadi; Beka menyimpan laporan dan membuka halaman
GitHub untuk mengirimnya. Atau kabari orang yang memberimu Beka.
