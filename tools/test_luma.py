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
    elif shell == ["pm", "list", "packages", "dev.luma.tv"]:
        if device["installed"]:
            print("package:dev.luma.tv")
    elif shell[:5] == ["cmd", "package", "set-home-activity", "--user", "0"]:
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
    def test_install_home_and_restore_preserve_original_launcher(self):
        with tempfile.TemporaryDirectory(prefix="luma-test-") as directory:
            root = Path(directory)
            shutil.copy2(Path(__file__).resolve().parents[1] / "luma.sh", root)
            (root / "dist").mkdir()
            (root / "dist/luma-tv.apk").touch()
            (root / "bin").mkdir()
            adb = root / "bin/adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o755)
            original = "com.gathin.tvhome/.MainActivity"
            device_file = root / "device.json"
            device_file.write_text(json.dumps({"home": original, "installed": False}))
            env = dict(os.environ, PATH=str(root / "bin") + os.pathsep + os.environ["PATH"],
                       LUMA_TEST_DEVICE=str(device_file))
            journal = root / ".local/home-testtv.txt"

            def run(action):
                result = subprocess.run(["bash", "luma.sh", "-s", "test-tv", action],
                                        cwd=root, env=env, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

            run("install")
            self.assertTrue(journal.is_file(), "Save HOME before installation resets it")
            self.assertEqual(journal.read_text(), "original=" + original + "\n")
            self.assertEqual(json.loads(device_file.read_text())["home"],
                             "android/com.android.internal.app.ResolverActivity")
            run("home")
            self.assertEqual(json.loads(device_file.read_text())["home"], "dev.luma.tv/.MainActivity")
            run("install")
            run("home")
            self.assertEqual(journal.read_text(), "original=" + original + "\n")
            run("restore")
            device = json.loads(device_file.read_text())
            self.assertEqual(device["home"], original)
            self.assertTrue(device["installed"])
            self.assertFalse(journal.exists())


if __name__ == "__main__":
    unittest.main()
