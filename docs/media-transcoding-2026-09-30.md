# 视频转码与播放体验改进

## 需求和验收

- MKV 是容器，不按扩展名强制重新编码视频。按浏览器解码能力、视频编码和音频编码选择直接播放、重新封装、仅转音频或完整转码。
- 文件列表支持单文件、多选和目录中的视频后台处理；提交前选择分辨率，默认原始分辨率，不放大低分辨率来源。
- 后台任务显示媒体时长进度、倍速、FPS、剩余和预计总耗时。排队、准备、编码、成品整理、反馈中断和无产出有明确状态；没有有效速度时不提供虚假估算。
- 兼容播放显示来源总时长，允许键盘、触控和进度条跳转到未转码位置；跳转后按目标位置生成可播放内容。预览缩略图使用来源时间线。
- 操作提示位于进度条上方右侧，续播提示在同一行左侧；窄屏文字换行，不与文件名重叠。
- 成品默认保存在各源文件所在目录，包括递归子目录和跨目录多选；用户可指定统一目录。复用现有目录选择、权限检查、任务取消/重试和事件推送，写入验收只用隔离数据。

## 当前事实和设计

- 起点 master / 65c7d002，运行镜像 2026.9.30-v3。保留工作树中用户的 Docker、Compose、README、CI、构建脚本和静态产物修改。
- 当前 H.264 编码线程和软件滤镜线程固定为 1；HLS 没有向任务中心报告 FFmpeg 进度，WebM 只有缓存状态中的已处理秒数。
- NAS 为 RK3588S，宿主机已有 RKMPP 编解码、RGA 缩放和 Mali OpenCL；生产容器没有相关设备和 FFmpeg 支持。硬件启用须有专用运行镜像和最小设备映射，保留原软件路径，不采用 privileged 或 host network。
- 2026-09-30 真实 12.47 秒 3840×1600 HEVC 10-bit HDR 样本：软件单线程 HDR→SDR + H.264 耗时 79.215 秒，四线程 53.335 秒；RKMPP 解码/RGA 缩放/H.264 编码耗时 1.892 秒（此轮没有色调映射，不能当作等价比较）；加入 OpenCL 色调映射的完整链路成功，末段 FFmpeg 报告 2.85×。
- 进度使用 FFmpeg progress 数据块，out_time_us 和 out_time_ms 均按微秒解析；速度无效、排队和停滞不估算剩余时间。预计总耗时 = 实际编码耗时 + 剩余媒体秒数 / 当前报告速度，不包含未知的排队等待。

## 交付和验证

每个独立功能切片执行 lint/typecheck、相关 Go 测试和 vet、聚焦前端测试、diff 检查与 CR 后提交和推送。全部完成后运行全量测试、隔离生产构建和真实 Playwright；生产验收保持只读。最终发布记录明确 SHA、镜像和旧版本回滚点；不清理旧镜像、容器或用户数据。

已完成代码门禁的切片：播放器反馈布局 1435ca76；转码遥测切片见 Git 后续记录。尚未代表最终部署或整体视觉验收完成。

## RK3588 运行路径

- 软件路径默认启用最多 4 个解码、滤镜和编码线程，可通过 `FB_HLS_ENCODE_THREADS=1..4` 限制；重新封装不启用视频编码。
- 可选 `Dockerfile.rk3588` 和 `docker-compose.rk3588.yml` 使用固定 digest 的 Jellyfin 10.11.11 FFmpeg 运行时。只覆盖 filebrowser，现有配置、数据库、端口和缓存卷继续使用。运行镜像包含上游播放器运行文件，但入口为 File Browser，不启动 Jellyfin 服务。
- `FB_HLS_ACCELERATOR=rkmpp` 使用 MPP 解码、RGA 缩放/PGS 合成、OpenCL HDR→SDR。H.264 使用固定 QP 18；VP9 保留软件编码并使用硬件解码与滤镜。其他编码或超过 4096 的来源宽/高继续软件路径，任务指标显示实际执行方式。
- 真实隔离容器验证发现：默认容器屏蔽 `/sys/firmware`，MPP 无法读取设备树，误判为 unknown；缺少 `/dev/dri/card0` 时分配器不可用。需只读设备树挂载、systempaths 配置及明确的 MPP/RGA/Mali/DMA/DRM 设备映射；未使用 privileged、host network 或修改宿主机权限。
- 完整 HDR 1080p 硬件链路末段报告 3.94×；实际代码的 MP4+实时 HLS 共用一次编码，HDR 和 HDR+PGS 两项成品探测、完整解码均通过，字幕截图已确认。7.33/7.94 秒测试耗时包含编码、探测和软件解码回归，不能当作纯转码速度。
- 回滚到此前 v3 镜像并只使用原 Compose 文件，即恢复原软件运行路径；保留旧镜像、配置和缓存数据。

## 调研依据

- [MDN：媒体容器与编码](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Containers)
- [FFmpeg：stream copy 和 progress](https://ffmpeg.org/ffmpeg.html)
- [Jellyfin：Rockchip 硬件转码](https://jellyfin.org/docs/general/post-install/transcoding/hardware-acceleration/rockchip/)
