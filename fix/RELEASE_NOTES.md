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
- Nama akun di tiap HP harus berbeda.

### Kejang saat memuat chunk (HP RAM kecil)
- Thread latar generator chunk dibatasi 2 (`-Dmax.bg.threads=2`) pada HP RAM < 3 GB agar tidak berebut CPU/RAM dengan thread render dan server.

### Rilis
- Catatan rilis kini diambil dari `RELEASE_NOTES.md` di repo.
