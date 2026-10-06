import importlib.util
import io
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load("prepare_vlc", "prepare-vlc-font-backend.py")


class SourceSafetyTest(unittest.TestCase):
    def archive(self, folder, entries):
        path = folder / "source.tar.gz"
        with tarfile.open(path, "w:gz") as output:
            for name, content, link in entries:
                member = tarfile.TarInfo(name)
                if link:
                    member.type = tarfile.SYMTYPE
                    member.linkname = link
                    output.addfile(member)
                else:
                    member.size = len(content)
                    member.mode = 0o4755
                    output.addfile(member, io.BytesIO(content))
        return path

    def test_existing_workspace_is_preserved_before_download(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            workspace = root / "existing"
            workspace.mkdir()
            sentinel = workspace / "user-file"
            sentinel.write_bytes(b"keep")
            with self.assertRaisesRegex(ValueError, "existing"):
                prepare.prepare(workspace, root / "cache")
            self.assertEqual(b"keep", sentinel.read_bytes())
            self.assertFalse((root / "cache").exists())

    def test_checkout_workspace_and_cache_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "outside"):
                prepare.prepare(prepare.REPOSITORY / "not-created", Path(folder))
            with self.assertRaisesRegex(ValueError, "outside"):
                prepare.prepare(Path(folder) / "not-created", prepare.REPOSITORY / "not-created")

    def test_corrupt_cache_is_not_overwritten_or_downloaded(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / "locked.tar.gz"
            archive.write_bytes(b"bad-cache-to-preserve")
            with self.assertRaisesRegex(ValueError, "checksum"):
                prepare.archive_for({"root": "locked", "sha256": "0" * 64}, root)
            self.assertEqual(b"bad-cache-to-preserve", archive.read_bytes())

    def test_traversal_is_rejected_before_writing_any_member(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = self.archive(root, [("source/valid", b"valid", None), ("source/../../escape", b"bad", None)])
            destination = root / "output"
            destination.mkdir()
            with self.assertRaisesRegex(ValueError, "path"):
                prepare.extract(archive, destination, "source")
            self.assertEqual([], list(destination.iterdir()))
            self.assertFalse((root / "escape").exists())

    def test_escaping_symlink_is_rejected_before_any_member(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = self.archive(root, [("source/valid", b"valid", None), ("source/link", b"", "../../escape")])
            destination = root / "output"
            destination.mkdir()
            with self.assertRaisesRegex(ValueError, "regular"):
                prepare.extract(archive, destination, "source")
            self.assertEqual([], list(destination.iterdir()))

    def test_regular_extraction_keeps_content_and_exec_without_setuid(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = self.archive(root, [("source/build.sh", b"#!/bin/sh\nexit 0\n", None)])
            destination = root / "output"
            destination.mkdir()
            extracted = prepare.extract(archive, destination, "source") / "build.sh"
            self.assertEqual(b"#!/bin/sh\nexit 0\n", extracted.read_bytes())
            self.assertEqual(0o755, stat.S_IMODE(extracted.stat().st_mode))


if __name__ == "__main__":
    unittest.main()
