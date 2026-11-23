# TASK-015 企业端运营控制台首切片

| 属性 | 内容 |
|---|---|
| 阶段 | 企业端 V2.0 / 首切片 |
| 负责人 | 用户 |
| 状态 | **首切片实现完成（DEMO），2026-11-24；真实板/生产未验收** |
| 依赖 | TASK-011～014 的既有企业/运输/设备代码；V1～V15 迁移；现有 MQTT 与模拟器 |
| 产品依据 | [企业端物流运营控制台 V2.0 需求规格](../../requirements/product/企业端物流运营控制台-V2.0-需求规格.md)、[CR-007](../../management/changes/CR-007-企业端优先与物流运营控制台.md) |
| 视觉依据 | [`frontend/design-exploration` 的 `direction=route`](../../../frontend/design-exploration/README.md) |
| 范围 | 运营总览、运输任务、位置与设备（MOCK 地图）；资产/告警/箱体总览只读支撑 |
| 不做 | 资产 CRUD/场景切换、八分区详情、告警人工处置、CSV、轨迹回放、真实 GPS、骑手/客户/访客新增功能 |

> **本任务的状态口径：**设计已经落笔，代码、迁移、前端页面正在按本文实施。只有第 12 节的最小验证实际执行并在第 15 节记录结果后，才能把本任务标为“首切片完成（DEMO）”；设计文本、编译通过或页面能打开都不等于业务验收。

## 1. 目标、非目标与完成口径

### 1.1 目标

1. 企业管理员登录后直接进入 route 风格的物流运营控制台，五个一级页与两个二级页可用。
2. 企业管理员可为本企业箱体创建 READY、编辑 READY 的物品/模拟路线、开始 ACTIVE、结束 COMPLETED。
3. 运营总览的 KPI、地图标记、任务侧栏、最近动态读取同一批服务端事实。
4. 地图按任务路线显示可复现的 MOCK 位置；READY 停在起点，ACTIVE 随时间推进，COMPLETED 固定历史终点。
5. 1 个实物箱体与 5 个模拟箱体在同一列表/地图/统计中出现，来源、在线、运输、告警、数据质量各自独立。
6. 资产、告警、箱体总览提供只读支撑面，保证企业导航没有死按钮；八 Tab、告警处置等留给后续。
7. 只做与原型演示相称的必要验证，把主要工作投入业务和页面。

### 1.2 非目标

- 不建设生产级物流 SaaS：无真实 GPS、真实地图导航、签收、运费、派单、路线最优、客户/骑手协作。
- 不实现资产创建/编辑/停用、模拟场景切换 UI、八分区详情、告警人工确认/处理、CSV、轨迹动画回放。
- 不扩大设备控制权限；首切片不新增设备控制入口。
- 不重写旧骑手/访客页面，不删除旧接口和旧迁移。
- 不为 V2 新建正式 REQ/TC 编号；E2 需求仍按产品文档追踪，正式 REQ 化另行处理。

### 1.3 首个可演示闭环

登录 → 运营总览 → 新建运输 READY → 开始 ACTIVE（必要时风险确认）→ 地图/详情看到位置推进 → 结束 COMPLETED → 旧任务、路线、历史仍可查 → 资产/告警/箱体总览能跳到同一箱体。告警只读，不执行人工处置。

## 2. 输入事实与现有代码边界

| 事实 | 影响 |
|---|---|
| 最新迁移为 V15；V14 `transport_tasks.rider_id NOT NULL` | 企业直接建任务必须追加 V16，放宽 `rider_id` 并记录企业操作者 |
| `TransportService` 所有写操作先 `requireCourier` | 不修改旧骑手服务；新增企业写服务，避免破坏旧接口 |
| `uk_transport_active_box` 生成列唯一约束已存在 | 继续依赖数据库保证同箱最多一个 ACTIVE |
| 企业任务旧查询靠 `box_ownerships` 连接 | 新 console 查询同时支持 `enterprise_id` 与所有权兜底，旧接口不改 |
| `AssetBootstrapService` 有受控 JSON 种子 | 扩展为可创建模拟箱体/节点/演示历史，不覆盖实物数据 |
| `location_samples` 无 `transport_id` | 不复用它做任务路线；任务保存路线快照，服务端确定性投影 |
| `QueryService` 在线规则为必需节点 + 90 秒，环境 5 秒/位置 15 秒新鲜度 | 新读服务沿用同一口径；不把 `user_box_access` 当企业所有权 |
| CORS 仅 GET/POST | 所有新写端点使用 POST |
| 旧企业页使用 `/enterprise?view=assets|transports` | 新路由保留兼容重定向 |

