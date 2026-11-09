#!/usr/bin/env bash
# Tambahan hemat RAM untuk HP < 3GB (mis. Oppo A3s RAM 2GB)
# Jalankan SETELAH: git apply mod-zalith2.patch   (root repo): bash maou-ram.sh
# Hanya menyentuh blok "low-RAM" dari mod-zalith2.patch. Idempotent (aman dijalankan 2x).
set -e
F=ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/Launcher.kt
[ -f "$F" ] || { echo "Jalankan dari root repo"; exit 1; }
grep -q "Zalith Mod" "$F" || { echo "[!] mod-zalith2.patch belum di-apply. Apply dulu."; exit 1; }
grep -q "Maou RAM" "$F" && { echo "[skip] sudah pernah dijalankan"; exit 0; }

python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
ok = True

def sub(old, new, label):
    global s, ok
    if old in s:
        s = s.replace(old, new, 1)
        print("[ok]", label)
    else:
        ok = False
        print("[!] tidak ditemukan:", label)

# 1) Heap awal kecil di HP < 3GB: RAM tidak langsung dikomit semua, heap tumbuh sesuai kebutuhan
sub('args.add("-Xms${ramAllocationString}M")',
    'args.add("-Xms${if (deviceMb in 1..3071) minOf(cappedRam, 384) else cappedRam}M") // Maou RAM',
    "Xms kecil")

# 2) Young gen disesuaikan agar tidak melebihi heap awal
sub('args.add("-Xmn${minOf(256, maxOf(96, cappedRam / 3))}M")',
    'args.add("-Xmn${minOf(160, maxOf(96, cappedRam / 4))}M")',
    "Xmn lebih kecil")

# 3) Tambahan di blok low-RAM
sub('args.add("-XX:CICompilerCount=2")',
    'args.add("-XX:CICompilerCount=2")\n'
    '            // Maou RAM: JIT C1 saja (hemat RAM + CPU, kurangi stutter dari kompilasi C2), heap menyusut cepat\n'
    '            args.add("-XX:TieredStopAtLevel=1")\n'
    '            args.add("-XX:MinHeapFreeRatio=10")\n'
    '            args.add("-XX:MaxHeapFreeRatio=30")\n'
    '            args.add("-XX:ReservedCodeCacheSize=48m")\n'
    '            args.add("-XX:+UseCompressedOops")',
    "flag JIT/heap")

open(p, "w", encoding="utf-8").write(s)
sys.exit(0 if ok else 2)
PY
echo "Selesai. git diff $F untuk lihat hasilnya."
