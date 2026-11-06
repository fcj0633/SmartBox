# TASK-006 网页与首轮闭环验收

| 属性 | 内容 |
|---|---|
| 阶段 | P1 |
| 负责人 | 用户（独立开发与验收） |
| 状态 | 待开始 |
| 依赖 | TASK-003（前端可先按契约并行）；最终验收依赖 TASK-005 |
| MOD | [MOD-03](../../requirements/modules/MOD-03-设备接入与控制.md)、[MOD-05](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md)、[MOD-06](../../requirements/modules/MOD-06-用户与权限管理.md) |
| 需求 | [REQ-M03-004](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-004)、[REQ-M03-006](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-006)、[REQ-M03-007](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-007)、[REQ-M05-002](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-002)、[REQ-M05-003](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-003)、[REQ-M05-004](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-004)、[REQ-M05-005](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-005)、[REQ-M06-001](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-001)、[REQ-M06-002](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-002)、[REQ-M06-003](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-003)、[REQ-M06-004](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-004)、[REQ-M06-005](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-005)、[NFR-003](../../requirements/quality/安全与可维护性.md) |

## 工作与产出

建立 Vue/TS 路由与状态、登录/列表/详情/命令状态。按契约并行开发，随后使用真实后端/MySQL/MQTT 验收。补 ITER-001 测试报告和 REL-001。

## 完成标准

浏览器显示两个模拟节点数据、MOCK/过期/离线、命令回执；外卖用户不能取得证据和控制；断线后同步。不得把模拟通过记为板端通过。

## 验证与收尾

按 [追踪矩阵](../../management/需求追踪矩阵.md) 关联 TC 执行，保留实际版本和脱敏日志，更新任务/迭代/当前进度。由用户独立实施和验收；复杂任务可拆内部步骤以便验证，不设置跨人交接，保持原技术依赖和验收边界。

## 实际记录

尚未执行；无编译、运行或板端通过证据。
