---
feature: windows-experience
status: delivered
updated: 2026-09-18
branch: master
commits: ca056aa..HEAD
---

# Windows 体验完善（README/网络/缩略图/排序/移动端）

## Report

**What was built** — README 按 NAS 仓库结构重写并去掉「本机」措辞，访问地址改为 `192.168.5.115`（WLAN）与 `100.77.77.77`（Tailscale）；存储卷后端/前端按盘符 C→D 稳定排序；图片缩略图通过查询参数 `auth` + Cookie/`X-Auth` 可用（媒体 URL 才注入 JWT，分享链接不携带令牌）；登录排序写回 `users.sorting`，未登录用 localStorage；移动端 video.js 控制栏超时 10s、触控区加大。

**Verification** — `go test ./http -run TestDiscoverVolumes` PASS；`./files ./risk ./users` PASS；`vue-tsc`/`vite build`/`go build` PASS；runtime：`preview?auth=` 200 image/jpeg，volumes `C:,D:`，`192.168.5.115:8888` 与 `100.77.77.77:8888` health 200。`TestConcurrentExclusiveWrites` 在 Windows 上 `mkdir :` 失败为 PRE-EXISTING（`path.Split` 处理宿主路径）。

**Journey log**
- 局域网地址以 WLAN `192.168.5.115` 为准，不要引用 `10.222.222.1`（NodeBabyLink）。
- 媒体标签无法带 `X-Auth`，查询 `auth` 必须按 endpoint 白名单注入。
- 卷显示名不能按中文排序，否则「存储盘」会排到「系统盘」前。
- 开机自启需管理员跑 `service/install-admin-once.cmd`（本会话 schtasks 无权限）。
- README/文档禁用「本机」指代。

## [S1] Problem

Windows 部署后体验仍有明显问题：

1. README 措辞不当（「本机」不应出现在项目介绍里），且不像一份可照着做的项目说明
2. 访问地址写错：文档/回复曾误用 `10.222.222.1`；真实局域网是 **WLAN `192.168.5.115`**，Tailscale 是 **`100.77.77.77`**
3. 图片/视频缩略图全部失败
4. 移动端视频控制栏过快消失、按钮过小，全屏难点击
5. 文件列表排序未按用户跨设备记忆（登录应服务端记住；未登录用浏览器缓存）
6. 存储卷默认顺序错误：出现 D: 在 C: 前

## [S2] Design

### README
- 对齐 nas-file-browser 章节结构；避免「本机」措辞
- 访问地址：`192.168.5.115`（WLAN）/ `100.77.77.77`（Tailscale）/ `127.0.0.1`

### 网络访问
- 服务 `0.0.0.0:8888`
- 实测 health：LAN 与 Tailscale 均为 200

### 卷顺序
- 后端按 `driveLetter` 字典序稳定排序（C 在 D 前）
- 前端 `displayVolumes` 同样按盘符排序，禁止按中文卷名排序

### 缩略图
- 根因：`<img>` 无法带 `X-Auth`；未走 Cookie 时 preview 401
- 后端 ExtractToken 支持查询参数 `auth`
- 前端 `createURL` 自动附加当前 JWT（供 img/video）
- 部署启用 `--cacheDir`；视频封面仍依赖本机 FFmpeg

### 列表排序记忆
- 登录：排序变更写回 `users.sorting`（跨设备）
- 未登录：`localStorage` `win-file-browser-default-sort-v1`
- 初始加载优先账号 sorting，否则 guest 缓存

### 移动端视频控制栏
- video.js `inactivityTimeout: 10000`
- 控制按钮最小触控 44–56px，全屏/播放加大
- 窄屏下控制条加高

## [S3] Out of Scope
- 完整 PWA / 应用商店上架
- 视频转码管线重写
- 非管理员多租户权限模型
- go.mod 模块改名

## Tasks

- [x] T1: 重写 README — acceptance: 无「本机」措辞，含正确 IP 示例 (covers: S2)
- [x] T2: 卷顺序 C→D — acceptance: volumes API 返回 C:,D: (covers: S2)
- [x] T3: 缩略图 auth — acceptance: `preview/thumb?auth=` 返回 200 image/jpeg (covers: S2)
- [x] T4: 排序记忆 — acceptance: 登录写 users.sorting；未登录 localStorage (covers: S2)
- [x] T5: 移动端控制栏 — acceptance: inactivityTimeout 与触控区 CSS 已提交 (covers: S2)
- [x] T6: cacheDir 部署参数 — acceptance: 启动参数含 cacheDir (covers: S2; depends: T3)
- [x] T7: 测试/冒烟/分模块推送 — acceptance: volumes 测试 PASS，origin/master 更新 (covers: S2)
