# 文档导航

当前需求组织基线：**BL-002，2026-11-06**；当前P1接口准备、双角色固件骨架、TASK-003后端数据库基础与TASK-004模拟MQTT已完成，完整业务闭环仍待后续任务。本基线根据用户批准的实施计划建立；需求规则由功能需求、非功能需求、角色权限和 contracts 共同定义。原始文档保留来源，不作为冲突时的执行规范。

## 阅读顺序

1. [项目章程](management/项目章程.md) → [产品范围](requirements/product/产品范围与场景.md) → [角色与权限](requirements/product/角色与权限.md)。
2. [需求目录及状态](requirements/README.md) → [追踪矩阵](management/需求追踪矩阵.md)。
3. [总体设计](design/architecture/系统总体设计.md) → [模块与数据流](design/architecture/模块边界与数据流.md) → [契约索引](../contracts/README.md)。
4. [环境清单](development/environment/环境清单.md) → [协作流程](development/guides/开发与文档协作.md) → [首轮迭代](development/iterations/ITER-001-三端骨架与通信闭环.md)。
5. [测试策略](testing/测试策略.md) → [当前进度](management/当前进度.md) → [P0 交付记录](delivery/releases/REL-000-P0基线.md)。

## 文档地图与权威来源

| 内容 | 目录 | 维护方式 |
|---|---|---|
| 原始输入 | requirements/baseline | 原文只读，原始需求文件已从根目录移动至此 |
| 多份业务需求与质量要求 | requirements/modules、quality、product | 一个编号一个责任边界；Git 保存修改历史 |
| 目标、进度、追踪、决策与变更 | management | 需求变更经 CR 记录，技术选择经 ADR 记录 |
| 系统和各端设计 | design | 解释如何实现，引用需求和契约，不复制造协议 |
| 具体任务、迭代和操作指南 | development | TASK 推进，ITER 汇总，不把计划结果写成实测 |
| 测试用例与执行证据 | testing | TC 定义预期，报告记录实际，证据放 artifacts |
| 部署、版本和演示 | delivery | 发布记录固定交付范围和限制 |
| 外部学习资料 | references | 保留链接，不复制完整 SDK 或旧实验 |
| 可复用格式 | templates | 新文档从模板创建，删除不适用项并说明原因 |

[基线修订记录 CR-001](management/changes/CR-001-原始需求规范化.md) 记录原始需求与有效规范的差异。后续批准的 CR 需更新相关规范，不能仅在变更记录里追加规则。

## 当前需求层次与重构基线

系统需求 → MOD模块需求 → REQ-Mxx-nnn子需求 → Design → TASK → TC。当前6个MOD、47项子需求和3项全局NFR；旧九份独立需求已迁移，不再作为有效编号。

[需求索引](requirements/README.md)、[系统总纲](requirements/00-系统需求规格说明书.md)、[CR-002迁移与生效](management/changes/CR-002-需求体系与编号重构.md)。模块文档是业务需求权威来源，根contracts是协议细节权威来源。

新增功能优先归已有模块；只有新业务领域才增加MOD。编号稳定不复用，修改同步设计、契约关联、任务、测试和追踪，不能改变未确认规则或按MOD顺序重排迭代。

## TASK-001交付入口

[版本矩阵](development/environment/工具与依赖版本矩阵.md)、[机器契约](../contracts/README.md)、[验证报告](testing/reports/TASK-001-契约验证报告.md)、[冻结与交接](development/iterations/TASK-001-接口评审与交接.md)。业务需求及P0历史验收保持原状态，后续任务按ITER-001依赖推进。

## 当前单人开发与固件骨架设计

当前全部开发与验收由用户独立完成，历史分工只保留当时事实。TASK-002详细设计集中在[任务文档](development/tasks/TASK-002-双角色固件骨架.md)，已完成软件骨架、实际SDK编译链接与恢复，见[报告](testing/reports/TASK-002-固件骨架验证报告.md)；未执行板端验收，TASK-003后端基础现已完成，见[报告](testing/reports/TASK-003-后端与数据库验证报告.md)及[启动指南](development/guides/后端数据库初始化与启动.md)，按任务索引从TASK-005继续。

## TASK-004交付入口

[详细任务](development/tasks/TASK-004-MQTT与模拟节点.md)、[真实验证报告](testing/reports/TASK-004-MQTT与模拟节点验证报告.md)、[模拟器使用](../tools/simulator/README.md)。37项单元/临时配置与55项真实MQTT检查通过；[CR-003](management/changes/CR-003-布防有效期限计时起点.md)只明确实际ARMED后计时，不解决其他待决事项。当前下一项TASK-005，完整系统闭环尚未验收。
