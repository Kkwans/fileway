---
feature: player-toast-mkv-loading
status: delivered
updated: 2026-09-21
branch: master
commits: 06477dea..working-tree
---

# Player toast + MKV native-first + HLS tests

## Report

**What was built** — ① 续播 toast 短文案 + 6s 自动关。② MKV 原生优先：用**响应式 mediaUiReady + 轮询**从 `<video>` 拉状态；缓冲期显示「原生加载中…（缓冲/探测）」或真实 buffered %；**有画面/正在播放立即隐藏加载层**（修复播着还转圈）。原生无画面约 6–8s 或 error 才切兼容。③ HLS 测试 Go stub；`writeFileExclusive` Windows `filepath.Dir`；analysis 路径 ToSlash。

**Verification** — 真实片源 `隐入尘烟...H265...mkv` Playwright：播放后 `loaderVisible=false`、`currentTime` 增加；`go test ./http ./users` ok；线上 hash 见部署。

**Journey log**
- `loadingVisible` 不能只 computed 读 video.readyState（Vue 不追踪 DOM 属性）。
- 大 MKV 原生会先空窗，必须给缓冲文案/进度，不能 1.6s 就误切兼容。
- Windows 测试 stub 用 go build。

## Tasks
- [x] T1 toast
- [x] T2 MKV 加载态
- [x] T3 http 测试
- [x] T4 真实 MKV 验证