## 3. UI 基准与页面地图

### 3.1 不可替换的视觉结构

- 深蓝左侧栏（品牌、工作空间、五项导航、账户）。
- 顶部工作区（面包屑、来源/MOCK 提示、刷新、用户）。
- 运营总览：KPI 行 → 地图主区＋任务侧栏 → 最近动态/告警下带。
- 位置与设备：地图大主区＋右侧资产列表/选中卡片＋地图工具/图例。
- 表格、抽屉、状态胶囊、来源标签、MOCK 水印沿用 route 原型的视觉语言。
- 允许为真实数据、空态、权限和可读性做最小调整；不重画成普通后台表格页。

### 3.2 页面与路由

| 页面 | 路由 | 首切片内容 | 后续 |
|---|---|---|---|
| 运营总览 | `/enterprise` | KPI、地图、任务侧栏、最近动态、快捷入口、刷新时间 | 时间维度分析 |
| 物流箱资产 | `/enterprise/assets` | 只读列表、搜索/来源/在线/运输筛选、进入详情 | 创建/编辑/导出 |
| 箱体总览 | `/enterprise/assets/:boxId` | 身份、来源、在线、环境、当前/最近任务、告警、节点、MOCK 位置 | 八分区 Tab |
| 运输任务 | `/enterprise/transports` | 列表、筛选、创建抽屉、READY 编辑 | 取消、导出、回放 |
| 运输详情 | `/enterprise/transports/:id` | 任务、物品、操作者、箱体、路线/位置、环境、开始/结束 | 事件筛选、回放 |
| 告警中心 | `/enterprise/alarms` | 只读活跃/恢复列表、筛选、详情抽屉 | 确认/处理中/已处理 |
| 位置与设备 | `/enterprise/map` | 大地图、标记、路线、资产列表、选中卡片、缩放/重置 | 场景切换、设备控制 |

旧 `/enterprise?view=assets|transports` 在路由守卫中重定向到新路径；旧 `/enterprise/transports/:id` 保留为任务详情。

### 3.3 数据来源标识

- `asset_source=REAL`：真实设备/数据；`SIMULATED`：模拟箱体/演示数据；两者同一列表但徽标不同。
- 地图位置始终标 `MOCK`；模拟箱体的位置也不称为 GPS。
- 告警/环境未知时显示 `UNKNOWN/—`，不使用绿色“正常”图标覆盖。
- 演示服务产生的模拟快照标 `SIMULATED · DEMO`，不写成真实采样历史。

## 4. 总体方案与 API 命名

- 保持单体 Spring Boot + 现有 Vue；不拆微服务、不引入地图 SDK、不重写请求层。
- 新增只增不改的 API 命名空间：`/api/v1/enterprise/console/**`。旧 `/enterprise/**`、`/rider/**`、`/boxes/**` 保持响应不变。
- 后端新增 `cn.smartbox.enterprise` 包：控制器、读服务、企业运输写服务、路线/位置投影、演示快照服务。
- 前端新增 `frontend/src/business/enterprise` 页面/组件与 `model.ts`；复用 `session.request`、`usePolling`、`ApiError`、`BusinessShell`/`EnterpriseLayout`。
- 数据流：MQTT/真实板 → 现有入库表（不变）；企业 console 读服务组装 `boxes/nodes/node_runtime/node_latest/transport_tasks/alarms/alarm_state/events`；前端只读后端视图。
- 轮询：企业页 12 秒；`usePolling` 增加可选间隔参数，原有调用默认 10 秒不变；响应带 `refreshed_at`。

## 5. V16 数据变更

新增 `backend/src/main/resources/db/migration/V16__enterprise_console.sql`：

