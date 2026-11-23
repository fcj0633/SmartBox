# TASK-017 企业箱体详情八分区

| 项 | 内容 |
|---|---|
| 任务编号 | TASK-017 |
| 所属版本 | 企业端 V2.0 / 箱体详情 |
| 负责人/执行者 | 用户（验收） / Codex（执行） |
| 创建时间 | 2026-11-24 |
| 状态 | **首切片实现完成（DEMO/只读），2026-11-24；真实设备与命令提交未验收** |
| 依赖 | TASK-015 首切片、TASK-016 模拟资产与场景、V17 |
| 需求映射 | E2-DETAIL-01～06、08；E2-DETAIL-07 仅只读历史/能力 |

> **状态口径：**本轮实现箱体详情八分区的读模型、页面、Tab 与只读控制中心。控制中心**不提交命令、不产生 ACK**，因此 E2-DETAIL-07 只完成只读部分，不能标记完整 P0。真实板/MQTT、告警人工处置、轨迹回放和 CSV 未验证。

## 1. 目标与范围

在 `/enterprise/assets/:boxId` 将现有箱体摘要升级为八分区：

1. 总览：身份、来源、场景、环境/姿态摘要、运输、告警、MOCK 位置。
2. 环境监测：最新值、1h/6h/24h 样本、时间质量、空态。
3. 姿态监测：状态、倾角、加速度、趋势、POSE/IMPACT 事件。
4. 定位轨迹：当前/最近任务 MOCK 位置与路线、任务列表；不自动回放。
5. 告警事件：箱体告警/恢复、设备事件；人工状态只读。
6. 设备节点：ENV/POSE、caps、required、online、最后心跳、最近状态。
7. 控制中心：节点能力、命令历史只读；无提交按钮。
8. 运行日志：设备事件、任务状态、审计、命令合并时间线，标来源。

Tab 用 URL `?tab=` 保留；切 Tab 不丢箱体上下文。

## 2. 现有事实与边界

- `EnterpriseReadService.box` 已提供身份/环境/节点/任务/告警/位置，TASK-017 增加 `pose`。
- `telemetry` 已有环境/姿态样本、时间质量；查询现有索引即可，无需新增表。
- `events`、`audit_logs`、`transport_tasks`、`command_operations`/`node_commands` 可组成日志与命令历史。
- 任务轨迹使用已有任务路线快照；不读 `location_samples` 冒充任务轨迹。
- 箱体范围由企业所有权确认；不依赖旧 `user_box_access` 作为所有权。
- 原型中的 3D 姿态、围栏、ETA、自动回放、健康分等为 Planned/Mock，本轮不编造。

## 3. 后端接口

