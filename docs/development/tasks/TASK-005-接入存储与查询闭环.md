# TASK-005 接入存储与查询闭环

| 属性 | 内容 |
|---|---|
| 阶段 | P1 |
| 负责人 | B |
| 状态 | 待开始 |
| 依赖 | TASK-003、TASK-004 |
| MOD | [MOD-03](../../requirements/modules/MOD-03-设备接入与控制.md)、[MOD-04](../../requirements/modules/MOD-04-事件管理与可靠性.md)、[MOD-05](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md)、[MOD-06](../../requirements/modules/MOD-06-用户与权限管理.md) |
| 需求 | [REQ-M03-001](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-001)、[REQ-M03-002](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-002)、[REQ-M03-003](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-003)、[REQ-M03-004](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-004)、[REQ-M03-005](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-005)、[REQ-M03-006](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-006)、[REQ-M03-007](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-007)、[REQ-M03-008](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-008)、[REQ-M04-003](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-003)、[REQ-M04-004](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-004)、[REQ-M04-008](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-008)、[REQ-M05-001](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-001)、[REQ-M05-002](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-002)、[REQ-M05-003](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-003)、[REQ-M05-004](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-004)、[REQ-M05-005](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-005)、[REQ-M06-001](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-001)、[REQ-M06-002](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-002)、[REQ-M06-003](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-003)、[REQ-M06-004](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-004)、[REQ-M06-005](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-005)、[REQ-M06-006](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-006)、[NFR-003](../../requirements/quality/安全与可维护性.md) |

## 工作与产出

接入 MQTT 校验身份，保存遥测和最新快照；事件与 outbox 同事务、提交后确认；基础查询和权限投影；命令持久化发布、回执聚合；WS ticket 和授权订阅。P1 不实现完整门融合算法。

## 完成标准

双模拟节点入同箱体，重复事件一条、数据库回滚不确认；心跳/过期区分；查询和 WS 无越权；命令可查逐节点结果。

## 验证与收尾

按 [追踪矩阵](../../management/需求追踪矩阵.md) 关联 TC 执行，保留实际版本和脱敏日志，更新任务/迭代/当前进度。若需跨人实施，开工前拆成各有单一负责人的子任务，父任务仍保留总负责人。

## 实际记录

尚未执行；无编译、运行或板端通过证据。
