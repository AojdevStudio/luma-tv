import contextlib
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
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
            out, code = self.adb(argv[argv.index("-P") + 2:])
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
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        self.tools = FakeTools(self.root)
        for name, value in {"ROOT": self.root, "KEYDIR": self.keys, "PORT": port, "SETTLE": 0}.items():
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
        stops = [call for call in self.tools.calls if call[-1] == "kill-server"]
        self.assertEqual(stops, [[str(verify.adb_bin()), "-P", str(verify.PORT), "kill-server"]])
        self.assertEqual(self.tools.calls[-1][-1], "kill-server")

    def test_safe_run_passes_with_launcher_suite(self):
        (self.root / "tools/test_luma.py").write_bytes(b"")
        code, summary = self.run_verify("safe")
        self.assertEqual((code, summary["status"]), (0, "PASS"), summary)
        self.assertEqual(set(summary["checks"]), {"source_build", "device_names", "capabilities", "weather_location", "commands", "voice_requests", "health_snapshot", "capability_panel", "home_presets", "profiles", "media_context", "offline_launcher", "doctor",
                                                  "launch_home_and_baseline", "source_unchanged"})
        self.assertEqual(summary["checks"]["source_build"]["cert_sha256"], "abc123")
        self.assertEqual(summary["observed"]["installed"]["versionName"], "2.4")
        self.assertTrue((self.evidence / "screen-before.png").is_file() and (self.evidence / "screen.png").is_file())
        self.assert_cleaned_up_once()

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
        for call in self.tools.adb_calls():
            self.assertTrue(call[3:] in (["start-server"], ["connect", verify.TARGET], ["kill-server"])
                            or call[3:5] == ["-s", verify.TARGET], call)
        allowed = {"PATH", "JAVA_HOME", "LUMA_JDK", "ANDROID_SDK_ROOT", "LUMA_KEYSTORE", "ANDROID_USER_HOME",
                   "ADB_VENDOR_KEYS", "ADB_MDNS_AUTO_CONNECT", "ADB_SERVER_SOCKET", "PYTHONDONTWRITEBYTECODE"}
        for env in self.tools.envs:
            self.assertLessEqual(set(env), allowed)
        self.assertEqual(self.tools.envs[-1]["ADB_VENDOR_KEYS"], str(self.keys / "adbkey"))
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

    def test_busy_port_leaves_foreign_server_alone(self):
        with socket.socket() as foreign:
            foreign.bind(("127.0.0.1", verify.PORT))
            foreign.listen()
            code, summary = self.run_verify("doctor")
        self.assertEqual(summary["status"], "FAIL")
        self.assertIn("left running", summary["error"])
        self.assertEqual(self.tools.adb_calls(), [])

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


if __name__ == "__main__":
    unittest.main()
