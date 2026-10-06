---
feature: settings-player-polish
status: delivered
updated: 2026-09-20
branch: master
commits: fb5ea171..working-tree
---

# Settings + Player Polish (master)

## Report

**What was built** — 在 master 上修复用户实测三类问题：① 续播：`resumeMode=resume` 自动 seek 后必须出现「已续播 mm:ss」toast + notice，`from-start` 为「已看到…跳转播放/从头播放」chooser；进页从 API 重拉账号偏好，避免缓存陈旧。② MKV：按扩展名判定容器不可原生播放，立即显示兼容加载层并 `switchEngine(compat)`；无解码帧时 watchdog ~2.2s 自动切兼容；加载层只在 `videoWidth>0` 且 readyState≥2 时消失。③ 设置页：账户 PC 双列（1.4fr/0.9fr）、控件列统一 200px 右缘对齐、模块内无「保存/更新」（仅 tab 行「保存」）、Ace 主题下拉必有选项（内置回退）、分享空态「图标+标题+三步引导+去文件列表」。

**Verification** — `vue-tsc --noEmit` PASS；vitest `settingsUiContract`+`tokenExpiration`+`mediaIconSemantics` 14 PASS；utils 全量另有 4 项 PRE-EXISTING（previewLifecycle/sidebarInteraction/uiFoundation 等，与本变更无关）。部署 SPA `index-BWOjmIYo.js`（http://127.0.0.1:8888）。Playwright：RESUME toast「已续播」；FROM_START「已看到…跳转播放」；MKV `loadingVisible=true`「兼容播放加载中」；PROFILE 双列 618/397、控件 right=913/width=200、editorTheme 44 options；GLOBAL 无卡内保存；SHARES steps=3。独立审查：三项验收均满足，无 critical。

**Journey log**
- 部署 hash 与 master 已提交内容不一致时，以线上 `index-*.js` + 源码 diff 为准，勿再把 DOM 契约测试当成视觉验收。
- `readyState≥2` 不等于可播：MKV 可能只有音轨帧信息，必须看 `videoWidth`。
- 用户偏好以 API 回读进 `authStore`，否则「改了设置播放器仍旧行为」。
- 设置控件右缘对齐：select 与 number+单位必须同一 200px 视觉盒（单位绝对定位叠在输入内）。
- Playwright 用户接口是 `PUT /api/users/{id}` + `{what,which:["PlayerPreferences"],data}`，不是裸 JSON。

## [S1] Problem

用户实测 master 部署后：续播模式无提示；MKV 黑屏无加载动画且播放按钮不切换；设置页大片空白、控件不对齐、编辑器主题空白、卡内「更新」与 tab「保存」并存、分享空态丑陋。

## [S2] Design

### 布局（已选：PC 双列 + 对齐重构）
- 账户设置 PC 双列 grid：左列交互/播放器/前缀，右列编辑器+密码；`align-items:start`；卡片高度随内容。
- 控件列契约：`setting-control-row` 右侧 `200px`；number 的单位叠在输入内，右缘与 select 对齐。
- 保存契约：PC 唯一可见保存 = tab 行 `.settings-nav-save`「保存」→ `winfb-settings-save`。播放偏好变更即入库；密码走保存事件。
- AceEditorTheme：至少「默认」+主题列表；themelist 对象/数组均归一化；失败回退内置主题，禁止空白 select。
- 分享空态：居中 + 标题 + 三步 +「去文件列表」。

### 播放器
- 续播：全模式可见反馈；resume=「已续播」+从头播放；from-start=chooser；toast 进 `$player`，DOM 重试注入，z-index 180。
- MKV/容器：扩展名黑名单 → 立即兼容加载层 + 自动 `switchEngine(compat)`；无帧 watchdog 2200ms；加载层仅在真实视频帧就绪后关闭。
- 进页 `usersApi.get` 刷新账号偏好后再读 resumeMode/rate。

## [S3] Out of Scope

- 在线字幕 API；雪碧图 hover（T4/T8）；倍速预设扩容；ArtPlayer 设置面板重设计。

## Tasks

- [x] T1: 续播 toast/notice 全模式可见 — QA: resume「已续播」/ from-start「已看到+跳转播放」(covers: S2)
- [x] T2: MKV 加载层 + 自动兼容 — QA: loadingVisible true +「兼容播放加载中」(covers: S2)
- [x] T3: Profile 双列 + 控件 200px 对齐 + 无模块内保存 — QA: grid 618/397, right 913 (covers: S2)
- [x] T4: AceEditorTheme 非空 — QA: options=44, 200×36 (covers: S2)
- [x] T5: Shares 空态 1+2 — QA: steps=3 + icon + 去文件列表 (covers: S2)
- [x] T6: 全 tab 唯一保存 — QA: GLOBAL bottomSaves/titleSaves=[] (covers: S2)
- [x] T7: 构建部署 + 测试/QA — hash `index-BWOjmIYo.js`；相关 vitest PASS (covers: S2)
