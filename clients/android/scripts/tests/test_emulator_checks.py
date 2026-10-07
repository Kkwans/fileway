"""Host orchestration contracts; these tests do not prove Android behavior."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import os

spec = importlib.util.spec_from_file_location("emulator_checks", Path(__file__).parents[1] / "run-emulator-checks.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EmulatorChecksTest(unittest.TestCase):
    def exercise(self, suite=0, prepare="OK (1 test)\n", verify="OK (1 test)\n", export=0):
        calls = []

        def run(command, cwd, timeout):
            calls.append(command)
            if "getprop" in command:
                return 0, "1\n"
            if command == ["adb", "devices"]:
                return 0, "List of devices attached\nemulator-5556\tdevice\n"
            if ":app:connectedDebugAndroidTest" in command:
                return suite, "suite result\n"
            if "instrument" in command:
                return 0, prepare if runner.RESTART_CLASSES[0] in command else verify
            if "pull" in command:
                return export, "export result\n"
            return 0, "Success\n"

        with tempfile.TemporaryDirectory(dir=os.environ.get("NFB_CHECK_TMP")) as root:
            result = runner.run_checks(Path(root), "emulator-5556", run)
        return result, calls

    def test_restart_gate_runs_in_two_invocations_with_stop_between(self):
        result, calls = self.exercise()
        self.assertEqual(0, result)
        prepare = next(i for i, c in enumerate(calls) if runner.RESTART_CLASSES[0] in c)
        stopped = next(i for i, c in enumerate(calls) if "force-stop" in c)
        verify = next(i for i, c in enumerate(calls) if runner.RESTART_CLASSES[1] in c)
        self.assertLess(prepare, stopped)
        self.assertLess(stopped, verify)
        suite = next(c for c in calls if ":app:connectedDebugAndroidTest" in c)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.notClass=" + ",".join(runner.RESTART_CLASSES), suite)
        for command in calls:
            if command[0] == "adb" and command != ["adb", "devices"]:
                self.assertEqual(["-s", "emulator-5556"], command[1:3])
            self.assertNotIn("uninstall", command)
            self.assertNotIn("clear", command)

    def test_suite_failure_is_preserved_while_restart_and_export_still_run(self):
        result, calls = self.exercise(suite=7, verify="FAILURES!!!\n", export=9)
        self.assertEqual(7, result)
        self.assertTrue(any(runner.RESTART_CLASSES[1] in c for c in calls))
        self.assertEqual("pull", calls[-1][3])

    def test_adb_zero_exit_is_not_a_passing_instrumentation_result(self):
        for result in ["FAILURES!!!\n", "INSTRUMENTATION_FAILED: process crashed\n", "OK (0 tests)\n", ""]:
            with self.subTest(result=result):
                status, calls = self.exercise(prepare=result)
                self.assertEqual(1, status)
                self.assertTrue(any(runner.RESTART_CLASSES[1] in c for c in calls))

    def test_evidence_export_failure_fails_an_otherwise_passing_run(self):
        result, _ = self.exercise(export=9)
        self.assertEqual(9, result)

    def test_observer_timeout_does_not_start_a_second_device_job(self):
        result, calls = self.exercise(suite=124)
        self.assertEqual(124, result)
        self.assertFalse(any("install" in c or "instrument" in c or "force-stop" in c for c in calls))
        self.assertEqual("pull", calls[-1][3])

    def test_phone_and_unverified_emulator_are_rejected_before_mutations(self):
        calls = []

        def physical(command, cwd, timeout):
            calls.append(command)
            return 0, "\n"

        for serial in ["personal-phone", "emulator-5556"]:
            with self.subTest(serial=serial):
                with self.assertRaises(ValueError):
                    runner.run_checks(Path("."), serial, physical)
        self.assertEqual(1, len(calls))
        self.assertIn("getprop", calls[0])

    def test_an_additional_device_blocks_gradle_and_install(self):
        calls = []

        def run(command, cwd, timeout):
            calls.append(command)
            if "getprop" in command:
                return 0, "1\n"
            return 0, "List of devices attached\nemulator-5556\tdevice\npersonal-phone\tdevice\n"

        with self.assertRaises(ValueError):
            runner.run_checks(Path("."), "emulator-5556", run)
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
