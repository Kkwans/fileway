"""Resolve the pinned MSYS2 UCRT64 libmpv PE runtime without installing MSYS2.

All package bytes, extracted metadata, DLLs, and source bundles live outside Git.
The lock file records the exact packages actually selected by PE imports.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import urllib.request
import urllib.error
import pefile

PIN = "aca9ce7359c63caa33612d160edad059fd931e1c434a87d6ca01f5af015cac76"
PACKAGE = "mingw-w64-ucrt-x86_64-mpv-0.41.0-8-any.pkg.tar.zst"
BASE = "https://mirror.msys2.org/mingw/ucrt64/"


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(url, path, expected=None):
    if path.exists() and (expected is None or digest(path) == expected):
        return path
    temporary = path.with_suffix(path.suffix + ".partial")
    try:
        response = urllib.request.urlopen(url, timeout=60)
    except urllib.error.HTTPError as error:
        if error.code != 404 or not url.startswith(BASE):
            raise
        response = urllib.request.urlopen(url.replace("mirror.msys2.org", "repo.msys2.org", 1), timeout=60)
    with response, open(temporary, "wb") as output:
        shutil.copyfileobj(response, output)
    if expected and digest(temporary) != expected:
        raise RuntimeError("Package hash mismatch: " + path.name)
    temporary.replace(path)
    return path


def fields(path):
    values, key = {}, None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("%") and line.endswith("%"):
            key = line.strip("%")
            values[key] = []
        elif line and key:
            values[key].append(line)
    return values


def imports(path):
    image = pefile.PE(str(path), fast_load=True)
    image.parse_data_directories(directories=[1, 13])
    names = {entry.dll.decode("ascii").lower() for entry in getattr(image, "DIRECTORY_ENTRY_IMPORT", [])}
    names.update(entry.dll.decode("ascii").lower() for entry in getattr(image, "DIRECTORY_ENTRY_DELAY_IMPORT", []))
    image.close()
    return sorted(names)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts-root", required=True)
    parser.add_argument("--lock", required=True)
    args = parser.parse_args()
    root = pathlib.Path(args.artifacts_root).resolve()
    cache = root / "media-packages"
    runtime = root / "media-runtime" / "x64"
    metadata = cache / "repository-metadata"
    cache.mkdir(parents=True, exist_ok=True)
    runtime.mkdir(parents=True, exist_ok=True)
    database = download(BASE + "ucrt64.files", cache / "ucrt64.files")
    if not metadata.exists():
        metadata.mkdir()
        subprocess.run(["tar.exe", "-xf", str(database), "-C", str(metadata)], check=True)
    index, packages = {}, {}
    for description in metadata.glob("*/desc"):
        item = fields(description)
        name = item["NAME"][0]
        if not name.startswith("mingw-w64-ucrt-x86_64-"):
            continue
        packages[name] = item
        for filename in fields(description.parent / "files").get("FILES", []):
            if filename.lower().startswith("ucrt64/bin/") and filename.lower().endswith(".dll"):
                index[pathlib.PurePosixPath(filename).name.lower()] = (name, filename)
    selected, locations = {}, {}

    def extract(name, item):
        filename = item["FILENAME"][0]
        print("fetch", filename, flush=True)
        package = download(BASE + filename, cache / filename, item["SHA256SUM"][0])
        destination = cache / "extracted" / (name + "-" + item["VERSION"][0])
        if not destination.exists():
            destination.mkdir(parents=True)
            subprocess.run(["tar.exe", "-xf", str(package), "-C", str(destination)], check=True)
        selected[name] = {"name": name, "version": item["VERSION"][0], "packageUrl": BASE + filename,
                          "sha256": item["SHA256SUM"][0], "licenses": item.get("LICENSE", []),
                          "packageBase": item.get("BASE", [name])[0], "metadataDirectory": str(destination)}
        locations[name] = destination
        print("package", name, item["VERSION"][0], flush=True)

    pinned = download(BASE + PACKAGE, cache / PACKAGE, PIN)
    mpv = cache / "mpv"
    if not mpv.exists():
        mpv.mkdir()
        subprocess.run(["tar.exe", "-xf", str(pinned), "-C", str(mpv)], check=True)
    selected["mingw-w64-ucrt-x86_64-mpv"] = {"name": "mingw-w64-ucrt-x86_64-mpv", "version": "0.41.0-8",
        "packageUrl": BASE + PACKAGE, "sha256": PIN, "licenses": ["GPL-2.0-or-later"], "packageBase": "mingw-w64-mpv", "metadataDirectory": str(mpv)}
    locations["mingw-w64-ucrt-x86_64-mpv"] = mpv
    pending = [mpv / "ucrt64" / "bin" / "libmpv-2.dll"]
    resolved, system = {}, set()
    system32 = pathlib.Path(os.environ["SystemRoot"]) / "System32"
    while pending:
        needed = {}
        for dll in pending:
            basename = dll.name.lower()
            if basename in resolved:
                continue
            dependencies = imports(dll)
            shutil.copy2(dll, runtime / dll.name)
            resolved[basename] = {"file": dll.name, "sha256": digest(dll), "imports": dependencies}
            for dependency in dependencies:
                if dependency not in index and (dependency.startswith(("api-ms-", "ext-ms-")) or (system32 / dependency).exists()):
                    system.add(dependency)
                    continue
                if dependency in resolved:
                    continue
                if dependency not in index:
                    raise RuntimeError("Unresolved third-party PE import: " + dependency + " from " + basename)
                name, path = index[dependency]
                needed[dependency] = (name, path)
        missing = {name for name, _ in needed.values() if name not in locations}
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
            futures = [executor.submit(extract, name, packages[name]) for name in sorted(missing)]
            for future in futures:
                future.result()
        pending = [locations[name] / pathlib.PurePosixPath(path) for name, path in needed.values()]
    notices = runtime / "licenses"
    notices.mkdir(exist_ok=True)
    for name, location in locations.items():
        licenses = location / "ucrt64" / "share" / "licenses"
        if licenses.exists():
            shutil.copytree(licenses, notices / name, dirs_exist_ok=True)
        for item in (".PKGINFO", ".BUILDINFO"):
            source = location / item
            if source.exists():
                target = root / "media-provenance" / name
                target.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target / item)
        package = selected[name]
        package["sourceArchiveUrl"] = "https://mirror.msys2.org/mingw/sources/" + package["packageBase"] + "-" + package["version"] + ".src.tar.zst"
        package.pop("metadataDirectory")
    lock = {"mpvVersion": "0.41.0-8", "architecture": "ucrt64-x86_64", "repositoryDatabaseSha256": digest(database),
            "packages": sorted(selected.values(), key=lambda item: item["name"]), "runtimeFiles": sorted(resolved.values(), key=lambda item: item["file"]),
            "systemImports": sorted(system), "redistributionApproved": False, "sourceArchivesDownloaded": False}
    pathlib.Path(args.lock).write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    (runtime / "runtime-lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print("runtime", runtime, "dlls", len(resolved), "packages", len(selected), flush=True)


if __name__ == "__main__":
    main()
