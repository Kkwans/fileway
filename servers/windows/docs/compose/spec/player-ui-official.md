---
feature: player-ui-official
status: delivered
updated: 2026-09-20
branch: master
commits: fb5ea171..working-tree
---

# Official resume toast + PathPicker subtitle picker

## Report

**What was built** — 续播提示改为 ArtPlayer 官方层结构与样式（`.art-layer-auto-playback` + close/last/jump，毛玻璃 `rgba(0,0,0,.85)`，主题色关闭钮/跳转字）；阈值 **30 秒**。`from-start`：`上次看到 mm:ss` + `跳转播放`；`resume`：自动 seek + `已跳转至上次播放位置 mm:ss` + `从头播放`。PathPicker：**目录始终可列出导航**，文件按字幕扩展过滤（含 sup/smi/lrc 等）；播放器内深色毛玻璃，高度随内容（消灭底部空白与浅色泄漏）。服务侧增加 5 分钟 watchdog 脚本与安装项（需管理员执行一次）。

**Verification** — `vue-tsc` PASS；部署 SPA `index-B00SxJvh.js`。Playwright：`FROM_START` last=「上次看到 0:53」jump=「跳转播放」bg=official；`RESUME` last=「已跳转至上次播放位置 0:53」jump=「从头播放」。PathPicker：locBg=`rgba(255,255,255,0.06)`（非白）、panel bg 深色毛玻璃、height=266；上一级后列出 `css/js/photos/video_files` 等目录。

**Journey log**
- PathPicker `mode=file` 旧逻辑把 `isDir` 全滤掉 → 必须「目录永显示 + 文件按扩展名过滤」。
- 官方 toast 复用 class + `art.icons.close`，不要再画蓝底按钮。
- 播放器内 PathPicker 不要 `display:flex` 覆盖 grid，否则布局空洞。
- 进程被 SIGTERM 后无守护会一直挂；watchdog 比只做 ONSTART 更关键。

## [S1] Problem
自定义续播 UI 丑；字幕选择器白底/空白/进不去目录；扩展名不全；服务异常退出无法自愈。

## [S2] Design
见 Report；`RESUME_MIN_SEC=30`；官方 DOM/CSS；PathPicker 目录+扩展过滤+深色玻璃；watchdog 5min。

## [S3] Out of Scope
在线字幕 API；ASS 样式；雪碧图 hover。

## Tasks
- [x] T1: 官方续播层 + 30s — QA FROM_START/RESUME 文案与 bg
- [x] T2: PathPicker 目录可见 + 字幕扩展 — QA PARENT/GRAND 目录列表
- [x] T3: 深色毛玻璃无死空白 — QA locBg/height
- [x] T4: watchdog 脚本 + 部署 — hash B00SxJvh；需管理员跑 install-admin-once
