#!/usr/bin/env bash
# ============================================================
# 推送 nas-file-browser 镜像到阿里云 ACR
# 用法: ./scripts/push-acr.sh YYYY.M.D-vN
# 示例: ./scripts/push-acr.sh 2026.9.5-v1
# ============================================================

set -euo pipefail

# 配置
REGISTRY="crpi-33qulxhgw5nadzni.cn-beijing.personal.cr.aliyuncs.com"
NAMESPACE="dh4300plus"
IMAGE_NAME="nas-file-browser"
VERSION="${1:-${IMAGE_TAG:-}}"
if [[ -z "$VERSION" ]]; then
    echo "用法: $0 YYYY.M.D-vN" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
"$SCRIPT_DIR/check-image-tag.sh" "${IMAGE_NAME}:${VERSION}"

FULL_IMAGE="${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${VERSION}"
REVISION="${IMAGE_REVISION:-$(git -C "$PROJECT_DIR" rev-parse HEAD)}"
CREATED="${IMAGE_CREATED:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
[[ "$REVISION" != unknown ]] || { echo "正式构建禁止 IMAGE_REVISION=unknown" >&2; exit 1; }

echo "=========================================="
echo "构建并推送镜像到阿里云 ACR"
echo "镜像: ${FULL_IMAGE}"
echo "=========================================="

# 1. 构建镜像
echo "[1/4] 构建镜像..."
cd "$PROJECT_DIR"
docker build -f Dockerfile.custom \
  --build-arg IMAGE_VERSION="${VERSION}" \
  --build-arg IMAGE_REVISION="${REVISION}" \
  --build-arg IMAGE_CREATED="${CREATED}" \
  -t "${FULL_IMAGE}" .

actual=$(docker image inspect "${FULL_IMAGE}" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}|{{index .Config.Labels "org.opencontainers.image.version"}}|{{index .Config.Labels "org.opencontainers.image.created"}}')
expected="${REVISION}|${VERSION}|${CREATED}"
[[ "$actual" == "$expected" ]] || { echo "OCI 元数据不一致：$actual != $expected" >&2; exit 1; }

# 2. 登录 ACR
echo "[2/4] 登录阿里云 ACR..."
echo "请输入 ACR 密码（用户名: Kkwans）:"
docker login --username=Kkwans "${REGISTRY}"

# 3. 只推送不可变版本标签
echo "[3/3] 推送镜像 (${VERSION})..."
docker push "${FULL_IMAGE}"

echo "=========================================="
echo "✅ 推送完成！"
echo "镜像地址: ${FULL_IMAGE}"
echo "=========================================="
