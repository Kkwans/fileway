---
feature: windows-player-ux
status: delivered
updated: 2026-09-18
branch: master
commits: 
---

# 播放体验：HEVC 提示、控件超时、兼容面板布局、加载文案

## Report

**What was built** — 播放器去掉「网络或NAS」文案；兼容播放卡片改为整齐 flex 布局；控件默认 4s 自动隐藏，设置页可选 2–12 秒（localStorage），触摸时重置 `userActive` 缓解移动端秒隐；`nativeControlsForTouch=false`。HEVC/H.265 播放失败时明确说明 Chrome/Edge 桌面版通常无法硬解（黑屏/有声无画），主按钮为服务端转码兼容播放；`isHevcCodec` 规范化编码字符串。

**Verification** — vue-tsc PASS；vitest videoPlayback+previewLifecycleContract PASS；vite/go build PASS；服务静态资源 `index-DvBLq6BC.js`。

**Journey log**
- 文件名含 H265/x265 时，桌面 Chrome 原生失败是浏览器能力限制，产品应解释并提供转码，而不是装作能播。
- video.js 移动端秒隐：触摸事件里主动 `userActive(true)` + 关闭原生触控控件。
- 控件超时可配置必须有写入口（设置页），只读 localStorage 不算完成。
- 兼容面板空框多半是重复 CSS / flex 占位，删干净比再堆样式有用。

## [S1] Problem

1. PC Chrome 播 **H.265/HEVC MKV** 很慢 / 有声无画；手机偶发可播  
2. 加载文案「网络或NAS」不知所云  
3. 兼容播放卡片布局乱、对齐差  
4. 控件隐藏：要 4s 或可配置；移动端仍「秒隐」  
5. 需要自行优化若干体验点  

## [S2] Design

### 硬刷新
- 文档/回复说明：PC `Ctrl+F5`；手机 Chrome 清站点缓存或无痕窗口  

### 文案
- 「网络或NAS 正在准备下一段数据」→「正在缓冲视频数据…」  
- 禁止无场景的「NAS」字样出现在播放器 UI  

### 兼容面板
- 重写 `media-compatibility-card` CSS：flex 列布局、标题行对齐、按钮换行、宽度约束  
- 说明文字与操作区分层，避免空节点占位  

### 控件超时
- 默认 `4000`ms  
- 读取 `localStorage["win-file-browser-controls-timeout"]`（若用户设置 1–30s）  
- 移动端：`touchstart/touchend/click/play` 时 `userActive(true)` + `reportUserActivity()`，避免秒隐  
- `html5.nativeControlsForTouch = false`，统一用 video.js 控件  

### HEVC / H.265
- Chrome 无 HEVC 时原生失败是预期；检测 `media/info` videoCodec hevc/h265  
- 失败文案明确：「当前浏览器无法解码 H.265/HEVC，只有声音没有画面是常见现象」  
- 主按钮优先「兼容播放（服务器转 H.264）」；ffmpeg 在 `bin` 已就绪  
- 不自动无确认长转码，但一键可启动；进度文案真实  

### 其他优化
- stalled 阈值提示更克制  
- 兼容操作按钮组 `flex-wrap` + 等高  

## [S3] Out of Scope
- 保证 Chrome 原生播放 HEVC  
- 重写整个播放器皮肤系统  

## Tasks

- [x] T1: 加载文案 + 兼容卡片布局 (covers: S2)
- [x] T2: 控件 4s + 设置页可配置 + 移动端 keep-alive (covers: S2)
- [x] T3: HEVC 失败文案与兼容转码入口 (covers: S2)
- [x] T4: 构建测试推送 (covers: S2)
