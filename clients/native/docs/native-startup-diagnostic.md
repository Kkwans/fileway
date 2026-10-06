# 原生启动诊断 checkpoint（2026-10-05）

公开 v0.3 仍为 `35eee1f`，此次诊断未更新公开安装包。使用原批准的 libVLC 3.7.6，TX5Pro 的 API35/x86_64 专用模拟器、仓库内自有 320×180/12秒 MKV fixture、真实 JNI/HTTP/raw 与认证；不使用私人影片或凭据。

## 已确认的失败

- 外挂字幕首轮测试 1/1 通过，19.875 秒：NAS 文件选择、实际字幕字节读取、新 libVLC ID/选中、独立字幕 Surface 白色文字像素。菜单打开时的截图不能证明最终画面呈现。
- 随后的原基线复测在视频启动前置阶段失败，未执行到外挂字幕。20秒等待期限没有提高。状态：缓冲0%，playing=false，position=0，duration=12021ms，raw读取4次，播放器/客户端error=null。
- 同一运行的 SDK 日志出现 `video output creation failed`、`failed to create video output`、`Opaque Vout request failed`。这证明失败落在视频输出建立阶段，不证明最终根因是网络、硬件、视图或引擎版本中的哪一项。
- “布局后绑定、先 updateVideoSurfaces”的候选修改构建通过，但新增 `PlayerSurfaceStartupTest` 仍在 Open0 得到相同状态，39.643秒失败。该候选已撤回，不作为修复交付。

## 可复现验证

工程新增 `PlayerSurfaceStartupTest.repeatedOpenHasDecodedVideoWithPositiveWindowBounds`：连续三次打开，每次要求实际播放时钟与视频 Surface 的彩色解码画面；失败报告阶段、位置、缓冲和读取次数。默认 Maven 依赖的基线为已知失败；下面记录私有字体试验包的独立通过结果，不把两者混为同一产物。

独立运行：

```sh
./gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.kkwans.nasfilebrowser.PlayerSurfaceStartupTest
```

私人证据在仓库外：`player-surface-runtime-20261005.log`、`external-subtitle-diagnostic-runtime-20261005.log`、`external-startup-private-20261005.log` 和 owned fixture 截图。日志输出脱敏，不公开媒体入口、登录链接或节点状态。

## 后续边界

仍需针对同一原生输入比较输出初始化、实际 Surface 状态及设备 codec；新增外挂字幕的命名/关闭控制后呈现测试被此启动失败阻挡，不能称完整通过。真实手机硬解码/HDR、9813秒样本/全部音轨PGS和网络门禁另行验证。

此次 checkpoint 保留已批准架构与验收标准，不把构建成功、metadata 或一次模拟器成功当作正式可用。原有播放时钟/ASS三个诊断工作树修改独立保留，不混入已发布版本。

## 诊断收尾：2026-10-06

本次收尾没有替换生产依赖、硬件解码偏好或公开 APK。以下运行发生于 2026-10-05；2026-10-06 核对最终日志。

| 比较项 | 实际结果 | 结论边界 |
| --- | --- | --- |
| 原依赖与布局后绑定候选 | Open0 失败，39.643秒 | 布局候选撤回，未作为修复提交。 |
| 私有 software-only 候选 | 失败，37.910秒；playing=true、buffer=100%、time=0 | 强制软件解码未解决启动，不进入主线。 |
| software-only + verbose 诊断 | FreeType/fontconfig 初始化耗时 26,487,768µs，超过20秒期限；窗口曾达到1080×607 | 字体扫描是有证据的瓶颈；超时拆卸后出现的输出错误不能单独证明缺失 GLES 模块。 |
| 主线播放器设置 + 私有 FreeType Android 字体发现库 | `PlayerSurfaceStartupTest` 1项通过，17.158秒；该方法内部连续三次打开并检查视频 Surface 彩色解码像素 | 仅API35/x86_64、自有12秒fixture。该轮没有清空应用数据；不证明冷安装、手机、HDR、ASS、全片或p95达标。 |

最后一轮先从已推送源码恢复构建镜像的 `NativePlayer.kt`（硬解码偏好保持原值、无 verbose 候选），`assembleDebug`/`assembleDebugAndroidTest` 52秒通过，再仅替换私有 APK 中三个 x86_64 原生库。APK SHA256：`663349e37886636babf49f7f047fb9c76701862ef07899574c4bd049b8f11278`。测试通过 adb 直接启动，避免 Gradle 重装官方依赖 APK 覆盖候选。

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e class io.github.kkwans.nasfilebrowser.PlayerSurfaceStartupTest \
  io.github.kkwans.nasfilebrowser.test/androidx.test.runner.AndroidJUnitRunner
```

三个试验库 SHA256（该轮为 FreeType-only，不是后来多补丁 ASS/seek 试验）：

- libvlc.so：`69c430c1eddafc4427e99109c200433dd0c584b56d584bff2da2314c9f0343cc`
- libvlcjni.so：`443f50edd7f12df74e5f050094aa841c094048031fe03bdd7e845c4a908a26a7`
- libc++_shared.so：`e4cd73c8a3607269f3be58d15c21f78bff112e27f9398d6261e5f965668f8746`

原理是在 Android FreeType renderer 内选用现有 Android 字体 XML provider；仍保留 libass 所需 fontconfig。源码基础锁定于 `native/vlc-font-backend.lock.json`，历史试验及未解决 ASS 问题见 `native-font-backend.md`。不采用禁用字幕、提高超时或降低实际像素断言来制造通过。

仓库外证据：`font-provider-baseline-build-20261005.log`、`font-provider-repack-20261005.log`、`font-provider-root-runtime-20261005.log`、`software-only-root-runtime-20261005.log`、`software-verbose-root-runtime-20261005.log`。专用模拟器已在有界运行结束后退出。旧的三处暂停 seek/ASS 工作树实验保留并在私有交接包备份，未进入提交。

**结案范围是诊断对比完成，不是启动缺陷关闭。** 后续先核对试验源码/产物一致性，再执行清数据重复启动、双ABI可复现构建、文本/ASS字体效果/PGS/seek以及真机硬解码/HDR门禁，之后才决定是否采用字体后端修订。不要重复已经失败的布局和 software-only 猜测。
