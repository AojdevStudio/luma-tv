#!/bin/bash
set -euo pipefail
source "$LUMA_OBSERVER"
source "$LUMA_FIXTURE/android.env"
original_args="$*"
observe_event adb_before 0 "$original_args" -
finish() {
    printf 'home=%s\ninstalled=%s\nstock_enabled=%s\n' "$home" "$installed" "$stock_enabled" \
        > "$LUMA_FIXTURE/android.next"
    mv "$LUMA_FIXTURE/android.next" "$LUMA_FIXTURE/android.env"
    observe_event adb_after 0 "$original_args outcome=${outcome:-deterministic}" "$1"
    exit "$1"
}
choose() {
    local operation="$1" options="$2" index=0 row op selected
    [[ ! -f "$LUMA_FIXTURE/cursor" ]] || read -r index < "$LUMA_FIXTURE/cursor"
    printf '%s\n' "$((index + 1))" > "$LUMA_FIXTURE/cursor"
    local n=0
    outcome=""
    while IFS=$'\t' read -r op selected; do
        if [[ "$n" = "$index" ]]; then
            [[ "$op" = "$operation" ]] || { printf 'choice operation mismatch\n' > "$LUMA_FIXTURE/error"; exit 98; }
            outcome="$selected"
            break
        fi
        n=$((n + 1))
    done < "$LUMA_FIXTURE/plan.tsv"
    [[ -n "$outcome" ]] || outcome="${options%% *}"
    case " $options " in *" $outcome "*) ;; *) printf 'choice outside domain\n' > "$LUMA_FIXTURE/error"; exit 98 ;; esac
    printf '%s\t%s\t%s\n' "$operation" "$outcome" "$options" >> "$LUMA_FIXTURE/choices.tsv"
}
[[ "${1:-}" = -s && "${2:-}" = OFFLINE ]] || { printf 'unexpected device or transport\n' > "$LUMA_FIXTURE/error"; exit 98; }
shift 2
if [[ "${1:-}" = install ]]; then
    choose install 'preserve stock chooser luma empty fail'
    case "$outcome" in
        fail) finish 1 ;;
        stock) [[ "$stock_enabled" = 0 ]] && home=C || home=S ;;
        chooser) home=C ;;
        luma) home=L ;;
        empty) home=E ;;
    esac
    installed=1
    printf 'Success\n'
    finish 0
fi
[[ "${1:-}" = shell ]] || { printf 'unexpected adb command\n' > "$LUMA_FIXTURE/error"; exit 98; }
shift
case "$*" in
    'getprop ro.serialno') printf 'OFFLINE\n' ;;
    'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
        case "$home" in
            S) printf 'com.google.android.tvlauncher/.Home\n' ;;
            L) printf 'dev.luma.tv/.MainActivity\n' ;;
            C) printf 'android/.ResolverActivity\n' ;;
            E) : ;;
            *) printf 'unknown home\n' > "$LUMA_FIXTURE/error"; exit 98 ;;
        esac ;;
    'pm list packages dev.luma.tv') [[ "$installed" = 0 ]] || printf 'package:dev.luma.tv\n' ;;
    'pm list packages com.google.android.tvlauncher') printf 'package:com.google.android.tvlauncher\n' ;;
    'pm list packages -d com.google.android.tvlauncher') [[ "$stock_enabled" = 1 ]] || printf 'package:com.google.android.tvlauncher\n' ;;
    'pm list packages com.google.android.apps.tv.launcherx'|'pm list packages com.google.android.leanbacklauncher'|'pm list packages com.google.android.tungsten.setupwraith') : ;;
    'pm enable --user 0 com.google.android.tvlauncher')
        choose enable 'accept fail'
        [[ "$outcome" != fail ]] || finish 1
        stock_enabled=1 ;;
    'pm disable-user --user 0 com.google.android.tvlauncher')
        choose disable 'accept fail'
        [[ "$outcome" != fail ]] || finish 1
        stock_enabled=0
        [[ "$home" != S ]] || home=C ;;
    'cmd package set-home-activity --user 0 '*)
        target="${!#}"
        case "$target" in
            dev.luma.tv/.MainActivity) target=L ;;
            com.google.android.tvlauncher/.Home) target=S ;;
            android/.ResolverActivity) target=C ;;
            *) printf 'unknown target\n' > "$LUMA_FIXTURE/error"; exit 98 ;;
        esac
        choose "set_home_$target" 'accept keep fail'
        [[ "$outcome" != fail ]] || finish 1
        if [[ "$outcome" = accept ]]; then
            if [[ "$target" = S && "$stock_enabled" = 0 ]] || [[ "$target" = L && "$installed" = 0 ]]; then
                outcome=unavailable
                finish 1
            fi
            home="$target"
        fi ;;
    'dumpsys power') printf 'mWakefulness=Asleep\n' ;;
    *) printf 'unexpected shell command: %s\n' "$*" > "$LUMA_FIXTURE/error"; exit 98 ;;
esac
finish 0
