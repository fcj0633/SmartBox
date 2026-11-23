# SmartBox 文档入口

> **当前开发方向：企业端物流运营控制台 V2.0。** 骑手端、客户/访客端暂不作为新阶段目标；TASK-011～014 的已实现代码和验证资料保留供兼容与参考。**需求目标不等于已实现**。

## 从这里开始（只需这 5 个入口）

| 你想做什么 | 首先阅读 | 说明 |
|---|---|---|
| **理解下一版要开发什么** | [企业端 V2.0 需求规格](requirements/product/企业端物流运营控制台-V2.0-需求规格.md) | **当前产品主入口**：五个主页面、八分区详情、企业运输、模拟地图、1＋5 箱体、告警 |
| 理解本次为什么改方向 | [CR-007 产品转向](management/changes/CR-007-企业端优先与物流运营控制台.md) | 旧三端范围如何暂停，哪些技术/历史仍保留 |
| 启动项目 | [开发者完整部署指南](delivery/deployment/开发者完整部署指南.md) | 环境、MySQL、服务端、前端、真实板与模拟节点 |
| 找真实代码/接口 | [后端](../backend/README.md) · [前端](../frontend/README.md) · [有效契约](../contracts/effective/README.md) | 旧接口仍需兼容；新需求尚待实现 |
| 查任务和实际进度 | [任务索引](development/tasks/README.md) · [当前进度](management/当前进度.md) | 已交付、待做、跳过测试分开阅读 |

## 按专项定位，不从旧草案开始

| 专题 | 入口 |
|---|---|
| 物流地图 UI 原型 | [企业 route 原型](../frontend/design-exploration/README.md) · [原型源码](../frontend/design-exploration/src/App.vue) |
| 监测/设备/控制现有规则 | [模块需求](requirements/README.md) · [总体架构](design/architecture/系统总体设计.md) |
| 核心 MQTT/HTTP/WS 契约 | [effective 契约](../contracts/effective/README.md) · [HTTP 契约](../contracts/http/v1.md) |
| 模拟器与实物验证 | [模拟器](../tools/simulator/README.md) · [固件](../firmware/README.md) · [设备报告](testing/reports/TASK-007-双板接入验证报告.md) |
| 数据库/技术设计 | [后端数据模型](design/backend/后端模块与数据模型.md) · [服务端初始化](development/guides/后端数据库初始化与启动.md) |
| 测试与事实 | [TC 目录](testing/cases/README.md) · [测试策略](testing/测试策略.md) · [测试报告](testing/reports/) |
| 开发约定 | [AGENTS](../AGENTS.md) · [文档协作指南](development/guides/开发与文档协作.md) |

## 历史文档怎么读

- [原始需求](requirements/baseline/项目原始需求-v1.0.md)、[BL-002 需求索引](requirements/README.md) 与[需求追踪矩阵](management/需求追踪矩阵.md)：**保留的正式设备与历史需求追踪**，不要假定其三端业务仍是 V2 的产品交付重点。
- [CR-006](management/changes/CR-006-多角色物流箱与运输任务平台.md)、[多角色 V1.1 说明](requirements/SmartBox多角色物流箱管理平台需求变更说明.md)、[TASK-011～014](development/tasks/README.md)：上一阶段三端设计及实际开发证据，**不是本轮实施清单**。
- [旧路线图](management/路线图.md)、[历史发布](delivery/releases/)、[实测报告](testing/reports/)：用于追踪项目演进，旧报告只反映当时版本，不被 V2 文档修改。
- [设计 ADR](management/decisions/)、[变更 CR](management/changes/) 与[详细测试](testing/)：需要定位某项历史决策、协议或故障时再读，不作为新人的强制阅读顺序。

## 文档维护原则

1. **产品需求只在 V2 文档描述本轮范围**；实施细节留给后续设计和 TASK，README 不复制完整需求。
2. 旧编号和历史报告不可随意删除或挪路径，避免破坏追踪与冻结证据；不重要的旧草案退出主导航，而非伪装成现行。
3. 文档链接、现行与历史标识要准确；修改后运行 `python tools/check_docs.py`。本 README 只导航，不代表已执行检查或完成业务。
4. 未实现的新能力标“待开发”；真实硬件、模拟器和地图 MOCK 必须严格区分。
