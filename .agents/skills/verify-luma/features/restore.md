# Return to the prior launcher

Restore selects the launcher recorded before Luma and keeps Luma installed for another trial.

## Sub-features

- `journal` identifies the saved original and packages changed by Luma.
- `restore` returns HOME to the recorded launcher when expressly authorized.
- `failure` keeps the journal when Android rejects the HOME change.

## How to get to it (user POV)

- Run `luma.sh restore` from the mini for the selected TCL.
- Luma Settings also has a return-home option, but it cannot re-enable a disabled stock launcher. It is not a substitute for the script journey.

## Driving it with verify.py

Preconditions: the current request expressly authorizes live restore. The journal names CleanYourTV and CleanYourTV remains enabled.

- Inspect the journal through the helper's snapshot. Preserve its copy in the evidence directory.
- With authorization, run `python3 .agents/skills/verify-luma/scripts/verify.py restore --allow-launcher-change --evidence "$HOME/Backups/luma-verification/restore-$(date -u +%Y%m%dT%H%M%SZ)"`.
- Require HOME to equal the journal's original after the command, with CleanYourTV enabled. Luma stays installed.
- If a command fails, require a nonzero result and retain the saved evidence and live journal. Do not retry blindly.

## Gotchas

- The current skills task excludes live restore. Only the offline regression and formal checker exercise it now.
- Restore is not an app-data or firmware backup.
- The script's no-journal fallback can re-enable stock launchers disabled before Luma. The live helper refuses that path.
- Leave existing backup `restore.sh` files unchanged.
