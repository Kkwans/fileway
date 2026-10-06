#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abi="${1:?Specify arm64-v8a or x86_64}"
workspace="${2:?Specify a new external workspace}"
archive_dir="${3:?Specify an external source archive cache}"
fail() { printf '%s\n' "$*" >&2; exit 1; }
case "$abi" in arm64-v8a|x86_64) ;; *) echo 'Unsupported test ABI' >&2; exit 2 ;; esac
test "$(uname -s)" = Linux || fail 'Native source build requires Linux'
test "$(uname -m)" = x86_64 || fail 'Native source build requires an x64 host'
# A runner's ambient ANDROID_NDK_HOME may point to a different preinstalled
# version. Resolve the task SDK, or an explicit task-owned override, instead.
ndk_root="${NFB_VLC_NDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
printf 'Selected native NDK: %s\n' "$ndk_root"
test -x "$ndk_root/ndk-build" || fail 'Selected NDK has no executable ndk-build'
ndk_revision="$(python3 - "$ndk_root/source.properties" <<'PY'
from pathlib import Path
import sys
properties = dict(line.split('=', 1) for line in Path(sys.argv[1]).read_text().splitlines() if '=' in line)
print(next((value.strip() for key, value in properties.items() if key.strip() == 'Pkg.Revision'), 'missing'))
PY
)"
printf 'Selected native NDK revision: %s\n' "$ndk_revision"
test "$ndk_revision" = 28.2.13676358 || fail 'Selected NDK differs from the locked version'
for command in git python3 cc c++ make autoconf automake autopoint libtoolize pkg-config cmake meson ninja; do
  command -v "$command" >/dev/null || fail "Missing source-build tool: $command"
done
python3 "$repo_root/scripts/prepare-vlc-font-backend.py" --workspace "$workspace" --archive-dir "$archive_dir"
workspace="$(cd "$workspace" && pwd)"
python3 "$repo_root/scripts/generate-subtitle-cache-test.py" "$workspace" "$workspace/subtitle-cache-test.c"
cc -std=gnu99 -Wall -Wextra -Werror "$workspace/subtitle-cache-test.c" -o "$workspace/subtitle-cache-test"
"$workspace/subtitle-cache-test"
python3 "$repo_root/scripts/generate-subtitle-cache-test.py" "$workspace" "$workspace/subtitle-cache-original.c" --baseline --baseline-source "$workspace/subtitle-cache-baseline.c"
cc -std=gnu99 -Wall -Wextra -Werror "$workspace/subtitle-cache-original.c" -o "$workspace/subtitle-cache-original"
baseline_status=0
"$workspace/subtitle-cache-original" >"$workspace/subtitle-cache-original-result.txt" 2>&1 || baseline_status=$?
test "$baseline_status" = 1 || fail 'Original callbacks did not reproduce the expected cache failure'
grep -q 'FAIL.*blends == 2.*rgba\[0\] == 127' "$workspace/subtitle-cache-original-result.txt" || fail 'Original callbacks failed for an unexpected reason'
python3 "$repo_root/scripts/generate-subtitle-seek-test.py" "$workspace/sdk/vlc/modules/demux/mkv/matroska_segment_seeker.cpp" "$workspace/subtitle-seek-test.cpp"
c++ -std=c++11 -Wall -Wextra -Werror "$workspace/subtitle-seek-test.cpp" -o "$workspace/subtitle-seek-test"
"$workspace/subtitle-seek-test"
python3 "$repo_root/scripts/generate-subtitle-seek-test.py" "$workspace/subtitle-seek-baseline.cpp" "$workspace/subtitle-seek-original.cpp"
c++ -std=c++11 -Wall -Wextra -Werror "$workspace/subtitle-seek-original.cpp" -o "$workspace/subtitle-seek-original"
seek_baseline_status=0
"$workspace/subtitle-seek-original" >"$workspace/subtitle-seek-original-result.txt" 2>&1 || seek_baseline_status=$?
test "$seek_baseline_status" = 1 || fail 'Original seek callback did not reproduce the expected exclusion'
grep -q '^FAIL selected earlier subtitle excluded by video range$' "$workspace/subtitle-seek-original-result.txt" || fail 'Original seek callback failed for an unexpected reason'
cd "$workspace/sdk"
# The archive is already prepared with every upstream patch. Do not invoke
# get-vlc.sh: its default path resets Git history and commits the patch series.
# Build contribs from the locked source rules; avoid floating prebuilt bundles.
ANDROID_NDK="$ndk_root" RELEASE=1 bash buildsystem/compile-libvlc.sh -a "$abi" --release --license g
python3 "$repo_root/scripts/package-vlc-font-backend.py" "$workspace" "$abi" "$ndk_root"
