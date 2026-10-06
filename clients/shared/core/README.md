# Fileway 客户端网络核心

这是 Go 客户端模块，不是文件服务器，也没有另一套 Web UI。

| 目录 | 职责 |
| --- | --- |
| `transport` | HTTP 传输、请求取消、播放数据/缓存边界 |
| `tailnet` | 内嵌 Tailscale 节点及网络生命周期 |
| `bridge` | 原生客户端可调用的桥接接口 |
| `mobile` | Android Go 原生库入口 |

Android 的 [`build-native.sh`](../../android/scripts/build-native.sh) 从这里
生成 `generated/<ABI>/libnfbcore.so`，Gradle 使用同一路径打包。`generated` 是
构建产物，不提交 Git。Windows 客户端尚未实现，计划复用不等于已完成。

## 已核验的复用边界

`transport`、`bridge`、`tailnet` 的受控单元测试已在 Windows 原生环境运行通过，
CI 继续分别检查 Linux 和 Windows。这支持复用 Go 网络/控制逻辑，不代表 Windows
客户端、DLL/.NET 绑定或真实 Tailscale 登录已经完成验收。

`tailnet/platform_android.go` 只处理 Android 网络接口和日志目录；其他平台使用
Tailscale 原生的系统接口。Android JNI/C++ 在 `clients/android`，不能原样用作
Windows 绑定。`mobile` 导出 C 控制接口，但 Windows DLL 构建、调用约定、UI、
播放器与系统密钥保护仍需要各自适配和端到端验证。

旧模块路径暂时保留兼容性，产品名为栖卷 · Fileway。此组件使用 GPL-3.0，见
[LICENSE](LICENSE)。使用 `go.mod` 指定的版本，在此目录执行：

```sh
go test ./transport ./bridge
go vet ./transport ./bridge
```
