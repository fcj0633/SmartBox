# SmartBox — 基于 OpenHarmony 的多功能物流柜

双 BearPi-HM Nano（ENV + POSE）通过 MQTT 接入 Spring Boot，将环境、姿态、模拟定位及事件保存到 MySQL，由 Vue 网页按角色展示并下发控制命令。

项目英文名：**SmartBox**。代码仓库：[fcj0633/SmartBox](https://github.com/fcj0633/SmartBox)。

**当前进度：P0基线已建立，P1接口准备及双角色固件骨架已完成；尚无可运行的业务应用。** 官方IA1/SC2例程据用户反馈通过；SmartBox软件准备继续推进，P1 不等待接板；真实采集和硬件结果在 P2 验证。

## 从这里开始

1. 阅读 [文档导航](docs/README.md) 和 [产品范围](docs/requirements/product/产品范围与场景.md)。
2. 按 [需求目录](docs/requirements/README.md) 查看当前有效规范；[原始需求](docs/requirements/baseline/项目原始需求-v1.0.md) 仅作来源。
3. 查看 [总体设计](docs/design/architecture/系统总体设计.md)、[MQTT 契约](contracts/mqtt/v1.md) 和 [HTTP / WebSocket 契约](contracts/http/v1.md)。
4. 从 [当前进度](docs/management/当前进度.md) 进入 [ITER-001](docs/development/iterations/ITER-001-三端骨架与通信闭环.md)。
5. 开发前遵循 [AGENTS.md](AGENTS.md)；提交前运行 `python tools/check_docs.py`。

## 工程布局

| 目录 | 用途 |
|---|---|
| firmware | 设备角色、业务、驱动及 SDK 构建适配 |
| backend | Spring Boot 模块化单体；Java 21 / MySQL 8 |
| frontend | Vue 3 / TypeScript 网页 |
| contracts | MQTT、REST、WebSocket 的唯一协议规范 |
| deploy | 项目专用部署配置；当前不含可执行部署 |
| tools | 文档检查等辅助工具 |
| tests | 跨端协议与联调检查 |
| docs | 需求、设计、任务、测试及交付规范 |
| artifacts | 本地固件、日志、证据，不进入 Git |

固件双角色编译入口见[编译指南](docs/development/guides/编译烧录与联调流程.md)；业务应用首次启动、建库仍待后续骨架完成。SDK 和旧学习资料不复制入仓库。

## 当前需求层次与重构基线

系统需求 → MOD模块需求 → REQ-Mxx-nnn子需求 → Design → TASK → TC。当前6个MOD、47项子需求和3项全局NFR；旧九份独立需求已迁移，不再作为有效编号。

[需求索引](docs/requirements/README.md)、[系统总纲](docs/requirements/00-系统需求规格说明书.md)、[CR-002迁移与生效](docs/management/changes/CR-002-需求体系与编号重构.md)。模块文档是业务需求权威来源，根contracts是协议细节权威来源。

新增功能优先归已有模块；只有新业务领域才增加MOD。编号稳定不复用，修改同步设计、契约关联、任务、测试和追踪，不能改变未确认规则或按MOD顺序重排迭代。

## 接口准备与检查

TASK-001交付入口：[版本矩阵](docs/development/environment/工具与依赖版本矩阵.md)、[机器契约与样例](contracts/README.md)、[实际检查报告](docs/testing/reports/TASK-001-契约验证报告.md)。契约工具使用独立Python环境运行，依赖安装见版本矩阵；没有应用启动、数据库或板端验收通过声明。

## 固件骨架交付

[TASK-002](docs/development/tasks/TASK-002-双角色固件骨架.md)、[源码与端口](firmware/README.md)、[构建与检查工具](tools/README.md)、[实际报告](docs/testing/reports/TASK-002-固件骨架验证报告.md)。两角色已实际编译链接并恢复原学习环境；尚未烧录、采集真实数据或联网，后续按依赖推进TASK-003。
