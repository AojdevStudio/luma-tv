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

The offline action compiles every `tools/*Test.java` suite against `src`, runs each `dev.luma.tv` test class, then runs `tools/test_luma.py`. On Linux, put JDK 17 on `PATH`. Missing baseline Java suites or the launcher suite produce PARTIAL and a nonzero exit code. The evidence records the JVM version. The discovery regression compiles and runs real Java in a temporary checkout. Device tests in the helper suite use fake ADB and contact no device. The server ownership tests start real ADB servers with scratch keys, private sockets, and device discovery off. They never contact a device or the default ADB server. Run them in a private network namespace when you can (`unshare -Urn` with loopback up). They use `LUMA_TEST_ADB`, else the helper's SDK `adb`, else `adb` on `PATH`, and skip when none exists. CI sets `LUMA_TEST_ADB`, so they cannot skip there.

## Evidence

Use a fresh directory outside the repository. The helper writes private command transcripts, exit results, before and after device snapshots, screenshots, a journal copy, and `summary.json` there. It prints only the result and evidence path. Never paste raw device dumps or key files into a PR.

PASS covers the selected action only. PARTIAL identifies skipped checks. FAIL identifies a failed check and retains evidence. Exit 0 means PASS, exit 1 means PARTIAL or FAIL, and exit 2 means invalid invocation. A skipped install or restore is not verified through the Home-key check.

## Cleanup

Each live run starts its own ADB server as a child process. The server listens only on a Unix socket in a new private directory under `~/.luma-verify/`, so no other process can reach it. Its `HOME` is a private directory in the same place that holds only a copy of the trusted key, mode 0600 in 0700 directories. ADB 37 reads keys from `$HOME/.android` and ignores `ANDROID_USER_HOME`, so this keeps the operator's own keys out, and ADB never opens a trusted path. If either trusted key file is missing or cannot be read, the run stops before any ADB command and creates no key. The server has USB, emulator, and mDNS discovery turned off. Its stderr (`adb-server.stderr`) and log go to the evidence directory. If it exits before it is ready, the error names the exit signal or status and the server's last error line, with the home directory shown as `~`.

Cleanup (`adb-stop`) sends SIGTERM to that child. It does not send the child `kill-server`, because ADB 37.0.1 sometimes hangs after that request when it has just started (2 of 40 tries). Then `adb-sweep` renames the directory, so no `adb` client can start another server on that socket. If a server still listens there, such as one that an `adb` command restarted, the sweep connects to the socket and sends that server `host:kill` on the connection. The server holds its end until it exits, so the closed connection proves that it stopped, with no process ID involved. If the server answers but keeps the connection open, it has hung, and the sweep kills it with SIGKILL. On Linux it signals a pidfd that it opened before the request. Elsewhere it signals the process ID only while the connection is still open. The sweep then waits until the server has exited and removes the directory. It never matches process names or arguments. If a server cannot be stopped, the directory stays so the next run tries again, and the run reports a `cleanup_error`.

Each command runs in its own process group. On a timeout, Ctrl-C, or SIGTERM, the helper kills that group and waits for it to empty before cleanup goes on. The first Ctrl-C or SIGTERM stops the run. While the helper starts a child or stops one, and for all of cleanup, a signal waits or is ignored, so a second signal cannot cut a stop short. An interrupted run reports FAIL. Cleanup stops everything before it writes the log, so a failed write to the evidence directory cannot leave a server running. Cleanup then reports the failed write. SIGKILL cannot be caught. A command that was running then keeps running, but the next run stops the dead run's server, so that command can no longer reach the TCL.

One run drives the TCL at a time. A live run holds a lock on `~/.luma-verify/tcl.lock` until cleanup has stopped its server and commands. A second run fails with `Another verifier is driving the TCL` and leaves the first one running. The system releases the lock when a run exits or is killed. The next run then stops the killed run's server the same way (`adb-reap`) before it starts its own server.

Older checkouts of this helper used a shared server on port 5042 and do not take the lock. A live run therefore also binds port 5042 on `127.0.0.1` and `::1` without listening, and without sharing the port. While it does, older helpers cannot bind the port, and a client that connects is refused instead of reaching a socket that never answers. If an older helper or another program holds the port, the run fails with `Port 5042 is in use` and leaves that program running. This includes a wildcard listener and a program that has bound the port but not yet listened. A connection that closed in the last minute (TIME_WAIT) also blocks the bind, because the helper cannot tell it apart from such a program. A bind that shares the port (`SO_REUSEADDR`) would pass TIME_WAIT, but it would also pass a program that has bound the port with `SO_REUSEADDR` and not yet listened, as ADB does. Runs of this helper never listen on port 5042, so back-to-back runs leave no TIME_WAIT. Only a connection to an older helper does. After an older helper stops, wait a minute and retry. If IPv6 is missing, the helper binds `127.0.0.1` only. Remove this hold when no older checkout can drive the TCL.

Cleanup preserves the evidence, installed app, existing keys, and TV state. Confirm that `commands.jsonl` records `adb-stop` for the server that `adb-start` started, and that the screenshots and summary remain after cleanup.

## Helpers

`scripts/verify.py` implements every command above. Use `--help` for the action list. Keep the feature map current with `/t3-pstack:maintain-verification-skill` after app changes.
