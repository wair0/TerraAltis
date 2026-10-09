#!/usr/bin/env python3
"""Genera mipmaps e ic_launcher_foreground desde icon_base64.txt"""
import base64, io, os, sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    os.system(f"{sys.executable} -m pip install -q pillow")
    from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
b64_path = ROOT / "app" / "src" / "main" / "res" / "icon_base64.txt"
if not b64_path.exists():
    print("No icon_base64.txt, skipping")
    sys.exit(0)

data = base64.b64decode(b64_path.read_text().strip())
src = Image.open(io.BytesIO(data)).convert("RGBA")
res = ROOT / "app" / "src" / "main" / "res"

legacy = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
fg = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

for folder, size in legacy.items():
    d = res / folder
    d.mkdir(parents=True, exist_ok=True)
    img = src.resize((size, size), Image.LANCZOS)
    img.save(d / "ic_launcher.png")
    img.save(d / "ic_launcher_round.png")

for folder, size in fg.items():
    d = res / folder
    d.mkdir(parents=True, exist_ok=True)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    content = int(size * 0.85)
    icon = src.resize((content, content), Image.LANCZOS)
    offset = (size - content) // 2
    canvas.paste(icon, (offset, offset), icon)
    canvas.save(d / "ic_launcher_foreground.png")

drawable = res / "drawable"
drawable.mkdir(parents=True, exist_ok=True)
src.resize((432, 432), Image.LANCZOS).save(drawable / "ic_launcher_foreground.png")
print("Icons generated OK")
