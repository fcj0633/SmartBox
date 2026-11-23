# TASK-018 企业控制中心：命令提交与执行回执

| 项 | 内容 |
|---|---|
| 任务编号 | TASK-018 |
| 所属版本 | 企业端 V2.0 / 控制中心 |
| 负责人/执行者 | 用户（验收） / Codex（执行） |
| 创建时间 | 2026-11-24 |
| 状态 | **首切片实现完成（测试/模拟回执），2026-11-24；真实设备命令执行未验收** |
| 依赖 | TASK-017 箱体详情、现有 CommandService、V3/V7 命令表 |
| 需求映射 | E2-DETAIL-07、E2-OPS-04、E2-OPS-05 |

> **状态口径：**本轮实现企业控制中心五类命令的提交、幂等、逐节点 PENDING/ACK/失败/超时展示。测试环境 MQTT 关闭，命令会停在 PENDING/EXPIRED；这是真实状态，不伪造成功。真实开发板命令未在本次执行，避免把 HTTP 受理当设备执行。

## 1. 目标与范围

- 控制中心按节点 caps 只展示支持动作：output、sentry、profile、gps_mode、calibrate。
- 提交后生成 operation，页面显示状态与逐节点回执。
- `Idempotency-Key` 幂等；同 key 不同 body 冲突。
- 企业范围校验；模拟输入停用拒绝提交。
- 不新增表/迁移，复用 `CommandService` 与 V3/V7 命令表。

## 2. 后端

### 2.1 服务

新增 `EnterpriseControlService`：

```text
submit(user, boxId, key, body)
  read.box(user, boxId)                        // 企业所有权
  SIMULATED 且 demo_enabled=false -> 409
  INSERT IGNORE user_box_access(user, boxId)   // 旧命令服务访问关系
  CommandService.submit(...)                   // 五类命令/幂等/能力/SNTP
operation(user, boxId, operationId)
  read.box + ensure access
  CommandService.operation(...)
```

访问关系由所有权补齐，不能反向把 `user_box_access` 当成所有权。

### 2.2 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/enterprise/console/boxes/{id}/commands` | `Idempotency-Key` + `{cmd,params,target_node_id?}`；202 `{operation_id}` |
| GET | `/enterprise/console/boxes/{id}/commands/{operationId}` | `operation_id,status,nodes[]` |
| GET | `/enterprise/console/boxes/{id}/commands` | 已有只读历史 |

错误：400 参数、403 权限、404 资源、409 `DEMO_DISABLED`/时间未同步/无能力/幂等冲突；超时为服务端 `EXPIRED`。

### 2.3 数据流

```text
UI -> POST enterprise command -> CommandService.submit
  -> command_operations + node_commands + command_dispatch
  -> MQTT/设备 ack -> CommandService.receipt
  -> GET operation 轮询 -> 状态/节点结果
```

## 3. 前端

新增 `ControlCenter.vue`，嵌入 TASK-017 控制中心 Tab：

- 五类命令表单，按 caps 过滤目标和可选动作。
- output：light/fan/indicator、开关、1～30 秒。
- sentry：布防/撤防、0～86400 秒；提示 3 秒缓冲。
- profile：画像 ID、温湿度上下限、正回差。
- gps_mode：FIXED/ROUTE/WALK、seed。
- calibrate：静置约 3 秒提示。
- 提交 UUID 幂等键；轮询至终态；节点 ACK/错误码可见；HTTP 202 不显示为成功。
- 模拟输入停用/无匹配节点时禁用；能力表和历史保留。
- 历史刷新；空态明确。

## 4. 边界与安全

- 真实开发板当前只有 POSE 在线、ENV 过期；完整真实命令执行未验证。
- 测试环境 MQTT 关闭，操作会 PENDING 后 EXPIRED；不得显示 SUCCEEDED。
- 固件不支持的动作会 FAILED/EXPIRED，不隐藏。
- 不实现告警人工处置、CSV、轨迹回放。
- 不新增数据库迁移；不改旧 `/boxes` 命令接口。

## 5. 验证结果（2026-11-24）

- 后端编译通过；`MockPositionProjectorTest` 5 项通过。
- `EnterpriseConsoleIT` **12 项**通过；新增 `commandSubmissionIdempotencyAndDemoGuard`：
  - 模拟箱提交 output -> 202；
  - GET operation -> PENDING，1 个 ENV 节点；
  - 同 key 同 body 幂等返回同 operation；
  - 同 key 不同 body -> 409；
  - 停用模拟箱 -> 409 `DEMO_DISABLED`。
- 前端 `typecheck`、`build` 通过。
- 浏览器：测试库模拟箱详情→控制中心→提交输出，显示 PENDING 与节点回执；历史刷新；无页面错误。
- 真实开发库 `15175` + `BOX-0001` 控制中心渲染通过（能力/表单可见），**未提交真实命令**，避免 ENV 过期和固件范围不清时制造假成功。
- 截图：`artifacts/task018-ui/desktop-control-submitted.png`、`desktop-real-control.png`。
- `check_docs.py` 通过。

## 6. 未验证/后续

- 真实设备 ACK/失败/超时未执行（真实板 POSE 在线、ENV 过期；固件支持范围有限）。
- 告警人工处置/转交/备注未实现。
- 控制历史筛选、CSV 导出未实现。
- 完整 `mvn verify` 的既有 Mockito/JDK 自附加问题仍单独记录。
- 生产环境迁移/真实命令验收未完成。

## 7. 假设

1. 复用现有五类命令和 `CommandService`，不新增固件能力。
2. 企业所有权 -> 旧命令访问关系补齐；不改变旧 access 语义。
3. 测试/模拟 PENDING/EXPIRED 是允许的真实结果。
4. 控制成功后不自动恢复告警；告警处置另立任务。