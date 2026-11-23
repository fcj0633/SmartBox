# 企业端运营控制台增量接口（TASK-015）

状态：**DEMO 首切片增量契约，2026-11-24，未完成生产安全/长期验证。** 本目录只描述 `/api/v1/enterprise/console/**`，不改动既有 [transport](../transport/README.md)、[transport-view](../transport-view/README.md)、[assets](../assets/README.md) 或 [v1](../v1.md) 契约。

## 1. 边界

- 仅 `LOGISTICS_OPERATOR` 可访问；企业范围由服务端根据登录管理员解析，请求不接受 `enterprise_id`。
- 响应统一 `{data,request_id}`；错误 `{error:{code,message},request_id}`。时间均为 UTC epoch 毫秒。
- 坐标是归一化画布位置（`x/y` 0～100）并标 `source=MOCK`，不是 GPS。
- `asset_source=REAL/SIMULATED` 与位置 `MOCK` 是两个维度；前端不得把模拟箱体说成真实设备。
- 告警在这个切片只读；没有人工确认/处理中/已处理字段。
- 所有写操作使用 POST（当前 CORS 只允许 GET/POST）。

## 2. 路由

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/enterprise/console/overview` | 企业总览：KPI、箱体快照、最近任务、最近动态 |
| GET | `/enterprise/console/boxes` | 企业箱体快照列表 |
| GET | `/enterprise/console/boxes/{boxId}` | 单箱体快照；跨企业 404 |
| GET | `/enterprise/console/routes` | 3 条 DEMO MOCK 路线 |
| GET | `/enterprise/console/transports` | 企业任务列表（最近 50） |
| GET | `/enterprise/console/transports/{id}` | 任务详情＋箱体/路线/位置/环境/事件 |
| POST | `/enterprise/console/transports` | 创建 READY：`box_id,item_name,item_description?,route_code` |
| POST | `/enterprise/console/transports/{id}/edit` | READY 编辑：`item_name,item_description?,route_code` |
| POST | `/enterprise/console/transports/{id}/start` | 开始 ACTIVE；`confirm_risk` 可选布尔 |
| POST | `/enterprise/console/transports/{id}/finish` | 结束 COMPLETED |
| GET | `/enterprise/console/alarms` | 只读告警/恢复列表 |
| POST | `/enterprise/console/boxes` | 创建模拟箱：`display_name,purpose?,notes?,scenario?` |
| POST | `/enterprise/console/boxes/{id}/edit` | 编辑名称/用途/备注 |
| POST | `/enterprise/console/boxes/{id}/scenario` | 切换模拟场景 `{scenario}` |
| POST | `/enterprise/console/boxes/{id}/disable` | 停用模拟输入 |
| POST | `/enterprise/console/boxes/{id}/enable` | 启用模拟输入 |
| GET | `/enterprise/console/boxes/{id}/events` | 只读设备事件；`limit` 10–100 |
| GET | `/enterprise/console/boxes/{id}/samples` | 环境/姿态样本；`kind=env\|pose`、`hours=1\|6\|24` |
| GET | `/enterprise/console/boxes/{id}/alarms` | 该箱体告警/恢复 |
| GET | `/enterprise/console/boxes/{id}/commands` | 最近命令与逐节点结果，只读 |
| GET | `/enterprise/console/boxes/{id}/logs` | 设备/任务/审计/命令合并日志 |

## 3. 关键响应字段

- `overview`: `enterprise_id, enterprise_name, refreshed_at, kpis, boxes[], tasks[], recent_activity[]`.
- `kpis`: `box_total, online, partial, offline, unknown, active_transports, active_alarm_boxes, unknown_alarm_boxes, completed_today`.
- `box`: `box_id, display_name, asset_source, purpose, notes, demo_scenario, demo_enabled, connectivity, data_state, environment, pose, alarm, current_transport, last_completed_transport, position, last_seen_at, nodes[]`.
- `task`: `task_id, transport_no, box_id, box_display_name, status, item_name, item_description, operator_username, created_at, started_at, ended_at, route, position, start_check`.
- `position`: `x, y, place, route_code, route_name, progress, position_at, stale, finished, source`.
- `alarm`: `assessment, active, level, active_count`.
- `route`: `code, name, from, to, duration_ms, points[{x,y,place}]`.

## 4. 错误与状态

| HTTP | code | 场景 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 物品/路线/箱体参数不合法 |
| 403 | `FORBIDDEN` | 非企业管理员或缺少企业 |
| 404 | `NOT_FOUND` | 跨企业、任务不存在 |
| 409 | `INVALID_TASK_STATE` | 非 READY 编辑、非 ACTIVE 结束 |
| 409 | `BOX_OFFLINE` | 必需节点不在线，不能开始 |
| 409 | `RISK_CONFIRMATION_REQUIRED` | 有活动告警或告警判定未知，未确认 |
| 409 | `BOX_IN_USE` | 同箱已有 ACTIVE |
| 409 | `TRANSPORT_NO_CONFLICT` | 运输编号冲突 |
| 409 | `ASSET_NOT_SIMULATED` | 对 REAL 箱体执行场景/启停 |
| 409 | `BOX_IN_USE` | ACTIVE 运输中停用模拟输入 |
| 400 | `INVALID_SCENARIO` | 场景不是 normal/env_high/offline/stale/impact |
| 409 | `FACT_GENERATION_FAILED` | 场景事实生成失败，场景保持不变 |

## 5. 示例

```json
{
  "data": {
    "enterprise_id": 1,
    "enterprise_name": "演示物流企业",
    "refreshed_at": 1794000000000,
    "kpis": { "box_total": 6, "online": 4, "partial": 0, "offline": 2, "unknown": 0,
      "active_transports": 1, "active_alarm_boxes": 2, "unknown_alarm_boxes": 2, "completed_today": 1 },
    "boxes": [],
    "tasks": [],
    "recent_activity": []
  },
  "request_id": "..."
}
```

完整字段以实现和 [TASK-015](../../../docs/development/tasks/TASK-015-企业端运营控制台首切片.md) 为准；本契约不批准真实 GPS、资产 CRUD 或告警人工处置。
