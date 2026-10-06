# Fileway Linux 部署

Linux 只有一个 [Dockerfile](Dockerfile)、一个 [Compose 基础配置](compose.yml)
和同一份服务端/Web 源码。硬件及厂商集成是可选能力，不是独立项目。

## 构建与发布

从仓库根目录执行（示例标签，实际使用自己的已验证版本）：

```sh
bash deploy/linux/build.sh YYYY.M.D-vN
```

默认使用 Alpine 运行环境和普通 FFmpeg。RK3588 使用：

```sh
FILEWAY_MEDIA_PROFILE=rockchip bash deploy/linux/build.sh YYYY.M.D-vN
```

`rockchip` 选择固定摘要的 Jellyfin/FFmpeg 运行环境，包含 Rockchip MPP/RGA
支持。它不是另一个 Web 构建或后端。RK3588 是硬件型号；旧 `custom` 只是历史
构建文件名，现在不再维护 `Dockerfile.custom` / `Dockerfile.rk3588` 副本。

可用 `FILEWAY_RUNTIME_BASE` 选择明确的兼容运行环境。打包已经验证的静态 Linux
程序时，设置 `FILEWAY_BINARY_DIR`，目录中必须只有准备发布的 `fileway-server`
及必要校验材料；脚本校验 Go 构建元数据的提交、OS、架构和静态链接设置：

```sh
IMAGE_REVISION=<原始二进制源提交SHA> \
FILEWAY_BINARY_DIR=/absolute/path/to/verified-binary \
FILEWAY_RUNTIME_BASE=<已验证的兼容运行环境> \
bash deploy/linux/build.sh YYYY.M.D-vN
```

源程序必须先通过代码门禁；二进制元数据检查不是签名或供应链真实性证明。
构建使用仓库根 `.dockerignore` 排除凭据、数据库、依赖缓存和旧 Web 产物。
`push.sh` 只推送已有且元数据匹配的显式版本镜像，不重复构建、不硬编码注册表
账号、不自动登录，也不推送 `latest`。使用前自行完成所需注册表登录：

```sh
bash deploy/linux/push.sh registry.example.com/team/fileway:YYYY.M.D-vN
```

## 通用配置

准备仓库外的配置/数据库目录，明确设置：

- `IMAGE_TAG`：已经验证的不可变版本标签。
- `FILEWAY_ROOT`：实际需要提供文件服务的目录，禁止隐式挂载宿主 `/`。
- `FILEWAY_RUNTIME_DIR`：仓库外的运行目录，提前创建 `config` 和 `database`。
- `FILEWAY_BIND` / `FILEWAY_PORT`：默认 `127.0.0.1:8888`，按授权访问范围调整。

```sh
docker compose -f deploy/linux/compose.yml config -q
docker compose -f deploy/linux/compose.yml up -d --no-build --pull never --wait
```

硬件加速时另加 `-f deploy/linux/profiles/rockchip.yml`；它声明设备和只读设备树，
不使用 privileged/host network。该设备组合针对已验证的 RK3588 环境，不代表
其他所有 Rockchip 设备都支持。需要绿联共享目录发现时，另加
`-f deploy/linux/profiles/ugreen.yml`；普通 Linux 不加载此厂商配置。

## 升级已有部署

不要用新安装模板直接覆盖旧部署。先检查实际 Compose 项目、挂载、端口、设备、
进程身份与运行版本；保留原数据库、缓存卷和账号范围。已有缓存卷可通过
`FILEWAY_PREVIEW_VOLUME` / `FILEWAY_HLS_VOLUME` 指定，不能创建同名空数据替代旧卷。

记录旧镜像和配置、停止目标服务后做一致性备份，再切换已验证且已 push 的提交。
失败时恢复明确的旧运行环境并复核健康、认证和实际页面。数据库、回滚配置和
部署记录必须留在仓库外；本目录只维护通用构建/运行入口。
