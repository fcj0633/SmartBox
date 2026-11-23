# TASK-016 企业模拟资产与场景管理

| 属性 | 内容 |
|---|---|
| 阶段 | 企业端 V2.0 / 资产与场景切片 |
| 负责人 | 用户 |
| 状态 | **首切片实现完成（DEMO），2026-11-24；真实设备/生产未验收** |
| 依赖 | [TASK-015 首切片](TASK-015-企业端运营控制台首切片.md)、V16、现有 `/enterprise/console/**`、`DemoFleetService`、`AssetsPage` |
| 产品依据 | [企业端 V2.0 需求](../../requirements/product/企业端物流运营控制台-V2.0-需求规格.md)、[CR-007](../../management/changes/CR-007-企业端优先与物流运营控制台.md) |
| 需求范围 | E2-ASSET-04/05/06/07 |
| 视觉依据 | [`frontend/design-exploration` 的 `direction=route`](../../../frontend/design-exploration/README.md) |
| 明确不做 | 八分区详情、告警人工处置、CSV、真实 GPS、真实 MQTT 新身份、真实箱体删除、客户/骑手/访客新增功能 |

> **状态口径：**设计已经按本文实施，实际结果见第 16 节。V17、API、前端交互和场景事实生成已在隔离 `smartbox_test` 执行最小验证；真实设备、MQTT 模拟身份、开发库迁移和生产环境仍未验收。本文不改变 TASK-015 的已完成范围，也不把模拟箱体说成真实 MQTT 设备。

## 1. 目标、范围与非目标

### 1.1 目标

1. 企业管理员可在资产页创建新的模拟箱体，系统生成独立 `box_id`、ENV/POSE 节点身份和企业归属。
2. 管理员可编辑箱体名称、用途、备注；REAL 与 SIMULATED 都允许元信息编辑，但不允许改 `box_id`、来源、节点身份或所有权。
3. 管理员可为模拟箱体切换正常、高温、离线、过期、姿态冲击五种演示场景，并在地图、资产、箱体总览、告警中心看到服务端事实变化。
4. 管理员可停用/启用模拟输入；有 ACTIVE 运输时拒绝停用；停用保留任务、历史、位置和告警记录。
5. `DemoFleetService` 从数据库场景读取，而不是只读种子；新建的模拟箱体无需改种子也能被演示快照服务接管。
6. 保持 route 原型的资产表格、抽屉、状态胶囊、MOCK/SIMULATED 标注；不出现无业务死按钮。
7. 真实箱体数据、真实 MQTT 入库、旧 `/enterprise/**`、`/rider/**`、`/boxes/**` 不被本任务改写。

### 1.2 非目标

- 不做八分区箱体详情、环境/姿态趋势、控制中心、运行日志。
- 不做告警人工确认/处理中/已处理、CSV、通知。
- 不为 UI 创建的模拟箱体申请真实 MQTT 账号/ACL；它们只有独立节点身份，由演示快照服务提供 SIMULATED/DEMO 事实。
- 不做真实箱体删除、来源变更、节点重绑、跨企业转移。
- 不把 `impact` 场景说成现有规则自动产生的告警；当前 `DomainEventWorker` 不为 IMPACT 派生告警。
- 不清理 `smartbox_dev`、不停止 8080/8081/5173/5174、不改真实板/MQTT。

### 1.3 首轮可演示路径

资产页 → 新建模拟箱 → 默认 normal 在线 → 切 env_high，告警中心/箱体摘要出现环境告警 → 切 offline，节点离线且保留最后位置 → 切 normal，环境告警恢复 → 停用被 ACTIVE 任务阻止 → 编辑 REAL 箱体的名称/用途，来源和节点不变。

## 2. 现有事实与复用边界

