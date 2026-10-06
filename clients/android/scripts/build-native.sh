#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$toolchain/clang"
cd "$repo_root/../../shared/core"
for spec in 'arm64-v8a arm64 aarch64' 'x86_64 amd64 x86_64'; do
  read -r abi go_arch target <<< "$spec"
  mkdir -p "generated/$abi"
  CGO_ENABLED=1 GOOS=android GOARCH="$go_arch" CC="$toolchain/$target-linux-android29-clang" \
    go build -trimpath -buildmode=c-shared -ldflags='-s -w -extldflags "-Wl,-z,max-page-size=16384 -Wl,-soname,libnfbcore.so"' \
    -o "generated/$abi/libnfbcore.so" ./mobile
done
