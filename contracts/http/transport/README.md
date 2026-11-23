# 骑手运输接口增量（TASK-012）

本目录是 CR-006 演示切片的骑手运输 REST 增量契约，独立于冻结 v1 的 11 个设备操作；不修改冻结文件，也不授予设备控制权限。

- [OpenAPI](openapi.yaml)：6 个 JWT 操作、请求与错误响应；
- [结构定义](responses.schema.json)：任务 DTO、请求与错误白名单；
- [正反样例](fixtures.json)：离线结构检查输入，不是运行证据；
- [只读检查工具](../../../tools/check_transport_contracts.py)。

全部接口使用现有 `/api/v1` 与 `data/request_id`、`error/request_id` 结构，Bearer JWT，仅 `COURIER` 可访问。`transport_no` 由服务端生成，客户端不能提交箱体所有者、骑手、编号或起止时间。

## 操作

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rider/transports` | 创建 READY；输入仅 `box_id` |
| POST | `/rider/transports/{id}/item` | 保存物品摘要；仅本人 READY |
| POST | `/rider/transports/{id}/start` | 开始 ACTIVE；`confirm_risk` 显式确认 |
| POST | `/rider/transports/{id}/finish` | 结束 COMPLETED；重复结束不改时间 |
| GET | `/rider/transports` | 本人最近 50 项 |
| GET | `/rider/transports/{id}` | 本人详情；他人任务 404 |

## 状态与错误

- 状态：`READY → ACTIVE → COMPLETED`；ACTIVE 期间同箱唯一。
- 400 `VALIDATION_ERROR`、401、403、404、409、503。
- 409 业务码：`BOX_IN_USE`、`INVALID_TASK_STATE`、`BOX_OFFLINE`、`RISK_CONFIRMATION_REQUIRED`；运输编号冲突为 `TRANSPORT_NO_CONFLICT`。
- 400 不表示数据库故障；已知唯一冲突不能返回 503。

## 边界

- DEMO 允许同箱多个 READY；重复创建不保证返回同一任务，严格创建幂等未实现。
- 风险确认读取现有 `alarm_state`，不新增环境阈值；在线按现有约 90 秒必需节点窗口。
- 本契约不包含企业、访客或网页接口；这些归 TASK-013。
- 契约结构检查通过不等于真实 MySQL/HTTP、权限或页面验收。
