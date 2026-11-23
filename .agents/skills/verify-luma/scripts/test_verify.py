import contextlib
import errno
import fcntl
import io
import json
import os
from pathlib import Path
import re
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
        self.state, self.timeout = b"device\n", None
        self.on_home = None

    def __call__(self, argv, cwd, env, capture_output, timeout):
        self.calls.append(argv)
        self.envs.append(env)
        if argv[-1] == self.timeout:
            raise subprocess.TimeoutExpired(argv, timeout, output=b"partial")
        out, code = b"", 0
        if argv[:3] == ["bash", "luma.sh", "build"]:
            (self.root / "dist").mkdir(exist_ok=True)
            (self.root / "dist/luma-tv.apk").write_bytes(b"apk")
        elif argv[0].endswith("apksigner"):
            out = b"Verifies\nSigner #1 certificate SHA-256 digest: abc123\n"
        elif argv[0].endswith("adb"):
            out, code = self.adb(argv[argv.index("-L") + 2:])
        return subprocess.CompletedProcess(argv, code, out, b"")

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
        patcher = mock.patch.object(verify.subprocess, "run", self.tools)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_verify(self, *args):
        with contextlib.redirect_stdout(io.StringIO()):
            code = verify.main([*args, "--evidence", str(self.evidence)])
        return code, json.loads((self.evidence / "summary.json").read_text())

    def assert_cleaned_up_once(self):
        start = next(call for call in self.tools.calls if call[-1] == "start-server")
        stops = [call for call in self.tools.calls if call[-1] == "kill-server"]
        self.assertEqual(stops, [start[:3] + ["kill-server"]])
        self.assertEqual(self.tools.calls[-1][-1], "kill-server")
        self.assertEqual(list(verify.STATE.glob("adb-*")), [])
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
        self.assertRegex(server[2], "^localfilesystem:" + str(verify.STATE) + "/adb-[^/]+/adb.sock$")
        for call in self.tools.adb_calls():
            self.assertEqual(call[:3], server)
            self.assertTrue(call[3:] in (["start-server"], ["connect", verify.TARGET], ["kill-server"])
                            or call[3:5] == ["-s", verify.TARGET], call)
        allowed = {"PATH", "JAVA_HOME", "LUMA_JDK", "ANDROID_SDK_ROOT", "LUMA_KEYSTORE", "ANDROID_USER_HOME",
                   "ADB_VENDOR_KEYS", "ADB_MDNS_AUTO_CONNECT", "ADB_SERVER_SOCKET", "PYTHONDONTWRITEBYTECODE",
                   "TMPDIR", "ADB_MDNS", "ADB_USB", "ADB_EMU"}
        for env in self.tools.envs:
            self.assertLessEqual(set(env), allowed)
        self.assertEqual(self.tools.envs[-1]["ADB_VENDOR_KEYS"], str(self.keys / "adbkey"))
        self.assertEqual(self.tools.envs[-1]["ADB_SERVER_SOCKET"], server[2])
        self.assertEqual({self.tools.envs[-1][name] for name in ["ADB_MDNS", "ADB_USB", "ADB_EMU"]}, {"0"})
        evidence = b"".join(path.read_bytes() for path in self.evidence.rglob("*") if path.is_file())
        self.assertNotIn(b"sentinel-value", evidence)

    def test_missing_adb_key_refuses_before_any_adb(self):
        (self.keys / "adbkey").unlink()
        code, summary = self.run_verify("doctor")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("No key was created", summary["error"])
        self.assertEqual(self.tools.adb_calls(), [])
        self.assertFalse((self.keys / "adbkey").exists())

    def test_missing_signing_key_refuses_build(self):
        (self.root / ".signing/local.keystore").unlink()
        code, summary = self.run_verify("build")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("refused to create a credential", summary["error"])
        self.assertEqual(self.tools.calls, [])
        self.assertFalse((self.root / ".signing/local.keystore").exists())

    def test_doctor_can_run_after_the_previous_listener_closed(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", verify.LEGACY_PORT))
            listener.listen()
            with socket.create_connection(listener.getsockname()) as client:
                connection, _ = listener.accept()
                connection.close()
                self.assertEqual(client.recv(1), b"")
        code, summary = self.run_verify("doctor")
        self.assertEqual((code, summary["status"]), (0, "PASS"), summary)

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

    def test_wildcard_listener_is_never_contacted(self):
        with socket.socket() as foreign:
            foreign.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            foreign.bind(("0.0.0.0", verify.LEGACY_PORT))
            foreign.listen()
            self.run_verify("doctor")
            self.assert_left_running(foreign)

    def test_foreign_bound_port_is_in_use_before_adb_starts(self):
        with socket.socket() as foreign:
            foreign.bind(("127.0.0.1", verify.LEGACY_PORT))
            code, summary = self.run_verify("doctor")
            self.assertEqual((code, summary["status"]), (1, "FAIL"), summary)
            self.assertIn("in use", summary["error"])
            self.assertEqual(self.tools.adb_calls(), [])

    def test_server_that_has_bound_but_not_listened_cannot_share_the_port(self):
        with socket.socket() as foreign:
            foreign.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            foreign.bind(("127.0.0.1", verify.LEGACY_PORT))
            run = verify.Run(self.evidence)
            try:
                run.start_server()
            except RuntimeError as error:
                self.assertIn("in use", str(error))
            else:
                with self.assertRaises(OSError) as refused:
                    foreign.listen()
                self.assertEqual(refused.exception.errno, errno.EADDRINUSE)
            finally:
                run.close()

    def test_older_verifier_checks_refuse_while_a_run_holds_the_port(self):
        run = verify.Run(self.evidence)
        try:
            run.start_server()
            with socket.create_connection(("127.0.0.1", verify.LEGACY_PORT)):
                pass
            for reuse in (0, 1):
                with self.subTest(reuse=reuse), socket.socket() as old:
                    old.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, reuse)
                    with self.assertRaises(OSError) as refused:
                        old.bind(("127.0.0.1", verify.LEGACY_PORT))
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
            self.assertEqual([call[-1] for call in self.tools.adb_calls()], ["start-server"])
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
    print("started", run.server, flush=True)
    sys.stdin.readline()
