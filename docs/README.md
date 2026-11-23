# SmartBox 文档入口

> **当前方向：企业端物流运营控制台 V2.0。** 需求目标不等于已实现；真实设备、模拟器和历史文档必须区分。

## 1. 当前在做什么

| 想知道 | 打开 |
|---|---|
| 当前产品范围与功能 | [企业端 V2.0 需求规格](requirements/V2-企业端物流运营控制台需求规格.md) |
| 为什么转向企业端 | [CR-007 产品转向](changes/CR-007-企业端优先与物流运营控制台.md) |
| 现在做到哪里 | [当前进度](project/当前进度.md) · [任务索引](tasks/README.md) |

## 2. 它怎么变成现在这样

| 想知道 | 打开 |
|---|---|
| 需求/实现变化时间线 | [项目演进](project/项目演进.md) |
| 历史/当前实现对照 | [项目演进](project/项目演进.md#历史与当前实现对照) |
| 历史需求、旧三端、旧发布 | [历史资料索引](history/README.md) |
| 需求追踪 | [需求追踪矩阵](project/需求追踪矩阵.md) |

## 3. 按类型查文档

| 类型 | 入口 |
|---|---|
| 需求 | [需求索引](requirements/README.md) · 系统总纲、七个模块、NFR、V2 |
| 设计 | [设计索引](design/README.md) |
| 任务 | [任务索引](tasks/README.md) |
| 启动/部署/环境/排障 | [运维索引](operations/README.md) |
| CR/ADR | [变更与决策索引](changes/README.md) |
| 代码/契约 | [后端](../backend/README.md) · [前端](../frontend/README.md) · [有效契约](../contracts/effective/README.md) |

## 文档维护原则

1. 当前文档放在 `requirements/design/tasks/operations/changes/project`；历史资料只放 `history/`。
2. 目录最多两级，二级目录不再建子目录；文件名用 `MOD-`、`NFR-`、`TASK-`、`CR-`、`ADR-` 前缀识别。
3. 历史不重写；变更过程统一记录在 [项目演进](project/项目演进.md)，当前状态看 [当前进度](project/当前进度.md)。
4. 测试用例/报告不再作为独立文档维护；代码测试保留，验证结论保留在 TASK 和当前进度中。
5. 修改后运行 `python tools/check_docs.py`；文档通过不代表硬件或业务验收通过。