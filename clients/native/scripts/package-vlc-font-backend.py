#!/usr/bin/env python3
"""Collect native trial outputs and verify their ABI and 16 KiB LOAD alignment."""
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys


def inspect_elf(path, machine):
    with path.open("rb") as source:
        header = source.read(64)
        if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", header, 16) != (3, machine):
            raise ValueError("Unexpected native ELF ABI: " + path.name)
        offset = struct.unpack_from("<Q", header, 32)[0]
        stride, count = struct.unpack_from("<HH", header, 54)
        if stride != 56 or not count:
            raise ValueError("Invalid ELF program headers")
        alignments = []
        for index in range(count):
            source.seek(offset + index * stride)
            entry = source.read(stride)
            if len(entry) != stride:
                raise ValueError("Truncated ELF")
            if struct.unpack_from("<I", entry)[0] == 1:
                alignment = struct.unpack_from("<Q", entry, 48)[0]
                if alignment < 16384 or alignment & (alignment - 1):
                    raise ValueError("Native library lacks 16 KiB LOAD alignment: " + path.name)
                file_offset, address = struct.unpack_from("<QQ", entry, 8)
                if file_offset % alignment != address % alignment:
                    raise ValueError("Incongruent ELF LOAD segment")
                alignments.append(alignment)
        if not alignments:
            raise ValueError("No ELF LOAD segments")
        return alignments


def collect(workspace, abi, ndk):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    libraries = workspace / "sdk/libvlc/jni/libs" / abi
    output = workspace / "artifact" / abi
    manifest = json.loads((workspace / "source-provenance.json").read_text())
    manifest.update(status="compiled-trial-not-integrated", abi=abi,
                    ndk=(ndk / "source.properties").read_text(), libraries=[])
    for name in ("libvlc.so", "libvlcjni.so", "libc++_shared.so"):
        path = libraries / name
        alignments = inspect_elf(path, machine)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest["libraries"].append({"name": name, "sha256": digest, "loadAlignment": alignments})
    clang = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    manifest["compiler"] = subprocess.check_output([clang, "--version"], text=True).strip()
    output.mkdir(parents=True, exist_ok=False)
    for item in manifest["libraries"]:
        shutil.copyfile(libraries / item["name"], output / item["name"])
    (output / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(output)


if __name__ == "__main__":
    collect(Path(sys.argv[1]).resolve(), sys.argv[2], Path(sys.argv[3]).resolve())
