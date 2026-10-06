---
feature: settings-controls-unified
status: delivered
updated: 2026-09-20
branch: master
commits: fb5ea171..working-tree
---

# Settings controls + resume threshold + visual QA

## Report

**What was built** — ① 交互三项改为 **flex 行卡片**（勾选框在左、标题/说明在右），勾选视觉与全局设置一致；根因是 AppCheckbox 挂在 `label` 上被 `.dashboard .card-content label` 的 uppercase/块级样式压成竖排，已改为原生 checkbox + 独立 CSS，并在 settings.css 排除 `label.check-card`。② **续播提示阈值可配置**：后端 `PlayerPreferences.resumeMinSec`（默认 **10**，钳制 **5–600**）；账户设置数字输入；播放器读账号值。③ 保存 toast / AppSelect / 前缀按钮布局保持。**部署前用 Playwright 截图做视觉验收**（不再只看 DOM 计数）。

**Verification** — vue-tsc PASS；settingsUiContract 6 PASS；`go test ./users` PASS；视觉截图 `frontend/output/settings-visual/profile-*.png`：三卡片高度约 72px，横排勾选+文案；「续播提示阈值」默认 10 秒；API PUT `resumeMinSec=10` 回读成功。QA 构建 hash `index-DGx9pFd8.js`（8899）。8888 需管理员 `deploy-admin.cmd` 替换 exe。

**Journey log**
- 设置页里 **label** 有全局样式，组件根节点不要用 label 当布局容器。
- UI 改动必须 **截图目视**，DOM metrics 会漏掉竖排换行。
- 嵌入前端必须换 exe；watchdog **必须先 Stop-Process 再 Copy**，否则占用导致部署空转。
- 勾选框不要叠两种 ::after（全局 `content:✓` + 边框勾）；用单一 SVG 背景并 `content:none`。

## [S1] Problem
交互卡片被压成竖排；续播阈值写死且与需求（默认10s、5–600可配）不符。

## [S2] Design
见 Report。

## Tasks
- [x] T1: 交互卡片 flex 横排 + 全局勾选样式 — 截图确认
- [x] T2: resumeMinSec 后端/设置/播放器 — 默认10，5–600
- [x] T3: 视觉 QA 截图 — profile-interaction.png
