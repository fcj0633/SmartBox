# TASK-004 MQTT与模拟节点

| 属性 | 内容 |
|---|---|
| 阶段 | P1 |
| 负责人 | 用户（独立开发与验收） |
| 状态 | 待开始 |
| 依赖 | TASK-001；端到端阶段另依赖 TASK-003 |
| MOD | [MOD-03](../../requirements/modules/MOD-03-设备接入与控制.md) |
| 需求 | [REQ-M03-001](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-001)、[REQ-M03-002](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-002)、[REQ-M03-003](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-003)、[REQ-M03-004](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-004)、[REQ-M03-005](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-005)、[REQ-M03-006](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-006)、[REQ-M03-007](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-007)、[REQ-M03-008](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-008)、[NFR-003](../../requirements/quality/安全与可维护性.md) |

## 工作与产出

创建项目 Broker 独立配置、每节点凭据/ACL 示例及启动说明，不修改全局旧配置。开发 ENV/POSE 模拟节点，发布遥测/事件、处理命令、注入断线和重复消息。

## 完成标准

真实 Broker 双节点上线、ACL 越权拒绝、断线重订阅、request_id 幂等和无效命令拒绝。脚本不把密码写 Git，保留脱敏收发日志。

## 验证与收尾

按 [追踪矩阵](../../management/需求追踪矩阵.md) 关联 TC 执行，保留实际版本和脱敏日志，更新任务/迭代/当前进度。由用户独立实施和验收；复杂任务可拆内部步骤以便验证，不设置跨人交接，保持原技术依赖和验收边界。

## 实际记录

尚未执行；无编译、运行或板端通过证据。
