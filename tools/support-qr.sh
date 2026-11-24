#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in
    "") command -v qrencode >/dev/null || { echo "Install qrencode to redraw the QR codes." >&2; exit 1; } ;;
    --check) command -v zbarimg >/dev/null || { echo "Install zbar-tools to check the QR codes." >&2; exit 1; } ;;
    *) echo "Usage: tools/support-qr.sh [--check]" >&2; exit 2 ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/payloads"
javac -encoding UTF-8 -d "$work" src/dev/luma/tv/SupportInfo.java tools/SupportQrPayloads.java
java -cp "$work" dev.luma.tv.SupportQrPayloads "$work/payloads"

images=res/drawable-nodpi
for payload in "$work"/payloads/*; do
    image="$images/${payload##*/}.png"
    if [[ ${1:-} == --check ]]; then
        zbarimg --quiet --raw "$image" > "$work/decoded" || {
            echo "Cannot decode $image. Run tools/support-qr.sh to redraw it." >&2
            exit 1
        }
        { cat "$payload"; echo; } > "$work/expected"
        if ! cmp -s "$work/expected" "$work/decoded"; then
            echo "$image differs from the displayed value. Run tools/support-qr.sh to redraw it." >&2
            diff -u -L expected -L decoded "$work/expected" "$work/decoded" >&2 || true
            exit 1
        fi
    else
        qrencode -t PNG -s 12 -m 2 -l M -o "$image" -r "$payload"
    fi
done
shopt -s nullglob
for image in "$images"/qr_*.png; do
    name=${image##*/}
    [[ -f "$work/payloads/${name%.png}" ]] && continue
    if [[ ${1:-} == --check ]]; then
        echo "$image has no matching row. Run tools/support-qr.sh to remove it." >&2
        exit 1
    fi
    rm "$image"
done
