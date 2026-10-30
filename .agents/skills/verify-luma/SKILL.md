---
name: verify-luma
description: Verify Luma on the TCL test TV with source checks and direct screenshot proof of visible Home and Apps behavior.
---

# Verify Luma

Run from the repository root on the mini. Read [the feature map](features/README.md), then choose the entry that matches the change.

## Launch

The helper targets only `10.69.3.171:5555`, a TCL BeyondTV5 on Android 9. It uses the existing trusted key under `~/Backups/cleanyourtv/office-tcl-20261019/checkout/tools/adbkey/`. It refuses to create an ADB key or Luma signing key.

For a source build, offline tests, launch, and Home-key check against the current installation:

```sh
python3 .agents/skills/verify-luma/scripts/verify.py safe --evidence "$HOME/Backups/luma-verification/safe-$(date -u +%Y%m%dT%H%M%SZ)"
```

Read `summary.json` and inspect `screen-before.png` and `screen.png`. Ready means Luma is HOME, its window has focus, and its activity is resumed. A successful process start alone is insufficient.

Build requires the existing `.signing/local.keystore`, JDK 17, Android platform 34, build-tools 36.0.0, and Python 3. The helper uses the Homebrew SDK and JDK paths. It builds source, verifies the APK signature, and records the built certificate fingerprint. It does not compare the installed APK certificate with that fingerprint.

## When the user says "verify"

Treat `verify` as an end-to-end proof on the real TCL, not as a build-only check.

1. Run `doctor` when transport or device state is uncertain, then run `safe` with a fresh evidence directory.
2. Require `summary.json` to report `PASS`. A running process or successful APK install alone is not proof.
3. Inspect the fresh `screen-before.png` and `screen.png` files with an image viewer. Check the visible result against the requested behavior.
4. For Home or content changes, capture a full-resolution TCL screenshot after launching Luma and inspect the Home card, weather card, and Home app row directly.
5. Open the Apps tab with the TV's D-pad input, capture it, and inspect the visible app grid. Query the TV's installed packages when the claim concerns an app being present or absent. Resolve package labels from the actual TV before reporting them.
6. If the screenshot or package evidence disagrees with the requested result, report a failed verification, fix the source, rebuild, reinstall, and repeat the full proof loop. Do not report a partial result as verified.
7. Hand back the fresh evidence directory and the screenshots used for the conclusion. State any check that could not run.

The visual check is mandatory for every `verify` request. A green `safe` summary without inspected screenshots is incomplete.

## Doctor

When the instance or transport looks wrong, run the read-only doctor first:

```sh
python3 .agents/skills/verify-luma/scripts/verify.py doctor --evidence "$HOME/Backups/luma-verification/doctor-$(date -u +%Y%m%dT%H%M%SZ)"
```

Require the fixed address, expected model and Android version, authorized transport, and installed Luma version. Stop at an authorization, Install, or Allow prompt. Do not accept it. A sleeping TV is not permission to wake it.

## Drive

`safe` starts the installed activity and sends Android's `KEYCODE_HOME`. It checks HOME resolution, the focused window, and the resumed activity separately. It compares the disabled-package list, CleanYourTV enabled state, restore journal, source, and signing file before and after. It leaves HOME and permissions unchanged.

This tests the Android Home-key event. It does not prove the physical remote's hardware or its button mapping. Inspect the screenshots before claiming visual success. The optional Up Next Allow access tile is a control, not permission to select it.

The [installation](features/installation.md) and [restore](features/restore.md) recipes change launcher state. Run them only when the current user request expressly authorizes that step. Their command-line flag records that choice and grants no authority by itself. For this task, live installation and live restore are excluded.

## Offline checks

```sh
python3 .agents/skills/verify-luma/scripts/verify.py offline --evidence "$HOME/Backups/luma-verification/offline-$(date -u +%Y%m%dT%H%M%SZ)"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .agents/skills/verify-luma/scripts -p test_verify.py -v
```

The offline action runs DevicesTest, CapabilitiesTest, WeatherLocationTest, and `tools/test_luma.py`. If the launcher suite is absent, it reports PARTIAL rather than a pass. The helper tests use fake subprocesses and temporary files. They contact no device.

## Evidence

Use a fresh directory outside the repository. The helper writes private command transcripts, exit results, before and after device snapshots, screenshots, a journal copy, and `summary.json` there. It prints only the result and evidence path. Never paste raw device dumps or key files into a PR.

PASS covers the selected action only. PARTIAL identifies skipped checks. FAIL identifies a failed check and retains evidence. Exit 0 means PASS, exit 1 means PARTIAL or FAIL, and exit 2 means invalid invocation. A skipped install or restore is not verified through the Home-key check.

## Cleanup

The helper stops only the isolated ADB server it started on port 5042. It refuses an occupied port. Cleanup preserves the evidence, installed app, existing keys, and TV state. Confirm `adb-stop` succeeded in the transcript and that the screenshots and summary remain after cleanup.

## Helpers

`scripts/verify.py` implements every command above. Use `--help` for the action list. Keep the feature map current with `/t3-pstack:maintain-verification-skill` after app changes.
