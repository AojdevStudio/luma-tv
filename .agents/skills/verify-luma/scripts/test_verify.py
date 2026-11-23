import contextlib
import errno
import fcntl
import io
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify  # noqa: E402

LUMA_FOCUS = ("  mCurrentFocus=Window{1a2b u0 dev.luma.tv/dev.luma.tv.MainActivity}\n"
              "  mFocusedApp=AppWindowToken{6c1e token=Token{2f0b ActivityRecord{9d0a u0 dev.luma.tv/.MainActivity t12}}}")
DEVICE = {
    "home": "priority=0 preferredOrder=0 match=0x108000\ndev.luma.tv/.MainActivity",
    "disabled": "package:com.google.android.tvlauncher\npackage:com.tcl.esticker",
    "clean": "package:com.gathin.tvhome",
    "version": "  Package [dev.luma.tv] (abc):\n    versionCode=26 minSdk=26 targetSdk=34\n    versionName=2.4\n    lastUpdateTime=2026-10-19 10:00:00",
    "focus": LUMA_FOCUS,
    "activity": ("    mResumedActivity: ActivityRecord{9d0a u0 dev.luma.tv/.MainActivity t12}\n"
                 "  ResumedActivity: ActivityRecord{9d0a u0 dev.luma.tv/.MainActivity t12}"),
    "model": "BeyondTV5", "android": "9", "serial": "ABC123", "power": "  mWakefulness=Awake",
}
SERVER = ["fork-server", "server", "--reply-fd"]
REAL_POPEN = subprocess.Popen
JOURNAL = b"original=com.gathin.tvhome/.MainActivity\ndisabled=com.google.android.tvlauncher\n"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class FakeTools:
    """Stands in for adb, the build, and the JDK. Nothing leaves the process."""

    def __init__(self, root):
        self.root = root
        self.calls, self.envs = [], []
        self.before, self.after = dict(DEVICE), {}
        self.pressed = False
        self.state, self.timeout, self.interrupt = b"device\n", None, None
        self.on_home = None

    def popen(self, argv, **kwargs):
        self.calls.append(argv)
        self.envs.append(kwargs["env"])
        if "pass_fds" in kwargs:
            os.write(kwargs["pass_fds"][0], b"OK\n")
            return FakeServer(self.calls, argv)
        if argv[-1] == self.timeout:
            return FakeCommand(b"partial", None)
        if argv[-1] == self.interrupt:
            return InterruptedCommand()
        out, code = b"", 0
        if argv[:3] == ["bash", "luma.sh", "build"]:
            (self.root / "dist").mkdir(exist_ok=True)
            (self.root / "dist/luma-tv.apk").write_bytes(b"apk")
        elif argv[0].endswith("apksigner"):
            out = b"Verifies\nSigner #1 certificate SHA-256 digest: abc123\n"
        elif argv[0].endswith("adb"):
            out, code = self.adb(argv[argv.index("-L") + 2:])
        return FakeCommand(out, code)

    def adb(self, args):
        if args[0] == "connect":
            return b"connected to " + args[1].encode() + b"\n", 0
        if args[0] != "-s":
            return b"", 0
        args = args[2:]
        if args == ["get-state"]:
            return self.state, 0 if self.state.strip() == b"device" else 1
        if args[0] == "exec-out":
            return b"\x89PNG\r\n\x1a\nimage", 0
        if args == ["shell", "input keyevent KEYCODE_HOME"]:
            self.pressed = True
            if self.on_home:
                self.on_home()
        for name, command in verify.SNAPSHOT.items():
            if args == ["shell", command]:
                device = {**self.before, **self.after} if self.pressed else self.before
                return device[name].encode(), 0
        return b"", 0

    def adb_calls(self):
        return [call for call in self.calls if call[0].endswith("adb")]


class FakeCommand:
    """A command that has finished, or one that times out (code None). A timeout gets a real `true` process in its
    own group, because the verifier kills the group of a command that timed out."""

    def __init__(self, out, code):
        self.out, self.code, self.returncode = out, code, None
        if code is None:
            self.group = REAL_POPEN(["true"], start_new_session=True)
            self.pid = self.group.pid

    def communicate(self, timeout=None):
        if self.code is None and self.returncode is None:
            raise subprocess.TimeoutExpired("fake", timeout)
        self.wait()
        return self.out, b""

    def wait(self, timeout=None):
        self.returncode = -signal.SIGKILL if self.code is None else self.code
        if self.code is None:
            self.group.wait()
        return self.returncode


class InterruptedCommand:
    """A command whose group holds a real sleep, and which receives SIGTERM while the verifier waits for it."""

    def __init__(self):
        self.group = REAL_POPEN(["sleep", "300"], start_new_session=True)
        self.pid, self.returncode = self.group.pid, None

    def communicate(self, timeout=None):
        os.kill(os.getpid(), signal.SIGTERM)
        time.sleep(5)
        raise AssertionError("SIGTERM did not interrupt the command.")

    def wait(self, timeout=None):
        self.returncode = self.group.wait(timeout)
        return self.returncode


