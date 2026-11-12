# Maou MCEF (Android)

Pengganti **MCEF** supaya mod **WebDisplays 1.2.24 (Fabric 1.21.1)** bisa jalan di **Android** lewat **Maou Launcher** —
tanpa PC, tanpa Chromium desktop, tanpa mod tambahan selain jar ini.

## Cara kerja
- WebDisplays asli memanggil MCEF lewat refleksi. Jar ini menyediakan kelas `com.cinemamod.mcef.*` dengan nama & method yang sama.
- Halaman web dirender oleh **WebView Android** di dalam Maou Launcher (2.10.12+). Gambarnya dikirim ke game lewat soket lokal
  `127.0.0.1:28501`, lalu dipakai sebagai tekstur layar WebDisplays. Klik, scroll, dan ketikan dari game diteruskan ke WebView.
- Mod WebDisplays asli **tidak diubah**.

## Pasang
1. Maou Launcher **2.10.12** atau lebih baru.
2. Folder mods (Fabric 1.21.1): `webdisplays-1.2.24.jar` + `maou-mcef-android-*.jar` + Fabric API.
3. Jangan pasang MCEF asli bersamaan.

## Pakai
Sama seperti WebDisplays: buat blok Screen, klik kanan layar lalu isi alamat web (URL). Sentuh/klik layar untuk membuka link,
scroll untuk menggulir, ketik lewat keyboard WebDisplays.

## Batasan (jujur)
- Memakai RAM tambahan (WebView): di HP 2GB sebaiknya 1 layar saja, resolusi kecil.
- Gambar dibatasi ±800×450 piksel dan ±8 fps supaya ringan.
- Suara/video web tidak dijamin; fitur `cefQuery` khusus halaman internal WebDisplays (mis. upgrade) tidak didukung.
- Belum diuji di perangkat saat ditulis; bila ada error kirim `latest.log`.

Lisensi GPL-3.0 (mengikuti WebDisplays/MCEF).

## Kualitas (v1.1.0)
Edit `config/maou_mcef.properties` di folder game: `quality=hemat` (±480×270, 5 fps), `normal` (±800×450, 8 fps, bawaan) atau `tajam` (±960×540, 10 fps).
Hanya baris layar yang berubah yang dikirim, dan layar yang tidak terlihat dijeda otomatis.
