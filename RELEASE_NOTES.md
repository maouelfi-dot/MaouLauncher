## Maou Launcher 2.10.25 (tidak resmi, fork Zalith Launcher 2)

> Belum diuji langsung di perangkat; mohon laporkan jika ada masalah (sertakan `latest_game.log`).

### Stutter 2-3 detik saat load chunk / RAM game > 85% (HP RAM kecil)
- Profil JVM HP RAM kecil kini sama seperti Zalith Launcher 1: generasi muda 96-256 MB, tanpa penyusutan heap otomatis, tanpa batas 2 thread latar, tanpa `CICompilerCount=2`.
- Modpack 40+ mod tidak lagi dipaksa JIT C1-saja (membuat pembuatan chunk lambat); code cache 64 MB.
- Batas heap otomatis naik: 55% RAM HP (dulu 42%), batas belajar maks 62%. Atur slider RAM game sendiri ke sekitar 900-1000 MB; 512 MB terlalu kecil untuk modpack sehingga GC penuh berulang.

## Maou Launcher (rilis berikutnya) - tidak resmi, fork Zalith Launcher 2

### Layout kontrol mengemudi (baru)
- Layout "Mobil (Stir & Gas)" otomatis tersedia di daftar layout kontrol: tombol Kiri/Kanan (stir), GAS, REM, Handbrake (Space), Klakson (H), Lampu (L), Kamera (F5), dan Masuk/Keluar (klik kanan). Cocok dipakai dengan mod mobil seperti Automobility. Belum diuji di perangkat.

## Maou Launcher 2.10.23 (tidak resmi, fork Zalith Launcher 2)

### Patah-patah saat pertama masuk dunia (HP RAM kecil)
- Heap awal JVM kini 75% dari batas (sebelumnya 50%), sehingga heap tidak perlu membesar berkali-kali saat chunk pertama dimuat. Belum diuji di perangkat.

## Maou Launcher 2.10.22 (tidak resmi, fork Zalith Launcher 2)

> Belum diuji langsung di perangkat; mohon laporkan jika ada masalah.

### Paket Performa (baru)
- Di layar Mod (versi Fabric) ada tombol **Paket Performa**: mengunduh Sodium, FerriteCore, ModernFix, ImmediatelyFast, dan Entity Culling dari Modrinth sesuai versi Minecraft. Yang sudah terpasang dilewati; yang tidak tersedia untuk versi itu dilaporkan di notifikasi.
- Mod `mods/maou-chunk-tuner` (render distance adaptif) ditambahkan ke repo; build lewat GitHub Actions.

## Maou Launcher 2.10.21 (tidak resmi, fork Zalith Launcher 2)

> Belum diuji langsung di perangkat; mohon laporkan jika ada masalah (sertakan `latest_game.log`).

### Modpack besar di HP RAM kecil
- Untuk HP RAM < 3 GB dengan 40+ mod: kompiler JIT ringan (C1), code cache dibatasi 96 MB, metaspace dibatasi 384 MB, stack 1 MB. Tujuannya mengurangi lonjakan RAM saat memuat ratusan class mod sehingga game tidak dibunuh sistem (FC).

### Rekam layar
- Kini 720p (sisi panjang 1280 px) di semua HP, target 30 fps, bitrate 3,5 Mbps (HP RAM kecil) / 6 Mbps. Jika game ikut berat, perekam menurunkan frekuensinya sendiri agar game tidak patah-patah.

### Penyimpanan
- Log launcher/native > 7 hari dan cache unduhan sementara > 1 hari dibersihkan otomatis di latar. Dunia, mod, pengaturan, dan rekaman tidak disentuh.

## Maou Launcher 2.10.19 (tidak resmi, fork Zalith Launcher 2)

> Belum diuji langsung di perangkat (tidak ada Android SDK saat dibuat); mohon laporkan jika ada masalah.

### Tampilan menu utama
- Nama akun kini teks biasa (gaya judul "MaouLauncher"), tanpa cahaya ungu. Label "Offline" juga teks biasa tanpa bingkai ungu.
- Kartu hanya melingkupi bagian versi + tombol Luncurkan; karakter melayang tanpa kartu di belakangnya. Posisi kartu tetap bisa digeser.
- Tutorial awal kini menyuruh mengetuk karakter Steve untuk membuat/mengganti akun.

### Java
- Java bawaan (Internal-8/17/21/25) kini bisa dihapus agar penyimpanan lega. Tombol "Pulihkan Java bawaan" memasangnya lagi saat launcher dibuka ulang.

## Maou Launcher 2.10.18 (tidak resmi, fork Zalith Launcher 2)

### Perbaikan crash saat membuka game (Android 8.1 / Oppo A3s)
- Versi 2.10.17 langsung crash saat tombol Luncurkan ditekan: `ClassCastException: OpenSSLRSAPrivateKey cannot be cast to RSAPrivateCrtKey`. Penyebabnya kunci RSA tetap untuk skin offline dipaksa ke tipe `RSAPrivateCrtKey`, padahal Android 8.1 memberi tipe lain. Kini kunci publik dibaca dari pasangannya dan tidak ada cast; bila kunci tetap gagal dimuat, launcher memakai kunci acak supaya game tetap bisa dibuka.

## Maou Launcher 2.10.17 (tidak resmi, fork Zalith Launcher 2)

> Perubahan di bawah ini belum diuji langsung di perangkat (Oppo A3s) saat dibuat; mohon laporkan jika ada masalah, sertakan `latest_game.log`.

### Minecraft 26.2 / 26.3 di HP tanpa Vulkan (mis. Oppo A3s)
- Minecraft 26.2+ punya backend Vulkan (butuh Vulkan 1.2). Sebelumnya, jika `options.txt` sudah berisi `vulkan` atau `default` dari snapshot, launcher lain, atau pilihan lama, launcher **tidak menimpanya** sehingga game berjalan dengan Vulkan di HP yang tidak mendukungnya dan crash / layar hitam.
- Kini launcher memeriksa HP (Vulkan 1.2 tersedia atau tidak). Bila **tidak**, game selalu dipaksa **OpenGL** (lewat renderer OpenGL seperti MobileGlues). Dialog pemeriksaan Vulkan juga dilewati sehingga tidak ada pemeriksaan native yang bisa gagal.
- Pilih renderer **MobileGlues** untuk versi 26.x. Perlu Java 25 (sudah ada di launcher).
- Catatan: 26.x jauh lebih berat; di HP RAM 2 GB bisa tetap kehabisan memori atau sangat lambat walau Vulkan sudah bukan masalah.