# Listens like an ADB server and answers host:kill with OKAY. With "hang" it then keeps running, as ADB 37.0.1
# sometimes does right after it starts. Like a server that adb restarts, it is not the caller's child.
HOST_KILL = """
import os, socket, sys, time
if os.fork():
    os._exit(0)
os.setsid()
server = socket.socket(socket.AF_UNIX)
server.bind(sys.argv[1])
server.listen()
print(os.getpid(), flush=True)
os.dup2(os.open(os.devnull, os.O_WRONLY), 1)
connection, _ = server.accept()
assert connection.recv(13) == b"0009host:kill"
connection.sendall(b"OKAY")
if sys.argv[2] == "hang":
    time.sleep(300)
"""


class FakeServer:
    def __init__(self, calls, args):
        self.calls, self.args, self.pid, self.returncode = calls, args, 4242, None

    def terminate(self):
        self.calls.append(["terminate", self.args[2]])

    def wait(self, timeout=None):
        self.returncode = -15
        return self.returncode


class OfflineDiscoveryTest(unittest.TestCase):
    def test_unregistered_failing_suite_fails_the_offline_command(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            repo = base / "café"
            script = repo / ".agents/skills/verify-luma/scripts/verify.py"
            script.parent.mkdir(parents=True)
            shutil.copyfile(verify.__file__, script)
            suites = repo / "tools"
            suites.mkdir()
            (suites / "CaféTest.java").write_text(
                'package dev.luma.tv; public class CaféTest {'
                'public static void main(String[] args) { assert false : "suite ran"; } }', encoding="utf-8")
            evidence = base / "evidence"
            result = subprocess.run([sys.executable, script, "offline", "--evidence", evidence], capture_output=True)
            self.assertEqual(result.returncode, 1)
            summary = json.loads((evidence / "summary.json").read_text())
            self.assertEqual(summary["status"], "FAIL", summary)
            self.assertIn("CaféTest", summary["error"])
            self.assertIn("suite ran", (evidence / "CaféTest.stderr").read_text())


class VerifyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        base = Path(temp.name)
        self.root, self.keys, self.evidence = base / "repo", base / "keys", base / "evidence"
        for name, data in {"AndroidManifest.xml": b"<manifest/>", "luma.sh": b"", "build.sh": b"",
                           "src/dev/luma/tv/Devices.java": b"", "tools/DevicesTest.java": b"",
                           ".signing/local.keystore": b"key", ".local/home-ABC123.txt": JOURNAL,
                           "../keys/adbkey": b"private", "../keys/adbkey.pub": b"public"}.items():
            (self.root / name).parent.mkdir(parents=True, exist_ok=True)
            (self.root / name).write_bytes(data)
        for suite in (verify.ROOT / "tools").glob("*Test.java"):
            (self.root / "tools" / suite.name).touch()
        self.tools = FakeTools(self.root)
        for name, value in {"ROOT": self.root, "KEYDIR": self.keys, "STATE": base / "state",
                            "LEGACY_PORT": free_port(), "SETTLE": 0}.items():
            patcher = mock.patch.object(verify, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(verify.subprocess, "Popen", self.tools.popen)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_verify(self, *args):
        with contextlib.redirect_stdout(io.StringIO()):
            code = verify.main([*args, "--evidence", str(self.evidence)])
        return code, json.loads((self.evidence / "summary.json").read_text())

    def assert_cleaned_up_once(self):
        starts = [call for call in self.tools.calls if call[3:6] == SERVER]
        stops = [call for call in self.tools.calls if call[0] == "terminate"]
        self.assertEqual(len(starts), 1)
        self.assertEqual(stops, [["terminate", starts[0][2]]])
        after = self.tools.calls[self.tools.calls.index(stops[0]) + 1:]
        self.assertEqual(after, [])
        self.assertEqual(list((verify.STATE / "run").glob("*-*")), [])
        self.assert_lock_free()

    def assert_lock_free(self):
        with (verify.STATE / "tcl.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def assert_left_running(self, listener):
        listener.setblocking(False)
        with self.assertRaises(BlockingIOError):
            listener.accept()
        with socket.create_connection(listener.getsockname()[:2]):
            pass

    def test_reaping_an_abandoned_run_preserves_evidence_with_a_server_prefix(self):
        evidence = verify.STATE / "adb-doctor"
        run = verify.Run(evidence)
        saved = b'{"status":"PASS"}'
        (evidence / "summary.json").write_bytes(saved)
        unmarked = verify.STATE / "run" / "old-saved"
        unmarked.mkdir(parents=True)
        (unmarked / "summary.json").write_bytes(saved)
        run.start_server()
        abandoned = run.server
        run.server = None  # Leave the directory behind, as after an interrupted verifier.
        run.close()
        code, summary = self.run_verify("doctor")
        self.assertEqual(code, 0, summary)
        self.assertFalse(abandoned.exists())
        self.assertEqual((evidence / "summary.json").read_bytes(), saved)
        self.assertEqual((unmarked / "summary.json").read_bytes(), saved)

    def test_verifier_rejects_evidence_inside_its_private_runtime_directory(self):
        path = verify.STATE / "run" / "adb-doctor"
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            verify.main(["offline", "--evidence", str(path)])
        self.assertFalse(path.exists())

    def test_safe_run_passes_with_launcher_suite(self):
        (self.root / "tools/test_luma.py").write_bytes(b"")
        code, summary = self.run_verify("safe")
        self.assertEqual((code, summary["status"]), (0, "PASS"), summary)
        expected = {"source_build", "device_names", "capabilities", "house_scenes", "weather_location",
                    "commands", "voice_requests", "health_snapshot", "capability_panel", "home_presets",
                    "profiles", "media_context", "offline_launcher", "doctor", "launch_home_and_baseline",
                    "source_unchanged"}
        self.assertLessEqual(expected, summary["checks"].keys())
        self.assertEqual(summary["checks"]["source_build"]["cert_sha256"], "abc123")
        self.assertEqual(summary["observed"]["installed"]["versionName"], "2.4")
        self.assertTrue((self.evidence / "screen-before.png").is_file() and (self.evidence / "screen.png").is_file())
        self.assert_cleaned_up_once()

    def test_offline_suite_cannot_report_device_or_build_checks(self):
        for name in ["DoctorTest", "SourceBuildTest"]:
            (self.root / "tools" / (name + ".java")).touch()
        _, summary = self.run_verify("offline")
        self.assertNotIn("doctor", summary["checks"])
        self.assertNotIn("source_build", summary["checks"])
        self.assertEqual(summary["java_suites"]["DoctorTest"], "PASS")
        self.assertEqual(summary["java_suites"]["SourceBuildTest"], "PASS")

    def test_missing_profiles_suite_leaves_verification_incomplete(self):
        (self.root / "tools/ProfilesTest.java").unlink()
        (self.root / "tools/test_luma.py").touch()
        code, summary = self.run_verify("offline")
        self.assertEqual((code, summary["status"]), (1, "PARTIAL"), summary)
        self.assertIn("ProfilesTest", " ".join(summary["skipped"]))

    def test_missing_launcher_suite_is_partial_not_pass(self):
        code, summary = self.run_verify("offline")
        self.assertEqual((code, summary["status"]), (1, "PARTIAL"))
        self.assertIn("tools/test_luma.py", summary["skipped"][0])
        self.assertEqual(self.tools.adb_calls(), [])

    def test_only_fixed_tcl_and_existing_key_pointers(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            verify.main(["doctor", "-s", "10.69.3.99:5555", "--evidence", str(self.evidence)])
        with mock.patch.dict(os.environ, {"LUMA_DEVICE": "10.69.3.99:5555", "SECRET_TOKEN": "sentinel-value"}):
            code, summary = self.run_verify("doctor")
        self.assertEqual(summary["status"], "PASS", summary)
        server = self.tools.adb_calls()[0][:3]
        self.assertRegex(server[2], "^localfilesystem:" + str(verify.STATE / "run") + "/adb-[^/]+/adb.sock$")
        for call in self.tools.adb_calls():
            self.assertEqual(call[:3], server)
            self.assertTrue(call[3:6] == SERVER or call[3:] == ["connect", verify.TARGET]
                            or call[3:5] == ["-s", verify.TARGET], call)
        allowed = {"PATH", "JAVA_HOME", "LUMA_JDK", "ANDROID_SDK_ROOT", "LUMA_KEYSTORE", "HOME",
                   "ADB_MDNS_AUTO_CONNECT", "ADB_SERVER_SOCKET", "PYTHONDONTWRITEBYTECODE",
                   "TMPDIR", "ADB_MDNS", "ADB_USB", "ADB_EMU"}
        for env in self.tools.envs:
            self.assertLessEqual(set(env), allowed)
        self.assertEqual(self.tools.envs[-1]["HOME"], str(Path(server[2].split(":", 1)[1]).parent / "home"))
        self.assertEqual(self.tools.envs[-1]["ADB_SERVER_SOCKET"], server[2])
        self.assertEqual({self.tools.envs[-1][name] for name in ["ADB_MDNS", "ADB_USB", "ADB_EMU"]}, {"0"})
        evidence = b"".join(path.read_bytes() for path in self.evidence.rglob("*") if path.is_file())
        self.assertNotIn(b"sentinel-value", evidence)

    def test_missing_or_unreadable_adb_key_refuses_before_any_adb(self):
        (self.keys / "adbkey").unlink()
        for label, make in {"missing": lambda: None, "unreadable": (self.keys / "adbkey").mkdir}.items():
            with self.subTest(label):
                make()
                self.evidence = self.evidence.with_name("evidence-" + label)
                code, summary = self.run_verify("doctor")
                self.assertEqual(summary["status"], "FAIL")
                self.assertIn("No key was created", summary["error"])
                self.assertEqual(self.tools.adb_calls(), [])
                self.assertFalse((self.keys / "adbkey").is_file())

    def test_missing_signing_key_refuses_build(self):
        (self.root / ".signing/local.keystore").unlink()
        code, summary = self.run_verify("build")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("refused to create a credential", summary["error"])
        self.assertEqual(self.tools.calls, [])
        self.assertFalse((self.root / ".signing/local.keystore").exists())

    def test_consecutive_runs_pass_and_a_recently_closed_connection_asks_for_a_retry(self):
        for name in ["first", "second"]:
            self.evidence = self.evidence.with_name(name)
            self.assertEqual(self.run_verify("doctor"), (0, mock.ANY))
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", verify.LEGACY_PORT))
            listener.listen()
        self.evidence = self.evidence.with_name("after-idle-listener")
        self.assertEqual(self.run_verify("doctor"), (0, mock.ANY))
        # An older verifier's server closes its side first, which leaves the port in TIME_WAIT for up to a minute.
        # A bind that does not share the port cannot tell that apart from an owner that has bound but not listened.
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", verify.LEGACY_PORT))
            listener.listen()
            with socket.create_connection(listener.getsockname()) as client:
                connection, _ = listener.accept()
                connection.close()
                self.assertEqual(client.recv(1), b"")
        self.evidence = self.evidence.with_name("after-time-wait")
        code, summary = self.run_verify("doctor")
        self.assertEqual((code, summary["status"]), (1, "FAIL"), summary)
        self.assertIn("retry after about a minute", summary["error"])
        self.assertEqual(sum(call[3:6] == SERVER for call in self.tools.adb_calls()), 3)
        self.assertEqual(list((verify.STATE / "run").glob("*-*")), [])
        self.assert_lock_free()

    def test_missing_ipv6_loopback_holds_ipv4_only(self):
        real = socket.socket
        for number in (errno.EAFNOSUPPORT, errno.EADDRNOTAVAIL):
            def no_ipv6(family=socket.AF_INET, *args):
                if family == socket.AF_INET6:
                    raise OSError(number, os.strerror(number))
                return real(family, *args)
            with self.subTest(errno.errorcode[number]), mock.patch.object(verify.socket, "socket", no_ipv6):
                self.evidence = self.evidence.with_name("evidence-" + str(number))
                run = verify.Run(self.evidence)
                try:
                    run.start_server()
                    with real() as old, self.assertRaises(OSError) as refused:
                        old.bind(("127.0.0.1", verify.LEGACY_PORT))
                    self.assertEqual(refused.exception.errno, errno.EADDRINUSE)
                finally:
                    run.close()
                self.assert_lock_free()

    def test_old_verifier_listener_on_ipv4_or_ipv6_loopback_is_left_running(self):
        for family, host in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
            with self.subTest(host), socket.socket(family) as foreign:
                self.evidence = self.evidence.with_name("evidence-" + str(family))
                if family == socket.AF_INET6:
                    foreign.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                foreign.bind((host, verify.LEGACY_PORT))
                foreign.listen()
                code, summary = self.run_verify("doctor")
                self.assertEqual((code, summary["status"]), (1, "FAIL"), summary)
                self.assertIn("in use on " + host, summary["error"])
                self.assertIn("left running", summary["error"])
                self.assertEqual(self.tools.adb_calls(), [])
                self.assert_left_running(foreign)
                self.assert_lock_free()

    def test_wildcard_listener_refuses_the_run_and_is_never_contacted(self):
        for family, host, v6only, refused_on in ((socket.AF_INET, "0.0.0.0", None, "127.0.0.1"),
                                                 (socket.AF_INET6, "::", 0, "127.0.0.1"),
                                                 (socket.AF_INET6, "::", 1, "::1")):
            with self.subTest(host=host, v6only=v6only), socket.socket(family) as foreign:
                self.evidence = self.evidence.with_name("evidence-" + host + str(v6only))
                foreign.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                if v6only is not None:
                    foreign.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, v6only)
                foreign.bind((host, verify.LEGACY_PORT))
                foreign.listen()
                code, summary = self.run_verify("doctor")
                self.assertEqual((code, summary["status"]), (1, "FAIL"), summary)
                self.assertIn("in use on " + refused_on, summary["error"])
                self.assertEqual(self.tools.adb_calls(), [])
                self.assert_left_running(foreign)
                self.assert_lock_free()

    def test_foreign_bound_port_is_in_use_before_adb_starts(self):
        with socket.socket() as foreign:
            foreign.bind(("127.0.0.1", verify.LEGACY_PORT))
            code, summary = self.run_verify("doctor")
            self.assertEqual((code, summary["status"]), (1, "FAIL"), summary)
            self.assertIn("in use", summary["error"])
            self.assertEqual(self.tools.adb_calls(), [])

    def test_owner_that_has_bound_but_not_listened_keeps_the_port(self):
        for reuse in (0, 1):
            with self.subTest(reuse=reuse), socket.socket() as foreign:
                foreign.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, reuse)
                foreign.bind(("127.0.0.1", verify.LEGACY_PORT))
                run = verify.Run(self.evidence.with_name("evidence-" + str(reuse)))
                try:
                    with self.assertRaisesRegex(RuntimeError, "in use on 127.0.0.1"):
                        run.start_server()
                finally:
                    run.close()
                foreign.listen()
                self.assert_left_running(foreign)
                self.assertEqual(self.tools.adb_calls(), [])

    def test_older_verifier_checks_refuse_while_a_run_holds_the_port(self):
        run = verify.Run(self.evidence)
        try:
            run.start_server()
            for family, host in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
                with self.assertRaises(ConnectionRefusedError):
                    socket.create_connection((host, verify.LEGACY_PORT), timeout=5).close()
                for reuse in (0, 1):
                    with self.subTest(host=host, reuse=reuse), socket.socket(family) as old:
                        old.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, reuse)
                        with self.assertRaises(OSError) as refused:
                            old.bind((host, verify.LEGACY_PORT))
                        self.assertEqual(refused.exception.errno, errno.EADDRINUSE)
        finally:
            run.close()

    def test_second_verifier_refuses_and_leaves_the_first_server_running(self):
        first = verify.Run(self.evidence.with_name("first"))
        try:
            first.start_server()
            code, summary = self.run_verify("doctor")
            self.assertEqual((code, summary["status"]), (1, "FAIL"), summary)
            self.assertIn("Another verifier", summary["error"])
            self.assertEqual([call[3:6] for call in self.tools.adb_calls()], [SERVER])
            self.assertTrue(first.server.is_dir())
        finally:
            first.close()
        self.assert_lock_free()

    def test_unauthorized_key_stops_without_retry_and_cleans_up(self):
        self.tools.state = b""
        code, summary = self.run_verify("doctor")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("not authorized", summary["error"])
        self.assertEqual(sum(call[3] == "connect" for call in self.tools.adb_calls()), 1)
        self.assertFalse(any("shell" in call for call in self.tools.calls))
        self.assert_cleaned_up_once()

    def test_focus_must_be_luma_not_a_substring_of_the_dump(self):
        self.tools.after["focus"] = ("  Window #3 Window{1a2b u0 dev.luma.tv/dev.luma.tv.MainActivity}\n"
                                     "  mCurrentFocus=Window{9f9f u0 com.netflix.ninja/com.netflix.ninja.MainActivity}")
        code, summary = self.run_verify("safe")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("did not leave Luma", summary["error"])
        self.assertEqual(summary["observed"]["after"]["window"], "com.netflix.ninja/com.netflix.ninja.MainActivity")
        self.assert_cleaned_up_once()

    def test_permission_prompt_stops_without_any_input(self):
        self.tools.before["focus"] = ("  mCurrentFocus=Window{4c4c u0 com.android.packageinstaller/"
                                      "com.android.packageinstaller.permission.ui.GrantPermissionsActivity}")
        code, summary = self.run_verify("safe")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("Do not click", summary["error"])
        self.assertFalse(any(arg.startswith(("input", "am start")) for call in self.tools.calls for arg in call))
        self.assert_cleaned_up_once()

    def test_baseline_or_journal_change_fails_the_safe_run(self):
        journal = self.root / ".local/home-ABC123.txt"
        changes = {"disabled list": lambda: self.tools.after.update(disabled="package:com.google.android.tvlauncher"),
                   "journal": lambda: setattr(self.tools, "on_home", lambda: journal.write_bytes(b"original=other/.A\n"))}
        for label, change in changes.items():
            with self.subTest(label):
                self.evidence = self.evidence.with_name("evidence-" + label.replace(" ", "-"))
                self.tools.after, self.tools.on_home, self.tools.pressed = {}, None, False
                journal.write_bytes(JOURNAL)
                change()
                code, summary = self.run_verify("safe")
                self.assertEqual(summary["status"], "FAIL")
                self.assertIn("changed during the safe run", summary["error"])
                self.assertEqual((self.evidence / "journal-before.txt").read_bytes(), JOURNAL)

    def test_timeout_keeps_logs_and_cleans_up(self):
        self.tools.timeout = verify.SNAPSHOT["activity"]
        code, summary = self.run_verify("doctor")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("before-activity timed out", summary["error"])
        log = [json.loads(line) for line in (self.evidence / "commands.jsonl").read_text().splitlines()]
        self.assertIn({"name": "before-activity", "exit": "timeout"}, [{k: e[k] for k in ("name", "exit")} for e in log])
        self.assertEqual((self.evidence / "before-activity.stdout").read_bytes(), b"partial")
        self.assert_cleaned_up_once()

    def test_second_signal_during_command_cleanup_still_stops_its_group(self):
        self.tools.interrupt = verify.SNAPSHOT["model"]
        real_killpg, groups = os.killpg, []

        def killpg(pid, number):
            if number == signal.SIGKILL and not groups:
                groups.append(pid)
                os.kill(os.getpid(), signal.SIGTERM)
            real_killpg(pid, number)

        with mock.patch.object(verify.os, "killpg", killpg):
            code, summary = self.run_verify("doctor")
        self.assertEqual((code, summary["status"], summary["error"]), (1, "FAIL", "The run was interrupted."), summary)
        self.assertNotIn("cleanup_error", summary)
        with self.assertRaises(ProcessLookupError):
            os.killpg(groups[0], 0)
        log = [json.loads(line) for line in (self.evidence / "commands.jsonl").read_text().splitlines()]
        self.assertIn({"name": "before-model", "exit": "interrupted"}, [{k: e[k] for k in ("name", "exit")} for e in log])
        self.assert_cleaned_up_once()

    def test_server_that_hangs_after_host_kill_is_killed_through_its_connection(self):
        def stop(pid):
            with contextlib.suppress(ProcessLookupError):
                os.kill(pid, signal.SIGKILL)

        for mode, pidfd in (("exit", True), ("hang", True), ("hang", False)):
            with self.subTest(mode=mode, pidfd=pidfd), mock.patch.dict(os.__dict__):
                if not pidfd:
                    del os.pidfd_open
                self.evidence = self.evidence.with_name("evidence-" + mode + str(pidfd))
                run = verify.Run(self.evidence)
                try:
                    run.start_server()
                    path = str(run.server / "adb.sock")
                    with REAL_POPEN([sys.executable, "-c", HOST_KILL, path, mode], stdout=subprocess.PIPE) as start:
                        pid = int(start.communicate(timeout=10)[0])
                    self.addCleanup(stop, pid)
                finally:
                    run.close()
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
                log = [json.loads(line) for line in (self.evidence / "commands.jsonl").read_text().splitlines()]
                sweep = [(entry["argv"][0], entry["exit"]) for entry in log if entry["name"] == "adb-sweep"]
                self.assertEqual(sweep, [("host:kill", 0)] if mode == "exit" else [("host:kill", "hung"), ("kill", 0)])
                self.assertEqual(list((verify.STATE / "run").glob("*-*")), [])
                self.assert_lock_free()

    def test_install_needs_flag_and_cleanyourtv_journal(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as refused:
            verify.main(["install", "--evidence", str(self.evidence)])
        self.assertEqual(refused.exception.code, 2)
        (self.root / ".local/home-ABC123.txt").write_bytes(b"original=com.google.android.tvlauncher/.MainActivity\n")
        code, summary = self.run_verify("install", "--allow-launcher-change")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("CleanYourTV", summary["error"])
        self.assertFalse(any("install" in call[3:] or call[-1] == "home" for call in self.tools.calls))
        self.assert_cleaned_up_once()


def find_adb():
    named = os.environ.get("LUMA_TEST_ADB")
    if named:
        return Path(named)
    found = verify.adb_bin() if verify.adb_bin().is_file() else shutil.which("adb")
    return Path(found) if found else None


ADB = find_adb()
OPERATOR = """
import json, sys
from pathlib import Path
config = json.loads(sys.argv[1])
sys.path.insert(0, config["scripts"])
import verify
verify.adb_bin = lambda: Path(config["adb"])
verify.KEYDIR, verify.STATE, verify.LEGACY_PORT = Path(config["keys"]), Path(config["state"]), config["port"]
run = verify.Run(Path(config["evidence"]))
sys.stdin.readline()
try:
    run.start_server()
    print("started", run.server, run.process.pid, flush=True)
    sys.stdin.readline()
except RuntimeError as error:
    print("refused", error, flush=True)
finally:
    run.close()
"""
# Runs main() as an operator would, with a fixture launcher command in place of the TCL steps.
INTERRUPTED = """
import json, sys
from pathlib import Path
config = json.loads(sys.argv[1])
sys.path.insert(0, config["scripts"])
import verify
verify.adb_bin = lambda: Path(config["adb"])
verify.KEYDIR, verify.STATE, verify.LEGACY_PORT = Path(config["keys"]), Path(config["state"]), config["port"]
def live(run, action):
    run.start_server()
    run.command("select-home", ["bash", config["launcher"], config["pids"]], 120)
verify.Run.live = live
sys.exit(verify.main(["doctor", "--evidence", config["evidence"]]))
"""
LAUNCHER = """sleep 300 &
echo "$$ $!" > "$1.part"
mv "$1.part" "$1"
wait
"""


@unittest.skipUnless(ADB, "No adb found. Set LUMA_TEST_ADB to run the real ADB server checks.")
class RealAdbServerTest(unittest.TestCase):
    """Runs real ADB servers on private sockets with scratch keys and device discovery off. Contacts no device."""

    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="luma-adb-")
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.keys, self.state, self.port = self.base / "keys", self.base / "state", free_port()
        self.keys.mkdir()
        self.isolated = {"PATH": "/usr/bin:/bin", "HOME": str(self.base), "TMPDIR": str(self.base),
                         "ANDROID_USER_HOME": str(self.keys), "ADB_VENDOR_KEYS": str(self.keys / "adbkey"),
                         "ADB_MDNS": "0", "ADB_USB": "0", "ADB_EMU": "0", "ADB_MDNS_AUTO_CONNECT": "0"}
        subprocess.run([ADB, "keygen", self.keys / "adbkey"], env=self.isolated, capture_output=True, check=True)
        for name, value in {"KEYDIR": self.keys, "STATE": self.state, "LEGACY_PORT": self.port}.items():
            patcher = mock.patch.object(verify, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(verify, "adb_bin", lambda: ADB)
        patcher.start()
        self.addCleanup(patcher.stop)

    def foreign_server(self, spec):
        subprocess.run([ADB, "-L", spec, "start-server"], env=self.isolated, capture_output=True, check=True)
        self.addCleanup(subprocess.run, [ADB, "-L", spec, "kill-server"], env=self.isolated, capture_output=True)

    def listener(self, path):
        with socket.socket(socket.AF_UNIX) as client:
            client.connect(str(path))
            return verify.peer(client, path)

    def alive(self, pid):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        state = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        return bool(state) and not state.startswith("Z")

    def answers(self, family, address):
        try:
            with socket.socket(family) as client:
                client.settimeout(5)
                client.connect(address)
                client.sendall(b"000chost:version")
                return client.recv(4) == b"OKAY"
        except OSError:
            return False

    def stop_later(self, pid):
        def stop():
            with contextlib.suppress(ProcessLookupError):
                os.kill(pid, signal.SIGKILL)
        self.addCleanup(stop)

    def assert_exits(self, pid):
        for _ in range(100):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.05)
        self.fail("ADB server " + str(pid) + " is still running.")

    def operator(self, name):
        config = {"scripts": str(Path(__file__).resolve().parent), "adb": str(ADB), "keys": str(self.keys),
                  "state": str(self.state), "port": self.port, "evidence": str(self.base / name)}
        child = subprocess.Popen([sys.executable, "-c", OPERATOR, json.dumps(config)], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, text=True)
        for cleanup in (child.stdout.close, child.stdin.close, lambda: child.wait(10), child.kill):
            self.addCleanup(cleanup)
        child.stdin.write("\n")
        child.stdin.flush()
        return child

    def report(self, child):
        word, *rest = child.stdout.readline().split()
        if word == "started":
            self.stop_later(int(rest[1]))
        return word, rest

    def test_closing_a_run_stops_only_its_own_server(self):
        foreign = self.base / "foreign"
        foreign.mkdir()
        self.foreign_server("localfilesystem:" + str(foreign / "adb.sock"))
        self.foreign_server("tcp:localhost:" + str(free_port()))
        run = verify.Run(self.base / "evidence")
        try:
            run.start_server()
            own, pid = run.server / "adb.sock", run.process.pid
            self.assertTrue(self.answers(socket.AF_UNIX, str(own)))
        finally:
            run.close()
        self.assert_exits(pid)
        self.assertFalse(own.parent.exists())
        self.assertTrue(self.answers(socket.AF_UNIX, str(foreign / "adb.sock")))

    def test_server_that_adb_restarts_during_a_run_is_stopped_at_cleanup(self):
        for lost in (False, True):
            with self.subTest(evidence_lost=lost):
                evidence = self.base / ("evidence-" + str(lost))
                run = verify.Run(evidence)
                try:
                    run.start_server()
                    own = run.server / "adb.sock"
                    run.process.kill()
                    run.process.wait()
                    run.command("restart", [ADB, "-L", "localfilesystem:" + str(own), "start-server"])
                    restarted = self.listener(own)
                    self.stop_later(restarted)
                    self.assertNotEqual(restarted, run.process.pid)
                    if lost:
                        shutil.rmtree(evidence)
                finally:
                    if lost:
                        with self.assertRaisesRegex(RuntimeError, "Could not write commands.jsonl"):
                            run.close()
                    else:
                        run.close()
                self.assertFalse(self.alive(restarted))
                self.assertEqual(list((self.state / "run").glob("*-*")), [])
                if not lost:
                    log = [json.loads(line) for line in (evidence / "commands.jsonl").read_text().splitlines()]
                    self.assertIn({"name": "adb-sweep", "argv": ["host:kill", str(own).replace("/adb-", "/old-")],
                                   "pid": restarted, "exit": 0}, log)

    def test_server_reads_only_a_private_copy_of_the_selected_key(self):
        keys = {name: (self.keys / name).read_bytes() for name in ["adbkey", "adbkey.pub"]}
        run = verify.Run(self.base / "evidence")
        try:
            run.start_server()
            home = Path(run.env["HOME"])
            self.assertEqual(home, run.server / "home")
            copies = {str(path.relative_to(home)): path for path in home.rglob("*") if not path.is_dir()}
            self.assertEqual(sorted(copies), [".android/adbkey", ".android/adbkey.pub"])
            for name, path in copies.items():
                self.assertFalse(path.is_symlink(), name)
                self.assertEqual((path.read_bytes(), path.stat().st_mode & 0o777), (keys[Path(name).name], 0o600))
            self.assertEqual({path.stat().st_mode & 0o777 for path in (home, home / ".android")}, {0o700})
            self.assertEqual(self.listener(run.server / "adb.sock"), run.process.pid)
            # If the trusted files disappear and a restarted server finds no key, nothing is created in their place.
            for name in keys:
                (self.keys / name).unlink()
            run.process.kill()
            run.process.wait()
            own = run.server / "adb.sock"
            run.command("restart", [ADB, "-L", "localfilesystem:" + str(own), "start-server"])
            self.stop_later(self.listener(own))
        finally:
            run.close()
        self.assertEqual(list(self.keys.iterdir()), [])

    def test_failed_start_reports_the_daemon_reason(self):
        verify.STATE = self.state = self.base / ("s" * 100)
        run = verify.Run(self.base / "evidence")
        try:
            with self.assertRaisesRegex(RuntimeError, "exited with signal SIGABRT before it was ready: could not "
                                        "install \\*smartsocket\\* listener"):
                run.start_server()
        finally:
            run.close()
        self.assertEqual(list((self.state / "run").glob("*-*")), [])

    def test_reserved_port_refuses_an_old_adb_server_and_its_clients(self):
        run = verify.Run(self.base / "evidence")
        try:
            run.start_server()
            with self.assertRaises(ConnectionRefusedError):
                socket.create_connection(("127.0.0.1", self.port), timeout=5).close()
            old = ["-L", "tcp:localhost:" + str(self.port)]
            self.addCleanup(subprocess.run, [ADB, *old, "kill-server"], env=self.isolated, capture_output=True, timeout=10)
            started = subprocess.run([ADB, *old, "start-server"], env=self.isolated, capture_output=True, timeout=30)
            self.assertNotEqual(started.returncode, 0, started.stderr)
            self.assertFalse(self.answers(socket.AF_INET, ("127.0.0.1", self.port)))
            self.assertFalse(self.answers(socket.AF_INET6, ("::1", self.port)))
        finally:
            run.close()

    def test_old_verifier_server_on_the_fixed_port_is_left_running(self):
        self.foreign_server("tcp:localhost:" + str(self.port))
        run = verify.Run(self.base / "evidence")
        with self.assertRaisesRegex(RuntimeError, "in use .* left running"):
            try:
                run.start_server()
            finally:
                run.close()
        self.assertFalse((run.evidence / "commands.jsonl").exists())
        self.assertTrue(self.answers(socket.AF_INET, ("127.0.0.1", self.port)))

    def test_simultaneous_starts_admit_one_operator(self):
        children = [self.operator("first"), self.operator("second")]
        reports = [self.report(child) for child in children]
        self.assertEqual(sorted(word for word, _ in reports), ["refused", "started"], reports)
        winner = [word for word, _ in reports].index("started")
        loser = 1 - winner
        self.assertIn("Another verifier", " ".join(reports[loser][1]))
        self.assertEqual(children[loser].wait(10), 0)
        own = Path(reports[winner][1][0]) / "adb.sock"
        self.assertTrue(self.answers(socket.AF_UNIX, str(own)))
        children[winner].stdin.write("\n")
        children[winner].stdin.flush()
        self.assertEqual(children[winner].wait(10), 0)
        self.assert_exits(int(reports[winner][1][1]))
        self.assertFalse(own.parent.exists())

    def test_killed_run_releases_the_lock_and_the_next_run_stops_its_server(self):
        foreign = self.base / "foreign"
        foreign.mkdir()
        self.foreign_server("localfilesystem:" + str(foreign / "adb.sock"))
        child = self.operator("killed")
        word, (server, pid) = self.report(child)
        self.assertEqual(word, "started")
        child.send_signal(signal.SIGKILL)
        child.wait(10)
        orphan = Path(server)
        self.assertTrue(self.answers(socket.AF_UNIX, str(orphan / "adb.sock")))
        run = verify.Run(self.base / "evidence")
        try:
            run.start_server()
            self.assert_exits(int(pid))
            self.assertFalse(orphan.exists())
            self.assertTrue(self.answers(socket.AF_UNIX, str(run.server / "adb.sock")))
            self.assertTrue(self.answers(socket.AF_UNIX, str(foreign / "adb.sock")))
        finally:
            run.close()

    def test_interrupted_launcher_command_stops_before_the_lock_is_released(self):
        launcher = self.base / "launcher.sh"
        launcher.write_text(LAUNCHER)
        for number in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal.Signals(number).name):
                evidence, pids = self.base / ("evidence-" + str(number)), self.base / ("pids-" + str(number))
                config = {"scripts": str(Path(__file__).resolve().parent), "adb": str(ADB), "keys": str(self.keys),
                          "state": str(self.state), "port": self.port, "evidence": str(evidence),
                          "launcher": str(launcher), "pids": str(pids)}
                child = subprocess.Popen([sys.executable, "-c", INTERRUPTED, json.dumps(config)], stdout=subprocess.PIPE)
                self.addCleanup(child.kill)
                for _ in range(600):
                    if pids.exists() or child.poll() is not None:
                        break
                    time.sleep(0.05)
                shell, sleeper = map(int, pids.read_text().split())
                start = next(json.loads(line) for line in (evidence / "commands.jsonl").read_text().splitlines()
                             if json.loads(line)["name"] == "adb-start")
                for pid in (shell, sleeper, start["pid"]):
                    self.stop_later(pid)
                    self.assertTrue(self.alive(pid))
                child.send_signal(number)
                with (self.state / "tcl.lock").open("a") as lock:
                    for _ in range(600):
                        try:
                            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                            break
                        except BlockingIOError:
                            time.sleep(0.01)
                    else:
                        self.fail("The interrupted run kept the lock.")
                    self.assertEqual([pid for pid in (shell, sleeper, start["pid"]) if self.alive(pid)], [])
                    self.assertEqual(list((self.state / "run").glob("*-*")), [])
                    fcntl.flock(lock, fcntl.LOCK_UN)
                self.assertEqual(child.wait(10), 1)
                child.stdout.close()
                summary = json.loads((evidence / "summary.json").read_text())
                self.assertEqual((summary["status"], summary["error"]), ("FAIL", "The run was interrupted."), summary)
                self.assertNotIn("cleanup_error", summary)
                log = [json.loads(line)["name"] for line in (evidence / "commands.jsonl").read_text().splitlines()]
                self.assertEqual(log, ["adb-start", "select-home", "adb-stop"])


if __name__ == "__main__":
    unittest.main()
