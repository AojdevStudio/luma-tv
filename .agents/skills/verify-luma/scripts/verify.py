#!/usr/bin/env python3
"""Verify Luma using one fixed TCL transport and private evidence."""
import argparse
import contextlib
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[4]
TARGET = "10.69.3.171:5555"
LEGACY_PORT = 5042
STATE = Path.home() / ".luma-verify"
MODEL, ANDROID = "BeyondTV5", "9"
LUMA = "dev.luma.tv/.MainActivity"
CLEAN = "com.gathin.tvhome"
SDK = Path("/opt/homebrew/share/android-commandlinetools")
JDK = Path("/opt/homebrew/opt/openjdk@17")
KEYDIR = Path.home() / "Backups/cleanyourtv/office-tcl-20261019/checkout/tools/adbkey"
SETTLE = 2
SOURCE = ["AndroidManifest.xml", "build.sh", "luma.sh", "res", "src", "tools", ".local", ".signing/local.keystore"]
LEGACY_SUITE_CHECKS = {
    "CapabilitiesTest": "capabilities", "CapabilityPanelTest": "capability_panel",
    "CommandsTest": "commands", "DevicesTest": "device_names", "HealthSnapshotTest": "health_snapshot",
    "HomePresetsTest": "home_presets", "HouseScenesTest": "house_scenes", "MediaContextTest": "media_context",
    "ProfilesTest": "profiles", "VoiceRequestsTest": "voice_requests", "WeatherLocationTest": "weather_location",
}
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