```sql
ALTER TABLE boxes
  ADD COLUMN asset_source VARCHAR(16) NOT NULL DEFAULT 'REAL'
  CHECK (asset_source IN ('REAL','SIMULATED'));

ALTER TABLE transport_tasks
  MODIFY rider_id BIGINT NULL,
  ADD COLUMN operator_user_id BIGINT NULL,
  ADD COLUMN enterprise_id BIGINT NULL,
  ADD COLUMN route_code VARCHAR(32) NULL,
  ADD COLUMN route_snapshot JSON NULL;

UPDATE transport_tasks SET operator_user_id = rider_id WHERE operator_user_id IS NULL;
UPDATE transport_tasks t
  JOIN box_ownerships o ON o.box_id=t.box_id AND o.owner_type='ENTERPRISE'
  SET t.enterprise_id=o.enterprise_id
  WHERE t.enterprise_id IS NULL;

ALTER TABLE transport_tasks
  ADD CONSTRAINT fk_task_operator FOREIGN KEY (operator_user_id) REFERENCES users(id),
  ADD CONSTRAINT fk_task_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprises(id),
  ADD CONSTRAINT chk_task_actor CHECK (rider_id IS NOT NULL OR operator_user_id IS NOT NULL),
  ADD INDEX ix_task_enterprise_created (enterprise_id, created_at, id);
```

- `route_snapshot`：`{code,name,from,to,duration_ms,points:[{x,y,place}]}`；创建/编辑 READY 时冻结。旧任务为 NULL。
- 不新增任务位置表。位置由 `route_snapshot + started_at/ended_at` 服务端计算，刷新/跨浏览器可复现。
- 不新增告警人工处置表。首切片只读 `alarms/alarm_state`；后续再加 `alarm_actions`。
- 旧任务保留 `rider_id`；`operator_user_id` 回填为 `rider_id`；`enterprise_id` 按迁移时企业所有权回填，个人箱体保持 NULL。
- 企业 console 任务范围：`enterprise_id=当前企业`，并允许 `enterprise_id IS NULL` 且箱体当前属于该企业的旧/兼容任务，避免 V16 后旧骑手接口新建的企业箱任务丢失。
- 旧 `uk_transport_active_box`、`chk_transport_times` 不变。

## 6. 读模型、KPI 与位置算法

### 6.1 视图

- `EnterpriseOverview { enterprise, refreshed_at, kpis, boxes[], tasks[], recent_activity[] }`
- `EnterpriseBox { box_id, display_name, asset_source, connectivity, data_state, environment, current_transport?, last_completed_transport?, alarm, position?, last_seen_at }`
- `EnterpriseTask { task_id, transport_no, box_id, box_display_name, status, item_name, item_description, operator_username, created_at, started_at, ended_at, route, position, start_check }`
- `EnterpriseRoute { code, name, from, to, duration_ms, points[] }`
- `EnterpriseAlarm { box_id, display_name, alarm_key, active, level, started_at, updated_at, recovered_at, source_event, details }`
- `Activity { at, type, box_id, task_id?, title, source }`
- `Position { x, y, place, route_code, route_name, progress, position_at, stale, finished, source='MOCK' }`

### 6.2 KPI 与状态口径

- 资产总数：当前企业 `box_ownerships` 的箱体数。
- 在线：全部必需节点在线且 `last_seen` 90 秒内；`PARTIAL`、`OFFLINE`、`UNKNOWN` 分别计数，不把 PARTIAL 算在线。
- ACTIVE 运输：企业范围内 `status=ACTIVE` 的任务数。
- 活跃告警箱：`alarm_state.assessment='EVALUATED' AND active=1` 的去重箱数；另列“告警判定未知箱数”。
- 今日完成：`ended_at` 在 Asia/Shanghai 当日、`status=COMPLETED` 的任务数。
- 环境：只使用 `alarm_state` 的评估结论；`UNKNOWN` 不显示正常。最新样本带观测时间和质量。
- 数据质量：ENV 最新样本按 `received_at` 与 5 秒门槛近似 `FRESH/STALE`；`valid=false` 为 `INVALID`；无样本为 `UNKNOWN`。首切片不展示历史曲线，不声称生产级时间质量。
- 最近动态：任务创建/开始/结束、告警触发/恢复、最近设备事件合并，按服务端时间倒序，最多 8 条。
- 所有统计限定当前企业，服务端根据登录管理员解析企业，不接受前端企业 ID。

### 6.3 MOCK 位置

- 预设 3 条 DEMO 路线，归一化 `x/y`（0～100）与地点名，不使用 `lat/lon`；时长 3～5 分钟，便于演示。
- 箱体位置优先级：ACTIVE > READY > 最近 COMPLETED；无任务显示“未定位”。
- READY：起点。ACTIVE：按 `now-started_at` 沿路线插值；COMPLETED：按 `ended_at` 固定。
- 超出路线时长夹紧终点并提示“路线已到终点，运输未结束”。
- `connectivity != ONLINE` 时冻结在最后已知时刻（`min(now,last_seen)`），`stale=true`，显示“最后已知 MOCK 位置”，不继续推进。
- 前端不生成随机坐标、不使用原型固定点。

