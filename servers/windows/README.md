# win-file-browser

Windows 网页文件浏览器。基于 [filebrowser](https://github.com/filebrowser/filebrowser)，从 [nas-file-browser](https://github.com/Kkwans/nas-file-browser) 分离，面向 Windows 多磁盘与家庭局域网访问。

## 特性

- 全中文界面
- Windows 多磁盘卷：浏览 `C:` / `D:` 等逻辑盘（虚拟路径 `/C`、`/D`）
- 侧边栏存储卷：系统盘 / 存储盘命名，容量条与「可用空间 / 总容量」文案
- 目录风险标识、收藏、标签、Markdown 编辑
- 图片缩略图与视频封面（视频封面需要在运行服务的 Windows 上安装 FFmpeg）
- 列表排序偏好：登录后保存到账号，未登录时使用浏览器本地存储
- 局域网访问，可配合 Tailscale 外网访问

## 快速开始

### 环境要求

- Windows 10/11 x64
- Go 1.25+（仅编译时需要）
- Node.js 24+ / pnpm 10+（仅改前端时需要）
- FFmpeg（可选，用于视频封面与兼容播放）

### 编译

```powershell
git clone https://github.com/Kkwans/win-file-browser.git
cd win-file-browser\frontend
corepack pnpm install --frozen-lockfile
corepack pnpm exec vite build
# 将 frontend/dist 同步到 backend/frontend/dist 后编译后端
Copy-Item -Recurse -Force dist\* ..\backend\frontend\dist\

cd ..\backend
$env:CGO_ENABLED = "0"
go build -ldflags="-s -w" -o ..\dist\filebrowser.exe .
```

### 初始化管理员

```powershell
..\dist\filebrowser.exe hash "你的密码"
# 记下输出的 $2a$10$... 哈希
```

### 启动（局域网 + 多磁盘）

```powershell
..\dist\filebrowser.exe `
  --address 0.0.0.0 `
  --port 8888 `
  --root computer `
  --database ..\data\filebrowser.db `
  --log ..\logs\app.log `
  --cacheDir ..\data\preview-cache `
  --username admin `
  --password '$2a$10$...'
```

浏览器访问：

| 场景 | 地址示例 |
|---|---|
| 本地调试 | `http://127.0.0.1:8888` |
| 家庭局域网 | `http://192.168.5.115:8888`（替换为运行 Windows 的设备 IP） |
| Tailscale | `http://100.77.77.77:8888`（替换为该机的 Tailscale IP） |

虚拟根 `/` 列出全部逻辑盘；点击「系统盘 (C:)」「存储盘 (D:)」进入对应盘。

## 配置说明

| 参数 | 说明 |
|---|---|
| `--address` | `0.0.0.0` 供局域网/VPN 访问；`127.0.0.1` 仅 Windows 服务所在设备可访问 |
| `--root computer` | Windows 多磁盘虚拟根（也接受 `/`、`drives`） |
| `--cacheDir` | 图片/预览缓存目录，建议启用 |
| `--database` | BoltDB 路径，单文件即可 |
| 鉴权 | 使用登录账号；API 媒体资源支持 Cookie / `X-Auth` / 查询参数 `auth` |

安全建议：

- 修改初始管理员密码
- 防火墙仅对「专用网络」放行端口
- 不要把 8888 直接映射到公网；远程访问用 Tailscale 等 VPN
- 系统盘上的 `Windows`、`Program Files` 等目录在界面中会标为高危

## 开机自启

开发目录提供提权安装脚本（管理员运行一次）：

```text
service\install-admin-once.cmd
```

将创建计划任务并放行 TCP 8888。也可自行使用 WinSW/NSSM 注册 Windows 服务。

## 与 nas-file-browser 的关系

| | nas-file-browser | win-file-browser |
|---|---|---|
| 目标场景 | 绿联等 NAS | Windows 文件服务器 / 家庭多设备访问 |
| 卷模型 | `volume1` / `volume2` | 盘符 `C:` / `D:` |
| 默认部署 | Docker Compose | 原生 exe + 计划任务/Windows 服务 |
| 仓库 | [Kkwans/nas-file-browser](https://github.com/Kkwans/nas-file-browser) | 本仓库 |

## 开发

- 规格：`docs/compose/spec/windows-multi-drive.md`、`docs/compose/spec/windows-experience.md`
- 后端测试：`cd backend && go test ./...`
- 前端：`cd frontend && corepack pnpm install && corepack pnpm exec vue-tsc -p ./tsconfig.app.json --noEmit`
- 前端产物嵌入 `backend/frontend/dist`

## 许可证

Apache-2.0（与上游 filebrowser / nas-file-browser 一致）
