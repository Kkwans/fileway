#!/usr/bin/env python3
"""Prepare pinned Media3 audio sources and Android FFmpeg static libraries.

Linux only; the caller supplies an approved absolute work root and installed NDK.
No tool installation, product dependency changes, or global cache/data cleanup.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "native/media3-ffmpeg.lock.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_owned(path, data, mode=0o644):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.is_symlink() or path.read_bytes() != data:
            raise ValueError(f"Existing input differs; preserve it and choose a new work root: {path}")
        return
    temporary = path.with_name(path.name + f".part-{os.getpid()}")
    try:
        with temporary.open("xb") as output:
            output.write(data)
        temporary.chmod(mode & 0o755)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def download(entry, destination, supplied=None):
    if destination.is_file():
        data = destination.read_bytes()
    elif supplied is not None:
        data = supplied.read_bytes()
    else:
        with urllib.request.urlopen(entry["url"], timeout=60) as response:
            data = response.read(64 * 1024 * 1024 + 1)
    if len(data) > 64 * 1024 * 1024 or digest(data) != entry["sha256"]:
        raise ValueError(f"Pinned source hash mismatch: {entry['url']}")
    write_owned(destination, data)


def extract_regular(archive, destination, expected_root):
    """The pinned release contains only files/directories; reject links and escapes."""
    with tarfile.open(archive) as source:
        members = source.getmembers()
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] != expected_root:
                raise ValueError("Unsafe source archive path")
            if not (member.isfile() or member.isdir()):
                raise ValueError("Links and special files are not accepted in the source archive")
            if not (destination / member.name).resolve().is_relative_to(destination.resolve()):
                raise ValueError("Source destination escapes the owned work root")
        for member in members:
            output = destination / member.name
            if member.isdir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                with source.extractfile(member) as content:
                    write_owned(output, content.read(), member.mode)


def configure_arguments(source, prefix, toolchain, abi, lock):
    arch, triple = {"arm64-v8a": ("aarch64", "aarch64-linux-android"),
                    "x86_64": ("x86_64", "x86_64-linux-android")}[abi]
    api = lock["android"]["minSdk"]
    args = [str(source / "configure"), f"--prefix={prefix}", "--target-os=android",
            f"--arch={arch}", "--enable-cross-compile", "--enable-static", "--disable-shared",
            "--enable-pic", "--disable-debug", "--disable-doc", "--disable-programs",
            "--disable-everything", "--disable-autodetect", "--disable-network",
            "--disable-avdevice", "--disable-avformat", "--disable-avfilter", "--disable-swscale",
            "--enable-avcodec", "--enable-avutil", "--enable-swresample",
            "--disable-gpl", "--disable-version3", "--disable-nonfree",
            f"--cc={toolchain / (triple + str(api) + '-clang')}",
            f"--cxx={toolchain / (triple + str(api) + '-clang++')}"]
    for name in ["ar", "nm", "ranlib", "strip"]:
        args.append(f"--{name}={toolchain / ('llvm-' + name)}")
    # Match upstream's x86 portability choice. ARM64 keeps its assembly paths;
    # emulator timing is never the physical-device performance gate.
    if abi == "x86_64":
        args.append("--disable-asm")
    return args + [f"--enable-decoder={decoder}" for decoder in lock["decoders"]]


def verify_configuration(build, lock):
    config = (build / "config.h").read_text()
    for option in ["GPL", "VERSION3", "NONFREE"]:
        if f"#define CONFIG_{option} 0" not in config:
            raise ValueError(f"Unapproved FFmpeg license configuration: {option}")
    components = (build / "config_components.h").read_text()
    for decoder in lock["decoders"]:
        if f"#define CONFIG_{decoder.upper()}_DECODER 1" not in components:
            raise ValueError(f"Requested decoder was not enabled: {decoder}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-root", type=Path, required=True)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--ffmpeg-archive", type=Path, help="Optional already downloaded, hash-checked archive")
    parser.add_argument("--fetch-only", action="store_true")
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if not args.work_root.is_absolute() or args.work_root.resolve() == Path.home() or len(args.work_root.parts) < 4:
        parser.error("Use an explicitly approved absolute task/build directory")
    if not 1 <= args.jobs <= 16:
        parser.error("jobs must be between 1 and 16")
    if not args.fetch_only and (platform.system() != "Linux" or platform.machine() != "x86_64"):
        parser.error("Native compilation requires an installed Linux x86_64 Android NDK")
    if not args.fetch_only and args.ndk is None:
        parser.error("--ndk is required; this script never installs a toolchain")
    work = args.work_root.resolve()
    lock_bytes = LOCK.read_bytes()
    lock = json.loads(lock_bytes)
    work.mkdir(parents=True, exist_ok=True)
    with (work / ".build.lock").open("a+") as mutex:
        fcntl.flock(mutex, fcntl.LOCK_EX | fcntl.LOCK_NB)
        write_owned(work / "input-lock.json", lock_bytes)
        for entry in lock["media3"]["files"]:
            relative = PurePosixPath(entry["path"])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Invalid locked source path")
            download(entry, work / "sources" / entry["path"])
        archive = work / "downloads" / f"ffmpeg-{lock['ffmpeg']['version']}.tar.xz"
        download(lock["ffmpeg"], archive, args.ffmpeg_archive)
        extract_regular(archive, work / "sources/upstream", lock["ffmpeg"]["archiveRoot"])
        source = work / "sources/upstream" / lock["ffmpeg"]["archiveRoot"]
        write_owned(work / "sources/licenses/ffmpeg-LGPL-2.1.txt", (source / "COPYING.LGPLv2.1").read_bytes())
        if args.fetch_only:
            print("Pinned audio sources verified; no native build performed")
            return
        ndk = args.ndk.resolve()
        if f"Pkg.Revision = {lock['android']['ndk']}" not in (ndk / "source.properties").read_text():
            raise ValueError("Installed NDK does not match the pinned revision")
        notices = [path for path in ndk.glob("NOTICE*") if path.is_file()]
        if not notices:
            raise ValueError("Installed NDK lacks its redistribution notices")
        for notice in notices:
            write_owned(work / "sources/licenses" / ("android-ndk-" + notice.name + ".txt"), notice.read_bytes())
        toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        for abi in lock["android"]["abis"]:
            build = work / "build" / abi
            build.mkdir(parents=True, exist_ok=True)
            command = configure_arguments(source, work / "prefix" / abi, toolchain, abi, lock)
            subprocess.run(command, cwd=build, check=True)
            verify_configuration(build, lock)
            subprocess.run(["make", f"-j{args.jobs}"], cwd=build, check=True)
            subprocess.run(["make", "install"], cwd=build, check=True)
        info = {"schema": 1, "inputLockSha256": digest(lock_bytes), "recipeSha256": digest(Path(__file__).read_bytes()),
                "media3Version": lock["media3"]["version"], "ffmpegVersion": lock["ffmpeg"]["version"],
                "abis": lock["android"]["abis"], "ndk": lock["android"]["ndk"],
                "toolchainNotices": ["android-ndk-" + path.name + ".txt" for path in notices], "runtimeVerified": False}
        (work / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
        print("FFmpeg audio archives built; package JNI/AAR and run the device gates next")


if __name__ == "__main__":
    main()
