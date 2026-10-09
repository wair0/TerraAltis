#!/usr/bin/env bash
# Decodifica recursos .png.b64 y elimina los archivos auxiliares para que
# Android AAPT2 no intente procesarlos como recursos de la app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="$ROOT/app/src/main/res"

while IFS= read -r -d '' f; do
  out="${f%.b64}"
  echo "Decoding ${f#"$ROOT"/} -> ${out#"$ROOT"/}"
  base64 -d "$f" > "$out"
  # Verifica que el resultado tenga contenido antes de borrar la fuente auxiliar.
  test -s "$out"
  rm "$f"
done < <(find "$RES" -type f -name '*.png.b64' -print0)

echo "Icon base64 resources decoded."
