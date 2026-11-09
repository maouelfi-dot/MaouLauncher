#!/usr/bin/env bash
# Maou Launcher setup untuk fork ZalithLauncher2
# Jalankan di root repo (Codespace): bash maou-setup.sh
# Urutan: 1) git apply mod-zalith2.patch  2) bash maou-setup.sh
set -e
M=ZalithLauncher/src/main
[ -d "$M" ] || { echo "Jalankan dari root repo ZalithLauncher2"; exit 1; }

# 1) Nama (tanpa "ZalithLauncher"/"ZL" sesuai klausul lisensi)
sed -i 's/^launcher_name=.*/launcher_name=MaouLauncher/;
s/^launcher_app_name=.*/launcher_app_name=Maou Launcher/;
s/^launcher_short_name=.*/launcher_short_name=Maou/' ZalithLauncher/gradle.properties
echo "[ok] nama diganti"

# 2) Hemat storage: matikan arsip AppCDS dari mod-zalith2.patch (jika sudah di-apply)
L=$M/java/com/movtery/zalithlauncher/game/launch/Launcher.kt
if grep -q "runtime.javaVersion >= 21 && PathManager" "$L"; then
  sed -i 's/if (runtime.javaVersion >= 21 \&\& PathManager/if (false \&\& PathManager/' "$L"
  echo "[ok] AppCDS dimatikan"
else
  echo "[skip] AppCDS: baris tidak ditemukan (patch belum di-apply?)"
fi

# 3) Cadangan RAM lebih besar untuk HP < 3GB (mis. Oppo A3s)
if grep -q "if (deviceMb < 3072) 700 else 900" "$L"; then
  sed -i 's/if (deviceMb < 3072) 700 else 900/if (deviceMb < 3072) 1000 else 900/' "$L"
  echo "[ok] cadangan RAM 1000MB"
fi

# 4) Logo: latar ungu + huruf M putih (adaptive icon)
mkdir -p $M/res/drawable $M/res/mipmap-anydpi-v26
V='xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"'
echo "<vector $V><path android:fillColor=\"#6D28D9\" android:pathData=\"M0,0h108v108h-108z\"/></vector>" > $M/res/drawable/maou_bg.xml
echo "<vector $V><path android:fillColor=\"#FFFFFF\" android:pathData=\"M30,72L30,38L42,38L54,56L66,38L78,38L78,72L68,72L68,53L54,73L40,53L40,72Z\"/></vector>" > $M/res/drawable/maou_fg.xml
found=0
for n in $(grep -oE 'android:(icon|roundIcon)="@mipmap/[A-Za-z_0-9]+"' $M/AndroidManifest.xml | sed 's/.*@mipmap\///;s/"//' | sort -u); do
  echo '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/maou_bg"/><foreground android:drawable="@drawable/maou_fg"/></adaptive-icon>' > $M/res/mipmap-anydpi-v26/$n.xml
  echo "[ok] ikon diganti: $n"
  found=1
done
[ $found = 1 ] || echo "[!] Ikon manifest bukan @mipmap. Kirim: grep icon $M/AndroidManifest.xml"

git status --short
echo "Selesai. Build lewat tab Actions di GitHub."
