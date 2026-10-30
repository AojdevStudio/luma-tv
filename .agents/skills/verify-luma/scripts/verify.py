#!/usr/bin/env python3
"""Verify Luma using one fixed TCL transport and private evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[4]
TARGET = "10.69.3.171:5555"
PORT = 5042
MODEL, ANDROID = "BeyondTV5", "9"
LUMA = "dev.luma.tv/.MainActivity"
CLEAN = "com.gathin.tvhome"
SDK = Path("/opt/homebrew/share/android-commandlinetools")
JDK = Path("/opt/homebrew/opt/openjdk@17")
KEYDIR = Path.home() / "Backups/cleanyourtv/office-tcl-20261019/checkout/tools/adbkey"
SETTLE = 2
SOURCE = ["AndroidManifest.xml", "build.sh", "luma.sh", "res", "src", "tools", ".local", ".signing/local.keystore"]
SNAPSHOT = {
    "home": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    "disabled": "pm list packages -d", "clean": "pm list packages -e " + CLEAN,
    "version": "dumpsys package dev.luma.tv", "focus": "dumpsys window windows",
    "activity": "dumpsys activity activities", "model": "getprop ro.product.model",
    "android": "getprop ro.build.version.release", "serial": "getprop ro.serialno",
    "power": "dumpsys power | grep mWakefulness= || true",
}
FOCUS = re.compile(r"mCurrentFocus=Window\{\S+ u\d+ ([^}]*)\}")
FOCUSED_APP = re.compile(r"mFocusedApp=.*?ActivityRecord\{\S+ u\d+ ([^\s}]+)")
RESUMED = re.compile(r"(?<![A-Za-z])(?:mResumedActivity|ResumedActivity|topResumedActivity)[:=]\s*ActivityRecord\{\S+ u\d+ ([^\s}]+)")
VERSION = {"versionName": r"versionName=(\S+)", "versionCode": r"versionCode=(\d+)", "lastUpdateTime": r"lastUpdateTime=(.+)"}
PROMPT = re.compile(r"permission|packageinstaller|Application Error|Not Responding|Dialog|Alert", re.I)


def adb_bin():
    return SDK / "platform-tools/adb"


def component(name):
    package, slash, cls = name.strip().partition("/")
    return package + slash + (package + cls if slash and cls.startswith(".") else cls)


def home(snap):
    lines = snap["home"].splitlines()
    return component(lines[-1]) if lines else ""


def only(pattern, text, what):
    found = {component(match) for match in pattern.findall(text)}
    if len(found) != 1:
        raise RuntimeError("Could not read exactly one " + what + ". Found: " + (", ".join(sorted(found)) or "none") + ".")
    return found.pop()


def refuse_prompt(snap):
    for title in FOCUS.findall(snap["focus"]):
        if PROMPT.search(title):
            raise RuntimeError("A system prompt has focus (" + title.strip() + "). Stop. Do not click it.")


def fingerprint():
    files = {}
    for name in SOURCE:
        path = ROOT / name
        for item in sorted([path] if path.is_file() else path.rglob("*") if path.is_dir() else []):
            if item.is_file():
                files[str(item.relative_to(ROOT))] = hashlib.sha256(item.read_bytes()).hexdigest()
    return files


class Run:
    def __init__(self, evidence):
        self.evidence = evidence
        evidence.mkdir(parents=True, mode=0o700)
        self.env = {"PATH": os.pathsep.join([str(SDK / "platform-tools"), str(JDK / "bin"), os.environ.get("PATH", "/usr/bin:/bin")]),
                    "JAVA_HOME": str(JDK), "LUMA_JDK": str(JDK),
                    "ANDROID_SDK_ROOT": str(SDK),
                    "LUMA_KEYSTORE": str(ROOT / ".signing/local.keystore")}
        self.started = False
        self.summary = {"target": TARGET, "checks": {}, "skipped": [], "observed": {}}

    def command(self, name, args, timeout=30, check=True):
        argv = [str(a) for a in args]
        entry = {"name": name, "argv": argv}
        try:
            result = subprocess.run(argv, cwd=ROOT, env=self.env, capture_output=True, timeout=timeout)
            out, err, entry["exit"] = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            out, err, entry["exit"] = error.stdout or b"", error.stderr or b"", "timeout"
        except OSError as error:
            out, err, entry["exit"] = b"", str(error).encode(), "not-run"
        (self.evidence / (name + ".stdout")).write_bytes(out)
        (self.evidence / (name + ".stderr")).write_bytes(err)
        with (self.evidence / "commands.jsonl").open("a") as log:
            log.write(json.dumps(entry) + "\n")
        if entry["exit"] == "timeout":
            raise RuntimeError(name + " timed out after " + str(timeout) + "s. See " + name + ".stdout and " + name + ".stderr.")
        if entry["exit"] == "not-run":
            raise RuntimeError(name + " could not start. See " + name + ".stderr.")
        if check and entry["exit"]:
            raise RuntimeError(name + " exited " + str(entry["exit"]) + ". See " + name + ".stderr.")
        return out

    def adb(self, name, *args, check=True):
        return self.command(name, [adb_bin(), "-P", str(PORT), "-s", TARGET, *args], check=check)

    def connect(self):
        for name in ["adbkey", "adbkey.pub"]:
            if not (KEYDIR / name).is_file():
                raise RuntimeError("Existing trusted ADB key missing. No key was created.")
        try:
            with socket.socket() as check:
                check.bind(("127.0.0.1", PORT))
        except OSError:
            raise RuntimeError("Port " + str(PORT) + " is busy. Another adb server may own it. It was left running.")
        self.env.update({"ANDROID_USER_HOME": str(KEYDIR), "ADB_VENDOR_KEYS": str(KEYDIR / "adbkey"),
                         "ADB_MDNS_AUTO_CONNECT": "0", "ADB_SERVER_SOCKET": "tcp:localhost:" + str(PORT)})
        self.command("adb-start", [adb_bin(), "-P", str(PORT), "start-server"])
        self.started = True
        output = self.command("adb-connect", [adb_bin(), "-P", str(PORT), "connect", TARGET], 12)
        if b"connected to " + TARGET.encode() not in output:
            raise RuntimeError("TCL did not accept the connection. Stop. Do not retry or accept a prompt on the TV.")
        if self.adb("adb-ready", "get-state", check=False).strip() != b"device":
            raise RuntimeError("TCL has not authorized the existing key. See adb-ready.stderr. Stop. Do not accept a prompt on the TV.")

    def snapshot(self, prefix):
        result = {name: self.adb(prefix + "-" + name, "shell", command).decode(errors="replace").replace("\r", "").strip()
                  for name, command in SNAPSHOT.items()}
        (self.evidence / (prefix + ".json")).write_text(json.dumps(result, indent=2) + "\n")
        return result

    def screenshot(self, name):
        png = self.adb(name, "exec-out", "screencap", "-p")
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("Screenshot " + name + " was not a PNG.")
        (self.evidence / (name + ".png")).write_bytes(png)

    def build(self):
        if not (ROOT / ".signing/local.keystore").is_file():
            raise RuntimeError("Existing Luma signing key missing. Build refused to create a credential.")
        self.command("source-build", ["bash", "luma.sh", "build"], 180)
        apk = ROOT / "dist/luma-tv.apk"
        signature = self.command("apk-signature", [SDK / "build-tools/36.0.0/apksigner", "verify", "--verbose", "--print-certs", apk])
        cert = re.search(rb"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signature)
        self.summary["checks"]["source_build"] = {"status": "PASS", "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                                                  "cert_sha256": cert.group(1).decode() if cert else None}

    def offline(self):
        classes = self.evidence / "java-classes"
        self.command("devices-compile", [JDK / "bin/javac", "-d", classes,
                     "src/dev/luma/tv/Devices.java", "tools/DevicesTest.java"])
        self.command("devices-test", [JDK / "bin/java", "-cp", classes, "dev.luma.tv.DevicesTest"])
        self.summary["checks"]["device_names"] = "PASS"
        capability_classes = self.evidence / "capability-classes"
        self.command("capabilities-compile", [JDK / "bin/javac", "--release", "8", "-d", capability_classes,
                     "src/dev/luma/tv/Capabilities.java", "tools/CapabilitiesTest.java"])
        self.command("capabilities-test", [JDK / "bin/java", "-cp", capability_classes, "dev.luma.tv.CapabilitiesTest"])
        self.summary["checks"]["capabilities"] = "PASS"
        weather_classes = self.evidence / "weather-classes"
        self.command("weather-compile", [JDK / "bin/javac", "--release", "8", "-d", weather_classes,
                     "src/dev/luma/tv/WeatherLocation.java", "tools/WeatherLocationTest.java"])
        self.command("weather-test", [JDK / "bin/java", "-cp", weather_classes, "dev.luma.tv.WeatherLocationTest"])
        self.summary["checks"]["weather_location"] = "PASS"
        if (ROOT / "tools/test_luma.py").is_file():
            self.env["PYTHONDONTWRITEBYTECODE"] = "1"
            self.command("offline-launcher-tests", [sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_luma.py", "-v"])
            self.summary["checks"]["offline_launcher"] = "PASS"
        else:
            self.summary["skipped"].append("tools/test_luma.py is absent on this base. PR #4 contains that offline suite.")

    def journal(self, before):
        serial = re.sub("[^A-Za-z0-9]", "", before["serial"])
        if not serial:
            raise RuntimeError("TCL reported no serial, so its restore journal cannot be found.")
        path = ROOT / (".local/home-" + serial + ".txt")
        return path, path.read_bytes() if path.is_file() else None

    def live(self, action):
        self.connect()
        before = self.snapshot("before")
        if before["model"] != MODEL or before["android"] != ANDROID:
            raise RuntimeError("The fixed address does not identify the authorized TCL Android 9 baseline.")
        installed = {}
        for key, pattern in VERSION.items():
            match = re.search(pattern, before["version"])
            if match:
                installed[key] = match.group(1).strip()
        self.summary["observed"]["installed"] = installed
        if action != "install" and "versionName" not in installed:
            raise RuntimeError("Luma is not installed. Safe verification does not install it.")
        self.summary["checks"]["doctor"] = "PASS"
        if action == "doctor":
            return
        refuse_prompt(before)
        journal, saved = self.journal(before)
        self.summary["observed"]["journal_sha256"] = hashlib.sha256(saved).hexdigest() if saved is not None else None
        if saved is not None:
            (self.evidence / "journal-before.txt").write_bytes(saved)
        if action in ["install", "restore"]:
            self.change_launcher(action, journal, saved, before)
        else:
            self.safe(journal, saved, before)

    def change_launcher(self, action, journal, saved, before):
        original = next((line[9:] for line in (saved or b"").decode(errors="replace").splitlines() if line.startswith("original=")), "")
        if not re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", original) or original.split("/")[0] != CLEAN:
            raise RuntimeError("A restore journal naming CleanYourTV as the original HOME is required before changing this TV.")
        if before["clean"] != "package:" + CLEAN:
            raise RuntimeError("CleanYourTV is not enabled. Launcher change refused.")
        if action == "install":
            self.command("install", [adb_bin(), "-P", str(PORT), "-s", TARGET,
                                     "install", "--no-incremental", "-r", ROOT / "dist/luma-tv.apk"], 90)
            if not journal.is_file() or journal.read_bytes() != saved:
                raise RuntimeError("Restore journal changed during install. HOME was not selected.")
            self.command("select-home", ["bash", "luma.sh", "-s", TARGET, "home"])
        else:
            self.command("restore", ["bash", "luma.sh", "-s", TARGET, "restore"])
        after = self.snapshot("after")
        if home(after) != component(LUMA if action == "install" else original):
            raise RuntimeError("HOME did not return the expected launcher. Keep all evidence.")
        if after["clean"] != "package:" + CLEAN:
            raise RuntimeError("CleanYourTV is no longer enabled. Keep all evidence.")
        self.summary["checks"][action] = "PASS"

    def safe(self, journal, saved, before):
        if home(before) != component(LUMA):
            raise RuntimeError("Safe verification requires Luma already selected as HOME.")
        if "mWakefulness=Awake" not in before["power"]:
            raise RuntimeError("TV is not awake. Safe verification does not wake it.")
        self.screenshot("screen-before")
        launch = self.adb("launch", "shell", "am start -W -n " + LUMA)
        if re.search(rb"^Error", launch, re.M):
            raise RuntimeError("Luma did not launch. See launch.stdout.")
        self.adb("home-button", "shell", "input keyevent KEYCODE_HOME")
        time.sleep(SETTLE)
        after = self.snapshot("after")
        refuse_prompt(after)
        focus = {"home": home(after), "window": only(FOCUS, after["focus"], "focused window"),
                 "resumed": only(RESUMED, after["activity"], "resumed activity")}
        if FOCUSED_APP.search(after["focus"]):
            focus["app"] = only(FOCUSED_APP, after["focus"], "focused app")
        self.summary["observed"]["after"] = focus
        if set(focus.values()) != {component(LUMA)}:
            raise RuntimeError("Home button did not leave Luma as HOME, focused window, and resumed activity.")
        if after["disabled"] != before["disabled"] or after["clean"] != before["clean"]:
            raise RuntimeError("Launcher package state changed during the safe run.")
        if (journal.read_bytes() if journal.is_file() else None) != saved:
            raise RuntimeError("Restore journal changed during the safe run.")
        self.screenshot("screen")
        self.summary["checks"]["launch_home_and_baseline"] = "PASS"

    def close(self):
        if self.started:
            self.started = False
            self.command("adb-stop", [adb_bin(), "-P", str(PORT), "kill-server"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["safe", "build", "offline", "doctor", "install", "restore"])
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--allow-launcher-change", action="store_true")
    args = parser.parse_args(argv)
    if args.action in ["install", "restore"] and not args.allow_launcher_change:
        parser.error("Install and restore require explicit user authorization and --allow-launcher-change.")
    evidence = args.evidence.resolve()
    if evidence == ROOT or ROOT in evidence.parents:
        parser.error("Evidence must live outside the repository.")
    if evidence.exists():
        parser.error("Evidence path already exists. Choose a new one.")
    os.umask(0o077)
    run = Run(evidence)
    try:
        source = fingerprint()
        if args.action in ["safe", "build", "install"]:
            run.build()
        if args.action in ["safe", "offline"]:
            run.offline()
        if args.action in ["safe", "doctor", "install", "restore"]:
            run.live(args.action)
        if args.action not in ["install", "restore"]:
            changed = sorted(set(source.items()) ^ set(fingerprint().items()))
            if changed:
                raise RuntimeError("Source changed during the run: " + ", ".join(sorted({name for name, _ in changed})) + ".")
            run.summary["checks"]["source_unchanged"] = "PASS"
        run.summary["status"] = "PARTIAL" if run.summary["skipped"] else "PASS"
    except Exception as error:
        run.summary["status"] = "FAIL"
        run.summary["error"] = str(error) if isinstance(error, RuntimeError) else type(error).__name__ + ": " + str(error)
    finally:
        run.summary.setdefault("status", "FAIL")
        try:
            run.close()
        except Exception as error:
            run.summary["status"] = "FAIL"
            run.summary["cleanup_error"] = str(error)
        (run.evidence / "summary.json").write_text(json.dumps(run.summary, indent=2) + "\n")
    print(json.dumps({"status": run.summary["status"], "evidence": str(run.evidence)}))
    return 0 if run.summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
