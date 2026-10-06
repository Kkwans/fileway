# 栖卷 Fileway App：新会话入口

项目目录：`/volume2/Project/fileway`，Android组件目录：`clients/native`。
先读取根与组件AGENTS.md、`docs/migration.md`，再读：

- [媒体体验计划输入](../clients/native/docs/media-experience-plan-input.md)
- [组件/图标素材研究](../clients/native/docs/media-reuse-research.md)
- [真机与HDR验收](../clients/native/docs/device-media-acceptance.md)
- [开发工作流](../clients/native/docs/development-workflow.md)
- [此前交接](../clients/native/docs/handoff-2026-10-06.md)

历史文档中的旧仓库绝对路径是来源记录，不是新的编辑/提交目标。
本入口不表示仓库迁移所有门禁已完成，先核对迁移账本与最新Git状态。

新会话先Plan：核验现有功能、用户失败反馈与参考，给出完整设计、复用决策、
状态机、切片、门禁和交付计划；用户确认/点击执行后再Goal自动持续实现。
不要在Plan阶段直接安装依赖、修改App或发布。执行获授权后，每个独立切片
验证、精确commit并立即FF push，最终完整回归及已授权预览交付。

图片翻页/渐进加载、视频队列/播放列表、左右手势/分区双击、字幕能力分层、
真实速度/缓冲可视化、图标/轨道/加载态重做和切字幕/音轨/倍速卡顿丢声都
属于计划输入，不只是换皮肤。默认保留原生libVLC＋Go/tsnet，重大引擎变更需
通过能力/许可/迁移成本比较和用户确认。产品名栖卷/Fileway不要求改变旧包ID。

用户提供小米14，最早2026-10-07晚（Asia/Shanghai）经TX5pro配合ADB；具体
时段未定。Tailscale外网已有USER_VERIFIED成功，HDR/音频/长片等另验。个人
手机不得默认清数据、卸载或重建节点。设备未到场时继续独立任务，不假称通过。

旧客户端三处NativePlayer/NativePlaybackTest/NativeSubtitleTest实验没有作为
正式源码迁入，仍保留原工作树及仓库外私有备份。需要研究时读取补丁比较，
不得无条件应用或混入普通功能提交。Windows原生客户端后续单独规划。
