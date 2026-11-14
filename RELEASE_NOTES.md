## Maou Launcher 2.10.16 (tidak resmi, fork Zalith Launcher 2)

> Versi tetap 2.10.16. Perubahan di bawah ini belum diuji langsung di perangkat (Oppo A3s) saat dibuat; mohon laporkan jika ada masalah, sertakan `latest_game.log`.

### Perbaikan rekam layar
- **Gambar bergaris / blok hijau-magenta acak / langit hitam di awal video** diperbaiki. Penyebabnya: frame game disalin dengan `TextureView.getBitmap()` dari thread latar yang balapan dengan thread render.
- Frame sekarang disalin lewat **PixelCopy** (disalin GPU, sinkron dengan buffer game, langsung diskalakan ke ukuran video) dan dikirim ke encoder lewat canvas hardware.
- **Video patah-patah walau game 30 FPS** diperbaiki: cara lama hanya menghasilkan sekitar 12 fps di HP lemah. Pengambilan frame kini tidak membebani thread UI dan memakai ritme waktu tetap (tidak mengejar dengan ledakan frame).
- Frame kosong (game belum menggambar apa pun) tidak lagi ditulis sebagai frame hitam.
- **Game ikut patah-patah saat merekam** dikurangi: perekam kini mengukur waktu kerjanya dan memakai paling banyak sekitar 1/3 waktu (jarak antar frame menyesuaikan, 10-30 fps), thread perekam berprioritas lebih rendah dari game, dan di HP RAM < 3 GB video direkam 854 px dengan bitrate 2,5 Mbps.
- Jika PixelCopy tidak tersedia di perangkat, otomatis kembali ke cara lama.

### Mabar Hotspot: skin tidak terlihat di HP lain
- Skin teman kini diunduh **saat game menanyakannya** (maks. ±5 dtk), bukan hanya lewat siaran berkala. Game menyimpan hasil pencarian skin yang gagal, sehingga sebelumnya skin tetap Steve sampai game dimulai ulang.
- **Penyebab utama skin host/skin sendiri tidak muncul di HP lain (ditemukan dari log):** URL skin berisi `localhost:<port server skin>` dan profil pemain dijawab server skin HOST. Port dulu acak per HP, jadi HP lain mengakses port yang salah. Kini server skin memakai **port tetap 24447** di semua HP (jatuh ke port acak jika terpakai).
- Kunci tanda tangan profil kini **tetap** di semua HP (dulu acak per HP, sehingga tanda tangan host ditolak HP lain).
- **Berlapis (cadangan bila satu lapis gagal):** (1) skin diunduh lewat siaran Mabar; (2) saat game menanyakan profil yang belum ada, skin diunduh saat itu juga; (3) saat game meminta tekstur yang belum ada, diunduh dari teman lalu dijawab; (4) login ke host tidak lagi ditolak bila skin pemain belum sempat tersinkron (dulu dijawab `{}`), pemain tetap masuk tanpa skin lalu skin menyusul.
- Semua akun offline didukung (memang itulah yang dilayani server skin lokal ini).
- Nama akun di tiap HP harus berbeda.

### Kejang saat memuat chunk (HP RAM kecil)
- Thread latar generator chunk dibatasi 2 (`-Dmax.bg.threads=2`) pada HP RAM < 3 GB agar tidak berebut CPU/RAM dengan thread render dan server.

### Fitur baru: FPS Boost Mode (nyata)
- Saklar di **menu melayang** dalam game. Menyetel `options.txt` sebelum game jalan: grafis cepat, tanpa pencahayaan halus, awan, bayangan entity, dan partikel minimal, biome blend 0, mipmap 0, jarak entity 0,5, jarak render maks 4 dan simulasi maks 5. FPS yang tampil di game dan launcher adalah FPS sungguhan.
- Pengaturan asli disimpan di `options.txt.maou-boost` dan dikembalikan saat mode dimatikan.
- **Batas FPS** (hemat baterai) kini juga bisa digeser di menu melayang, bukan hanya di pengaturan launcher. Berlaku di peluncuran berikutnya.

### Rilis
- Perbaikan gagal unggah: repo memakai immutable releases, jadi rilis dibuat sebagai draft, APK diunggah, lalu dipublikasikan; `RELEASE_NOTES.md` kini ikut di-checkout.
- Catatan rilis kini diambil dari `RELEASE_NOTES.md`; rilis resmi (bukan pre-release) dengan tag `v<versi>`.