## 7. API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/enterprise/console/overview` | 总览聚合（KPI/箱体/任务/动态） |
| GET | `/enterprise/console/boxes` | 企业箱体快照列表 |
| GET | `/enterprise/console/boxes/{boxId}` | 单箱体快照 |
| GET | `/enterprise/console/routes` | 3 条 DEMO 路线 |
| GET | `/enterprise/console/transports` | 企业任务列表，支持 `status/box_id/q` |
| GET | `/enterprise/console/transports/{id}` | 任务详情＋箱体/路线/位置/环境/事件 |
| POST | `/enterprise/console/transports` | 创建 READY：`box_id/item_name/item_description/route_code` |
| POST | `/enterprise/console/transports/{id}/edit` | READY 编辑物品/路线 |
| POST | `/enterprise/console/transports/{id}/start` | 开始 ACTIVE；可带 `confirm_risk` |
| POST | `/enterprise/console/transports/{id}/finish` | 结束 COMPLETED |
| GET | `/enterprise/console/alarms` | 只读告警列表，支持 `active/level/box_id` |

- 所有端点要求 `LOGISTICS_OPERATOR` 且存在企业；无企业 403，跨企业/不存在 404。
- 错误码沿用现有 `ApiException`：`VALIDATION_ERROR`、`NOT_FOUND`、`INVALID_TASK_STATE`、`BOX_OFFLINE`、`BOX_IN_USE`、`RISK_CONFIRMATION_REQUIRED`。
- 新契约目录：[`contracts/http/enterprise-console/`](../../../contracts/http/enterprise-console/README.md)（OpenAPI + README），旧契约不改。
- 旧接口继续保留：`/enterprise/boxes`、`/enterprise/transports`、`/enterprise/transports/{id}/monitoring`、`/rider/**`。

## 8. 关键流程与事务

### 8.1 创建 READY

1. 解析企业上下文，校验角色与箱体 ID、物品（1～50 字）、说明（≤200 字）、路线代码。
2. `SELECT box_ownerships ... FOR UPDATE`，确认箱体属于当前企业；不信任前端企业 ID。
3. 生成 `TR-` 编号；插入 `rider_id=NULL, operator_user_id=管理员, enterprise_id=企业, status='READY'`，冻结路线快照。
4. READY 不占用同箱唯一 ACTIVE；允许同箱多个 READY，开始时才排他。
5. 提交后写审计 `TRANSPORT_CREATE`。

### 8.2 编辑 READY

- 锁所有权与任务；仅 READY 可改物品/路线；ACTIVE/COMPLETED 返回 `INVALID_TASK_STATE`。
- 更新物品、路线代码/快照、`updated_at`；写审计 `TRANSPORT_EDIT`。
- 不允许改箱体、设备身份、运输编号。

### 8.3 开始 ACTIVE

1. 锁所有权 → 锁任务（顺序与旧骑手流程一致，避免死锁）。
2. 校验 READY、已填物品。
3. 必需节点全部在线（90 秒规则），否则 `BOX_OFFLINE`。
4. `alarm_state` 不是 EVALUATED 或 active=true 时要求 `confirm_risk=true`，否则 `RISK_CONFIRMATION_REQUIRED`；确认不改变告警结论。
5. `UPDATE status='ACTIVE', started_at=now`；数据库唯一冲突映射 `BOX_IN_USE`；重复开始返回当前任务。
6. 写审计 `TRANSPORT_START`（风险确认另记 `TRANSPORT_RISK_CONFIRM`）。

### 8.4 结束 COMPLETED

- 锁所有权与任务；仅 ACTIVE 可结束；`ended_at=now`；重复结束返回原记录，不改写时间。
- 设备离线允许结束；不修改 ONLINE/告警/模拟路线；写审计 `TRANSPORT_FINISH`。
- 状态 COMPLETED 只表示本次运输结束，不代表签收。

### 8.5 位置与总览

- 位置投影在读取时计算，不启动后台调度器；同一服务端时钟下刷新可复现。
- 总览聚合一次读取箱体/节点/任务/告警/事件，避免各页面各自编造数字。
- 告警只读：设备异常是否活跃来自 `alarms/alarm_state`；首切片没有人工处置字段。

