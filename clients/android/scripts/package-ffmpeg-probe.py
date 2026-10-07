#!/usr/bin/env python3
"""Verify both JNI ABIs and package a comparison AAR with corresponding sources."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import tempfile
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_elf(data, abi):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 JNI library")
    if struct.unpack_from("<H", data, 16)[0] != 3:
        raise ValueError("JNI payload is not a shared ELF object")
    if struct.unpack_from("<H", data, 18)[0] != {"arm64-v8a": 183, "x86_64": 62}[abi]:
        raise ValueError("JNI library ABI mismatch")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or not count or offset + count * size > len(data):
        raise ValueError("Invalid ELF program header table")
    loads = 0
    for index in range(count):
        base = offset + index * size
        if struct.unpack_from("<I", data, base)[0] != 1:
            continue
        loads += 1
        file_offset, address = struct.unpack_from("<QQ", data, base + 8)
        alignment = struct.unpack_from("<Q", data, base + 48)[0]
        if alignment < 16384 or (file_offset - address) % 16384:
            raise ValueError("JNI LOAD segment is not 16 KiB compatible")
    if not loads:
        raise ValueError("JNI library has no LOAD segments")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--work-root", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    work = args.work_root.resolve()
    output = args.output_dir.resolve()
    if not args.output_dir.is_absolute() or output == Path.home() or len(output.parts) < 4:
        parser.error("Use an explicitly approved absolute artifact directory")
    output.mkdir(parents=True, exist_ok=True)
    lock = json.loads((root / "native/media3-ffmpeg.lock.json").read_text())
    info = json.loads((work / "build-info.json").read_text())
    if info["inputLockSha256"] != sha(root / "native/media3-ffmpeg.lock.json"):
        raise ValueError("Native build inputs differ from this checkout")
    readelf = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    libraries = []
    with zipfile.ZipFile(args.aar) as archive, tempfile.TemporaryDirectory(dir=output) as folder:
        expected = {f"jni/{abi}/libffmpegJNI.so" for abi in lock["android"]["abis"]}
        if {name for name in archive.namelist() if name.endswith(".so")} != expected:
            raise ValueError("Unexpected or missing native libraries in the probe AAR")
        for abi in lock["android"]["abis"]:
            data = archive.read(f"jni/{abi}/libffmpegJNI.so")
            verify_elf(data, abi)
            library = Path(folder) / f"{abi}.so"
            library.write_bytes(data)
            symbols = subprocess.check_output([str(readelf), "--dyn-syms", "--wide", str(library)], text=True)
            names = [line.split()[-1] for line in symbols.splitlines() if line.split() and line.split()[0].endswith(":")]
            if "JNI_OnLoad" not in names or any(name.startswith(("av_", "avcodec_", "avpriv_", "swr_", "ff_")) for name in names):
                raise ValueError("JNI entry point missing or FFmpeg symbols are exposed to other engines")
            libraries.append({"abi": abi, "sha256": hashlib.sha256(data).hexdigest(), "loadAlignment": 16384})
        for license_name in ["media3-APACHE-2.0.txt", "ffmpeg-LGPL-2.1.txt", *info["toolchainNotices"]]:
            if archive.read("assets/" + license_name) != (work / "sources/licenses" / license_name).read_bytes():
                raise ValueError("Native probe license is missing or altered")
    target = output / "fileway-media3-ffmpeg-probe.aar"
    shutil.copyfile(args.aar, target)
    source_bundle = output / "fileway-media3-ffmpeg-sources.tar.gz"
    with tarfile.open(source_bundle, "w:gz") as archive:
        archive.add(work / "downloads", arcname="downloads")
        archive.add(work / "sources/media3", arcname="media3")
        archive.add(work / "sources/licenses", arcname="licenses")
        archive.add(work / "input-lock.json", arcname="input-lock.json")
        for name in ["build.gradle.kts", "CMakeLists.txt", "consumer-rules.pro", "src/main/AndroidManifest.xml"]:
            archive.add(root / "ffmpeg-probe" / name, arcname="recipe/ffmpeg-probe/" + name)
        archive.add(root / "native/media3-ffmpeg.lock.json", arcname="recipe/native/media3-ffmpeg.lock.json")
        for script in ["prepare-ffmpeg-probe.py", "package-ffmpeg-probe.py"]:
            archive.add(root / "scripts" / script, arcname="recipe/scripts/" + script)
        for abi in lock["android"]["abis"]:
            archive.add(work / "build" / abi / "config.h", arcname=f"configuration/{abi}/config.h")
            archive.add(work / "build" / abi / "config_components.h", arcname=f"configuration/{abi}/config_components.h")
    info.update({"aarFile": target.name, "aarSha256": sha(target), "sourcesFile": source_bundle.name,
                 "sourcesSha256": sha(source_bundle), "nativeLibraries": libraries, "packagingVerified": True,
                 "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()})
    (output / "ffmpeg-probe.json").write_text(json.dumps(info, indent=2) + "\n")
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()
