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
