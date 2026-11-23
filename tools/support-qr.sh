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
import subprocess
import sys

raw = Path("res/raw")
images = Path("res/drawable-nodpi")
rows = (line.strip() for line in (raw / "support.txt").read_text(encoding="utf-8").splitlines())
payloads = {}
for index, row in enumerate(line for line in rows if line and not line.startswith("#")):
    fields = row.split("|")
    if len(fields) < 3 or not fields[2].strip():
        sys.exit(f"Support row {index + 1} needs an address in its third field.")
    payloads[images / f"qr_{index}.png"] = fields[2].strip()
about = (line.strip() for line in (raw / "about.txt").read_text(encoding="utf-8").splitlines())
source = next((line[len("source="):] for line in about if line.startswith("source=")), "")
if not source:
    sys.exit("Set source= in res/raw/about.txt to the URL shown on the About screen.")
payloads[images / "qr_source.png"] = source

for image, payload in payloads.items():
    if sys.argv[1] == "--check":
        decoded = subprocess.run(["zbarimg", "--quiet", "--raw", str(image)], capture_output=True)
        if decoded.returncode or decoded.stdout != payload.encode("utf-8") + b"\n":
            sys.exit(f"{image} does not match its displayed value. Run tools/support-qr.sh to redraw it.")
    else:
        subprocess.run(["qrencode", "-t", "PNG", "-s", "12", "-m", "2", "-l", "M",
                        "-o", str(image), payload], check=True)
for stale in sorted(set(images.glob("qr_*.png")) - payloads.keys()):
    if sys.argv[1] == "--check":
        sys.exit(f"{stale} has no matching row. Run tools/support-qr.sh to remove it.")
    stale.unlink()
PY