## 9. 前端实现

### 9.1 目录与组件

```text
frontend/src/business/
  Enterprise.vue                         # 运营总览（保留入口文件名）
  enterprise/
    model.ts                             # DTO、状态文案、时间/质量格式
    MapScene.vue                         # route 风格地图：底图/标记/路线/缩放/MOCK
    TransportDrawer.vue                  # 创建/编辑 READY
    TransportDetail.vue                 # 企业任务详情
    AssetsPage.vue                       # 只读资产
    BoxOverview.vue                      # 最小箱体总览
    AlarmsPage.vue                       # 只读告警
    MapPage.vue                          # 位置与设备
    TransportsPage.vue                   # 运输列表
  layouts/EnterpriseLayout.vue           # 五项导航/顶栏
  styles/enterprise.css                  # 追加样式
```

允许按复杂度合并小组件，但页面、地图、抽屉、状态标签必须有清晰对应；禁止再建一套请求客户端或 Store。

### 9.2 页面要点

- **总览**：KPI 行；地图主区＋任务侧栏；下方最近动态/告警；快捷入口“新建运输/全部资产/告警中心”；最后更新时间；12 秒刷新。
- **地图**：复用原型底图几何；箱体标记按服务端 `x/y` 定位；选中路线高亮；图例文字化；无位置显示“未定位”；缩放/重置只改视图。
- **任务**：创建抽屉含箱体（来源/在线提示）、物品、3 条路线预览；READY 编辑；开始遇风险弹确认；结束二次确认；以后端返回为权威。
- **资产**：只读表格列“箱体/来源/在线/运输/环境/告警/更新时间”；前端搜索/筛选；点击进箱体总览。
- **箱体总览**：一屏摘要，明确“八分区详情后续提供”，不放假 Tab。
- **告警**：只读列表＋抽屉；显示来源/触发/恢复；人工处置按钮隐藏并注明后续。
- **选中态**：总览/地图选中任务或箱体用 URL query 保持返回上下文；详情使用路由参数。
- **状态**：加载、空、错误、未知/过期、无权限均有文案；不用绿色“正常”覆盖 UNKNOWN。

### 9.3 视觉与响应式

- 1440×900 为主；768×1024 地图/任务上下堆叠、KPI 两列、侧栏收窄，不横向溢出。
- 从原型迁移 `.sidebar/.topbar/.kpi-row/.route-dashboard-layout/.route-map-panel/.route-task-panel/.map-console/.map-asset-panel` 的构图与色值，不整份复制 60KB 三方向样式。
- 正文可读字号不低于 13px；不继承原型 9～10px 的评审缩放。
- 真实数据优先于原型装饰；没有真实位置时地图保留示意背景并说明，不绘制假点。

## 10. 演示数据与运行

### 10.1 1 实物 + 5 模拟

- 实物箱体沿用现有企业归属，`asset_source=REAL`；地图位置仍为 MOCK。
- 5 个模拟箱体由受控种子创建，独立 `box_id/node_id`，`asset_source=SIMULATED`，不使用实物身份。
- 场景：正常在线、环境偏高、节点离线、姿态冲击、已完成运输留史。
- 首切片最小运行：1 个实物 ENV/POSE（真实板）＋至少 1 个模拟箱体在线；其余模拟箱体离线/历史/未知。5 个箱体全部可见；5 个同时在线不是完成门槛。
- 模拟快照由演示 profile 的服务端快照服务产生，标 `SIMULATED/DEMO`，只写当前 `node_runtime/node_latest`，不冒充真实采样历史；默认关闭，启动时显式开启。真实箱体仍走现有 MQTT 入库。

### 10.2 种子与演示任务

- 扩展 `AssetBootstrapService` 的可选数组：`simulated_boxes[]`（箱体/节点/required/场景）、`history_tasks[]`（固定编号的 COMPLETED 演示任务）、`demo_alarms[]`（带来源事件的活动告警）。
- 幂等：已存在同 `box_id` 且 `asset_source=REAL` 时拒绝；不改历史时间；不删除开发库。
- 种子示例放在 `deploy/demo/enterprise-v2.seed.example.json`，不含密码/令牌；真实运行使用本地忽略配置。
- 演示账号沿用现有受控初始化；密码哈希只在数据库，前端不提交高权限明文。

