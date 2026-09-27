# Windows 通用改进回迁记录

- 来源：Kkwans/win-file-browser@0d1886faf0ba4234160ffa9630c5dfb8539c030a
- NAS 起点：master@a202d0e627d2f10a45a4dbc69c728c3f71090204
- 基线运行镜像：nas-file-browser:2026.9.6-v14；发布前重新核验。
- 既有工作区改动：CI、Dockerfile.custom、README、Compose、push-acr 脚本、嵌入前端产物及未跟踪文档/缓存/数据；不纳入本任务提交。
- 每个切片通过门禁后立即 commit/push，最终统一部署。

## 来源差异清单

| 路径 | 处理 |
| --- | --- |
| `frontend/src/api/media.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/api/utils.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/api/volumes.ts` | Windows 专属排除；保留 NAS 实现 |
| `frontend/src/components/Sidebar.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/components/files/ArtPlayerVideo.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/components/files/VideoPlayer.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/components/prompts/PathPicker.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/components/settings/Rules.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/components/ui/AppSelect.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/css/settings.css` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/css/styles.css` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/stores/categories.ts` | Windows 专属排除；保留 NAS 实现 |
| `frontend/src/stores/volumes.ts` | Windows 专属排除；保留 NAS 实现 |
| `frontend/src/types/user.d.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/__tests__/previewLifecycleContract.test.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/__tests__/settingsUiContract.test.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/__tests__/tokenExpiration.test.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/__tests__/videoPlayback.test.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/fileListing.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/playerControls.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/storageSize.ts` | Windows 专属排除；保留 NAS 实现 |
| `frontend/src/utils/tokenExpiration.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/utils/videoPlayback.ts` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/Settings.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/files/FileListing.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/files/Preview.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/settings/Global.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/settings/Profile.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/settings/Shares.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/settings/User.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `frontend/src/views/settings/Users.vue` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/analysis/storage.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/cmd/root.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/cmd/users.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/files/created_time.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/files/drivefs_errors.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/files/drivefs_other.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/files/drivefs_windows.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/files/listing.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/files/listing_sort_windows_test.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/files/virtual_root.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/hls/service.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/hls/service_test.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/auth.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/categories.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/http/duplicate_cleanup_test.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/ffmpeg_path.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/hls.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/hls_ffmpeg_stub_test.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/hls_test.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/http.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/image_preview.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/media_info.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/public.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/resource.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/share.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/stat_helper.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/http/token_expiration_test.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/users.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/video_preview.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/video_sprite.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/http/volumes.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/http/volumes_other.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/http/volumes_windows.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/risk/risk.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/settings/dir.go` | Windows 专属排除；保留 NAS 实现 |
| `backend/users/resume_min_sec_test.go` | NAS 适配；按相关功能切片迁入并回归 |
| `backend/users/users.go` | NAS 适配；按相关功能切片迁入并回归 |

## 最终交付索引

以下提交均按顺序独立验证、提交并立即推送到 `origin/master`。详细实施记录保留在下方，记录中的“尚未部署”描述的是当时切片交接状态。

| 顺序 | 功能 | Commit | 主要代码门禁 |
| --- | --- | --- | --- |
| 1 | 通用选择控件、PathPicker 与设置样式 | `2566a093` | typecheck、lint、4 文件 17 项前端测试 |
| 2 | 账户设置与播放器偏好契约 | `356372f9` | typecheck、lint、前端 6 项测试、Go users/http |
| 3 | 全局设置与统一保存 | `d5502c9c` | typecheck、lint、前端 6 项测试、生产构建 |
| 4 | 用户管理页面 | `d145c563` | typecheck、lint、前端 3 项测试 |
| 5 | 分享管理页面 | `0a9ecce8` | typecheck、lint、前端 3 项测试 |
| 6 | 会话上限扩展 | `aa35d1a4` | 前后端会话边界测试、typecheck、lint |
| 7 | 排序偏好持久化 | `128f329d` | 前端 8 项与后端测试、typecheck、lint |
| 8 | HLS 画质及媒体支持 | `21023bf8` | 前端 16 项、Go http/users、typecheck、lint |
| 9 | ArtPlayer 默认播放 | `4135c047` | 前端 12 项、Go hls/http、typecheck、lint |
| 10 | 字幕与续播 | `4bbddcb6` | 前端 26 项、typecheck、lint、生产构建 |
| 11 | 视频进度预览 | `eea3533d` | 前端 26 项、typecheck、lint |
| 12 | 等比例缩略图 | `1a9543ab` | Go 雪碧图/HLS、前端 12 项、typecheck、lint |
| 13 | Windows 图标视图 | `abc62d6c` | Go http、前端 23 项、typecheck、lint |
| CR 1 | 默认媒体请求与视图契约 | `1463ad43` | 前端全量 450 项、Go 全量、vet、生产构建 |
| CR 2 | WebM 真实画质转码 | `8bc2b838` | HLS 回归与 Go 全量、前端门禁 |
| CR 3 | 透明 PNG 缩略图 | `99e02d9d` | 真实编码回归、Go 全量、vet |
| CR 4 | 预览标题与兼容播放入口 | `4e76bbd7` | 前端全量 451 项、typecheck、lint、生产构建 |
| CR 5 | 视图切换时刷新缩略图适配模式 | `236ba891` | mediaLoadContract 15 项、typecheck、lint、diff-check |
| CR 6 | 真实验收返回状态 | `ab690c59` | typecheck、lint、真实浏览器历史恢复 |
| CR 7 | 移动端设置卡片高度 | `b8eae420` | typecheck、lint、390px 前后尺寸与截图 |
| CR 8 | 播放器弹层点击层级 | `5c26e146` | typecheck、lint、真实兼容播放点击与 WebM 播放 |
| CR 9 | 竖屏画质双边界转码 | `e8f05e86` | FFmpeg 实际尺寸回归、Go hls/http、vet、真实 480p 成品 |
| CR 10 | 移动端字幕工具栏 | `eee1d9f8` | typecheck、lint、390px 实际尺寸与截图 |
| CR 11 | 真实验收脚本对齐当前导航语义 | `fac12b78`、`1d7a29b6`、`9bb5d433` | 精确菜单、Escape 焦点返回、目录分类名称、真实浏览器矩阵 |

### 播放器来源版整段回迁纠偏

原 CR 10 的底部字幕工具栏不符合 Windows 来源版的交互，本提交以固定来源 `win-file-browser@0d1886faf0ba` 的完整 `ArtPlayerVideo.vue` 为基线回迁模板、逻辑与样式，字幕恢复到 ArtPlayer 原生“设置 → 字幕”菜单；播放模式、预设/自定义倍速、分辨率选择和独立控制条弹出菜单也使用来源实现。旧工具栏不再显示。

必要的 NAS 适配仅包括：`2160p/1440p` 转成 NAS HLS 接口的 `4k/2k`、无 H.264 解码时选择 WebM、等待 NAS 转码任务返回可播放地址、使用会转换为 VTT 的受保护字幕接口、沿用 NAS 的账号偏好保存队列与雪碧图接口、将本地字幕和续播标识按账号隔离。来源中字号/位置二级菜单的回调位置、扫描后未刷新轨道菜单、关闭字幕刷新后被重新启用、切换媒体时倍速被重置等缺陷按 ArtPlayer 5.4 的实际调用行为做了局部修正。真实浏览器回归脚本为 `frontend/e2e/subtitle-menu.real.spec.ts`，使用隔离媒体样本，不写入账号播放偏好。

下方“最终全量代码门禁”和运行验收记录是上一次发布的历史证据；本次回迁以新提交与部署后的门禁、截图和播放结果为准。

### Video.js 显式诊断回退来源版回迁

`?player=videojs` 保留为显式诊断入口。`VideoPlayer.vue` 整体采用同一固定 Windows 来源版，补回其播放模式按钮、账号倍速预设、自定义倍速控件、原生/兼容/询问操作和相应样式；`videoPlayback.ts` 同步补回 HEVC 识别与失败提示。仅将偏好写入改接 NAS 已有的账号保存队列，并去除来源中未使用的辅助代码。诊断参数不再混入文件预览标题。独立浏览器回归脚本为 `frontend/e2e/videojs-diagnostic.real.spec.ts`。

最终全量代码门禁：前端目录执行 `corepack pnpm test`（108 文件、452 项）、`corepack pnpm run typecheck`、`corepack pnpm run lint`；后端目录执行 `go test ./...`、`go vet ./...`；仓库根目录执行 `git diff a202d0e627d2..HEAD --check`，均通过。生产镜像的前端阶段再次执行 typecheck 与生产构建。NAS 缺少 GCC，`go test -race` 未执行。镜像从干净检出构建，未采用工作区已有的 `backend/frontend/dist`。

运行验收：ARM64 镜像启动后 `/health` 与 `/login` 返回 200，未授权访问受保护的雪碧图和等比例缩略图接口均返回 401；真实 Chromium 登录页在 1440/1024/768/390 宽度正常加载且无横向溢出或页面异常。使用授权 admin 账号检查了 Windows 图标四档尺寸、390/768/1024/1440 宽度、真实图片目录和隔离媒体目录；长达 178 字符的无空格名称完整换行，宽图、长图、横竖视频封面均返回 `fit=contain` 缩略图且保持原比例。搜索、最近访问、回收站、存储工具、任务中心和四个设置页面均无页面错误或横向溢出；移动端用户与分享卡片按内容收起，不再保留 30rem 空白高度。图标视图的账号偏好在验收后恢复为原有 `mosaic`。

媒体验收使用 `/tmp` 下隔离生成的 4 秒 H.264 横屏和 HEVC 竖屏样本，不改动用户媒体。原生解码失败时错误弹层可点击，兼容播放真实生成 VP9/Opus WebM 并播放；同名 VTT 字幕加载并显示，桌面与 390px 工具栏可用。竖屏 480p 成品实测为 270×480，新缓存键不命中旧的宽度单边界结果。雪碧图元数据为 5 格、50×90、1 秒间隔，图片接口返回 JPEG。测试结束后播放器和视图账号偏好均恢复。真实验收脚本原先依赖不存在的 aria 名称，已改为浏览器历史返回并验证原目录查询参数恢复。

最终真实部署矩阵 `real-acceptance.spec.ts` 用时 2.2 分钟并通过：浅色/深色、12 个基础宽度、2 个设置边界宽度，共记录 196 组几何测量和 196 张截图；横向溢出、console error、page error、失败 API 响应均为 0。矩阵中的原生视频页如实展示 NAS Chromium 的解码失败提示，兼容播放的实际画面、字幕和移动工具栏由独立聚焦验收覆盖。

回滚资产保留：原 `nas-file-browser:2026.9.6-v14` 镜像、发布前 Compose 与设置备份，以及前一版发布镜像均未清理。切换仅操作 `filebrowser` 服务；`/config`、`/database` 和媒体缓存挂载保持不变。

## 切片验证记录（实施时快照）

### 1. 通用控件与路径选择器

- 迁入 AppSelect、主题选择、规则表单和共享样式；补齐键盘选择/焦点/禁用语义及唯一 ID。
- 文件模式保留目录导航，但禁止选择目录；扩展名筛选大小写不敏感。
- 验证：前端 typecheck、lint、settingsUiContract/copyMoveUiContract/analysisAccessibilityContract/fileListIconContract；结果随门禁更新。
- 部署：尚未部署；真实浏览器及最终发布验收待完成。

### 2. 账户设置与播放器偏好

- 切片 1：`2566a093`，已 push master；typecheck/lint 通过，4 个测试文件 17 项通过。
- 账户页复用 Windows 分区布局；账号偏好串行保存，失败保留输入，账号切换隔离延迟响应。
- PlayerPreferences 在用户存储层合并局部更新，保留旧账号默认；会话上限暂不变。
- 聚焦门禁：accountPreferences/settingsUiContract；Go users/http；typecheck/lint/diff-check。
- 尚未部署，回滚候选仍为 v14；浏览器验收待最终发布。
- 结果：前端 2 文件 6 测试、typecheck、lint、Go users/http 通过。`CGO_ENABLED=1 go test -race ./users` 因 gcc 缺失无法执行；不安装系统编译器，竞态检测标为未验证。

### 3. 全局设置与统一保存

- 切片 2：`356372f9`，已 push master。
- 复用来源布局；通过当前路由组件公开保存方法接入唯一顶部入口，移除全局 window 保存事件。
- 全局设置请求去重，保存前读取最新分块大小；失败保留输入，成功后应用主题。
- typecheck/lint 与 2 文件 6 项聚焦测试通过；生产构建单独验证。
- 尚未部署，真实目标验收待完成；回滚候选 v14。

### 4. 用户管理

- 切片 3：`d5502c9c`，已 push；生产构建通过（保留既有大 chunk 提示）。
- 用户列表迁入来源标识；编辑页接入唯一顶部保存，保留原生表单验证与当前密码确认。
- 保存请求防重，提交快照不随表单输入漂移；明文密码不写入 auth store。
- 验证：settingsUiContract 3 项；typecheck/lint/diff-check。尚未部署，真实交互验收待完成。

### 5. 分享管理

- 切片 4：`d145c563`，已 push master；typecheck/lint 和 settingsUiContract 3 项通过。
- 迁入分享空态和复制反馈；删除仅在服务端确认成功后更新列表，并防止重复删除请求。
- 聚焦验证：settingsUiContract、typecheck、lint、diff-check。尚未部署，真实交互验收待完成。

### 6. 会话上限

- 切片 5 提交后记录 SHA；扩展允许范围为 10 分钟至 30 天，现有默认值与已保存值不变。
- 前后端使用同一边界，覆盖 24 小时兼容、30 天上限和超限拒绝。
- 尚未部署；部署前后均核对现有设置未被自动改写。

### 7. 排序偏好

- 切片 6：`aa35d1a4`，已 push；前端 8 项与后端边界测试通过。
- 登录账号将完整 `{ by, asc }` 保存到用户记录并写入登录响应；访客使用 `nas-file-browser-guest-sorting-v1`。
- 保留明确的降序选择，不迁入 Windows 版强制名称升序迁移。
- 尚未部署，刷新、重新登录和账号隔离留待最终浏览器验收。

### 8. HLS 画质与媒体后端

- 切片 7：`128f329d`，已 push；16 项前端测试及 Go http/users 通过。
- HLS 创建接口接受受限 quality 值；显式画质强制真实转码，目标宽度进入 profile 与缓存标识，且不放大小于目标的源视频。
- 保留 NAS 的 FFmpeg 路径、任务系统、单任务并发、取消、权限与缓存清理实现。
- 尚未部署；真实 FFmpeg 编码、任务进度和浏览器播放留待最终环境验收。

### 9. ArtPlayer 默认播放器

- 切片 8：`21023bf8`，已 push；Go hls/http、前端 typecheck/lint 与 12 项播放测试通过。
- ArtPlayer 5.4 与 hls.js 1.7 按官方 API 接入，默认启用；`?player=videojs` 保留显式诊断回退。
- 原生/兼容模式切换保留进度、倍速与播放状态；退出时销毁 ArtPlayer、HLS、轮询和媒体资源。
- HLS 请求使用现有 X-Auth；不把账号 token 写入 URL 或本地播放记录。

### 10. 字幕、倍速与续播

- 切片 9：`4135c047`，已 push；typecheck/lint、26 项聚焦测试与生产构建通过。
- 字幕文件/字号/位置/偏移按账号保存在浏览器；播放策略、续播阈值和倍速保存到账户。
- 自动续播、从头播放和询问三种策略生效；询问提示 6 秒关闭，默认兼容模式仍保留待恢复位置。
- 保存失败在播放器内反馈；退出时清理提示、倍速保存及兼容轮询计时器。

### 11. 视频进度预览

- 切片 10：`4bbddcb6`，已 push；typecheck/lint 与 26 项聚焦测试通过。
- 新增受权限保护的雪碧图元数据/图片接口；生成任务单并发、相同请求去重、结果进入预览缓存。
- 单格按源视频比例等比缩入 160×90，元数据返回真实单格宽高、数量、列数和采样间隔；不再假定 16:9。
- ArtPlayer 异步加载进度图；探测或生成失败只关闭预览图，不阻断视频播放。

### 12. 等比例缩略图

- 切片 11：`eea3533d`，已 push；Go 雪碧图/HLS 测试、typecheck/lint 与 12 项前端契约测试通过。
- `fit=contain` 仅供新图标视图使用；图片缩入 512×512，视频封面沿用原比例生成，前端使用 `object-fit: contain`。
- contain 使用 `preview:v4` 独立缓存键，不命中或清除现有裁切缩略图；未启用缩略图时返回不可用并由前端显示类型图标。

### 13. Windows 图标视图

- 切片 12：`1a9543ab`，已 push；Go http、typecheck/lint、23 项前端测试通过。
- 新增独立 `windows-icons`，小/中/大/超大四档尺寸，独立本地偏好，原有四种视图保持不变。
- 等宽 CSS Grid、同一行自动等高，图标区域固定高度，文件名完整换行；图片/视频封面 `contain`，仅图标与名称常驻。
- 复用文件项的选择、键盘、长按、拖放、右键菜单、分组和分页逻辑；视觉验收待部署后执行。

### 发布前 CR 补充

- `1463ad43` 已 push；108 个前端测试文件 450 项通过，Go 全量测试与 vet 通过，生产构建通过。
- CR 发现显式画质在 WebM 兼容路径被忽略；新增 WebM 独立 profile 和实际缩放，保留 WebM 默认路径原有拷贝优化。
- 最终镜像必须从此补充提交后的明确 SHA 重新构建；`1463ad43` 的构建已停止。
- 等比例透明 PNG 缩略图保留 PNG 格式与 alpha；新增宽图和透明角像素的真实编码回归测试。
- 预览标题复用来源的无底色排版；导航栏自动隐藏时长使用账户偏好。ArtPlayer 原生播放失败时提供明确的兼容播放按钮。
