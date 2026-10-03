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
ASS_PATCH = REPOSITORY / "native/patches/libass-0.17.5-android-provider.patch"
ASS_PROVIDER = REPOSITORY / "native/ass_android_font_provider.h"
SUBTITLE_PATCH = REPOSITORY / "native/patches/libvlc-3.7.6-subtitle-cache.patch"
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
    if "ASS_VERSION := 0.17.5" not in (vlc / "contrib/src/ass/rules.mak").read_text():
        raise ValueError("Android ASS provider only supports the reviewed libass 0.17.5 source")
    patches = sorted((sdk / "libvlc/patches").glob("*.patch"))
    if len(patches) != 20:
        raise ValueError("Unexpected SDK patch series")
    applied = []
    for patch in [*patches, PATCH, SUBTITLE_PATCH]:
        if patch == SUBTITLE_PATCH:
            # Retain the actual upstream callbacks for an expected-failing
            # regression comparison, without preparing a second source tree.
            shutil.copyfile(vlc / "modules/video_output/android/display.c",
                            workspace / "subtitle-cache-baseline.c")
        # git apply works on this external archive tree without a Git repository.
        # Check exact context first; do not use reset/am/rebase or fuzzy patching.
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=vlc, check=True)
        subprocess.run(["git", "apply", str(patch)], cwd=vlc, check=True)
        applied.append({"name": patch.name, "sha256": sha256(patch)})
    # Contrib verifies the upstream libass tarball with its pinned SHA512 rule.
    # These two owned inputs are copied to that build rule, not to an existing
    # app dependency. Include both in the derivative's provenance and revision.
    for source, destination in ((ASS_PATCH, "nfb-android-provider.patch"),
                                (ASS_PROVIDER, "nfb_android_font_provider.h")):
        shutil.copyfile(source, vlc / "contrib/src/ass" / destination)
        applied.append({"name": source.name, "sha256": sha256(source)})
    # VLC's supported archive-build fallback reads src/revision.txt when Git
    # describe is unavailable. Identify this derivative honestly instead of
    # manufacturing a Git repository or claiming an upstream release hash.
    derivative = hashlib.sha256(json.dumps(applied, sort_keys=True).encode()).hexdigest()
    revision = lock["vlc"]["commit"][:12] + "-sdk-" + lock["sdk"]["commit"][:12] + "-nfb-" + derivative[:12]
    (vlc / "src/revision.txt").write_text(revision + "\n")
    manifest = {"lock": lock, "patches": applied, "archiveRevision": revision,
                "subtitleCacheBaselineSha256": sha256(workspace / "subtitle-cache-baseline.c"),
                "status": "prepared-not-built"}
    (workspace / "source-provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return sdk


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--archive-dir", required=True, type=Path)
    args = parser.parse_args()
    print(prepare(args.workspace, args.archive_dir))
