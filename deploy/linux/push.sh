#!/usr/bin/env bash
set -euo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ref=${1:?Provide a full image reference with an explicit tag}
bash "$script_dir/check-image-tag.sh" "$ref"
actual=$(docker image inspect "$ref" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}|{{index .Config.Labels "org.opencontainers.image.version"}}|{{index .Config.Labels "org.opencontainers.image.created"}}')
IFS='|' read -r revision version created <<< "$actual"
[[ "$revision" =~ ^[0-9a-f]{40}$ && "$version" == "${ref##*:}" && -n "$created" && "$created" != unknown ]] || { echo '镜像元数据不完整或标签不匹配' >&2; exit 1; }
[[ -z "${IMAGE_REVISION:-}" || "$revision" == "$IMAGE_REVISION" ]] || { echo '源提交不匹配' >&2; exit 1; }
# Login is managed by the caller outside this script; no registry/account is embedded.
docker push "$ref"