| 事实 | 设计含义 |
|---|---|
| V16 已有 `boxes.asset_source`、企业 console API、只读资产页 | V17 增量扩展，不重写首切片 |
| `boxes` 目前无用途/备注/场景/启停字段 | V17 增加 `purpose/notes/demo_scenario/demo_enabled` |
| `DemoFleetService` 当前从 `DemoAssetConfig` 种子的 `simulated_boxes[].scenario` 读取 | 改为按数据库中 `asset_source='SIMULATED'` 的箱体和场景运行 |
| 现有种子有 5 个模拟箱、静态 `demo_alarms` | 种子继续负责初始资产；动态场景不依赖静态告警 |
| `DomainEventWorker` 将 `ENV_HIGH/ENV_LOW/ENV_RECOVERED` 派生为环境告警；`IMPACT` 当前不派生告警 | `env_high` 用现有规则；`impact` 只产生设备事件，不伪造告警 |
| `EnterpriseReadService.Box` 当前无 purpose/notes/scenario/enabled | DTO、OpenAPI、前端类型需扩展 |
| `AssetsPage` 当前只读，无新建/编辑/场景/停用按钮 | 新增抽屉与行操作，沿用 route 视觉语言 |
| 真实板数据在开发库；演示/测试使用 `smartbox_test` | TASK-016 只在隔离测试库验证，不触碰真实板数据 |

复用：

- 企业范围继续由 `EnterpriseReadService.context` 解析，不接受前端 `enterprise_id`。
- 写接口继续用 POST、`ApiEnvelope`、`ApiException`、`AuditService`。
- 地图/资产/总览继续消费同一个 `EnterpriseBox` 读模型。
- 场景快照继续由 `DemoFleetService` 产生，默认关闭，只处理 SIMULATED。

## 3. UI 设计

### 3.1 资产页

在现有 `AssetsPage` 上增加：

- 标题操作区：“＋ 新建模拟箱体”。
- 表格列保持“物流箱/来源/在线/运输/环境/告警/最后位置/更新时间”，增加“场景”与“操作”列：
  - SIMULATED 行显示场景徽标（正常/高温/离线/过期/冲击）与“停用/启用”状态。
  - REAL 行场景显示“—”，不显示场景/停用操作。
- 行操作：
  - “编辑”：打开编辑抽屉。
  - “场景”：仅 SIMULATED，打开场景抽屉。
  - “停用/启用”：仅 SIMULATED；停用前二次确认。
- 筛选增加“场景”和“启停”可选条件；演示规模前端过滤即可。
- 只读说明改为：创建/编辑/场景/启停可用；八分区详情与 CSV 仍不在本切片。

### 3.2 抽屉

**新建模拟箱体**

- 字段：展示名（必填，1～128）、用途（可选，≤120）、备注（可选，≤500）、初始场景（默认 normal）。
- 明确提示“将创建 SIMULATED 箱体与独立 ENV/POSE 节点；数据由演示快照服务产生，不是 MQTT 实体设备”。
- 创建成功后关闭抽屉、刷新列表、选中新箱体。
- 不提供手填 `box_id/node_id`。

**编辑箱体**

- 字段：展示名/用途/备注；只读显示 box_id、来源、节点身份。
- REAL/SIMULATED 都可编辑这三个字段；保存后刷新。
- 不允许改来源、节点、所有权。

**场景切换**

- 选项：正常、高温、离线、过期、姿态冲击；显示每个场景的预期事实（正常在线/高温告警/离线保留位置/过期不更新/姿态事件）。
- 二次确认；提交后刷新，不前端直接改状态或塞告警。
- 错误原样显示；失败不改变场景。

**停用/启用确认**

- 停用：说明“停止模拟快照，保留任务/历史/最后位置；ACTIVE 运输时拒绝”。
- 启用：按当前场景恢复模拟快照；不自动创建真实 MQTT。

### 3.3 视觉与响应式

- 保留 route 原型的深蓝侧栏、顶部工作区、表格/抽屉/状态胶囊、MOCK/SIMULATED 标签。
- 场景徽标使用文字＋颜色，不仅靠颜色区分。
- 1440×900 为主；768×1024 抽屉全宽或可滚动，表格可横向滚动，主按钮不被遮挡。
- 空态：无资产时保留“新建模拟箱体”；无模拟箱体时 REAL 行仍可编辑。

## 4. 数据模型 V17

新增 `backend/src/main/resources/db/migration/V17__demo_asset_scenarios.sql`：

