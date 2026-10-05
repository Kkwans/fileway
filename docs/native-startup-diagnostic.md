# 原生启动诊断 checkpoint（2026-10-05）

公开 v0.3 仍为 `35eee1f`，此次诊断未更新公开安装包。使用原批准的 libVLC 3.7.6，TX5Pro 的 API35/x86_64 专用模拟器、仓库内自有 320×180/12秒 MKV fixture、真实 JNI/HTTP/raw 与认证；不使用私人影片或凭据。

## 已确认的失败

- 外挂字幕首轮测试 1/1 通过，19.875 秒：NAS 文件选择、实际字幕字节读取、新 libVLC ID/选中、独立字幕 Surface 白色文字像素。菜单打开时的截图不能证明最终画面呈现。
- 随后的原基线复测在视频启动前置阶段失败，未执行到外挂字幕。20秒等待期限没有提高。状态：缓冲0%，playing=false，position=0，duration=12021ms，raw读取4次，播放器/客户端error=null。
- 同一运行的 SDK 日志出现 `video output creation failed`、`failed to create video output`、`Opaque Vout request failed`。这证明失败落在视频输出建立阶段，不证明最终根因是网络、硬件、视图或引擎版本中的哪一项。
- “布局后绑定、先 updateVideoSurfaces”的候选修改构建通过，但新增 `PlayerSurfaceStartupTest` 仍在 Open0 得到相同状态，39.643秒失败。该候选已撤回，不作为修复交付。

## 可复现验证

工程新增 `PlayerSurfaceStartupTest.repeatedOpenHasDecodedVideoWithPositiveWindowBounds`：连续三次打开，每次要求实际播放时钟与视频 Surface 的彩色解码画面；失败报告阶段、位置、缓冲和读取次数。此测试目前为已知失败诊断，不是绿色正式门禁。

独立运行：

```sh
./gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.kkwans.nasfilebrowser.PlayerSurfaceStartupTest
```

私人证据在仓库外：`player-surface-runtime-20261005.log`、`external-subtitle-diagnostic-runtime-20261005.log`、`external-startup-private-20261005.log` 和 owned fixture 截图。日志输出脱敏，不公开媒体入口、登录链接或节点状态。

## 后续边界

仍需针对同一原生输入比较输出初始化、实际 Surface 状态及设备 codec；新增外挂字幕的命名/关闭控制后呈现测试被此启动失败阻挡，不能称完整通过。真实手机硬解码/HDR、9813秒样本/全部音轨PGS和网络门禁另行验证。

此次 checkpoint 保留已批准架构与验收标准，不把构建成功、metadata 或一次模拟器成功当作正式可用。原有播放时钟/ASS三个诊断工作树修改独立保留，不混入已发布版本。
