#!/usr/bin/env python3
"""Run the full suite and host-managed restart gates on a disposable emulator.

UTP removes the target package after connected tests. Reinstall the already built
APKs before the two restart invocations; never use this runner on a personal phone.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = "io.github.kkwans.nasfilebrowser"
RESTART_PACKAGE = f"{PACKAGE}.hostrestart"
RESTART_CLASSES = [f"{RESTART_PACKAGE}.ThemeRestartPrepareTest", f"{RESTART_PACKAGE}.ThemeRestartVerifyTest"]
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"


def suite_contract(component):
    reports = list((component / "app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
    if not reports:
        return 1, "Full suite has no JUnit report\n"
    cases = []
    try:
        for report in reports:
            cases.extend(ET.parse(report).getroot().iter("testcase"))
    except (ET.ParseError, OSError) as error:
        return 1, f"Unreadable full-suite JUnit: {type(error).__name__}\n"
    if not cases:
        return 1, "Full suite executed no reported test cases\n"
    misplaced = [case.get("classname", "") for case in cases
                 if case.get("classname", "").startswith(RESTART_PACKAGE + ".")
                 or case.get("classname", "") in [f"{PACKAGE}.ThemeRestartPrepareTest", f"{PACKAGE}.ThemeRestartVerifyTest"]]
    if misplaced:
        return 1, "Host restart tests leaked into the ordinary suite: " + ", ".join(sorted(set(misplaced))) + "\n"
    failed = sum(case.find("failure") is not None or case.find("error") is not None for case in cases)
    skipped = sum(case.find("skipped") is not None for case in cases)
    if failed or skipped:
        return 1, f"Full-suite JUnit reports {failed} failed and {skipped} unverified/skipped cases\n"
    return 0, f"Full suite reported {len(cases)} cases; host restart tests are isolated\n"


def run_command(command, cwd, timeout):
    try:
        result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=timeout)
        return result.returncode, result.stdout
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        return 124, output + "\nHost deadline exceeded\n"


def run_checks(component, serial, run=run_command):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("This runner requires an explicitly selected disposable emulator")
    adb = ["adb", "-s", serial]
    status, output = run(adb + ["shell", "getprop", "ro.kernel.qemu"], component, 30)
    if status or output.strip() != "1":
        raise ValueError("Refusing to install or run tests on a non-emulator target")
    status, output = run(["adb", "devices"], component, 30)
    devices = [line.split() for line in output.splitlines()[1:] if line.strip()]
    if status or devices != [[serial, "device"]]:
        raise ValueError("Connected tests require this emulator to be the only attached device")

    evidence = component / "app/build/acceptance"
    evidence.mkdir(parents=True, exist_ok=True)
    first_failure = 0

    def check(name, command, timeout=120, instrumentation=False):
        nonlocal first_failure
        status, output = run(command, component, timeout)
        if instrumentation and (not re.search(r"(?m)^OK \(1 test\)\s*$", output)
                                or re.search(r"(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED)", output)):
            status = status or 1
        (evidence / f"{name}.txt").write_text(output, encoding="utf-8")
        print(f"{name}: exit {status}", flush=True)
        if status and not first_failure:
            first_failure = status
        return status

    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    suite_status = check("connected-suite", [wrapper, ":app:connectedDebugAndroidTest", "--stacktrace",
          # AGP/UTP discovery did not preserve the second comma-separated notClass
          # exclusion. A dedicated package is one unambiguous argument value.
          "-Pandroid.testInstrumentationRunnerArguments.notPackage=" + RESTART_PACKAGE], 1200)
    if suite_status == 124:
        # Losing the Gradle observer does not prove device instrumentation ended.
        # Preserve evidence without racing another install/test against it.
        check("evidence-export", adb + ["pull", "/sdcard/Download/nfb-client-acceptance", str(evidence)])
        return first_failure

    contract_status, contract_output = suite_contract(component)
    (evidence / "suite-contract.txt").write_text(contract_output, encoding="utf-8")
    print(f"suite-contract: exit {contract_status}", flush=True)
    if contract_status and not first_failure:
        first_failure = contract_status

    # No fresh install or data clearing: the restart gate must retain the saved
    # theme in app-private storage across an actual process stop.
    installed = True
    for name, apk in [("target-install", "app/build/outputs/apk/debug/app-debug.apk"),
                      ("tests-install", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
        if check(name, adb + ["install", "-r", apk]):
            installed = False
            break
    if installed:
        check("theme-restart-prepare", adb + ["shell", "am", "instrument", "-w", "-r",
              "-e", "class", RESTART_CLASSES[0], RUNNER], instrumentation=True)
        stopped = check("theme-force-stop", adb + ["shell", "am", "force-stop", PACKAGE])
        # The verifier also restores the original preference after a failed gate.
        # Run it after preparation failure so partial setup can recover its state.
        if not stopped:
            check("theme-restart-verify", adb + ["shell", "am", "instrument", "-w", "-r",
                  "-e", "class", RESTART_CLASSES[1], RUNNER], instrumentation=True)

    check("evidence-export", adb + ["pull", "/sdcard/Download/nfb-client-acceptance", str(evidence)])
    return first_failure


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    raise SystemExit(run_checks(Path(__file__).resolve().parents[1], args.serial))
