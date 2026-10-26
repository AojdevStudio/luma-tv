# Loaded only in a private fixture. All observation uses Bash builtins.
observe_event() {
    local phase="$1" line="$2" detail="$3" result="$4" callers="${5:-}"
    local home="E" installed=0 stock_enabled=1 journal="-" row
    if [[ -f "$LUMA_FIXTURE/android.env" ]]; then
        source "$LUMA_FIXTURE/android.env"
    fi
    if [[ -f "$LUMA_FIXTURE/.local/home-OFFLINE.txt" ]]; then
        journal=""
        while IFS= read -r row || [[ -n "$row" ]]; do
            journal+="$row;"
        done < "$LUMA_FIXTURE/.local/home-OFFLINE.txt"
    fi
    detail="${detail//$'\n'/\\n}"
    detail="${detail//$'\t'/\\t}"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$phase" "$line" "$result" "$home" "$installed" "$stock_enabled" "$journal" "$callers" "$detail" \
        >> "$LUMA_FIXTURE/events.tsv"
}
if [[ "$0" = "$LUMA_FIXTURE/luma.sh" ]]; then
    set -T
    trap 'observe_event shell_exit "${LUMA_LAST_LINE:-0}" "$BASH_COMMAND" "$?"' EXIT
    trap 'if [[ "${BASH_SOURCE[0]:-}" = "$LUMA_FIXTURE/luma.sh" && "$LINENO" -gt 0 && "$BASH_SUBSHELL" -eq 0 ]]; then LUMA_LAST_LINE=$LINENO; observe_event shell_before "$LINENO" "$BASH_COMMAND" - "${BASH_LINENO[*]}"; fi' DEBUG
fi
