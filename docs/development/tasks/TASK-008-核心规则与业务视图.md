# TASK-008 核心规则与业务视图

| 属性 | 内容 |
|---|---|
| 阶段 | P3 |
| 负责人 | 用户（独立开发与验收） |
| 状态 | 待开始 |
| 依赖 | TASK-007；软件规则测试可提前 |
| MOD | [MOD-01](../../requirements/modules/MOD-01-物流箱运行监测.md)、[MOD-02](../../requirements/modules/MOD-02-安全防护与哨兵.md)、[MOD-05](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md) |
| 需求 | [REQ-M01-001](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-001)、[REQ-M01-002](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-002)、[REQ-M01-003](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-003)、[REQ-M01-004](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-004)、[REQ-M01-005](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-005)、[REQ-M01-006](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-006)、[REQ-M01-007](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-007)、[REQ-M01-008](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-008)、[REQ-M01-009](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-009)、[REQ-M01-010](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-010)、[REQ-M01-011](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-011)、[REQ-M01-012](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-012)、[REQ-M01-013](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-013)、[REQ-M01-014](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-014)、[REQ-M02-001](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-001)、[REQ-M02-002](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-002)、[REQ-M02-003](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-003)、[REQ-M02-004](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-004)、[REQ-M02-005](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-005)、[REQ-M02-006](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-006)、[REQ-M05-002](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-002)、[REQ-M05-003](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-003)、[REQ-M05-004](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-004)、[NFR-001](../../requirements/quality/性能与资源约束.md) |

## 工作与产出

拆子任务开发环境去抖/回差、姿态/冲击、门证据及融合、三模式定位/围栏、哨兵 revision 和局部失败、告警视图；开工前按需求模板补参数及细化设计。

## 完成标准

TC-001～005、008 关联场景通过；无效/未校时/补传/节点离线降级可见。涉及持久恢复部分暂留 TASK-009，不能提前全验收。

## 验证与收尾

按 [追踪矩阵](../../management/需求追踪矩阵.md) 关联 TC 执行，保留实际版本和脱敏日志，更新任务/迭代/当前进度。由用户独立实施和验收；复杂任务可拆内部步骤以便验证，不设置跨人交接，保持原技术依赖和验收边界。

## 实际记录

尚未执行；无编译、运行或板端通过证据。
