---
feature: windows-playback-sort
status: delivered
updated: 2026-09-18
branch: master
commits: 886ee7a..HEAD
---

# Windows 播放/缩略图/排序二次修复

## Report

**What was built** — 修复 `byName.Less` 参数颠倒导致的 **D→C** 排序（`asc=true` 应为 C→D）；电脑根/侧边栏按盘符 C→D；账号默认 name 升序并写回 `users.sorting`。MKV/MOV 等不再按扩展名拦截，**原生优先**，失败后再提供兼容播放；ffmpeg/ffprobe 优先从 `exe` 旁 `bin/` 查找。缩略图继续走 preview `?auth=`；播放控制栏样式收敛。

**Verification** — `go test ./files -run TestApplySort` PASS；`vitest videoPlayback/fileListing` PASS；runtime：`/api/resources/` → `C,D` + `name/True`；`preview?auth=` 200 image/jpeg；volumes `C:,D:`；`bin\ffmpeg.exe`/`ffprobe.exe` 可执行。`http` 包 HLS 用例在 Windows 上因 `ffprobe`/shell 假脚本失败 → **PRE-EXISTING**（测试夹具 Unix 向）。

**Journey log**
- 后端 `natural.Less(j,i)` 会让 name 方向整体反了；修排序必须同步检查 share/public 的硬编码 Asc。
- 浏览器能播 ≠ 网页必须转码；兼容播放只应是失败后的备选。
- 服务进程 PATH 与开发 shell 不同：ffmpeg 查找写进可执行文件旁 `bin/` 比依赖 PATH 可靠。
- 修改排序语义后要跑 vitest，不能只看 vue-tsc/build。
- 用户已看到「更丑的播放器」——触控加大要有克制，避免整页重绘式 CSS。

## [S1] Problem

1. 缩略图仍失败  
2. 播放 UI 变丑难用  
3. MKV 被强制兼容播放且因无 ffmpeg 失败  
4. 默认排序 D→C 而非 C→D  
5. 排序跨设备记忆无效  

## [S2] Design

### 排序根因与修复
- `backend/files/listing.go` `byName.Less` 使用 `natural.Less(j, i)`，导致 name 比较方向整体颠倒；`asc=true` 时 API 变成 **D,C**
- 修复：Less 使用 `(i,j)`；目录始终在前；`Asc` 控制名字方向；ApplySort 对 name 不再 Reverse
- 前端 `sortListingItems`：目录始终在前；电脑根盘符名 **强制 C→D**
- 账号默认 sorting：`name/asc=true`（quickSetup Defaults + 前端 fallback `?? true`）
- 跨设备：`users.update({what,which:["sorting"],data})`；登录读 `user.sorting`

### 播放
- `isKnownIncompatibleVideo` 恒 false；`shouldAttachDirectSource` 恒 true → **MKV 原生优先**
- 兼容播放失败文案：检测到 ffmpeg 缺失时提示可下载/放置 `bin\ffmpeg.exe`
- ffmpeg 可选：`C:\Apps\WinFileBrowser\bin\ffmpeg.exe`，启动脚本加入 PATH

### 缩略图
- preview `?auth=` + 媒体 URL 白名单注入 JWT
- 部署更新 embed 前端（`index-BmeSSA7A.js`）
- `cacheDir` 已启用

### UI
- 控制栏触控区收敛为 40–48px，恢复接近默认观感
- `inactivityTimeout: 8000`

## [S3] Out of Scope
- 保证所有 MKV 编码可被浏览器解码  
- 重写播放器视觉系统  

## Tasks

- [x] T1: 修复 byName 排序方向 — acceptance: go test ApplySort C before D PASS；API `/` 返回 C,D (covers: S2)
- [x] T2: MKV 原生优先 + ffmpeg 缺失文案 — acceptance: 代码路径不再因扩展名拒绝挂载 (covers: S2)
- [x] T3: UI CSS 收敛 — acceptance: 已提交更克制的控制栏样式 (covers: S2)
- [x] T4: 排序记忆 — acceptance: PUT users sorting name/true 后 GET 一致 (covers: S2)
- [x] T5: 缩略图/部署 — acceptance: preview 200 image/jpeg；新 index 资源 (covers: S2)
- [x] T6: ffmpeg/ffprobe bin 可用 — acceptance: bin\ffmpeg.exe 与 ffprobe.exe -version 成功 (covers: S2)
- [x] T7: 构建推送 — acceptance: origin/master 更新 (covers: S2)
