#!/usr/bin/env python3
"""Isolated image-script tests; fake Docker never builds, logs in or pushes."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent


class ImagePolicy(unittest.TestCase):
    def run_script(self, name, *args, env=None):
        return subprocess.run(['bash', str(ROOT / name), *args],
                              env=env, text=True, capture_output=True)

    def test_tags(self):
        cases = {'nas-file-browser:2026.10.6-v1': True,
                 'nas-file-browser:2026.10.6-v2-rc1': True,
                 'registry.example.com/team/nas-file-browser:test-20261006-1': True,
                 'nas-file-browser': False, 'nas-file-browser:latest': False,
                 'nas-file-browser:': False, 'BadName:2026.10.6-v1': False,
                 'nas-file-browser:v1': False}
        for ref, valid in cases.items():
            with self.subTest(ref=ref):
                self.assertEqual(self.run_script('check-image-tag.sh', ref).returncode == 0, valid)

    def exercise(self, script, scenario='ok', revision='a' * 40, tag='2026.10.6-v1'):
        with tempfile.TemporaryDirectory(prefix='nfb-policy-test-') as folder:
            base = Path(folder)
            docker = base / 'docker'
            docker.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$NFB_TEST_LOG"
if [[ "$NFB_TEST_SCENARIO" == fail-build && "$*" == *"build"* ]]; then exit 19; fi
if [[ "$1 $2" == "image inspect" ]]; then
  if [[ "$NFB_TEST_SCENARIO" == bad-label ]]; then echo mismatch; else
    echo "$IMAGE_REVISION|$IMAGE_VERSION|$IMAGE_CREATED"
  fi
fi
''')
            docker.chmod(0o755)
            env = os.environ.copy()
            env.update(PATH=str(base) + os.pathsep + env['PATH'], IMAGE_TAG=tag,
                       IMAGE_VERSION=tag.rsplit(':', 1)[-1], IMAGE_REVISION=revision,
                       IMAGE_CREATED='2026-10-06T00:00:00Z',
                       NFB_TEST_LOG=str(base / 'calls'), NFB_TEST_SCENARIO=scenario)
            result = self.run_script(script, tag, env=env)
            calls = (base / 'calls').read_text() if (base / 'calls').exists() else ''
            return result, calls

    def test_build_success(self):
        result, calls = self.exercise('build.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        for command in ['build --platform', 'SERVER_SOURCE=source-artifact', 'image inspect']:
            self.assertIn(command, calls)
        self.assertNotIn('push', calls)

    def test_fail_closed(self):
        for name in ['build.sh', 'push.sh']:
            cases = [dict(revision='unknown'), dict(tag='latest'), dict(scenario='bad-label')]
            if name == 'build.sh':
                cases.append(dict(scenario='fail-build'))
            for kwargs in cases:
                with self.subTest(name=name, **kwargs):
                    result, calls = self.exercise(name, **kwargs)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn('login', calls)
                    self.assertNotIn('push ', calls)

    def test_invalid_profile_and_platform_do_not_build(self):
        for values in [{'FILEWAY_MEDIA_PROFILE': 'invalid'}, {'FILEWAY_PLATFORM': 'windows/amd64'}]:
            env = os.environ.copy()
            env.update(values, IMAGE_REVISION='a' * 40)
            result = self.run_script('build.sh', '2026.10.6-v1', env=env)
            self.assertNotEqual(result.returncode, 0)

    def test_push_explicit_tag_only(self):
        result, calls = self.exercise('push.sh', tag='fileway-linux:2026.10.6-v1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('login', calls)
        self.assertIn('push ', calls)
        self.assertNotIn(':latest', calls)
        self.assertNotIn('prune', calls)


if __name__ == '__main__':
    unittest.main()
