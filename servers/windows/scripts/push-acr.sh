#!/bin/bash
# ============================================================
# 推送 nas-file-browser 镜像到阿里云 ACR
# 用法: ./scripts/push-acr.sh [版本号]
# 示例: ./scripts/push-acr.sh 1.0.0
# ============================================================

set -e

# 配置
REGISTRY="crpi-33qulxhgw5nadzni.cn-beijing.personal.cr.aliyuncs.com"
NAMESPACE="dh4300plus"
IMAGE_NAME="nas-file-browser"
VERSION="${1:-latest}"

FULL_IMAGE="${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${VERSION}"
LATEST_IMAGE="${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:latest"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=========================================="
echo "构建并推送镜像到阿里云 ACR"
echo "镜像: ${FULL_IMAGE}"
echo "=========================================="

# 1. 构建镜像
echo "[1/4] 构建镜像..."
cd "$PROJECT_DIR"
docker build -f Dockerfile.custom -t "${FULL_IMAGE}" -t "${LATEST_IMAGE}" .

# 2. 登录 ACR
echo "[2/4] 登录阿里云 ACR..."
echo "请输入 ACR 密码（用户名: Kkwans）:"
docker login --username=Kkwans "${REGISTRY}"

# 3. 推送版本标签
echo "[3/4] 推送镜像 (${VERSION})..."
docker push "${FULL_IMAGE}"

# 4. 推送 latest 标签
if [ "${VERSION}" != "latest" ]; then
    echo "[4/4] 推送镜像 (latest)..."
    docker push "${LATEST_IMAGE}"
fi

echo "=========================================="
echo "✅ 推送完成！"
echo "镜像地址: ${FULL_IMAGE}"
echo "=========================================="

# 清理悬空镜像
echo "清理悬空镜像..."
docker image prune -f
