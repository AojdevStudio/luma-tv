"""Build the APK twice in a scratch copy and prove the second APK holds only current source."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROBE = "dev/luma/tv/StaleProbe"
DESCRIPTOR = ("L" + PROBE + ";").encode()


class RebuildTest(unittest.TestCase):
    def test_rebuild_drops_removed_class(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            shutil.copy2(ROOT / "build.sh", work)
            shutil.copy2(ROOT / "AndroidManifest.xml", work)
            shutil.copytree(ROOT / "res", work / "res")
            shutil.copytree(ROOT / "src", work / "src")
            probe = work / "src" / (PROBE + ".java")
            probe.write_text("package dev.luma.tv;\nfinal class StaleProbe {}\n")

            def build():
                subprocess.run(["bash", "build.sh"], cwd=work, check=True,
                               env={**os.environ, "LUMA_KEYSTORE": ".signing/local.keystore"})
                with zipfile.ZipFile(work / "dist/luma-tv.apk") as apk:
                    return [apk.read(name) for name in apk.namelist()
                            if re.fullmatch(r"classes(?:[0-9]+)?\.dex", name)]

            self.assertTrue(any(DESCRIPTOR in dex for dex in build()), "first APK lacks the probe class")
            key = (work / ".signing/local.keystore").read_bytes()

            probe.unlink()
            dex_files = build()
            self.assertTrue(any(b"Ldev/luma/tv/MainActivity;" in dex for dex in dex_files),
                            "rebuilt APK lacks the launcher activity")
            self.assertFalse(any(DESCRIPTOR in dex for dex in dex_files),
                             "rebuilt APK still holds the removed class")
            self.assertFalse((work / "build/classes" / (PROBE + ".class")).exists())
            self.assertNotIn(PROBE, (work / "build/classes.txt").read_text())
            self.assertEqual(key, (work / ".signing/local.keystore").read_bytes())


if __name__ == "__main__":
    unittest.main()
