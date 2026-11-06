# 文档导航

当前有效基线：**BL-001，2026-11-06，P0**。本基线根据用户批准的实施计划建立；需求规则由功能需求、非功能需求、角色权限和 contracts 共同定义。原始文档保留来源，不作为冲突时的执行规范。

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
| 多份业务需求与质量要求 | requirements/features、quality、product | 一个编号一个责任边界；Git 保存修改历史 |
| 目标、进度、追踪、决策与变更 | management | 需求变更经 CR 记录，技术选择经 ADR 记录 |
| 系统和各端设计 | design | 解释如何实现，引用需求和契约，不复制造协议 |
| 具体任务、迭代和操作指南 | development | TASK 推进，ITER 汇总，不把计划结果写成实测 |
| 测试用例与执行证据 | testing | TC 定义预期，报告记录实际，证据放 artifacts |
| 部署、版本和演示 | delivery | 发布记录固定交付范围和限制 |
| 外部学习资料 | references | 保留链接，不复制完整 SDK 或旧实验 |
| 可复用格式 | templates | 新文档从模板创建，删除不适用项并说明原因 |

[基线修订记录 CR-001](management/changes/CR-001-原始需求规范化.md) 记录原始需求与有效规范的差异。后续批准的 CR 需更新相关规范，不能仅在变更记录里追加规则。
