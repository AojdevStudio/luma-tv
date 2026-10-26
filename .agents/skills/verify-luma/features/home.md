# Launch and Home

The Home-key action brings the installed Luma launcher to the foreground without changing the selected launcher or permissions.

## Sub-features

- `launch` opens the installed Luma activity.
- `home` sends the Android Home-key event.
- `baseline` checks that HOME, disabled packages, CleanYourTV, and the journal stay intact.

## How to get to it (user POV)

- Open Luma from the TV's Apps page.
- Press Home on the remote when Luma is the selected launcher.

## Driving it with verify.py

Preconditions: the TV is awake, Luma is installed and already HOME, and no system prompt has focus.

- Run `python3 .agents/skills/verify-luma/scripts/verify.py safe --evidence "$HOME/Backups/luma-verification/home-$(date -u +%Y%m%dT%H%M%SZ)"`.
- Require `launch_home_and_baseline` PASS. HOME, focused window, and resumed activity must independently identify Luma.
- Inspect both PNG files. Confirm the app is usable and no modal prompt covers it.
- Compare the before and after snapshots and journal evidence. Source and baseline checks must pass after cleanup.

## Gotchas

- An injected Home event does not test the physical remote's hardware.
- A screenshot alone does not prove HOME resolution or baseline preservation.
- A sleeping TV stops the run. Do not wake it to make the check pass.
- Do not select the Up Next Allow access tile.
