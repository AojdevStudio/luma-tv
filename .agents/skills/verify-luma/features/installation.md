# Source build and installation

Luma installs from this fork's source while keeping CleanYourTV available for rollback.

## Sub-features

- `build` compiles and signs a source APK with the existing key.
- `install` updates the TCL package and selects Luma as HOME when expressly authorized.

## How to get to it (user POV)

- Build Luma on the mini.
- Install it on the TCL through ADB. The TV's Apps page then lists Luma.

## Driving it with verify.py

Preconditions: the existing signing key is present. For installation, the current request authorizes a live install, CleanYourTV is enabled, and the saved journal names CleanYourTV.

- Build with `python3 .agents/skills/verify-luma/scripts/verify.py build --evidence "$HOME/Backups/luma-verification/build-$(date -u +%Y%m%dT%H%M%SZ)"`. Require source_build PASS and a verified APK signature.
- After explicit install authorization, run `python3 .agents/skills/verify-luma/scripts/verify.py install --allow-launcher-change --evidence "$HOME/Backups/luma-verification/install-$(date -u +%Y%m%dT%H%M%SZ)"`. Require the install exit result, unchanged journal, Luma HOME, and enabled CleanYourTV in the after snapshot.

## Gotchas

- The current skills task excludes live install. Report it as unrun.
- Android may reset the selected HOME when a launcher is installed. Keep the pre-install journal.
- A published APK can use a different signer. The helper never uninstalls an app to get past a signature mismatch.
- An Install or Allow prompt is a stop point. The helper does not press it.
