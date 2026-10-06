#!/usr/bin/env bash
set -euo pipefail

root_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
tag=${1:-${IMAGE_TAG:-}}
if [[ -z "$tag" ]]; then
  echo '用法：build-image.sh IMAGE_TAG' >&2
  exit 2
fi

image_name=${FILEWAY_IMAGE_NAME:-fileway-linux}
"$root_dir/scripts/check-image-tag.sh" "$image_name:$tag"
revision=${IMAGE_REVISION:-$(git -C "$root_dir" rev-parse HEAD)}
created=${IMAGE_CREATED:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
[[ "$revision" != unknown ]] || { echo '正式构建禁止 IMAGE_REVISION=unknown' >&2; exit 1; }

compose_file="$root_dir/docker-compose.custom.yml"
IMAGE_TAG="$tag" IMAGE_VERSION="$tag" IMAGE_REVISION="$revision" IMAGE_CREATED="$created" \
  docker compose -f "$compose_file" config -q
IMAGE_TAG="$tag" IMAGE_VERSION="$tag" IMAGE_REVISION="$revision" IMAGE_CREATED="$created" \
  docker compose -f "$compose_file" build filebrowser

image="$image_name:$tag"
actual=$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}|{{index .Config.Labels "org.opencontainers.image.version"}}|{{index .Config.Labels "org.opencontainers.image.created"}}')
expected="$revision|$tag|$created"
[[ "$actual" == "$expected" ]] || { echo "OCI 元数据不一致：$actual != $expected" >&2; exit 1; }
echo "镜像构建通过：$image ($revision)"
