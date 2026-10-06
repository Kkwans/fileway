---
feature: player-controls-account
status: delivered
updated: 2026-09-18
branch: master
commits: 
---

# 播放器控件超时：账户级存储 + 1–20 秒/永不隐藏

## Report

**What was built** — 「账户设置」中的播放器控件超时改为写入 BoltDB 账户字段 `playerPreferences.controlsTimeoutSec`，并随 JWT 返回，跨设备生效。取值：**0 = 不自动隐藏**（video.js `inactivityTimeout<=0` 直接 return），**1–20 = 秒**，未设置默认 **4**。设置页提供预设 + 0–20 数字输入；未登录仍回退 localStorage。实测两部 MKV 均为 HEVC Main 8bit，差异不在档案而在封装/音轨/硬解细节，产品保留兼容转码路径。

**Verification** — `go test ./users` PASS；PUT `controlsTimeoutSec=0/6/4` 后 GET 一致；JWT 解码含 `playerPreferences`；vue-tsc/vite/go build PASS。

**Journey log**
- 「账户设置」必须入库；localStorage 只能当未登录兜底。
- video.js `inactivityTimeout: 0` 才是「不自动隐藏」，不是无限大秒数。
- 同为 x265/H265 的文件浏览器表现可以不同，不能用扩展名一刀切。
- 局域网地址是 `192.168.5.115`，不要写 `10.222.222.1`。

## [S1] Problem

1. 「账户设置」里控件超时却只写 localStorage，未入库、不能跨设备  
2. 只能选固定秒数，不能自定义 1–20 秒，也不能「永不自动隐藏」  
3. 两部同为 HEVC 的 MKV：一部有声黑屏，一部可正常播（需解释与产品化处理）  

## [S2] Design

### 账户偏好（入库）
- `users.User.PlayerPreferences.ControlsTimeoutSec *int`（JSON `playerPreferences.controlsTimeoutSec`）
- **nil / 缺省 → 4 秒**（默认）
- **0 → 不自动隐藏**（video.js `inactivityTimeout: 0`）
- **1–20 → 秒**
- Clean 钳制非法值；`users.update` `which=["PlayerPreferences"]`
- 登录 JWT `userInfo` 带上该字段，跨设备生效
- 前端优先读账号；未登录回退 localStorage  

### HEVC 两片差异（实测 ffprobe）
| | 美国丽人 x265 | 隐入尘烟 H265 |
|--|--|--|
| video | hevc Main 8bit yuv420p 1920x818 | hevc Main 8bit yuv420p 1672x1080 |
| audio | AAC 5.1 ×2 + 字幕 | EAC3 5.1 + AAC 2.0 |
| 体积/时长 | 3.6GB / ~2h | 3.0GB / ~2.2h |
| 本机浏览器结果 | 有声黑屏 | 可播 |

编码档案相同，差异更可能来自：双音轨/封装索引、GOP/Range 行为、硬解兼容细节；**不能**用「都是 HEVC 所以必挂/必播」一刀切。产品：`media/info` 驱动的失败文案 + 兼容转码；对黑屏文件建议兼容播放。

### 其他
- 访问地址以 **192.168.5.115** / **100.77.77.77** 为准（非 10.222.222.1）  

## [S3] Out of Scope
- 保证任意 HEVC Bluray  remux 在 Chrome 硬解可用  
- 服务端自动为每部电影预转码  

## Tasks

- [x] T1: 后端 PlayerPreferences 入库 + Clean + JWT (covers: S2)
- [x] T2: 设置页 0/1–20 自定义 (covers: S2)
- [x] T3: VideoPlayer 读取账号偏好 (covers: S2)
- [x] T4: 构建推送 (covers: S2)