```sql
ALTER TABLE boxes
  ADD COLUMN purpose VARCHAR(120) NULL,
  ADD COLUMN notes VARCHAR(500) NULL,
  ADD COLUMN demo_scenario VARCHAR(16) NULL,
  ADD COLUMN demo_enabled BOOLEAN NOT NULL DEFAULT 1;

UPDATE boxes SET demo_scenario='normal'
  WHERE asset_source='SIMULATED' AND demo_scenario IS NULL;

ALTER TABLE boxes ADD CONSTRAINT chk_demo_scenario CHECK (
  (asset_source='REAL' AND demo_scenario IS NULL)
  OR (asset_source='SIMULATED' AND demo_scenario IN ('normal','env_high','offline','stale','impact'))
);
```

- `purpose/notes` 对所有箱体可空、可编辑；长度限制在服务端再校验。
- `demo_scenario` 只对 SIMULATED 有意义；REAL 必须 NULL。
- `demo_enabled` 默认 1；REAL 保持 1，不用于控制真实设备。
- 不新增 `box_demo_settings` 表，减少 join；V17 只加列，V1～V16 不改。
- 新建模拟箱体 ID 建议 `BOX-SIM-` + 8 位大写十六进制；冲突重试。节点 ID 为 `{box_id}-ENV`/`{box_id}-POSE`，`caps=71/120`，`required=true`。
- 不创建 MQTT 账号、ACL、设备密钥；这是 DEMO 快照身份，不是实体设备身份。
- 新箱体写 `boxes/box_ownerships/nodes`，不写 `user_box_access`；企业 console 按所有权判断，旧设备页兼容不在本任务。

## 5. 场景与事实模型

| 场景 | DemoFleet 快照 | 服务端事实 | UI |
|---|---|---|---|
| `normal` | ENV/POSE 正常在线，温湿度/姿态正常 | 产生 `ENV_RECOVERED`（有活动环境告警时），由现有事件/规则链路清告警 | 在线、环境正常、无活动环境告警 |
| `env_high` | 温度约 9.2°C、湿度约 62% | 产生 `ENV_HIGH` 事件，经现有 `DomainEventWorker` 派生环境告警 | 告警中心/箱体摘要出现 WARNING 活动告警 |
| `offline` | `node_runtime.online=0`，停止新样本 | 保留最后位置和最后观测时间，不生成新位置 | OFFLINE，位置显示“最后已知 MOCK” |
| `stale` | 节点保持在线，停止更新 `node_latest` | 超过 5 秒后 `data_state=STALE` | 环境“已过期”，不把旧值当新读数 |
| `impact` | POSE 加速度/倾角冲击快照 | 产生 IMPACT 设备事件；**不伪造 IMPACT 告警** | 事件可见；告警仍用 env_high 演示 |

### 5.1 事实生成方式

- **快照**：`DemoFleetService` 每 5 秒从数据库读取启用、SIMULATED、指定场景的箱体，写 `node_runtime`/`node_latest`；不写 REAL。
- **env_high / normal 告警**：场景切换服务构造合法的 ENV_HIGH/ENV_RECOVERED 事件，经现有 `IngestionService` 的事件/Outbox 链路进入 `events`、`processing_outbox`，由 `DomainEventWorker` 生成/恢复告警。事件身份、boot/seq、内容要唯一，避免重复。
- **impact**：只产生 IMPACT 设备事件（同一受控事件入口），不改写 `alarms`；当前规则不会把 IMPACT 变成告警，不能在前端或服务端假造。箱体总览通过只读事件接口展示该事件。
- **offline/stale**：只改变快照/在线/新鲜度，不生成告警；已有告警不会被场景切换偷偷清除。
- **normal**：仅清理本任务产生的环境告警；不触碰真实箱体或其他来源告警。
- 现有示例种子中的静态 `IMPACT:pose` 告警作为历史演示数据处理：实施时从示例种子移除；已有测试库记录不强制清理，但不得作为动态场景承诺。
- 场景切换写审计（`ASSET_SCENARIO`），记录 actor、box、旧/新场景。

### 5.2 并发与生效时间

- 场景切换事务提交后触发一次即时快照/事件；最迟下一次 5 秒 tick 生效。
- `DemoFleetService` 每轮重新读取 DB 场景，禁止缓存种子场景；旧 tick 不得覆盖新场景（按当前行值生成）。
- 双击/并发切换以最后一次提交为准；返回最新 Box，前端刷新。
- 停用立即把节点标记离线并停止后续 tick；启用后按当前场景恢复。

