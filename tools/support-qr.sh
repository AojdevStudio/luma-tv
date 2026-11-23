#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in
    "") command -v qrencode >/dev/null || { echo "Install qrencode to redraw the QR codes." >&2; exit 1; } ;;
    --check) command -v zbarimg >/dev/null || { echo "Install zbar-tools to check the QR codes." >&2; exit 1; } ;;
    *) echo "Usage: tools/support-qr.sh [--check]" >&2; exit 2 ;;
esac

python3 - "${1:-}" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

raw = Path("res/raw")
images = Path("res/drawable-nodpi")
def raw_rows(name):
    trim = "".join(map(chr, range(33)))
    for line in (raw / name).read_text(encoding="utf-8").split("\n"):
        line = line.strip(trim)
        if line and not line.startswith("#"):
            yield re.split(r"\s*\|\s*", line, flags=re.ASCII)

payloads = {}
for index, fields in enumerate(raw_rows("support.txt")):
    if len(fields) < 3 or not fields[2]:
        sys.exit(f"Support row {index + 1} needs an address in its third field.")
    payloads[images / f"qr_{index}.png"] = fields[2]
source = next((row[0][len("source="):] for row in raw_rows("about.txt") if row[0].startswith("source=")), "")
if not source:
    sys.exit("Set source= in res/raw/about.txt to the source URL for the About screen.")
payloads[images / "qr_source.png"] = source

for image, payload in payloads.items():
    if sys.argv[1] == "--check":
        decoded = subprocess.run(["zbarimg", "--quiet", "--raw", str(image)], capture_output=True)
        if decoded.returncode:
            sys.stderr.buffer.write(decoded.stderr)
            sys.exit(f"Cannot decode {image}. Run tools/support-qr.sh to redraw it.")
        expected = payload.encode("utf-8") + b"\n"
        if decoded.stdout != expected:
            sys.exit(f"{image}: expected {expected!r}, decoded {decoded.stdout!r}. Run tools/support-qr.sh to redraw it.")
    else:
        subprocess.run(["qrencode", "-t", "PNG", "-s", "12", "-m", "2", "-l", "M",
                        "-o", str(image), payload], check=True)
for stale in sorted(set(images.glob("qr_*.png")) - payloads.keys()):
    if sys.argv[1] == "--check":
        sys.exit(f"{stale} has no matching row. Run tools/support-qr.sh to remove it.")
    stale.unlink()
PY
