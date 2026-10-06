#!/usr/bin/env python3
"""Exercise the pinned emulator runner's independent `sh -c` lines.

Only shell ordering/status/export is tested here, never Android playback.
"""
import os
from pathlib import Path
import subprocess
import tempfile


def commands():
    workflow = Path(__file__).resolve().parents[1] / ".github/workflows/android.yml"
    block = workflow.read_text().split("          script: |\n", 1)[1].split("      - uses:", 1)[0]
    return [line[12:] for line in block.splitlines() if line.strip()]


def check(api, target, page_size, test_status, export_status, expected, should_test=True):
    with tempfile.TemporaryDirectory(prefix="nfb-emulator-script-", dir=os.environ.get("NFB_CHECK_TMP")) as folder:
        root = Path(folder)
        (root / "gradlew").write_text('#!/bin/sh\n: > test-called\nexit "$NFB_FAKE_TEST_STATUS"\n')
        (root / "adb").write_text('''#!/bin/sh
case "$1" in
  shell) printf '%s\n' "$NFB_FAKE_PAGESIZE" ;;
  pull) test -f test-called || exit 91; : > export-called; exit "$NFB_FAKE_EXPORT_STATUS" ;;
  *) exit 92 ;;
esac
''')
        (root / "gradlew").chmod(0o700)
        (root / "adb").chmod(0o700)
        env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                   NFB_FAKE_TEST_STATUS=str(test_status), NFB_FAKE_EXPORT_STATUS=str(export_status), NFB_FAKE_PAGESIZE=str(page_size))
        status = 0
        for line in commands():
            line = line.replace("${{ matrix.api }}", api).replace("${{ matrix.target }}", target)
            status = subprocess.run(["sh", "-c", line], cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode
            if status:
                break
        assert status == expected, (api, page_size, test_status, export_status, status, expected)
        assert (root / "test-called").exists() == should_test
        assert (root / "export-called").exists() == should_test


if __name__ == "__main__":
    for api, target, pages in [("35", "google_apis", 4096), ("37.0", "google_apis_ps16k", 16384)]:
        for tests, export, expected in [(0, 0, 0), (7, 0, 7), (0, 1, 1), (7, 1, 1)]:
            check(api, target, pages, tests, export, expected)
    check("37.0", "google_apis_ps16k", 4096, 0, 0, 1, should_test=False)
    print("9 emulator-script ordering, export, status and page-size checks passed")
