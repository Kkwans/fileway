"""Artifact/input safety contracts; these tests do not prove native decoding."""
import importlib.util
import io
import os
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).parents[1] / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load('prepare-ffmpeg-probe')
package = load('package-ffmpeg-probe')


class ProbeInputsTest(unittest.TestCase):
    def test_existing_different_input_is_preserved(self):
        with tempfile.TemporaryDirectory(dir=os.environ['NFB_CHECK_TMP']) as folder:
            path = Path(folder) / 'existing'
            path.write_bytes(b'preserve this')
            with self.assertRaises(ValueError):
                prepare.write_owned(path, b'replacement')
            self.assertEqual(b'preserve this', path.read_bytes())

    def test_archive_escapes_and_links_are_rejected_before_any_extraction(self):
        for name, symlink in [('root/../escape', False), ('/absolute', False), ('root/link', True)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory(dir=os.environ['NFB_CHECK_TMP']) as folder:
                root = Path(folder)
                archive = root / 'source.tar'
                with tarfile.open(archive, 'w') as output:
                    first = tarfile.TarInfo('root/valid'); first.size = 1
                    output.addfile(first, io.BytesIO(b'x'))
                    bad = tarfile.TarInfo(name)
                    if symlink:
                        bad.type = tarfile.SYMTYPE; bad.linkname = '/outside'
                    output.addfile(bad)
                with self.assertRaises(ValueError):
                    prepare.extract_regular(archive, root / 'out', 'root')
                self.assertFalse((root / 'out').exists())

    def test_valid_source_retains_executable_mode_and_repeated_extraction_checks_content(self):
        with tempfile.TemporaryDirectory(dir=os.environ['NFB_CHECK_TMP']) as folder:
            root = Path(folder); archive = root / 'source.tar'
            with tarfile.open(archive, 'w') as output:
                item = tarfile.TarInfo('root/configure'); item.mode = 0o755; item.size = 2
                output.addfile(item, io.BytesIO(b'ok'))
            prepare.extract_regular(archive, root / 'out', 'root')
            path = root / 'out/root/configure'
            self.assertTrue(path.stat().st_mode & 0o100)
            prepare.extract_regular(archive, root / 'out', 'root')
            path.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                prepare.extract_regular(archive, root / 'out', 'root')
            self.assertEqual(b'changed', path.read_bytes())


class ProbeElfTest(unittest.TestCase):
    def elf(self, machine=183, alignment=16384, address=0):
        value = bytearray(120)
        value[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', value, 16, 3, machine)
        struct.pack_into('<Q', value, 32, 64)
        struct.pack_into('<HH', value, 54, 56, 1)
        struct.pack_into('<I', value, 64, 1)
        struct.pack_into('<Q', value, 80, address)
        struct.pack_into('<Q', value, 112, alignment)
        return value

    def test_both_64_bit_abis_require_aligned_load_segments(self):
        for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
            package.verify_elf(self.elf(machine), abi)
            package.verify_elf(self.elf(machine, alignment=65536), abi)
            for alignment, address in [(4096, 0), (16384, 4096)]:
                with self.assertRaises(ValueError):
                    package.verify_elf(self.elf(machine, alignment, address), abi)

    def test_wrong_abi_and_truncated_headers_are_rejected(self):
        for data in [self.elf(machine=62), b'\x7fELF', self.elf()[:100]]:
            with self.assertRaises(ValueError):
                package.verify_elf(data, 'arm64-v8a')


if __name__ == '__main__':
    unittest.main()
