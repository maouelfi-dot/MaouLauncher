#!/usr/bin/env bash
# Tema ungu-putih Maou Launcher (best-effort, tanpa menebak nama file)
# Cara pakai (root repo): bash maou-tema.sh
# Mencari semua lightColorScheme(...) / darkColorScheme(...) di kode .kt lalu
# mengganti argumen warna yang ada (primary = Color(0x........), dst).
# Kalau ZL2 membuat skema dari warna pilihan (material-color-utilities), tidak akan ada
# yang cocok: skrip melapor, dan kamu cukup memilih ungu di pengaturan tampilan aplikasi.
set -e
[ -d ZalithLauncher/src/main ] || { echo "Jalankan dari root repo"; exit 1; }

python3 - <<'PY'
import re, pathlib

LIGHT = dict(primary="6D28D9", onPrimary="FFFFFF", primaryContainer="EDE9FE",
  onPrimaryContainer="2E1065", secondary="7C3AED", onSecondary="FFFFFF",
  secondaryContainer="F3E8FF", onSecondaryContainer="3B0764", tertiary="8B5CF6",
  onTertiary="FFFFFF", background="FFFBFF", surface="FFFBFF", onSurface="1C1B1F",
  surfaceVariant="EEE8F4", onSurfaceVariant="4A4458", surfaceTint="6D28D9")
DARK = dict(primary="C4B5FD", onPrimary="2E1065", primaryContainer="5B21B6",
  onPrimaryContainer="EDE9FE", secondary="D8B4FE", onSecondary="3B0764",
  secondaryContainer="4C1D95", onSecondaryContainer="F3E8FF", tertiary="DDD6FE",
  onTertiary="2E1065", background="141218", surface="141218", onSurface="E6E1E9",
  surfaceVariant="49454F", onSurfaceVariant="CAC4D0", surfaceTint="C4B5FD")

def block_end(s, i):
    d = 0
    for j in range(i, len(s)):
        if s[j] == '(':
            d += 1
        elif s[j] == ')':
            d -= 1
            if d == 0:
                return j
    return -1

changed_files, total = [], 0
for p in pathlib.Path("ZalithLauncher/src/main").rglob("*.kt"):
    s = p.read_text(encoding="utf-8", errors="ignore")
    if "ColorScheme(" not in s:
        continue
    out, pos, n = [], 0, 0
    for m in re.finditer(r'\b(light|dark)ColorScheme\s*\(', s):
        start = m.end() - 1
        end = block_end(s, start)
        if end < 0 or m.start() < pos:
            continue
        pal = LIGHT if m.group(1) == "light" else DARK
        body = s[start:end + 1]
        def rep(mm):
            global total
            name = mm.group(2)
            if name in pal:
                return f'{mm.group(1)}{name}{mm.group(3)}Color(0xFF{pal[name]})'
            return mm.group(0)
        new = re.sub(r'(^|[\s,(])(\w+)(\s*=\s*)Color\(0x[0-9A-Fa-f]{8}\)', rep, body, flags=re.M)
        if new != body:
            n += len(re.findall(r'Color\(0xFF', new)) 
        out.append(s[pos:start]); out.append(new); pos = end + 1
    out.append(s[pos:])
    ns = "".join(out)
    if ns != s:
        p.write_text(ns, encoding="utf-8")
        changed_files.append(str(p))

if changed_files:
    print("[ok] warna ungu diterapkan di:")
    for f in changed_files:
        print("   ", f)
else:
    print("[!] Tidak ada ColorScheme dengan warna literal. Kemungkinan tema dibuat dari warna pilihan.")
    print("    Solusi: Pengaturan > Tampilan di aplikasi, pilih warna ungu (#6D28D9).")
    print("    Atau kirim hasil: grep -rn 'ColorScheme' ZalithLauncher/src/main --include=*.kt | head -30")
PY

git diff --stat | tail -5
echo
echo "CATATAN LISENSI: tambahkan label 'Versi modifikasi tidak resmi' yang jelas di halaman utama."
echo "Kirim hasil: grep -rn 'launcher_app_name\|app_name' ZalithLauncher/src/main | head -20"
echo "supaya aku buatkan patch labelnya yang pas."
