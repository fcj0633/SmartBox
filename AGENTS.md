# SmartBox 项目约定

## 定位

本仓库面向前端/后端开发者，保留 backend、frontend、firmware 源码参考、演示部署数据和运行时契约。

- 不维护自动化测试、Python 工具、硬件烧录/联调、完整 HTTP/MQTT 协议夹具。
- `firmware/` 仅源码参考，不能按本仓库完成构建或烧录。
- 当前产品入口：[企业端 V2.0 需求](docs/requirements/V2-企业端物流运营控制台需求规格.md)。

## 开发

- 后端：Java 21、Spring Boot、MySQL、Flyway；固定演示配置已提交，生产必须替换；不得新增未说明的真实生产凭据。
- 前端：Vue 3、TypeScript、Vite；使用 `npm ci`、`npm run dev`、`npm run build`。
- 启动说明：[前后端启动](docs/operations/前后端启动.md)。

## 变更

- 需求、设计、任务变化同步更新 `docs/requirements`、`docs/design`、`docs/tasks` 和[项目演进](docs/project/项目演进.md)。
- 数据库变更必须使用版本化 Flyway 迁移。
- 不修改 `backend/src/main/resources/contracts/effective/common.schema.json` 而不同时更新后端校验逻辑；该文件是运行时契约。
- 不伪造真实设备、MQTT 或生产验证结论。
- 本项目按所有者要求提交固定演示数据库/MQTT 配置；这些是公开演示凭据，生产环境必须全部替换。
- 每次完成有文件改动的工作并验证后提交本地 Git；提交标题和正文使用中文。
