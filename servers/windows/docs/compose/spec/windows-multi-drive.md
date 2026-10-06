---
feature: windows-multi-drive
status: delivered
updated: 2026-09-18
branch: master
commits: d3a7d7b..HEAD
---

# Windows 多磁盘卷（资源管理器式）

## Report

**What was built** — `win-file-browser` 从 `nas-file-browser` 分离，在 Windows 上以虚拟根 `computer` 将逻辑盘映射为 `/C` `/D`，侧边栏展示「系统盘 (C:) / 存储盘 (D:)」与 Explorer 式可用空间；`/api/volumes` 返回卷标/容量；风险与分类覆盖 Windows 路径。评审后补齐非 Windows stub、Rename 防护。

**Verification** — `GOOS=windows/linux go build` PASS；`go test ./files ./risk ./users` PASS；`vue-tsc` PASS；运行时 health/volumes/root list/volumes login（X-Auth）PASS。

**Journey log**
- API 鉴权头是 `X-Auth`，不是 Bearer。
- 本会话无管理员权限时 `schtasks /RU SYSTEM` 与防火墙规则会 Access denied；已提供 `service/install-admin-once.cmd` 供提权一次安装。
- 空 server.Root 不再默认启用多盘，需显式 `computer`/`/`。
- Docker 路线仍不适合「不占后台」；原生 exe 空载约 27MB。

## [S1] Problem

Windows 11 本机部署时，原 NAS 逻辑只识别 `server.Root` 下的 `volume*` 目录，无法把逻辑盘 C:/D: 展示成资源管理器式的存储卷；单一 `BasePathFs` 也无法跨盘访问。

需要：
1. 网页根浏览全部逻辑盘
2. 侧边栏「系统盘 (C:) / 存储盘 (D:)」+ Explorer 容量文案
3. 局域网（及 Tailscale）可访问
4. 与 `nas-file-browser` 仓库分离

## [S2] Design

### 路径模型
- 虚拟根 `/`；`/C` → `C:\`，`/D` → `D:\`
- `server.Root` 令牌：`computer`（也接受 `/`、`drives`）
- Windows 下默认 root=`computer`，启用 DriveFs

### DriveFs
- `backend/files/drivefs_windows.go` 实现 `afero.Fs`
- 根 `/` 列出盘符；`RealPath` 供 FullPath 使用
- Remove/Rename/RemoveAll 禁止作用于 `/` 与盘符根
- 非 Windows：`drivefs_other.go` 提供完整 afero.Fs stub；`volumes_other.go` stub 卷发现

### 卷发现 API
- Windows 虚拟根 → `discoverWindowsVolumes`
- 字段：`name`/`driveLetter`/`volumeLabel`/`freeSpace`/`totalSpace`/`usedSpace`
- 命名：`系统盘 (C:)`、`存储盘 (D:)`、`本地磁盘 (X:)` 等

### 用户 FS
- 虚拟根模式 `user.Fs = NewDriveFs()`，默认 Scope=`/`
- `FullPath`：DriveFs 解析失败返回空串（避免无效 cwd）
- `MakeUserDir` 虚拟根不 mkdir
- 虚拟根不做 filepath.Abs

### 风险
- `/C/Windows`、`Program Files`、`ProgramData`、`$Recycle.Bin` 等 → high
- `/*/.nas-file-browser-trash` → medium

### 前端
- Explorer 1024 进制容量文案「xx GB 可用，共 xx GB」
- 分类 patterns 兼容 `/C` `/D`
- 卷列表点击进入 `/files/C/`、`/files/D/`

### 部署
- 监听 `0.0.0.0:8888`，鉴权登录，禁止 noauth
- GitHub：`Kkwans/win-file-browser`
- 每模块 commit & push
- API 鉴权头：`X-Auth`

## [S3] Out of Scope
- NAS Docker 部署主路径
- 完整资源管理器 UI
- Tailscale 自动安装
- 非管理员用户的精细 Scope 隔离（后续迭代）
- go.mod 模块路径改名

## Tasks

- [x] T1: 创建 win-file-browser 远程仓库并切换 origin — acceptance: origin 指向 Kkwans/win-file-browser (covers: S2)
- [x] T2: 落地规格文档 — acceptance: docs/compose/spec/windows-multi-drive.md 存在 (covers: S2; depends: T1)
- [x] T3: 后端 DriveFs + FullPath/MakeUserDir — acceptance: `/` 列盘符，`/D/...` 可访问 (covers: S2)
- [x] T4: Windows 卷发现 API — acceptance: volumes 返回 C/D 与 freeSpace (covers: S2; depends: T3)
- [x] T5: 风险/分类 Windows 规则 — acceptance: `/C/Windows` high (covers: S2)
- [x] T6: 前端 Explorer 式文案 — acceptance: 侧边栏系统盘/存储盘 + 可用空间 (covers: S2; depends: T4)
- [x] T7: 编译冒烟 — acceptance: build + health/volumes/list 可用 (covers: S2; depends: T3-T6)
- [x] T8: 按模块 commit & push — acceptance: origin/master 更新 (covers: S2)
- [x] T8b: 评审修复：非 Windows stub、Rename 防护 — acceptance: GOOS=linux 可编译 (covers: S2)
- [x] T9: 本机部署 LAN+自启 — acceptance: 0.0.0.0:8888 可访问；开机自启需管理员安装一次（见 service/install-admin-once.cmd）(covers: S2; depends: T7)