新增 `EnterpriseDetailService`，复用 `EnterpriseReadService` 做企业范围确认。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/enterprise/console/boxes/{id}` | 已有箱体快照；增加 `pose` |
| GET | `/enterprise/console/boxes/{id}/samples?kind=env\|pose&hours=1\|6\|24` | 最近 200 个环境/姿态样本 |
| GET | `/enterprise/console/boxes/{id}/events?limit=10..100` | 设备事件 |
| GET | `/enterprise/console/boxes/{id}/alarms` | 该箱体告警/恢复 |
| GET | `/enterprise/console/boxes/{id}/commands` | 最近 20 次命令及逐节点结果，只读 |
| GET | `/enterprise/console/boxes/{id}/logs?limit=50` | 合并运行日志 |

### 3.1 读模型

- `Pose`：`state, tilt_deg, acc_g[], valid, observed_at, time_quality, data_state`。
- `SamplePoint`：`id, observed_at, received_at, valid, temperature, humidity, lux, state, tilt_deg, acc_g, time_quality`。
- `CommandRow/CommandNode`：操作、状态、节点、ACK 结果/时间，只读。
- `LogRow`：`at, source, title, detail, level`；来源为 device/task/audit/command。

### 3.2 数据来源

- `telemetry`：环境/姿态历史；按 `received_at` 窗口、箱体过滤。
- `node_latest`：最新 ENV/POSE。
- `alarms`/`alarm_state`：告警。
- `events`：设备事件。
- `transport_tasks`：任务创建/开始/结束。
- `audit_logs`：操作审计。
- `command_operations` + `node_commands`：命令历史。

### 3.3 模拟历史

`DemoFleetService` 现在除 `node_latest` 外，按同一 shape 写入 `telemetry`（boot=1、SNTP、property_seq），使模拟箱详情趋势有服务端样本；真实板上报链路不变。

## 4. 前端

- `BoxOverview.vue` 升级为八 Tab 容器；保留 `MapScene` 与 route 视觉。
- 新增 `SampleChart.vue`：按时间排序，无效/缺失点断线，不填 0。
- Tab 内容：
  - 总览：现有摘要 + 姿态 + MOCK 地图 + 最近事件。
  - 环境：最新值 + 1/6/24h 趋势 + 最近 30 条采样表。
  - 姿态：最新状态/倾角/加速度 + 趋势 + POSE/IMPACT 事件。
  - 定位：MOCK 路线、任务列表、已完成任务摘要；无回放。
  - 告警：箱体告警表 + 设备事件；无处置按钮。
  - 节点：节点能力/必需/在线/最后心跳 + 最近 ENV/POSE 状态。
  - 控制：capability 列表 + 命令历史；无提交按钮，明确标记“只读”。
  - 日志：合并时间线，来源标签。
- 1440×900 和 768×1024 可用；Tab 在窄屏横向滚动。
- 无数据/未知/过期/部分在线显示明确空态或质量，不用正常色覆盖。

## 5. 错误与边界

- 样本窗口不合法：400 `VALIDATION_ERROR`；kind 不支持：400。
- 箱体不存在/非本企业：404。
- 无样本/无姿态/无节点/无任务/无命令：空态，不显示正常。
- 真实板单节点在线：PARTIAL；不把 PARTIAL 显示为 ONLINE。
- 控制中心：不提交命令、不伪造 ACK；无固件/电量/信号时不编造。
- 任务历史与箱体最近样本分离；箱体详情不把下一任务样本当上一任务历史。
- 不新增数据库迁移；若查询性能不足再另评索引。

## 6. 最小验证与结果（2026-11-24）

### 6.1 已执行

- 后端编译通过。
- `MockPositionProjectorTest` 5 项通过。
- `EnterpriseConsoleIT` **11 项**通过；新增 `detailTabsReadModels` 覆盖 pose、env samples、events、alarms、logs、commands 历史。
- 前端 `vue-tsc --noEmit`、`vite build` 通过。
- 浏览器：登录→资产→详情，8 个 Tab 全部可切换；环境/姿态空态、模拟箱趋势、控制中心只读、日志；无页面错误。
- 截图（本机忽略目录）：`artifacts/task017-ui/desktop-detail-environment.png`、`desktop-detail-environment-sim.png`、`desktop-detail-control.png`、`tablet-detail-control.png`。
- **真实开发板（2026-11-24 04:31）：** V17 已应用到 `smartbox_dev`（flyway 17）；用只读后端 `18081`（MQTT 关闭、workers 关闭）+ 前端 `15175` 验证 `BOX-0001`：`connectivity=PARTIAL`、POSE `FRESH`、ENV `STALE`；POSE 1h 样本 200、ENV 1h 样本 0；姿态曲线正常渲染，环境为空态。真实截图 `artifacts/task017-ui/desktop-real-pose.png`、`desktop-real-env.png`。
- `python tools/check_docs.py` 通过。

### 6.2 未验证/未实现

- 控制中心提交、PENDING、设备 ACK、失败/超时已由 [TASK-018](TASK-018-企业控制中心.md) 补齐；TASK-017 本身只做只读历史。
- 告警人工处置/转交/备注未实现。
- 真实板详情只验证到 POSE；ENV 自 03:38 后无新心跳，完整 ONLINE、双板详情和 ENV 趋势未验证。
- 新 `18081` 为只读观察实例，未启用 MQTT；真实 MQTT 入湖仍由现有 8081 负责，双实例同时订阅可能重复入库，故未开启。
- 真实命令执行、真实 GPS/ETA/围栏、轨迹回放、3D 姿态、CSV 未验证。
- 完整 `mvn verify` 的既有 Mockito/JDK 自附加问题仍单独记录；本轮使用定向测试。
- 开发库迁移已执行；生产/其他环境迁移仍未验收。

## 7. 后续

[TASK-018 企业控制中心](TASK-018-企业控制中心.md) 已补齐控制提交/回执；告警人工处置、详情/日志导出和资产/告警/任务 CSV 仍是后续切片。

## 8. 假设

1. 控制中心本轮只读，不改变命令状态。
2. 箱体详情是箱体级，任务级历史仍以任务详情为准。
3. 模拟趋势由 `DemoFleetService` 写 `telemetry`，不引入真实 MQTT 身份。
4. 先完成读模型/页面，再做控制执行，避免无设备证据的“假成功”。