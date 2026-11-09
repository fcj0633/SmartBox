# 文档导航：按问题找到需求、设计与证据

这里保存SmartBox的产品规则、实现设计、开发任务、操作指南和验收记录。**当前P0/P1与TASK-007基础真实双板验证已完成，完整业务算法、设备Flash可靠性和最终验收未完成。** 当前能力先看[项目入口](../README.md)与[进度](management/当前进度.md)，不要从某份早期报告的“下一项任务”推断今天的状态。

## 1. 按开发目标阅读

| 想回答的问题 | 阅读入口 | 可以得到什么 |
|---|---|---|
| 产品为何建设、谁使用 | [产品范围](requirements/product/产品范围与场景.md)、[角色权限](requirements/product/角色与权限.md)、[系统总纲](requirements/00-系统需求规格说明书.md) | 业务场景、边界、目标和角色约束；目标不代表实现完成 |
| 某项需求是什么、怎样验收 | [六模块需求索引](requirements/README.md)、[追踪矩阵](management/需求追踪矩阵.md) | 稳定REQ、设计、TASK、TC关系和验收依据 |
| 系统怎样分工 | [总体设计](design/architecture/系统总体设计.md)、[模块数据流](design/architecture/模块边界与数据流.md)、[网络拓扑](design/architecture/部署与网络拓扑.md) | 目标架构与边界；其中规划能力需对照当前代码/报告 |
| 如何修改固件/Java/网页 | [固件导读](../firmware/README.md)、[后端导读](../backend/README.md)、[前端导读](../frontend/README.md) | 真实目录、核心调用关系、修改入口及验证方式 |
| 如何理解/修改接口 | [当前有效契约](../contracts/effective/README.md) | 冻结v1、CR-003/004补充、机器定义及检查入口 |
| 从哪项工作继续 | [任务目录](development/tasks/README.md)、[路线图](management/路线图.md)、[进度](management/当前进度.md) | 阶段、任务依赖和实际切片；MOD不是实施顺序 |
| 如何准备开发环境 | [环境清单](development/environment/环境清单.md)、[版本矩阵](development/environment/工具与依赖版本矩阵.md) | 现有工具和兼容依据；本机秘密需另外配置 |
| 如何启动软件闭环 | [后端初始化](development/guides/后端数据库初始化与启动.md)、[服务端](development/guides/服务端闭环启动与验收.md)、[网页](development/guides/网页启动与首轮验收.md) | 配置、普通运行、显式测试清理及退出边界 |
| 如何使用真实板 | [编译烧录联调](development/guides/编译烧录与联调流程.md)、[硬件说明](design/hardware/节点与演示动作.md) | 受控同步、镜像角色、烧录及用户实物操作 |
| 应运行哪些测试 | [测试体系](../tests/README.md)、[业务TC](testing/cases/README.md)、[测试策略](testing/测试策略.md) | 自动检查层次与业务标准，不混淆软件和硬件 |
| 结果是否可信 | [报告目录](testing/reports)、[证据规则](testing/evidence/README.md) | 实际版本、结果、限制和原始证据索引 |
| 规则/技术为何改变 | [变更CR](management/changes)、[技术ADR](management/decisions)、[风险假设](management/风险与假设.md) | 已批准决定、未决问题和验证条件 |
| 哪次发布完成什么 | [发布目录](delivery/releases)、[REL-001](delivery/releases/REL-001-P1软件与模拟闭环.md) | 当次交付与限制，不覆盖后续事实 |

## 2. 每类文档回答什么

| 类型 | 责任 | 不应该拿来替代什么 |
|---|---|---|
| SRS系统需求规格 | 全局业务目标、系统行为、跨模块流程与待决问题 | 具体实现和实测成绩 |
| MOD / REQ | 业务模块及模块内唯一需求，规定规则与验收 | Java包、固件目录或任务编号 |
| NFR | 全局性能、可靠性、安全和工程约束 | 单次小规模检查结果 |
| Design设计 | 技术分工、状态/事务/接口实现方式 | 已实现声明；设计目标需查代码 |
| TASK任务 | 在某阶段做什么、依赖什么、实际交付多少 | 完整REQ验收或全局功能清单 |
| TC用例 | 场景、前提、步骤与通过标准 | 实际执行证据 |
| 报告/证据 | 某版本在某环境执行后的事实、失败与限制 | 未来预期结果 |
| ADR / CR | 技术决定／业务基线变更及影响 | 未批准业务规则的自动生效 |
| ITER / REL | 迭代目标与当次交付范围 | 最新整个项目状态 |

当前需求组织基线BL-002保留6个模块、47项功能需求和3项NFR，详见[需求索引](requirements/README.md)与[CR-002](management/changes/CR-002-需求体系与编号重构.md)。原始输入在[baseline](requirements/baseline/项目原始需求-v1.0.md)逐字保留，仅作为来源；冲突按有效需求和已批准CR处理，不重新维护旧需求体系。

## 3. 当前系统证据与历史边界

- [P0报告](testing/reports/P0-文档基线验收.md)：工程/文档基线，不能证明业务已实现。
- [接口报告](testing/reports/TASK-001-契约验证报告.md)：契约准备与冻结，冻结README中的当时状态保持原样。
- [骨架报告](testing/reports/TASK-002-固件骨架验证报告.md)、[后端基础](testing/reports/TASK-003-后端与数据库验证报告.md)：各自阶段结果；后续真实接入不回写这些历史事实。
- [MQTT模拟](testing/reports/TASK-004-MQTT与模拟节点验证报告.md)→[服务端闭环](testing/reports/TASK-005-服务端闭环验证报告.md)→[网页](testing/reports/TASK-006-网页与首轮闭环验证报告.md)：分别补齐真实通信、保存确认和浏览器授权展示。
- [双板报告](testing/reports/TASK-007-双板接入验证报告.md)：真实IA1/SC2、三项output、310秒及恢复。GPS仍MOCK、完整算法和Flash未验收。

报告日期和输入版本必须一起读。REL-001里的“未烧录”是P1发布时范围；当前板端事实看TASK-007，而不是更改REL或早期TASK状态来消除历史差异。TASK-008～010未开始，不制造新验收记录。

## 4. 维护方式

先读[开发约定](../AGENTS.md)与[文档协作指南](development/guides/开发与文档协作.md)。需求变更记录CR、技术选择记录ADR，按追踪关系同步；只有改动范围适用时才更新任务/设计/测试，不靠README批准规则。

目录分工：`requirements`规定业务，`design`解释实现，`development`组织工作/操作，`testing`记录标准/事实，`management`保存进度与决定，`delivery`保存交付，`references`收录外部资料，`templates`提供格式。精确协议在根contracts，原始日志/镜像在忽略的artifacts，README不复制完整协议、SQL或每次任务过程。

修改后运行`python tools/check_docs.py`；增加代码文件链接时，文档工具临时样例也必须包含相应受控输入，不削弱检查规则。小型索引与脱敏摘要可受控，密码、JWT、ticket、原始日志及本机私有连接说明不提交。全部编号保持稳定。