## 6. 创建模拟箱体

请求：`display_name`（1～128）、`purpose`（≤120，可选）、`notes`（≤500，可选）、`scenario`（默认 normal）。

服务端事务：

1. 校验企业上下文、字段长度、场景合法。
2. 生成 `box_id` 与节点 ID；若冲突重试；不信任前端 ID。
3. 插入 `boxes`：`asset_source='SIMULATED'`、`demo_scenario`、`demo_enabled=1`、`purpose/notes`。
4. 插入 ENV/POSE `nodes`（caps 71/120，required=true）。
5. 写入企业 `box_ownerships`。
6. 提交后写审计 `ASSET_CREATE`；返回 `EnterpriseBox` 快照。
7. 不创建 MQTT 账号/ACL；不写真实设备凭据。

失败：重复 ID 重试；节点/所有权失败回滚；超出企业范围 403/404；字段非法 400。

## 7. 编辑与启停

### 7.1 编辑

- REAL/SIMULATED：只允许 `display_name/purpose/notes`。
- 拒绝 `box_id/asset_source/node_id/enterprise_id` 等字段。
- 事务锁 `box_ownerships` 后更新；写 `ASSET_EDIT` 审计。
- 显示名长度、用途/备注长度重新校验。

### 7.2 停用

- 仅 SIMULATED；REAL 返回 `ASSET_NOT_SIMULATED`。
- 若有 ACTIVE 运输：409 `BOX_IN_USE`，不改变启停。
- 设置 `demo_enabled=0`，节点 runtime offline，停止快照；保留 `transport_tasks/events/alarms/location_samples`。
- 写 `ASSET_DISABLE` 审计。

### 7.3 启用

- 仅 SIMULATED；设置 `demo_enabled=1`，保留当前场景；下一次 tick 恢复快照/事实。
- 若场景是 env_high，恢复后可能重新产生环境告警；不隐藏。
- 写 `ASSET_ENABLE` 审计。

## 8. API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/enterprise/console/boxes` | 创建模拟箱体 |
| POST | `/enterprise/console/boxes/{id}/edit` | 编辑名称/用途/备注 |
| POST | `/enterprise/console/boxes/{id}/scenario` | 切换场景；body `{scenario}` |
| POST | `/enterprise/console/boxes/{id}/disable` | 停用模拟输入 |
| POST | `/enterprise/console/boxes/{id}/enable` | 启用模拟输入 |
| GET | `/enterprise/console/boxes/{id}/events` | 只读最近设备事件（最多 10 条）；用于 impact 事件可见 |

- `GET /enterprise/console/boxes`、`GET /boxes/{id}` 返回新增字段：`purpose, notes, demo_scenario, demo_enabled`；`GET /boxes/{id}/events` 返回最近事件。
- 所有端点要求 `LOGISTICS_OPERATOR`＋企业范围；跨企业 404。
- 错误码：`VALIDATION_ERROR`、`NOT_FOUND`、`ASSET_NOT_SIMULATED`、`INVALID_SCENARIO`、`BOX_IN_USE`。
- 不修改旧 `/enterprise/boxes`、`/rider/**`、`/boxes/**`；新字段为增量。
- 新契约在实施时追加到现有 [console 契约](../../../contracts/http/enterprise-console/README.md)；本设计批准前不改契约文件。

## 9. 后端实现边界

| 文件/动作 | 内容 |
|---|---|
| `V17__demo_asset_scenarios.sql` | 上述字段/回填/约束 |
| `EnterpriseConsoleController` | 新增 create/edit/scenario/enable/disable 端点 |
| 新 `EnterpriseAssetService` | 企业范围、事务、ID/节点生成、启停、审计 |
| `EnterpriseReadService` / `EnterpriseViews.Box` | 返回 purpose/notes/demo_scenario/demo_enabled |
| `DemoFleetService` | 从 DB 读取 SIMULATED/启用/场景，不再只读种子 |
| 新 `DemoScenarioService` | 场景切换后的即时快照/ENV_HIGH/ENV_RECOVERED/IMPACT 事实；复用 `IngestionService` |
| `AuditService` | `ASSET_CREATE/EDIT/SCENARIO/ENABLE/DISABLE` |
| `deploy/demo` 示例种子 | 去掉/说明静态 IMPACT 告警；保留初始 5 箱 |