except RuntimeError as error:
    print("refused", error, flush=True)
finally:
    run.close()
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

    def answers(self, family, address):
        try:
            with socket.socket(family) as client:
                client.settimeout(5)
                client.connect(address)
                client.sendall(b"000chost:version")
                return client.recv(4) == b"OKAY"
        except OSError:
            return False

    def server_pid(self, evidence):
        log = next(evidence.glob("adb.*.log")).read_text()
        pid = int(re.findall(r"--- adb starting \(pid (\d+)\) ---", log)[-1])
        self.addCleanup(self.stop, pid)
        return pid

    def stop(self, pid):
        with contextlib.suppress(ProcessLookupError):
            os.kill(pid, signal.SIGKILL)

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
        self.addCleanup(child.wait, 10)
        self.addCleanup(child.kill)
        return child

    def test_closing_a_run_stops_only_its_own_server(self):
        foreign = self.base / "foreign"
        foreign.mkdir()
        self.foreign_server("localfilesystem:" + str(foreign / "adb.sock"))
        self.foreign_server("tcp:localhost:" + str(free_port()))
        run = verify.Run(self.base / "evidence")
        try:
            run.start_server()
            own = run.server / "adb.sock"
            self.assertTrue(self.answers(socket.AF_UNIX, str(own)))
            pid = self.server_pid(run.evidence)
        finally:
            run.close()
        self.assert_exits(pid)
        self.assertFalse(own.parent.exists())
        self.assertTrue(self.answers(socket.AF_UNIX, str(foreign / "adb.sock")))

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
        for child in children:
            child.stdin.write("\n")
            child.stdin.flush()
        lines = [child.stdout.readline().split(" ", 1) for child in children]
        self.assertEqual(sorted(word for word, _ in lines), ["refused", "started"], lines)
        winner = children[[word for word, _ in lines].index("started")]
        loser = children[[word for word, _ in lines].index("refused")]
        self.assertIn("Another verifier", lines[children.index(loser)][1])
        self.assertEqual(loser.wait(10), 0)
        own = Path(lines[children.index(winner)][1].strip()) / "adb.sock"
        self.assertTrue(self.answers(socket.AF_UNIX, str(own)))
        winner.stdin.write("\n")
        winner.stdin.flush()
        self.assertEqual(winner.wait(10), 0)
        self.assertFalse(own.parent.exists())

    def test_killed_run_releases_the_lock_and_the_next_run_stops_its_server(self):
        child = self.operator("killed")
        child.stdin.write("\n")
        child.stdin.flush()
        word, server = child.stdout.readline().split(" ", 1)
        self.assertEqual(word, "started")
        orphan = Path(server.strip())
        pid = self.server_pid(self.base / "killed")
        child.send_signal(signal.SIGKILL)
        child.wait(10)
        self.assertTrue(self.answers(socket.AF_UNIX, str(orphan / "adb.sock")))
        run = verify.Run(self.base / "evidence")
        try:
            run.start_server()
            self.assert_exits(pid)
            self.assertFalse(orphan.exists())
            self.assertTrue(self.answers(socket.AF_UNIX, str(run.server / "adb.sock")))
        finally:
            run.close()


if __name__ == "__main__":
    unittest.main()
