# Maou Launcher

![Status](https://img.shields.io/badge/status-unofficial%20fork-8E70C4)
![License](https://img.shields.io/badge/license-GPL--3.0-5B3FD4)

> [!IMPORTANT]
> **Maou Launcher adalah versi modifikasi TIDAK RESMI (unofficial fork) dari [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2).**
> Proyek ini tidak berafiliasi dengan, tidak didukung oleh, dan tidak dijalankan oleh tim Zalith Launcher, Mojang, maupun Microsoft.
> Jangan melaporkan bug Maou Launcher ke repositori resmi Zalith Launcher 2; laporkan di halaman *Issues* repositori ini.

**Maou Launcher** adalah launcher [Minecraft: Java Edition](https://www.minecraft.net/) untuk **perangkat Android**, dibuat ringan untuk HP dengan RAM kecil (target uji: Oppo A3s, RAM 2 GB, Android 8.1). Inti peluncurannya memakai basis [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher/tree/v3_openjdk/app_pojavlauncher/src/main/jni), dan UI-nya dibangun dengan **Jetpack Compose** dan **Material Design 3**.

*English:* Maou Launcher is an **unofficial, modified build of Zalith Launcher 2** for Android, tuned for low-RAM phones. It is a fork, not the official project.

## Apa yang berbeda dari Zalith Launcher 2

- **Akun offline langsung bisa dipakai**, tanpa login akun premium lebih dulu.
- **Tampilan baru:** tema hitam dengan aksen ungu, kartu pengaturan datar, bar loading tipis yang ringan (bukan bergelombang), dan latar anime bawaan yang bisa diganti.
- **Menu melayang** berbentuk pill berisi ikon gear dan FPS, bisa disembunyikan.
- **Mabar Hotspot:** main bareng lewat hotspot tanpa mengetik IP/port, ditambah kartu IP hotspot di menu game.
- **Sinkron skin dan cape** antar pemain di jaringan Mabar, serta fitur **cape custom** (PNG).
- **Rekam layar ringan** (maks 720p, 30 fps, tanpa audio).
- **Batas FPS game** (10–60 FPS atau tanpa batas) agar HP lebih dingin.
- **Optimasi HP RAM kecil:** heap dan GC disesuaikan RAM perangkat, penyetelan `options.txt` sekali pakai untuk chunk besar, daftar mod dan video latar yang lebih ringan.
- **Hotbar** lebih mudah ditekan di semua skala resolusi.
- **Rilis lebih kecil:** APK dibuat per arsitektur (arm64 dan arm).

> Daftar ini mengikuti isi patch rilis. Perilaku di tiap perangkat bisa berbeda; laporkan masalah lewat *Issues*.

## Unduh

Unduh APK dari halaman **[Releases](https://github.com/maouelfi-dot/ZalithLauncher2/releases)**:

| File | Untuk |
|------|-------|
| `MaouLauncher-<versi>-arm64-v8a.apk` | Hampir semua HP modern (disarankan) |
| `MaouLauncher-<versi>-armeabi-v7a.apk` | HP 32-bit lama |

Maou Launcher memakai ID paket sendiri (`com.maou.launcher`), jadi terpasang berdampingan dengan Zalith Launcher asli dan tidak menggantikannya.

## Membangun sendiri

Kebutuhan: JDK 21, Android SDK (min API 26, target API 35), Android NDK, Rust toolchain dengan `cargo-ndk`.

```bash
git clone https://github.com/maouelfi-dot/ZalithLauncher2.git
cd ZalithLauncher2
./gradlew :ZalithLauncher:assembleRelease -Darch=arm64
```

Atau biarkan GitHub Actions yang membangun: dorong tag seperti `v2.10.0` dan APK akan diunggah otomatis ke *Releases*.

> Build release memakai keystore bawaan di repositori sehingga bisa dibangun di fork tanpa secret. Untuk rilis publik jangka panjang, sebaiknya gunakan keystore pribadimu.

## Bahasa dan terjemahan

Terjemahan asli dikelola proyek Zalith Launcher 2 lewat [Weblate](https://hosted.weblate.org/projects/zalithlauncher2). Teks tambahan khusus Maou Launcher ditulis dalam Bahasa Indonesia dan Inggris.

## Peringatan tentang situs palsu

Proyek Zalith Launcher resmi menyatakan ada pihak ketiga yang membuat situs mirip resmi dengan memasang iklan. Maou Launcher **hanya** didistribusikan lewat repositori GitHub ini. Waspadai situs lain yang mengaku sebagai Maou Launcher atau Zalith Launcher.

## Lisensi

Kode proyek ini mengikuti lisensi **[GPL-3.0](LICENSE)**, sama seperti proyek asalnya.

### Syarat tambahan dari proyek asal (GPLv3 pasal 7)

1. Versi modifikasi harus mengubah nama atau nomor versi program agar berbeda dari versi asli. Nama versi modifikasi **tidak boleh memuat nama "ZalithLauncher" atau singkatan "ZL"**, dan tidak boleh memakai nama yang mirip sehingga membingungkan. Versi modifikasi **harus menandai dirinya sebagai "versi modifikasi tidak resmi"** secara jelas di halaman awal atau layar utama. Nama aplikasi diubah di [gradle.properties](./ZalithLauncher/gradle.properties).
2. Pemberitahuan hak cipta yang tampil di program tidak boleh dihapus.

Maou Launcher berbasis **Zalith Launcher 2, Copyright © MovTery dan kontributor**. Header lisensi di berkas sumber dipertahankan.

## Pustaka sumber terbuka yang digunakan

Perangkat lunak ini memakai pustaka berikut:

| Library | Copyright | License | Official Link |
|---------|-----------|---------|---------------|
| androidx-appcompat                    | Copyright © The Android Open Source Project                                                                   | Apache 2.0           | [Tautan](https://developer.android.com/jetpack/androidx/releases/appcompat)         |
| androidx-constraintlayout-compose     | Copyright © The Android Open Source Project                                                                   | Apache 2.0           | [Tautan](https://developer.android.com/develop/ui/compose/layouts/constraintlayout) |
| androidx-webkit                       | Copyright © The Android Open Source Project                                                                   | Apache 2.0           | [Tautan](https://developer.android.com/jetpack/androidx/releases/webkit)            |
| ANGLE                                 | Copyright 2018 The ANGLE Project Authors                                                                      | BSD 3-Clause License | [Tautan](http://angleproject.org/)                                                  |
| Apache Commons Codec                  | -                                                                                                             | Apache 2.0           | [Tautan](https://commons.apache.org/proper/commons-codec)                           |
| Apache Commons Compress               | -                                                                                                             | Apache 2.0           | [Tautan](https://commons.apache.org/proper/commons-compress)                        |
| Apache Commons IO                     | -                                                                                                             | Apache 2.0           | [Tautan](https://commons.apache.org/proper/commons-io)                              |
| ByteHook                              | Copyright © 2020-2024 ByteDance, Inc.                                                                         | MIT License          | [Tautan](https://github.com/bytedance/bhook)                                        |
| BuildKeys                             | Copyright © 2026 MovTery                                                                                      | Aoache 2.0           | [Tautan](https://github.com/MovTery/BuildKeys)                                      |
| Coil Compose                          | Copyright © 2025 Coil Contributors                                                                            | Apache 2.0           | [Tautan](https://github.com/coil-kt/coil)                                           |
| Coil Gifs                             | Copyright © 2025 Coil Contributors                                                                            | Apache 2.0           | [Tautan](https://github.com/coil-kt/coil)                                           |
| Coil SVG                              | Copyright © 2025 Coil Contributors                                                                            | Apache 2.0           | [Tautan](https://github.com/coil-kt/coil)                                           |
| Fishnet                               | Copyright © 2025 Kyant                                                                                        | Apache 2.0           | [Tautan](https://github.com/Kyant0/Fishnet)                                         |
| gl4es_extra_extra                     | Copyright © 2016-2018 Sebastien Chevalier; Copyright (c) 2013-2016 Ryan Hileman                               | MIT License          | [Tautan](https://github.com/PojavLauncherTeam/gl4es_extra_extra)                    |
| Gson                                  | Copyright © 2008 Google Inc.                                                                                  | Apache 2.0           | [Tautan](https://github.com/google/gson)                                            |
| kotlinx.coroutines                    | Copyright © 2000-2020 JetBrains s.r.o.                                                                        | Apache 2.0           | [Tautan](https://github.com/Kotlin/kotlinx.coroutines)                              |
| ktor-client-content-negotiation       | Copyright © 2000-2023 JetBrains s.r.o.                                                                        | Apache 2.0           | [Tautan](https://ktor.io)                                                           |
| ktor-client-core                      | Copyright © 2000-2023 JetBrains s.r.o.                                                                        | Apache 2.0           | [Tautan](https://ktor.io)                                                           |
| ktor-client-okhttp                    | Copyright © 2000-2023 JetBrains s.r.o.                                                                        | Apache 2.0           | [Tautan](https://ktor.io)                                                           |
| ktor-http                             | Copyright © 2000-2023 JetBrains s.r.o.                                                                        | Apache 2.0           | [Tautan](https://ktor.io)                                                           |
| ktor-serialization-kotlinx-json       | Copyright © 2000-2023 JetBrains s.r.o.                                                                        | Apache 2.0           | [Tautan](https://ktor.io)                                                           |
| LWJGL - Lightweight Java Game Library | Copyright © 2012-present Lightweight Java Game Library All rights reserved.                                   | BSD 3-Clause License | [Tautan](https://github.com/LWJGL/lwjgl3)                                           |
| material-color-utilities              | Copyright 2021 Google LLC                                                                                     | Apache 2.0           | [Tautan](https://github.com/material-foundation/material-color-utilities)           |
| Maven Artifact                        | Copyright © The Apache Software Foundation                                                                    | Apache 2.0           | [Tautan](https://github.com/apache/maven/tree/maven-3.9.9/maven-artifact)           |
| Media3                                | Copyright © The Android Open Source Project                                                                   | Apache 2.0           | [Tautan](https://developer.android.com/jetpack/androidx/releases/media3)            |
| Mesa                                  | Copyright © The Mesa Authors                                                                                  | MIT License          | [Tautan](https://mesa3d.org/)                                                       |
| MobileGlues                           | Copyright © 2025-2026 MobileGL-Dev                                                                           | LGPL-2.1 License     | [Tautan](https://github.com/MobileGL-Dev/MobileGlues)                         |
| MMKV                                  | Copyright © 2018 THL A29 Limited, a Tencent company.                                                          | BSD 3-Clause License | [Tautan](https://github.com/Tencent/MMKV)                                           |
| Navigation 3                          | Copyright © The Android Open Source Project                                                                   | Apache 2.0           | [Tautan](https://developer.android.com/jetpack/androidx/releases/navigation3)       |
| NG-GL4ES                              | Copyright © 2016-2018 Sebastien Chevalier; Copyright © 2013-2016 Ryan Hileman; Copyright (c) 2025-2026 BZLZHH | MIT License          | [Tautan](https://github.com/BZLZHH/NG-GL4ES)                                        |
| OkHttp                                | Copyright © 2019 Square, Inc.                                                                                 | Apache 2.0           | [Tautan](https://github.com/square/okhttp)                                          |
| Okio                                  | Copyright © 2013 Square, Inc.                                                                                 | Apache 2.0           | [Tautan](https://square.github.io/okio/)                                            |
| OpenNBT                               | Copyright © 2013-2021 Steveice10.                                                                             | MIT License          | [Tautan](https://github.com/GeyserMC/OpenNBT)                                       |
| Process Phoenix                       | Copyright © 2015 Jake Wharton                                                                                 | Apache 2.0           | [Tautan](https://github.com/JakeWharton/ProcessPhoenix)                             |
| proxy-client-android                  | -                                                                                                             | LGPL-3.0 License     | [Tautan](https://github.com/TouchController/TouchController)                        |
| Reorderable                           | Copyright © 2023 Calvin Liang                                                                                 | Apache 2.0           | [Tautan](https://github.com/Calvin-LL/Reorderable)                                  |
| sdl2-compat                           | Copyright (C) 2026 Sam Lantinga <slouken@libsdl.org>                                                          | Zlib License         | [Tautan](https://github.com/libsdl-org/sdl2-compat)                                 |
| SDL3                                  | Copyright (C) 1997-2026 Sam Lantinga <slouken@libsdl.org>                                                     | Zlib License         | [Tautan](https://github.com/libsdl-org/SDL)                                         |
| SimpleFPEWrapper                      | Copyright © 2026 MobileGL-Dev                                                                                | LGPL-3.0 License     | [Tautan](https://github.com/MobileGL-Dev/SimpleFPEWrapper)                    |
| sora-editor                           | Copyright (C) 2020-2026  Rosemoe                                                                              | LGPL-2.1 License     | [Tautan](https://github.com/Rosemoe/sora-editor)                                    |
| StringFog                             | Copyright © 2016-2023, Megatron King                                                                          | Apache 2.0           | [Tautan](https://github.com/MegatronKing/StringFog)                                 |
| tm4e (TextMate for Eclipse)           | Copyright © Eclipse Foundation                                                                                | EPL-2.0 License      | [Tautan](https://github.com/eclipse-tm4e/tm4e)                                      |
| XZ for Java                           | Copyright © The XZ for Java authors and contributors                                                          | 0BSD License         | [Tautan](https://tukaani.org/xz/java.html)                                          |
