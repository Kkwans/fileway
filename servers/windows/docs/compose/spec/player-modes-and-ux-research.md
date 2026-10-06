---
feature: player-modes-and-ux-research
status: in-progress
updated: 2026-09-18
branch: master
commits: 
---

# 播放方式偏好 / 倍速 / 硬解 / 控件选型（调研）

## Report

## [S1] Problem

1. 账户级默认播放方式：原生 / 兼容转码 / 打开时显示选择卡片
2. 播放器运行时可切换播放方式
3. 硬解/软解是什么，能否支持
4. 倍速 0.10–5.00 两位小数（如 1.15）
5. 底部控件观感差：先调研能否复用高质量控件或重构，**不轻易换库**

## [S2] Design

### 现状

- 播放器：video.js **8.23.7**（已 pnpm patch `video.js@8.23.7.patch`）
- 集成：VideoPlayer.vue + videojs-hotkeys + videojs-mobile-ui + 自有兼容播放/HLS 卡片
- 倍速：固定 playbackRates [0.5,1,1.5,2,2.5,3]
- 账户偏好已有 playerPreferences.controlsTimeoutSec

### 控件库对比（调研结论）

| | video.js 8.x | Plyr 3.8 | ArtPlayer 5.4 | Vidstack 0.6 |
|--|--|--|--|--|
| 成熟度 | 很高 | 高 | 高 | 中（Plyr/Vime 继任者） |
| 默认 UI | 偏传统，可换肤 | 现代干净，CSS 变量完善 | 现代，中文生态多 | 现代 |
| HLS | 内置 VHS + 本项目补丁 | 通常另接 hls.js | 插件 | 一等公民 |
| 替换成本 | 0（已在用） | 高：重写播放/续播/兼容卡片/热键/移动 | 中高 | 高 + 稳定性风险 |
| 倍速自定义 | API 可任意 playbackRate | speed.options 可配 | 可配 | 可配 |
| 结论 | **短期保留** | 观感好但迁移贵 | 观感/插件好，适合中期评估 | 暂不选 |

**推荐**：默认 **继续用 video.js**；控件用主题/CSS + 自定义按钮重构，不整库替换。若后续仍不满意，再评估 **ArtPlayer**（单独分支，不动兼容播放协议）。

### 硬解 / 软解

| | 含义 |
|--|--|
| 硬解 | 用 GPU/专用硬件解码，省电、流畅，支持哪些编码取决于显卡与驱动 |
| 软解 | CPU 解码，兼容性广，高码率 HEVC 可能卡顿 |

- **网页里无法像 PotPlayer 那样强开/强关硬解**；解码由浏览器调用系统 Media Foundation/DXVA 等
- 我们能做：Media Capabilities / `MediaSource.isTypeSupported` 能力探测、显示编码与兼容建议、失败时走服务端转码
- 服务端 FFmpeg 转码可用硬编（需 GPU），属转码加速，不是浏览器播放时的「硬解开关」

### 播放方式（产品设计）

账户 `playerPreferences.playbackMode`：

| 值 | 行为 |
|--|--|
| `native`（默认） | 先原生；失败再提示兼容 |
| `compat` | 打开即启动兼容转码/已缓存兼容流 |
| `ask` | 不自动播，显示选择卡片（原生/兼容/下载） |

运行时：控件区新增「播放方式」入口（顶栏按钮或倍速左侧），可切换三种并写回账户（可选「仅本次」）。

### 倍速

- 账户 `playerPreferences.playbackRate`（可选，默认 1）
- 倍速菜单扩展常用档 + **自定义 0.10–5.00 两位小数**
- video.js：`player.playbackRate(rate)` 对任意正数有效；菜单项用 `playbackRates` + 自定义按钮/输入框

### 控件 UI 路径（待选型确认）

1. **A. video.js 换肤**（推荐先做）：CSS 变量/主题 + 自定义按钮（播放方式、倍速输入）成本低、风险低  
2. **B. ArtPlayer 试验分支**：观感更好，需重迁续播/兼容/HLS  
3. **C. 自研 HTML 控件**：可控性最高，工作量最大  

## [S3] Out of Scope

- 浏览器强制硬解/软解切换  
- 本迭代整库替换播放器（除非选 B 并单独立项）  
- DRM/付费内容  

## Tasks

- [x] T1: 账户 playbackMode 入库 + 设置页 — native/compat/ask 可保存 (covers: S2)
- [x] T2: 播放器按 playbackMode 启动 + 运行时切换入口 — 控件区按钮循环切换 (covers: S2)
- [x] T3: 倍速 0.10–5.00 自定义 + 账户默认倍速 — 1.15 可保存 (covers: S2)
- [ ] T4: 控件换肤/按钮（路径 A）或 ArtPlayer 试验分支 — 用户看完调研后选型 (covers: S2)