## 11. 实施步骤

| 阶段 | 交付 | 检查点 |
|---|---|---|
| S0 视觉/文档 | 启动原型 5186，截 1440/768 基线；本文定稿 | 基线截图/观察记录；不改原型 |
| S1 V16＋种子 | 迁移、`asset_source`、模拟箱体/节点/历史/告警种子、演示快照服务 | 隔离 `smartbox_test` 迁移；1 实物＋5 模拟可查 |
| S2 读模型/总览 | `EnterpriseReadService`、overview/boxes/routes | KPI/地图/任务同源；无假数据 |
| S3 运输写入 | create/edit/start/finish＋详情 | 创建→开始→结束；同箱二次开始 409 |
| S4 前端总览/地图 | 路由、布局、model、总览、地图只读 | 1440/768 构图接近原型 |
| S5 前端任务/支撑面 | 任务列表/抽屉/详情、资产、箱体总览、告警 | 五导航无死按钮；告警只读 |
| S6 联调/收尾 | 演示数据、浏览器主路径、视觉对照、文档结果 | 最小验证通过；分次提交 |

## 12. 最小验证

- 前端：`npm --prefix frontend run typecheck`、`npm --prefix frontend run build`。
- 后端：编译；`MockPositionProjectorTest` 覆盖 READY/ACTIVE/离线冻结；`EnterpriseConsoleIT` 覆盖企业范围、创建、开始、同箱二次开始、结束/重复结束、跨企业拒绝。
- 数据库：在隔离 `smartbox_test` 上启动并应用 V16；不清理 `smartbox_dev`，不停止不属于本任务的 8080/5173。
- 浏览器：一条企业主路径（登录→总览→创建→开始→地图/详情→结束→资产/告警/箱体总览）；1440×900 与 768×1024 截图对照。
- 文档：`python tools/check_docs.py`；在本文第 15 节记录实际命令、结果、未验证项。
- 不做大量单元/异常矩阵/并发/性能/长稳测试；未测项明确写“未验证”。

## 13. 风险、兼容与回滚

- **V16 回填**：`enterprise_id` 按迁移时所有权回填；个人箱体保持 NULL；企业查询保留所有权兜底；回填后对比企业任务数量，异常停止不猜。
- **旧接口**：新 console 端点独立；旧 `/enterprise/**`、`/rider/**`、`/boxes/**` 不改。V16 只放宽 `rider_id` 并加列，旧骑手流程仍可写。
- **同箱约束**：继续依赖数据库生成列唯一索引；服务层捕获冲突映射 `BOX_IN_USE`。
- **模拟数据**：演示快照服务 profile 默认关闭，只处理 `SIMULATED`；不覆盖 REAL、不写真实历史；UI 明示来源。服务不可用时模拟箱体离线，实物箱体仍可演示，不以假在线补位。
- **位置**：离线冻结、终点夹紧、无路线空态；所有坐标 MOCK。
- **UI**：先原型截图再实现；1440/768 对照不通过不标完成。
- **check_docs**：本次不新建测试用例文档、不改校验器；TASK-015 为设计/实施文档，后续建立正式测试编号时再同步。

## 14. 明确不做与后续文档

- 资产创建/编辑/停用/场景切换见 [TASK-016](TASK-016-企业模拟资产与场景管理.md)（设计草案）；本节其余项仍待后续。
- 八分区箱体详情、环境/姿态趋势、控制中心、运行日志（第二份设计）。
- 告警人工确认/处理中/已处理、CSV、通知（第二份设计）。
- 历史轨迹回放、真实 GPS、真实签收/支付、骑手/客户/访客新增功能。
- 以上未完成项不得在页面放“看起来可点但无业务”的按钮；显示禁用/说明或隐藏。

## 15. 实施结果（2026-11-24）

> 本节记录实际执行结果；软件、浏览器与真实板/生产分开阅读。

### 15.1 环境与范围

- Java 21.0.10、Maven、MySQL 8、Node.js 24、Vue 3.5/Vite 8.3.6；仅使用隔离 `smartbox_test` 和本任务自己的 18080/15173 进程。
- 未停止或替换原有 8080/5173/5186 服务；未改开发库 `smartbox_dev`。
- 演示种子：1 个实物 `BOX-0001`（本轮未接真实板，保持离线）＋5 个 `SIMULATED` 箱体 `BOX-SIM-01～05`。模拟在线/温湿度/姿态由 `DemoFleetService`（显式 `smartbox.demo.fleet=true`）产生，明确标 SIMULATED/DEMO；真实 MQTT 入库存量链路未改。
- 交付：V16、`/api/v1/enterprise/console/**`、前端六页/二级页、`deploy/demo/enterprise-v2.seed.example.json`、[console 契约](../../../contracts/http/enterprise-console/README.md)。

