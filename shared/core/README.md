# Fileway 客户端网络核心

这是 Go 客户端模块，不是文件服务器，也没有另一套 Web UI。

| 目录 | 职责 |
| --- | --- |
| `transport` | HTTP 传输、请求取消、播放数据/缓存边界 |
| `tailnet` | 内嵌 Tailscale 节点及网络生命周期 |
| `bridge` | 原生客户端可调用的桥接接口 |
| `mobile` | Android Go 原生库入口 |

Android 的 [`build-native.sh`](../../clients/android/scripts/build-native.sh) 从这里
生成 `generated/<ABI>/libnfbcore.so`，Gradle 使用同一路径打包。`generated` 是
构建产物，不提交 Git。Windows 客户端尚未实现，计划复用不等于已完成。

旧模块路径暂时保留兼容性，产品名为栖卷 · Fileway。此组件使用 GPL-3.0，见
[LICENSE](LICENSE)。使用 `go.mod` 指定的版本，在此目录执行：

```sh
go test ./transport ./bridge
go vet ./transport ./bridge
```
