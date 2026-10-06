# 客户端共享代码

`clients/shared` 存放客户端域内的共用实现，不是第二个服务端。

当前只有 [`core`](core/README.md)：Go 网络传输、内嵌 Tailscale 和原生桥接。
Android 使用它；Windows 客户端可以复用适用部分。只有实际出现新的共享模块
才增加目录，不为未来平台预建空文件夹。

API、权限、文件/媒体操作、持久化和 Web UI 在 [`server`](../../server/README.md)
统一维护。共享客户端代码通过同一个 HTTP API 与服务端通信。
