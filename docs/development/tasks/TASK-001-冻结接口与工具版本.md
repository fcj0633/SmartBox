# TASK-001 冻结接口与工具版本

| 属性 | 内容 |
|---|---|
| 阶段 | P1 |
| 负责人 | B（A 评审） |
| 状态 | 待开始 |
| 依赖 | TASK-000 |
| MOD | [MOD-03](../../requirements/modules/MOD-03-设备接入与控制.md) |
| 需求 | [REQ-M03-001](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-001)、[REQ-M03-002](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-002)、[REQ-M03-003](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-003)、[REQ-M03-005](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-005)、[REQ-M03-006](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-006)、[REQ-M03-007](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-007)、[REQ-M03-008](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-008)、[NFR-003](../../requirements/quality/安全与可维护性.md) |

## 工作与产出

核对 Spring Boot 与 Java21、MyBatis/Flyway/MySQL、Vue/Vite/Node 的官方兼容性；记录精确版本。将文字契约补成 OpenAPI、WS/设备 JSON Schema、正反样例。

## 完成标准

版本矩阵有来源；契约验证覆盖无效时间、超长、权限投影和所有命令；A/B 对契约同意；无硬件依赖。

## 验证与收尾

按 [追踪矩阵](../../management/需求追踪矩阵.md) 关联 TC 执行，保留实际版本和脱敏日志，更新任务/迭代/当前进度。若需跨人实施，开工前拆成各有单一负责人的子任务，父任务仍保留总负责人。

## 实际记录

尚未执行；无编译、运行或板端通过证据。
