#!/usr/bin/env bash
set -euo pipefail

if (($# != 1)); then
  echo '用法：check-image-tag.sh IMAGE[:TAG]' >&2
  exit 2
fi

ref=$1
ref=${ref%@*}
tag=${ref##*:}
repo=${ref%:"$tag"}

if [[ "$repo" == "$ref" || -z "$repo" || -z "$tag" ]]; then
  echo "镜像引用必须包含显式 tag：$1" >&2
  exit 1
fi

if [[ ! "$repo" =~ ^[a-z0-9]+([._-][a-z0-9]+)*(\/[a-z0-9]+([._-][a-z0-9]+)*)*$ ]]; then
  echo "镜像仓库名不符合 NAS 规范：$repo" >&2
  exit 1
fi

if [[ "$tag" =~ ^[0-9]{4}\.[0-9]{1,2}\.[0-9]{1,2}-v[0-9]+(-rc[0-9]+)?$ || "$tag" =~ ^test-[0-9]{8}-[0-9]+$ ]]; then
  exit 0
fi

echo "镜像 tag 不符合 YYYY.M.D-vN（或 test-YYYYMMDD-N）：$tag" >&2
exit 1
