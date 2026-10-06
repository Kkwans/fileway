# 原生媒体组件与视觉素材候选

2026-10-06 GitHub API及官方README核验。星数是当日快照，不是质量评分；以下为进入Plan比较的候选，未安装到App、未编译验证，不能据此宣称兼容本项目。导入时需锁定revision、检查具体文件许可/依赖/引擎版本并运行聚焦试验。

## 优先评估

| 项目 | 当日Stars | 类型/适配判断 |
| --- | ---: | --- |
| [Telephoto](https://github.com/saket/telephoto) | 1,559 | Compose图片缩放、平移及大图subsampling，Apache-2.0；优先评估与当前Coil3/受认证lease/cache集成，配合Compose Pager，不自己重写整套缩放手势 |
| [ZoomImage](https://github.com/panpf/zoomimage) | 661 | Compose/View缩放、旋转与大图分块备选，Apache-2.0；热度低于Telephoto，只有匹配性明显更好才优先，不并装两套 |
| [Next Player](https://github.com/anilbeesetti/nextplayer) | 4,477 | 成熟原生播放器应用参考，Kotlin/Compose、左右亮度/音量等；GPL-3.0，当前README说明ExoPlayer/扩展路线，不是即插即用libVLC控件，ASS样式有明确限制 |
| [VLC Android](https://github.com/videolan/vlc-android) | 4,044 | 与现有libVLC引擎更接近，优先研究队列、手势和字幕设置实现；完整App不是可直接嵌入UI库。仓库级API标GPL-2.0，实际复用文件和LibVLC模块分别核对LICENSE，不能把全仓许可等同全部模块 |
| [GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer) | 21,506 | 热门Android播放器组件，Apache-2.0，支持多种引擎及手势/缓存；现有Compose＋libVLC＋tsnet匹配性需证明，不为高星重写媒体链路 |
| [Material Symbols](https://github.com/google/material-design-icons) | 54,077 | Android首选图标候选，Apache-2.0；统一Rounded/Outlined等一套风格，按需导入官方Android XML，不引入全量巨大图标依赖 |
| [Lucide](https://github.com/lucide-icons/lucide) | 24,876 | 克制统一的线性图标备选；API许可字段NOASSERTION不表示无许可，导入前读实际LICENSE和所用素材声明；优先官方SVG转换或经验证的原生封装 |
| [Tabler Icons](https://github.com/tabler/tabler-icons) | 21,917 | MIT，完整线性图标备选；与Lucide/Material择一为主，不按每个按钮喜好混搭 |

官方Compose文档建议采用Google Fonts图标的Android XML以使用当前Material Symbols：[Icons in Compose](https://developer.android.com/develop/ui/compose/graphics/images/material)。原生控件复用与高质量视觉不矛盾：可以复用手势/语义/渲染底座，使用统一产品tokens与图标，而不是复制整个示例App。

优先方案是保留已批准libVLC媒体链路与Go/tsnet认证/Range/缓存边界，评估可分离的UI/交互复用。若候选要求更换引擎，必须在Plan比较PGS/ASS/TrueHD/HDR、原生库体积/ABI、设备兼容、认证Range、生命周期和回归成本，再由用户批准重大架构变更。Next Player的Media3控件不能直接假定适配libVLC；也不要仅为套控件写庞大假Player适配层而不核算成本。

## 用户提到的六个项目：并非六个skill

| 项目 | 当日Stars | 实际用途 | 本项目处理 |
| --- | ---: | --- | --- |
| [Rough.js](https://github.com/rough-stuff/rough) | 21,238 | JS Canvas/SVG手绘风绘图 | 不解决原生播放器UI、标准图标或加载态；不为它引入WebView |
| [Radix Colors](https://github.com/radix-ui/colors) | 1,689 | 色阶/配色系统，MIT | 可评估转换为Compose语义颜色tokens；不直接安装npm到Android，也不把色板当对比度认证 |
| [Markdown Badges](https://github.com/Ileriayo/markdown-badges) | 17,102 | README徽章集合 | 文档装饰，不改善App播放器；不作为当前运行时依赖 |
| [Simple Icons](https://github.com/simple-icons/simple-icons) | 25,969 | 品牌标志SVG | 不是播放/快退/列表等操作图标主库；需要品牌标志时按素材使用，检查商标及许可 |
| [Fontsource](https://github.com/fontsource/fontsource) | 6,170 | 自托管字体npm分发 | Web项目可用；Android选合适字体文件与各字体许可/中文字形/包体/回退，不导入npm运行时 |
| [GitHub README Stats](https://github.com/anuraghazra/github-readme-stats) | 79,826 | GitHub统计卡片 | 文档展示，不是UI设计/播放器库，不安装到App |

此前技能安装没有将这些项目作为App依赖安装；这不是它们不存在或质量差，而是用途/集成层不同。`impeccable`、`frontend-design`、`android-native-dev/design-craft`覆盖排版、颜色、图标一致性和动效的选择/验收，但不会自动将素材或组件加入Android项目。安装skill也不能证明此前播放器界面已达标。

## Plan复用验收与产物

- 每个选定依赖记录源码/版本/许可、兼容现有Compose/Coil/AGP的证据、可维护性和替代方案；不照抄README中的旧依赖版本。
- 图片组件试验需证明受认证流与大图随机读取/缓存可用，Pager与缩放不抢手势；不能只验证公网JPEG样例。
- 播放器组件试验需证明现有libVLC/字幕Surface/Go代理不被绕过；播放器应用作为参考与作为直接依赖必须分清。
- 图标验收包含光学居中、线宽、实际尺寸、明暗背景、禁用/选中态、无障碍标签以及来源文件；进度条使用原生可靠语义/拖动逻辑，按项目版本正确实现定制track。
- 素材和组件选择落实到批准后的代码切片。当前只完成研究与计划输入，没有新增项目依赖或宣称实现通过。
