#!/usr/bin/env bash
# Decodifica iconos .png.b64 -> .png antes del build
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
find "$ROOT/app/src/main/res" -name '*.png.b64' 2>/dev/null | while read -r f; do
  out="${f%.b64}"
  echo "Decoding $out"
  base64 -d "$f" > "$out"
done
echo "Icons decoded."