- 继续用 `JdbcTemplate`/现有事务模式；不引入新框架。
- 旧 `TransportService` 不改；同箱 ACTIVE 守卫由现有 `transport_tasks` 唯一约束/查询提供。
- 场景事实只在 `smartbox.demo.fleet=true` 或显式演示 profile 下运行；默认关闭。

## 10. 前端实现边界

| 文件/动作 | 内容 |
|---|---|
| `AssetsPage.vue` | 新建/编辑/场景/停用/启用入口、场景列、筛选 |
| 新 `AssetDrawer.vue` | 创建/编辑共用抽屉；REAL 只读身份信息 |
| 新 `ScenarioDrawer.vue` | 5 个场景选择、说明、确认 |
| `enterprise/model.ts` | Box 新字段、场景标签、错误码 |
| `styles/enterprise.css` | 场景徽标、行操作、抽屉样式；沿用 `.sb-enterprise` |
| `router` | 无需新增路由；仍在 `/enterprise/assets` |

- 所有按钮调用真实 POST；停用/场景需确认；失败显示服务端错误。
- 刷新沿用 12 秒轮询；操作成功后立即刷新。
- 不在前端改 `box.asset_source`、在线、告警或位置；只展示服务端结果。
- 不放置八分区/告警处置假按钮。

## 11. 演示数据、迁移与兼容

- 现有 5 个模拟箱（BOX-SIM-01～05）回填 `demo_scenario='normal'`；原有任务/位置/告警保留。
- 现有静态种子告警：`env_high` 仍可由动态场景产生；`IMPACT:pose` 作为历史演示数据不再作为动态承诺，实施时更新示例种子。
- 新建的模拟箱体由 DB 驱动，不要求改种子；初始资产仍由受控种子创建。
- REAL 箱体 `demo_scenario=NULL`、`demo_enabled=1`；只能编辑名称/用途/备注。
- 旧接口/旧页面继续可用；V17 只加列，不删除字段。
- 真实 MQTT/板端数据不经过本任务的新建/场景逻辑。

## 12. 实施步骤（后续实施时）

| 阶段 | 交付 | 检查点 |
|---|---|---|
| S0 设计评审 | 本文定稿 | 范围/场景/API/V17 决策确认 |
| S1 V17＋DTO | 迁移、读模型字段、编译 | 隔离 `smartbox_test` 迁移；REAL/SIMULATED 回填正确 |
| S2 资产写入 | create/edit/disable/enable＋审计 | 企业范围、ID/节点生成、ACTIVE 守卫 |
| S3 场景事实 | 场景切换服务、DemoFleet DB 驱动 | env_high 告警、normal 恢复、offline/stale、impact 事件 |
| S4 前端抽屉 | 创建/编辑 | 真实 API，REAL 来源不可改 |
| S5 前端场景/启停 | 场景/停用/启用 | 无死按钮，错误可见 |
| S6 联调/收尾 | 种子、浏览器、文档结果 | 最小验证通过；分次提交 |

## 13. 最小验证

- 前端：`npm --prefix frontend run typecheck`、`npm --prefix frontend run build`。
- 后端：编译；新增 `EnterpriseAssetIT` 覆盖：
  - 创建模拟箱生成独立节点/来源；
  - 编辑 REAL 只改元信息；
  - 场景 env_high 生成告警、normal 恢复；
  - offline/stale 的快照/位置行为；
  - ACTIVE 运输停用 409；
  - 跨企业/REAL 场景/非法场景拒绝。
- 数据库：隔离 `smartbox_test` 上 V17；不清理 `smartbox_dev`，不停止 8080/8081/5173/5174。
- 浏览器：资产页新建 → 场景切换 → 地图/告警/箱体摘要变化 → 停用被 ACTIVE 拒绝 → REAL 编辑；
- 文档：`python tools/check_docs.py`。
- 不做大量单元/异常矩阵/并发/性能/长稳测试；未测项明确记录。

## 14. 风险、兼容与回滚