def peer(connection, path):
    """Return the pid of the process that listens at path, read from a connection to it."""
    if sys.platform == "darwin":
        # SOL_LOCAL with LOCAL_PEERPID, and LOCAL_PEERCRED whose struct xucred starts with version and uid.
        pid = struct.unpack("i", connection.getsockopt(0, 2, 4))[0]
        uid = struct.unpack_from("2I", connection.getsockopt(0, 1, 76))[1]
    else:
        pid, uid, _ = struct.unpack("3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
    if uid != os.getuid():
        raise RuntimeError("Another user's process listens on " + str(path) + ". It was left running.")
    return pid


def closed(connection, timeout):
    """True once the peer has closed the connection, which a server does when it exits."""
    if not select.select([connection], [], [], timeout)[0]:
        return False
    try:
        return connection.recv(1, socket.MSG_PEEK) == b""
    except ConnectionResetError:
        return True


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
        self.lock, self.holds, self.server, self.process = None, [], None, None
        self.summary = {"target": TARGET, "checks": {}, "skipped": [], "observed": {}}
        # signal: the first SIGINT or SIGTERM. deferred: it arrived inside a shield and has not been raised yet.
        # stopping: close() has begun, so no signal is raised again.
        self.signal, self.deferred, self.shielded, self.stopping = None, False, False, False
        self.unwritten = []

    def interrupt(self, number, frame):
        """Raise KeyboardInterrupt for the first signal. Inside a shield it waits, and later signals are ignored."""
        if self.signal is None:
            self.signal = number
            if self.shielded or self.stopping:
                self.deferred = True
            else:
                raise KeyboardInterrupt

    @contextlib.contextmanager
    def shield(self):
        """Hold an interruption until a child is recorded or a stop has finished, so no child is left running."""
        outer, self.shielded = self.shielded, True
        try:
            yield
        finally:
            self.shielded = outer
        if self.deferred and not self.shielded and not self.stopping:
            self.deferred = False
            raise KeyboardInterrupt

    def command(self, name, args, timeout=30, check=True):
        argv = [str(a) for a in args]
        entry, out, err, process = {"name": name, "argv": argv, "exit": "interrupted"}, b"", b"", None
        try:
            with self.shield():
                try:
                    # Its own process group, so a timeout or an interruption can stop everything the command started.
                    process = subprocess.Popen(argv, cwd=ROOT, env=self.env, stdin=subprocess.DEVNULL,
                                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                except OSError as error:
                    err, entry["exit"] = str(error).encode(), "not-run"
            if process:
                out, err = process.communicate(timeout=timeout)
                entry["exit"] = process.returncode
        except subprocess.TimeoutExpired:
            entry["exit"] = "timeout"
        finally:
            with self.shield():
                if entry["exit"] in ("timeout", "interrupted") and process:
                    self.stop_group(name, process)
                    if entry["exit"] == "timeout":
                        out, err = process.communicate()
                self.log(entry)
                (self.evidence / (name + ".stdout")).write_bytes(out)
                (self.evidence / (name + ".stderr")).write_bytes(err)
        if entry["exit"] == "timeout":
            raise RuntimeError(name + " timed out after " + str(timeout) + "s. See " + name + ".stdout and " + name + ".stderr.")
        if entry["exit"] == "not-run":
            raise RuntimeError(name + " could not start. See " + name + ".stderr.")
        if check and entry["exit"]:
            raise RuntimeError(name + " exited " + str(entry["exit"]) + ". See " + name + ".stderr.")
        return out

    def stop_group(self, name, process):
        """Kill the command's process group while its leader is unreaped, so the group id cannot name anyone else."""
        with self.shield():
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            for _ in range(200):
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    return
                time.sleep(0.05)
            raise RuntimeError(name + " left processes running in its group after SIGKILL.")

    def log(self, entry):
        """Record entry. Once cleanup has begun, entries wait in memory so a failed write cannot stop a stop."""
        self.unwritten.append(entry)
        if not self.stopping:
            self.write_log()

    def write_log(self):
        with (self.evidence / "commands.jsonl").open("a") as log:
            log.write("".join(json.dumps(entry) + "\n" for entry in self.unwritten))
        self.unwritten = []

    def adb_server(self, server):
        return [adb_bin(), "-L", "localfilesystem:" + str(server / "adb.sock")]

    def adb(self, name, *args, check=True):
        return self.command(name, [*self.adb_server(self.server), "-s", TARGET, *args], check=check)

    def start_server(self):
        try:
            keys = {name: (KEYDIR / name).read_bytes() for name in ["adbkey", "adbkey.pub"]}
        except OSError as error:
            raise RuntimeError("Existing trusted ADB key missing or unreadable (" + str(error.strerror)
                               + "). No key was created.") from error
        STATE.mkdir(mode=0o700, exist_ok=True)
        self.lock = (STATE / "tcl.lock").open("a")
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another verifier is driving the TCL. It was left running.") from None
        runtime = STATE / "run"
        if runtime.is_symlink():
            raise RuntimeError("Private ADB runtime directory is a symlink: " + str(runtime))
        runtime.mkdir(mode=0o700, exist_ok=True)
        for stale in sorted([*runtime.glob("adb-*"), *runtime.glob("old-*")]):
            marker = stale / ".owner"
            if not stale.is_symlink() and not marker.is_symlink() and marker.is_file() and marker.samefile(self.lock.name):
                self.stop_server(stale, "adb-reap")
        for legacy in [*STATE.glob("adb-*"), *STATE.glob("old-*")]:
            if (legacy / "adb.sock").is_socket():
                raise RuntimeError("An earlier checkout left an unmarked ADB socket at " + str(legacy)
                                   + ". Verify its owner before continuing.")
        self.hold_legacy_port()
        self.server = Path(tempfile.mkdtemp(prefix="adb-", dir=runtime))
        (self.server / ".owner").hardlink_to(self.lock.name)
        # ADB reads keys from $HOME/.android and creates a key there when none exists. A private home that holds
        # only a copy of the trusted key keeps the operator's own keys out, and ADB never opens a trusted path.
        home = self.server / "home"
        home.mkdir(mode=0o700)
        (home / ".android").mkdir(mode=0o700)
        for name, data in keys.items():
            with open(os.open(home / ".android" / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as copy:
                copy.write(data)
        self.env.update({"HOME": str(home), "ADB_SERVER_SOCKET": self.adb_server(self.server)[2],
                         "TMPDIR": str(self.evidence),
                         "ADB_MDNS_AUTO_CONNECT": "0", "ADB_MDNS": "0", "ADB_USB": "0", "ADB_EMU": "0"})
        argv = [str(arg) for arg in self.adb_server(self.server)] + ["fork-server", "server", "--reply-fd"]
        read, write = os.pipe()
        with open(read, "rb", buffering=0) as reply, (self.evidence / "adb-server.stderr").open("wb") as stderr:
            try:
                with self.shield():
                    # The command `adb start-server` runs, kept as our child so cleanup can signal it.
                    self.process = subprocess.Popen([*argv, str(write)], cwd=ROOT, env=self.env,
                                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=stderr,
                                                    pass_fds=[write])
            except OSError as error:
                raise RuntimeError("adb-start could not start: " + str(error)) from error
            finally:
                os.close(write)
            ready = bool(select.select([reply], [], [], 30)[0]) and reply.read(3) == b"OK\n"
        status = 0
        if not ready:
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.process.wait(5)
            status = "timeout" if self.process.returncode is None else self.process.returncode
        self.log({"name": "adb-start", "argv": [*argv, str(write)], "pid": self.process.pid, "exit": status})
        if status == "timeout":
            raise RuntimeError("The ADB server did not report ready within 30s. See adb-server.stderr and adb.*.log in the evidence.")
        if not ready:
            cause ="signal " + signal.Signals(-status).name if status < 0 else "status " + str(status)
            text = "".join(path.read_text(errors="replace") for path in [self.evidence / "adb-server.stderr",
                                                                         *self.evidence.glob("adb.*.log")])
            errors = re.findall(r"^.* [FE] adb\s*: \S+:\d+ (.*)$", text, re.M)
            reason = errors[-1].replace(str(Path.home()), "~") if errors else "it wrote no error"
            raise RuntimeError(f"The ADB server exited with {cause} before it was ready: {reason}. See adb-server.stderr in the evidence.")

    def stop_server(self, server, name):
        """Stop whatever listens on this run's socket, such as a server an adb command restarted, then remove it.
        A directory whose server could not be stopped stays, so the next run tries again."""
        with self.shield():
            if server.name.startswith("adb-"):
                # Clients know only the adb- path, so after the rename none of them can start a server on this socket.
                # The new name has the same length, so a socket path that was short enough to bind stays reachable.
                retired = server.with_name("old-" + server.name[4:])
                if os.path.lexists(retired):
                    raise RuntimeError("Cannot retire ADB directory because the destination exists: " + str(retired))
                server = server.rename(retired)
            for _ in range(5):
                if not self.stop_listener(name, server / "adb.sock"):
                    shutil.rmtree(server)
                    return
            raise RuntimeError("An ADB server kept listening on " + str(server / "adb.sock") + " after 5 stops.")

    def stop_listener(self, name, path):
        """Stop the server that listens on path through a connection to it. Return False when nothing listens.

        The shutdown request goes through the connected socket. If ADB hangs, only a stable process handle can
        authorize escalation. Without one the directory stays for the next recovery attempt."""
        if not path.exists():
            return False  # Nothing bound here. This also covers a path too long to bind.
        with socket.socket(socket.AF_UNIX) as connection:
            connection.settimeout(5)
            try:
                connection.connect(str(path))
            except (FileNotFoundError, ConnectionRefusedError):
                return False
            pid = peer(connection, path)
            try:
                handle = os.pidfd_open(pid) if hasattr(os, "pidfd_open") else None
            except ProcessLookupError:
                return True  # It exited after the connect. Check the socket again.
            try:
                try:
                    connection.sendall(b"0009host:kill")
                    reply = connection.recv(4)
                except (BrokenPipeError, ConnectionResetError):
                    return True  # It exited before it answered. Check the socket again.
                # OKAY arrives after pidfd_open, so the handle names this server and not a reused pid.
                if reply != b"OKAY":
                    return True
                if closed(connection, 10):
                    self.log({"name": name, "argv": ["host:kill", str(path)], "pid": pid, "exit": 0})
                else:
                    self.log({"name": name, "argv": ["host:kill", str(path)], "pid": pid, "exit": "hung"})
                    if handle is None:
                        raise RuntimeError("ADB did not close its socket after host:kill. No stable process handle "
                                           "is available. The server directory was retained: " + str(path.parent))
                    with contextlib.suppress(ProcessLookupError):
                        signal.pidfd_send_signal(handle, signal.SIGKILL)
                    self.log({"name": name, "argv": ["kill", "-KILL", str(pid)], "exit": 0})
                    if not closed(connection, 10):
                        raise RuntimeError("ADB server " + str(pid) + " did not exit within 10s of SIGKILL.")
                if handle is not None:
                    if not select.select([handle], [], [], 10)[0]:
                        raise RuntimeError("ADB server " + str(pid) + " closed its socket but did not exit within 10s.")
                    return True
                for _ in range(200):
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        return True
                    time.sleep(0.05)
                raise RuntimeError("ADB server " + str(pid) + " closed its socket but did not exit within 10s.")
            finally:
                if handle is not None:
                    os.close(handle)

    def hold_legacy_port(self):
        for family, host in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
            try:
                hold = socket.socket(family)
                self.holds.append(hold)
                if family == socket.AF_INET6:
                    hold.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                # Bound, not listening: older verifiers cannot bind, and a client is refused instead of
                # reaching a socket that never answers. No SO_REUSEADDR, so an owner that has bound but
                # not yet listened, or a wildcard listener, makes this bind fail rather than share the port.
                hold.bind((host, LEGACY_PORT))
            except OSError as error:
                if family == socket.AF_INET6 and error.errno in (errno.EAFNOSUPPORT, errno.EADDRNOTAVAIL):
                    continue
                if error.errno == errno.EADDRINUSE:
                    raise RuntimeError(f"Port {LEGACY_PORT} is in use on {host}. An older verifier or another "
                                       "program may own it. It was left running. A connection that closed in the "
                                       "last minute also holds it, so retry after about a minute if nothing uses it.") from error
                raise RuntimeError(f"Could not hold port {LEGACY_PORT} on {host}: {error}") from error

    def connect(self):
        self.start_server()
        output = self.command("adb-connect", [*self.adb_server(self.server), "connect", TARGET], 12)
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
        suites = sorted((ROOT / "tools").glob("*Test.java"))
        if not suites:
            raise RuntimeError("No offline Java suites found. Put each suite in tools/<Name>Test.java.")
        classes = self.evidence / "java-classes"
        if sys.platform.startswith("linux"):
            self.env["LC_ALL"] = "C.UTF-8"
        self.summary["java_suites"] = {}
        self.command("java-version", ["java", "-version"])
        self.command("java-compile", ["javac", "--release", "8", "-encoding", "UTF-8",
                                      "-sourcepath", "src", "-d", classes, *suites])
        for suite in suites:
            self.command(suite.stem, ["java", "-ea", "-cp", classes, "dev.luma.tv." + suite.stem])
            self.summary["java_suites"][suite.stem] = "PASS"
            if suite.stem in LEGACY_SUITE_CHECKS:
                self.summary["checks"][LEGACY_SUITE_CHECKS[suite.stem]] = "PASS"
        missing = LEGACY_SUITE_CHECKS.keys() - self.summary["java_suites"].keys()
        if missing:
            self.summary["skipped"].append("Missing baseline Java suites: " + ", ".join(sorted(missing)))
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
            self.command("install", [*self.adb_server(self.server), "-s", TARGET,
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
        """Stop the server, any server on its socket, and the port hold, then release the lock and write the log.
        Signals are ignored from here on. Each step runs even when an earlier one fails, and the failures are raised
        together at the end."""
        self.stopping = True
        failures = []
        if self.process:
            process, self.process = self.process, None
            process.terminate()
            try:
                process.wait(10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            self.log({"name": "adb-stop", "argv": ["kill", "-TERM", str(process.pid)], "exit": process.returncode})
        if self.server:
            server, self.server = self.server, None
            try:
                self.stop_server(server, "adb-sweep")
            except (OSError, RuntimeError) as error:
                failures.append(error)
        for held in [*self.holds, self.lock]:
            if held:
                held.close()
        self.lock, self.holds = None, []
        if self.unwritten:
            try:
                self.write_log()
            except OSError as error:
                failures.append(RuntimeError("Could not write commands.jsonl: " + str(error)))
        if failures:
            raise RuntimeError(" ".join(str(error) for error in failures))


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
    runtime = (STATE / "run").resolve()
    if evidence == runtime or runtime in evidence.parents or evidence == (STATE / "tcl.lock").resolve():
        parser.error("Evidence must live outside the verifier's private runtime directory and lock.")
    if evidence.exists():
        parser.error("Evidence path already exists. Choose a new one.")
    os.umask(0o077)
    run = Run(evidence)
    previous = {number: signal.signal(number, run.interrupt) for number in (signal.SIGINT, signal.SIGTERM)}
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
    except (Exception, KeyboardInterrupt) as error:
        run.summary["status"] = "FAIL"
        run.summary["error"] = ("The run was interrupted." if isinstance(error, KeyboardInterrupt) else str(error)
                                if isinstance(error, RuntimeError) else type(error).__name__ + ": " + str(error))
    finally:
        run.summary.setdefault("status", "FAIL")
        try:
            run.close()
        except RuntimeError as error:
            run.summary["status"] = "FAIL"
            run.summary["cleanup_error"] = str(error)
        if run.signal is not None and "error" not in run.summary:
            run.summary["status"], run.summary["error"] = "FAIL", "The run was interrupted."
        for number, handler in previous.items():
            signal.signal(number, handler)
        (run.evidence / "summary.json").write_text(json.dumps(run.summary, indent=2) + "\n")
    print(json.dumps({"status": run.summary["status"], "evidence": str(run.evidence)}))
    return 0 if run.summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
