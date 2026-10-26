"""Run the installer against a local Android transport fixture, with no device."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


FAKE_ADB = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

path = Path(os.environ["LUMA_TEST_DEVICE"])
device = json.loads(path.read_text())
args = sys.argv[1:]
assert args[:2] == ["-s", "test-tv"], args
command = args[2:]
if command[:1] == ["install"]:
    device["installed"] = True
    device["home"] = "android/com.android.internal.app.ResolverActivity"
    print("Success")
elif command[:1] == ["shell"]:
    shell = command[1:]
    if shell == ["getprop", "ro.serialno"]:
        print("test-tv")
    elif shell == ["cmd", "package", "resolve-activity", "--brief", "-a",
                   "android.intent.action.MAIN", "-c", "android.intent.category.HOME"]:
        print(device["home"])
    elif shell[:3] == ["pm", "list", "packages"]:
        package = shell[-1]
        if package == "dev.luma.tv" and device["installed"]:
            print("package:" + package)
        elif package == "com.google.android.tvlauncher" and "stock_enabled" in device:
            if "-d" not in shell or not device["stock_enabled"]:
                print("package:" + package)
    elif shell == ["pm", "enable", "--user", "0", "com.google.android.tvlauncher"]:
        device["stock_enabled"] = True
    elif shell[:5] == ["cmd", "package", "set-home-activity", "--user", "0"]:
        if device.get("reject_home") == shell[5]:
            sys.exit("Android rejected HOME selection")
        device["home"] = shell[5]
    elif shell == ["dumpsys", "power"]:
        print("mWakefulness=Asleep")
    else:
        sys.exit("Unexpected device command: " + repr(shell))
else:
    sys.exit("Unexpected ADB command: " + repr(command))
path.write_text(json.dumps(device))
'''


class LauncherRestoreTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="luma-test-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        shutil.copy2(Path(__file__).resolve().parents[1] / "luma.sh", self.root)
        (self.root / "dist").mkdir()
        (self.root / "dist/luma-tv.apk").touch()
        (self.root / "bin").mkdir()
        adb = self.root / "bin/adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        self.original = "com.gathin.tvhome/.MainActivity"
        self.device_file = self.root / "device.json"
        self.device_file.write_text(json.dumps({"home": self.original, "installed": False}))
        self.env = {"PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
                    "LUMA_TEST_DEVICE": str(self.device_file)}
        self.journal = self.root / ".local/home-testtv.txt"

    def run_action(self, action):
        result = subprocess.run(["bash", "luma.sh", "-s", "test-tv", action],
                                cwd=self.root, env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_install_home_and_restore_preserve_original_launcher(self):
        self.run_action("install")
        self.assertTrue(self.journal.is_file(), "Save HOME before installation resets it")
        self.assertEqual(self.journal.read_text(), "original=" + self.original + "\n")
        self.assertEqual(json.loads(self.device_file.read_text())["home"],
                         "android/com.android.internal.app.ResolverActivity")
        self.run_action("home")
        self.assertEqual(json.loads(self.device_file.read_text())["home"], "dev.luma.tv/.MainActivity")
        self.run_action("install")
        self.run_action("home")
        self.assertEqual(self.journal.read_text(), "original=" + self.original + "\n")
        self.run_action("restore")
        device = json.loads(self.device_file.read_text())
        self.assertEqual(device["home"], self.original)
        self.assertTrue(device["installed"])
        self.assertFalse(self.journal.exists())

    def test_update_without_journal_restores_disabled_stock_launcher(self):
        self.device_file.write_text(json.dumps({"home": "dev.luma.tv/.MainActivity",
                                               "installed": True, "stock_enabled": False}))
        self.run_action("install")
        self.run_action("restore")
        self.assertTrue(json.loads(self.device_file.read_text())["stock_enabled"])
        self.assertFalse(self.journal.exists())

    def test_failed_restore_keeps_journal_and_reports_failure(self):
        self.run_action("install")
        self.run_action("home")
        saved = self.journal.read_bytes()
        device = json.loads(self.device_file.read_text())
        device["reject_home"] = self.original
        self.device_file.write_text(json.dumps(device))
        result = subprocess.run(["bash", "luma.sh", "-s", "test-tv", "restore"],
                                cwd=self.root, env=self.env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.journal.read_bytes(), saved)
        self.assertEqual(json.loads(self.device_file.read_text())["home"], "dev.luma.tv/.MainActivity")

    def test_empty_home_stops_install_without_journal(self):
        self.device_file.write_text(json.dumps({"home": "", "installed": False}))
        result = subprocess.run(["bash", "luma.sh", "-s", "test-tv", "install"],
                                cwd=self.root, env=self.env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(json.loads(self.device_file.read_text())["installed"])
        self.assertFalse(self.journal.exists())


if __name__ == "__main__":
    unittest.main()
