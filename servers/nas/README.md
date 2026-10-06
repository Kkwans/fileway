# NAS 文件浏览器

基于 [filebrowser](https://github.com/filebrowser/filebrowser) 的二次开发版本，专为 NAS（网络附加存储）场景优化。

## ✨ 特性

- 🌐 **全中文界面** - 所有界面和错误信息均为中文
- 📁 **多存储卷支持** - 支持 volume1、volume2、外置 USB、网络存储等
- 🏷️ **目录分类系统** - 个人文件夹、共享文件夹、系统文件夹分类展示
- ⚠️ **风险等级标识** - 高危/中危/低危目录标识，高危操作二次确认
- ⭐ **目录收藏功能** - 收藏常用目录，快速访问（数据存储在后端数据库）
- 🏷️ **目录标签功能** - 给目录打标签，分类管理（数据存储在后端数据库）
- 📝 **Markdown 编辑** - 集成 Vditor 编辑器，支持实时预览
- 🎨 **现代化 UI** - 优化的界面设计，支持暗色模式
- 📱 **响应式设计** - 支持移动端访问

## 🏗️ 项目结构

```
nas-file-browser/
├── backend/                # Go 后端代码
│   ├── auth/              # 认证模块
│   ├── cmd/               # 命令行入口
│   ├── errors/            # 错误定义（中文）
│   ├── files/             # 文件操作
│   ├── http/              # HTTP 接口
│   ├── users/             # 用户管理
│   ├── settings/          # 设置管理
│   ├── storage/           # 存储层
│   ├── main.go            # 程序入口
│   ├── go.mod             # Go 模块定义
│   └── go.sum             # 依赖校验
├── frontend/              # Vue 3 前端代码
│   ├── src/               # 源代码
│   ├── public/            # 静态资源
│   └── package.json       # 前端依赖
├── docker/                # Docker 配置
├── Dockerfile.custom      # Docker 构建文件
├── docker-compose.custom.yml  # Docker Compose 配置
└── README.md              # 本文件
```

## 🚀 快速开始

### Docker 部署（推荐）

```bash
# 克隆仓库
git clone https://github.com/Kkwans/nas-file-browser.git
cd nas-file-browser

# 构建并启动
./scripts/build-image.sh 2026.9.5-v1
IMAGE_TAG=2026.9.5-v1 docker compose -f docker-compose.custom.yml up -d --no-build

# 访问
# 地址: http://your-nas-ip:8888
# 默认账号: admin
# 默认密码: 查看容器日志
```

### 查看默认密码

```bash
docker logs nas-file-browser 2>&1 | grep "password"
```

## ⚙️ 配置

### 存储卷挂载

编辑 `docker-compose.custom.yml`，修改 volumes 配置：

```yaml
volumes:
  - /volume1:/volume1:ro    # 主存储卷
  - /volume2:/volume2:ro    # 扩展存储卷（如有）
  - /volumeUSB1:/volumeUSB1:ro  # USB 外置存储（如有）
```

### 密码策略

默认密码策略：
- 最小长度：6 位
- 无复杂度要求（NAS 内网使用场景）

## 📖 API 文档

所有 API 返回中文错误信息：

| 状态码 | 含义 | 示例 |
|--------|------|------|
| 400 | 请求参数错误 | "请求参数错误" |
| 401 | 未授权 | "未授权，请重新登录" |
| 403 | 没有权限 | "没有管理员权限" |
| 404 | 资源不存在 | "文件不存在" |
| 409 | 资源冲突 | "文件已存在" |
| 500 | 服务器错误 | "服务器内部错误" |

## 🔧 开发

### 环境要求

- Go 1.25+
- Node.js 24+
- pnpm 10+

### 本地开发

```bash
# 后端
cd backend
go run .

# 前端
cd frontend
pnpm install
pnpm dev
```

### 构建镜像

```bash
./scripts/build-image.sh 2026.9.5-v1
IMAGE_TAG=2026.9.5-v1 docker compose -f docker-compose.custom.yml up -d --no-build
```

## 📋 更新日志

### v2.0.0 (2026-05-18)

- ✨ 全中文界面和错误信息
- 📁 多存储卷支持
- 🏷️ 目录分类系统
- ⚠️ 风险等级标识
- ⭐ 目录收藏功能
- 🏷️ 目录标签功能
- 🎨 现代化 UI 设计

## 📄 许可证

基于 [filebrowser](https://github.com/filebrowser/filebrowser) 开发，遵循原项目许可证。

## 🔗 链接

- [GitHub 仓库](https://github.com/Kkwans/nas-file-browser)
- [原项目](https://github.com/filebrowser/filebrowser)
