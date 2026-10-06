---
feature: global-cr-fixes
status: delivered
updated: 2026-09-20
branch: master
commits: fb5ea171..working-tree
---

# Global CR Fixes

## Report

**What was built** — 按全局 CR 修复：① 播放器 `playerPreferences` 写路径全部 spread，保留 `resumeMinSec`；error/超时会清 loader；「从头播放」`clearPlayback`；引擎切换持久化 `playbackMode`；`switchEngine` 防并发；HLS fatal notice；compat 续播校验 seekable；自动兼容画质默认 cap 1080p。② 设置：去掉 Settings 兜底双 toast，tab 自理提示；Profile 密码/保存单一 toast；Rules 去嵌套 form；Global 空列不占宽；User 可见「保存」+ form ref；Users 链接不套 button。③ 后端：`ResumeMinSec` 仅在有值时 clamp；sprite 非 video → 400；share expire 非法 → 400。④ 运维：启动/watchdog/WinSW 均注入 `PATH=...\bin`；watchdog 先停再拷。⑤ 卫生：QA 密码改 `WINFB_ADMIN_PASSWORD`；删除死组件 AppCheckbox；gitignore dist。

**Verification** — vue-tsc PASS；settingsUiContract + tokenExpiration + media.api **17 PASS**；`go test ./users` PASS（含 resumeMinSec clamp）；`go test ./http` 多项 **PRE-EXISTING**（Windows fake-ffmpeg / analysis / exclusive write）。线上 **`index-gzjJFmPs.js`**；视觉 QA：三张交互卡片 h=72、`resumeMinSec` 控件存在、tab「保存」。

**Journey log**
- 偏好整包覆盖是静默数据丢失，写路径必须 spread。
- Settings 兜底 toast 与 tab toast 必然双弹——保存反馈单一责任。
- 运维 PATH 不在启动环境时 ffmpeg 会 500；所有 Start 入口统一注入。
- QA 脚本勿写死密码；构建产物勿进 git。

## [S1] Problem
见 CR 报告确认的 P0–P3 缺陷。

## [S2] Design
见 Report；契约：prefs merge、单一 toast、PATH 注入、Clean 非强制物化。

## [S3] Out of Scope
双引擎完全合并、HLS 服务端 entry 重建、在线字幕 API。

## Tasks
- [x] T1 ArtPlayer prefs/loader/switch/restart
- [x] T2 VideoPlayer prefs spread
- [x] T3 设置页 toast/form/布局
- [x] T4 后端 Clean/sprite/share
- [x] T5 运维 PATH/watchdog + QA env + gitignore
- [x] T6 构建测试部署 gzjJFmPs + 复核
