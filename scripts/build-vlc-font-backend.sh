#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abi="${1:?Specify arm64-v8a or x86_64}"
workspace="${2:?Specify a new external workspace}"
archive_dir="${3:?Specify an external source archive cache}"
case "$abi" in arm64-v8a|x86_64) ;; *) echo 'Unsupported test ABI' >&2; exit 2 ;; esac
test "$(uname -s)" = Linux
test "$(uname -m)" = x86_64
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
test -x "$ndk_root/ndk-build"
grep -q '^Pkg.Revision = 28.2.13676358$' "$ndk_root/source.properties"
for command in git python3 make autoconf automake autopoint libtoolize pkg-config cmake meson ninja; do
  command -v "$command" >/dev/null
done
python3 "$repo_root/scripts/prepare-vlc-font-backend.py" --workspace "$workspace" --archive-dir "$archive_dir"
workspace="$(cd "$workspace" && pwd)"
cd "$workspace/sdk"
# The archive is already prepared with every upstream patch. Do not invoke
# get-vlc.sh: its default path resets Git history and commits the patch series.
# Build contribs from the locked source rules; avoid floating prebuilt bundles.
ANDROID_NDK="$ndk_root" RELEASE=1 bash buildsystem/compile-libvlc.sh -a "$abi" --release --license g
python3 "$repo_root/scripts/package-vlc-font-backend.py" "$workspace" "$abi" "$ndk_root"
