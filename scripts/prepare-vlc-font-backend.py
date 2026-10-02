#!/usr/bin/env python3
"""Prepare checksum-locked upstream sources in a new, external workspace.

Apply the SDK's complete patch series before the Android font selector patch.
This does not build, replace app dependencies, rewrite Git history or enroll a
network node. A failed workspace is retained for diagnosis.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

REPOSITORY = Path(__file__).resolve().parents[1]
LOCK = REPOSITORY / "native/vlc-font-backend.lock.json"
PATCH = REPOSITORY / "native/patches/libvlc-3.7.6-android-fonts.patch"
MAX_ARCHIVE = 128 << 20
MAX_EXTRACTED = 512 << 20


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def archive_for(spec, cache):
    archive = cache / (spec["root"] + ".tar.gz")
    if archive.exists():
        if sha256(archive) != spec["sha256"]:
            raise ValueError("Cached source archive checksum mismatch: " + archive.name)
        return archive
    if not spec["url"].startswith("https://code.videolan.org/"):
        raise ValueError("Unexpected upstream source URL")
    # Never replace a bad cache or an existing source tree. The temporary file
    # belongs only to this download and is removed when verification fails.
    with tempfile.NamedTemporaryFile(dir=cache, prefix="nfb-vlc-download-") as temporary:
        size = 0
        with urllib.request.urlopen(spec["url"], timeout=60) as source:
            while chunk := source.read(1 << 20):
                size += len(chunk)
                if size > MAX_ARCHIVE:
                    raise ValueError("Upstream archive exceeds size bound")
                temporary.write(chunk)
        temporary.flush()
        if sha256(Path(temporary.name)) != spec["sha256"]:
            raise ValueError("Downloaded source archive checksum mismatch")
        # Exclusive creation also prevents concurrent callers overwriting a
        # checksum-verified cache. Each CI job normally has its own cache.
        with archive.open("xb") as destination, Path(temporary.name).open("rb") as source:
            while chunk := source.read(1 << 20):
                destination.write(chunk)
    return archive


def extract(archive, parent, root):
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 20000 or sum(item.size for item in members) > MAX_EXTRACTED:
            raise ValueError("Source archive exceeds extraction size bound")
        for item in members:
            path = PurePosixPath(item.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != root:
                raise ValueError("Unexpected source archive path")
            if not (item.isfile() or item.isdir()):
                raise ValueError("Source archives must contain only regular files and directories")
        # These pinned Git archives contain no links. Reject links/devices in
        # the complete preflight instead of relying on extractall's security
        # defaults, which differ across host Python versions.
        for item in members:
            target = parent / item.name
            if item.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(item) as content, target.open("xb") as destination:
                    shutil.copyfileobj(content, destination)
                target.chmod(item.mode & 0o755)
    return parent / root


def prepare(workspace, cache):
    workspace, cache = workspace.resolve(), cache.resolve()
    if any(path == REPOSITORY or REPOSITORY in path.parents for path in (workspace, cache)):
        raise ValueError("Native workspace and archive cache must be outside the client checkout")
    if workspace.exists():
        raise ValueError("Refusing to modify an existing native build workspace")
    lock = json.loads(LOCK.read_text())
    cache.mkdir(parents=True, exist_ok=True)
    archives = {key: archive_for(lock[key], cache) for key in ("sdk", "vlc")}
    workspace.mkdir(parents=True)
    sdk = extract(archives["sdk"], workspace, lock["sdk"]["root"])
    sdk.rename(workspace / "sdk")
    sdk = workspace / "sdk"
    vlc = extract(archives["vlc"], workspace, lock["vlc"]["root"])
    vlc.rename(sdk / "vlc")
    vlc = sdk / "vlc"
    if "libvlcVersion = '3.7.6'" not in (sdk / "build.gradle").read_text():
        raise ValueError("SDK version does not match the app dependency")
    if "VLC_TESTED_HASH=" + lock["vlc"]["commit"] not in (sdk / "buildsystem/get-vlc.sh").read_text():
        raise ValueError("SDK tested VLC commit does not match the lock")
    patches = sorted((sdk / "libvlc/patches").glob("*.patch"))
    if len(patches) != 20:
        raise ValueError("Unexpected SDK patch series")
    applied = []
    for patch in [*patches, PATCH]:
        # git apply works on this external archive tree without a Git repository.
        # Check exact context first; do not use reset/am/rebase or fuzzy patching.
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=vlc, check=True)
        subprocess.run(["git", "apply", str(patch)], cwd=vlc, check=True)
        applied.append({"name": patch.name, "sha256": sha256(patch)})
    manifest = {"lock": lock, "patches": applied, "status": "prepared-not-built"}
    (workspace / "source-provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return sdk


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--archive-dir", required=True, type=Path)
    args = parser.parse_args()
    print(prepare(args.workspace, args.archive_dir))
