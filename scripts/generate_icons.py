#!/usr/bin/env python3
"""Genera iconos launcher Android desde el PNG decodificado en mipmap-mdpi."""
import base64
import io
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Pillow no está instalado; instala con: python -m pip install pillow", file=sys.stderr)
    raise

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
candidates = [
    RES / "mipmap-mdpi" / "ic_launcher.png",
    RES / "icon_base64.txt",
]
src_path = next((p for p in candidates if p.is_file() and p.stat().st_size > 100), None)
if src_path is None:
    raise SystemExit("No se encontró un icono fuente válido (mipmap-mdpi/ic_launcher.png).")

if src_path.suffix == ".txt":
    src = Image.open(io.BytesIO(base64.b64decode(src_path.read_text().strip()))).convert("RGBA")
else:
    src = Image.open(src_path).convert("RGBA")

legacy = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
          "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
foreground = {"mipmap-mdpi": 108, "mipmap-hdpi": 162, "mipmap-xhdpi": 216,
              "mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432}

for folder, size in legacy.items():
    dest = RES / folder
    dest.mkdir(parents=True, exist_ok=True)
    icon = src.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(dest / "ic_launcher.png")
    icon.save(dest / "ic_launcher_round.png")

for folder, size in foreground.items():
    dest = RES / folder
    dest.mkdir(parents=True, exist_ok=True)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    content = int(size * 0.72)
    icon = src.resize((content, content), Image.Resampling.LANCZOS)
    offset = (size - content) // 2
    canvas.alpha_composite(icon, (offset, offset))
    canvas.save(dest / "ic_launcher_foreground.png")

drawable = RES / "drawable"
drawable.mkdir(parents=True, exist_ok=True)
src.resize((432, 432), Image.Resampling.LANCZOS).save(drawable / "ic_launcher_foreground.png")
print("Launcher icons generated successfully.")