- **V17 回填**：SIMULATED 补 normal；REAL 保持 NULL；迁移失败不自动 clean。
- **场景竞态**：DemoFleet 每轮读 DB，旧 tick 不覆盖新场景。
- **误改真实箱体**：服务层拒绝 REAL 场景/停用/身份修改；V17 不写 REAL 的 demo_scenario。
- **告警真实性**：env_high 走现有事件/规则；impact 只事件；禁止前端造告警；现有静态 impact 告警标为历史。
- **演示服务关闭**：场景切换仍保存 DB，但无新快照；UI 显示离线/过期，不伪在线。
- **兼容**：旧接口/旧页面/任务/位置/告警保留；新字段增量；不新增 MQTT 身份。
- **回滚**：V17 只加列/约束，代码回退后旧服务可忽略新列；已写入的场景值保留，不自动删除。

## 15. 完成定义与待确认假设

设计/实施检查（实际结果见第 16 节）：

- [x] V17、API、场景矩阵、前端交互、错误/失败模式、最小验证均可直接实施。
- [x] 明确区分 REAL/SIMULATED、MOCK 位置、DEMO 快照、真实 MQTT，不混用。
- [x] 不把 IMPACT 说成现有规则告警；不把模拟箱体说成实体设备。
- [x] 旧接口/旧页面/真实板/MQTT 不变。
- [x] 更新任务索引、当前进度、文档入口、追踪说明；`check_docs.py` 通过。

**待确认假设：**

1. TASK-016 就是“企业模拟资产与场景管理”，不是八分区详情。
2. 新建模拟箱体使用演示快照服务，不申请真实 MQTT 账号/ACL；节点身份独立。
3. `impact` 场景只产生姿态设备事件，不伪造 IMPACT 告警；环境告警用 `env_high` 演示。
4. 本任务只在 `smartbox_test` 验证，不清理 `smartbox_dev`、不停止既有服务、不改真实板/MQTT。

如果以上任一假设需要改变，应另开设计/CR，不把扩展内容塞进本任务。

## 16. 实施结果（2026-11-24）

### 16.1 已实现

- V17 `purpose/notes/demo_scenario/demo_enabled`、SIMULATED 回填 normal、CHECK 约束。
- `EnterpriseAssetService` 创建/编辑/场景/停用/启用；`DemoScenarioService` 通过现有 `IngestionService`/`DomainEventWorker` 产生 `env_high` 告警和 `normal` 恢复；`impact` 只产生 IMPACT 事件。
- `DemoFleetService` 改为数据库驱动，支持 normal/env_high/offline/stale/impact、启停和 `smartbox.demo.fleet.tick` 测试开关。
- `GET /enterprise/console/boxes/{id}/events` 最近设备事件；Box DTO 返回 purpose/notes/scenario/enabled。
- 资产页新建/编辑/场景/启停抽屉、场景列、操作列；箱体总览显示元信息/场景/最近设备事件。
- console 契约与示例种子更新；示例静态 IMPACT 告警移除。

### 16.2 实际检查

- V17 在 `smartbox_test` 由 Flyway 从 V16 升到 V17。
- 后端：`MockPositionProjectorTest` 5 项、`EnterpriseConsoleIT` 10 项通过（含创建/编辑/场景/停用/impact 事件/离线/过期）。
- 前端：`npm --prefix frontend run typecheck`、`build` 通过。
- 浏览器：TASK-016 主路径创建→编辑→env_high→告警中心→normal/停用；1440×900 和 768×1024 截图保存在本机忽略目录 `artifacts/task016-ui/`。
- `python tools/check_docs.py`：通过，`links=3498, markdown=153`（只覆盖文档/迁移/样例，不代表应用或硬件验收）。

### 16.3 未验证/边界

- 真实板/MQTT、开发库 `smartbox_dev` 的 V17、八分区详情、告警人工处置、CSV、完整 `mvn verify`（本机既有 Mockito/JDK 自附加问题）未验证或未执行。
- 场景 `impact` 不产生告警，这是当前规则边界；如需冲击告警需另评 DEMO 规则。
- 新建模拟箱体不申请真实 MQTT 账号/ACL，仅由演示快照服务产生 SIMULATED/DEMO 事实。
