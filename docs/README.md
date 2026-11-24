# SmartBox 文档入口

本仓库面向**前端/后端开发者与演示部署**。不包含自动化测试、硬件操作工具或完整协议夹具。

## 1. 先运行

- [前后端启动](operations/前后端启动.md)：开箱即用数据库、后端、前端、验证。
- [当前进度](project/当前进度.md)：现在可用的功能和边界。
- [项目演进](project/项目演进.md)：需求/实现如何变化。
- [真实开发板接入](operations/真实开发板接入.md)：默认远程 MQTT、节点/主题和离线排查。

## 2. 开发

- [需求索引](requirements/README.md)
- [设计索引](design/README.md)
- [任务索引](tasks/README.md)
- [变更与决策](changes/README.md)

## 3. 边界

- 后端使用 MySQL 与 Spring Boot，前端使用 Vue/Vite。
- 仓库保留 `firmware/` 源码作为参考，但不提供硬件构建、烧录、测试或联调工具。
- 后端运行时 schema 位于 `backend/src/main/resources/contracts/effective/`。
- 没有 `tools/`、`tests/` 或自动化文档校验；验证以构建和手工启动为准。
- 前端只保留 `/enterprise/**` 企业端路由；旧 `/boxes`、`/rider`、`/track` 已移除。