### 15.2 V16 迁移

- 在 `smartbox_test` 上由 Flyway 从 V15 升到 V16，日志：`Successfully applied 1 migration ... now at version v16`。
- 新增 `boxes.asset_source`；放宽 `transport_tasks.rider_id` 为可空；新增 `operator_user_id/enterprise_id/route_code/route_snapshot`、回填操作者与企业、外键/检查/索引。旧 V1～V15 未改写。

### 15.3 后端检查

- `mvn -q -DskipTests compile`：通过。
- `MockPositionProjectorTest`：5 项通过（READY 起点、ACTIVE 插值、离线冻结、COMPLETED 进度/终点、无路线）。
- `EnterpriseConsoleIT`：5 项通过（企业范围、创建→开始→同箱 409→结束、风险确认、READY 编辑/状态校验、模拟箱体/历史/告警种子）。
- 完整 `mvn verify` 曾因本机既有的 Mockito/JDK 自附加问题在 `FoundationTest`、`LiveWebSocketTest` 失败；该失败与本切片新增代码无关，本轮没有把它标记为通过。定向的 5 项 console IT 与 5 项投影单测实际通过。

### 15.4 前端检查

- `npm --prefix frontend run typecheck`：通过。
- `npm --prefix frontend run build`：通过（1687 modules，产物生成）。首次在受限沙箱内 Vite 因 Windows 子进程 `spawn EPERM` 失败，放宽本机文件权限后重跑成功；未改业务代码规避。

### 15.5 浏览器主路径与截图

- 使用系统 Chrome（Playwright）在 `http://127.0.0.1:15173` → 代理 `18080` 实际登录测试企业账号，执行：
  登录 → 运营总览 → 位置与设备 → 运输任务 → 新建 READY（BOX-SIM-01、模拟路线）→ 开始（风险确认）→ 结束 → 资产 → 告警，并切到 768×1024 复查总览/地图。
- 结果：`PASS: enterprise console browser path and screenshots`，无页面异常；截图保存在本机忽略目录 `artifacts/task015-ui/`：`desktop-dashboard.png`、`desktop-map.png`、`desktop-transports.png`、`desktop-transport-active.png`、`desktop-transport-completed.png`、`desktop-assets.png`、`desktop-alarms.png`、`tablet-dashboard.png`、`tablet-map.png`、`checks.json`。
- 视觉对照：深蓝侧栏、顶部工作区、总览 KPI→地图＋任务侧栏→动态、地图全屏＋右侧资产、抽屉/状态胶囊沿用 route 原型；P1 差异包括地图标记数字/间距、部分表格换行、平板侧栏压缩方式，未发现 P0 结构偏离。

### 15.6 文档与契约

- 新增 [console 契约](../../../contracts/http/enterprise-console/README.md) 与 OpenAPI；旧 `/rider/**`、`/enterprise/**`、`/boxes/**` 未改。
- 任务索引、[当前进度](../../management/当前进度.md)、[文档入口](../../README.md)、[追踪矩阵](../../management/需求追踪矩阵.md) 已同步；E2 需求仍未正式 REQ 化。
- `python tools/check_docs.py`：通过，`modules=7, requirements=59, trace_rows=62, migration_rules=64, links=3487, markdown=152`。该检查只覆盖文档/迁移/样例，不代表应用或硬件验收。

### 15.7 未验证/明确跳过

- 真实板 ENV/POSE、真实 MQTT 模拟箱体、5 个模拟箱体同时在线、告警人工处置、资产 CRUD/场景切换、八分区详情、CSV、轨迹回放、真实 GPS、生产安全、并发/性能/长期稳定性均未在本切片验证。
- `BOX-0001` 当前在地图中可能来自历史/种子位置；没有把它说成实时 GPS 或在线设备。
- 平板仅做 768×1024 基本堆叠检查，未做手机 390×844 专项。
- 完整 `mvn verify` 仍受本机 Mockito/JDK 自附加问题影响；不能宣称全量后端回归通过。
