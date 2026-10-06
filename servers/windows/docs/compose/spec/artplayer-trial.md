---
feature: artplayer-trial
status: delivered
updated: 2026-09-19
branch: artplayer-trial
commits: bb91e1a..2be148cf+
---

# ArtPlayer 试验分支 + 设置页修复

## Report

**What was built** — ArtPlayer 作为默认播放器（`?player=videojs` 可回退）：官方 SVG 图标与右侧 tooltip 回显；底栏芯片独立 selector 弹层（点击展开，无 tooltip、无 hover）；原生优先 + `canPlayType` 探测，MKV/解码失败才兼容；兼容 HLS 显式画质真转码；账号续播（resume/from-start/ask）+ 官方 autoPlayback / layers toast；外挂字幕（同目录扫描 + PathPicker 选文件 + 字号/位置/偏移，收进「字幕」子菜单）；雪碧图 API；设置页顶部「保存」、账户偏好即存、会话上限 30 天、分享空态。

**Verification** — vue-tsc PASS；vite build PASS；Playwright QA：底栏三芯片齐全、倍速列表精简可点开且不打开设置面板、设置一级仅「字幕」、文件名顶栏无底色、全局设置 nav「保存」、会话 720h API 写入成功、token 单测 PASS、chrome/media 契约测试 13 PASS。HLS http 测试含 Windows fake-ffmpeg PRE-EXISTING。

**Journey log**
- 设置项禁止 `setting.add` 撞名；settings/controls 构造期注入，icons 在 mounted 克隆。
- 回显只用 `tooltip`；Material ligature 会在播放器里变成字母。
- 底栏不要 hover selector（`.art-bottom` 裁切）；独立弹层 + 点击 class。
- 兼容画质 copy 路径会忽略分辨率；显式 quality 必须 `ReserveWithProfile`。
- 后端会话上限与前端必须同时改，否则 400。

## [S1] Problem

1. 设置页播放偏好排版不可用  
2. 需要 ArtPlayer 试验并达到可合并质量：播放页切换/续播/字幕/雪碧图与设置页保存/布局  

## [S2] Design

### 设置页
- 偏好控件列对齐；账户播放偏好**变更即入库**；需要手保存的页在 **tab 栏右侧「保存」**  
- 会话超时 10 分钟–30 天（后端 `MaximumTokenExpirationTime`）

### ArtPlayer 契约（合并版）
- 图标：`art.icons.*` SVG 克隆  
- 回显：`html` 仅名称，`tooltip` 右侧灰字  
- 底栏：构造期 `controls[]` 带 `selector`；点击展开独立弹层；无 tooltip、禁用 hover  
- 引擎：`canPlayType` 原生优先；MKV/error → 兼容；兼容显式 quality 真转码；切换恢复进度/倍速  
- 续播：`resumeMode` resume|from-start|ask；resume 自动 seek +「从头播放」；其余用官方 autoPlayback toast  
- 字幕：一级「字幕」子菜单；同目录扫描 + PathPicker；字号/位置/偏移本地偏好  
- 雪碧图：`/api/media/sprite(.jpg)` + ArtPlayer `thumbnails`

## [S3] Out of Scope

- 在线字幕站 API（无开放密钥接口，未做）  
- 手势细调与 ASS 高级样式（后续 master 迭代）

## Tasks

- [x] T1–T3, T6–T7h: 设置页/ArtPlayer/底栏/续播/字幕/会话/分享等 — 见 Journey 与 Report  
- [ ] T4/T8: 进度条雪碧图完善与 hover 细节 — API/前端已挂载，体验待继续打磨  
- [ ] T5: 手势/续播迁移细节 — 实测后 master 迭代