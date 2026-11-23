# TASK-014 三端 UI 原型还原与工程接入

| 属性 | 内容 |
|---|---|
| 阶段 | CR-006 / 候选 BL-003，TASK-013 完成后的 UI 还原专项 |
| 负责人 | 用户 / 前端开发 Agent |
| 状态 | **已完成（DEMO 三端 UI 还原），2026-11-24；实板/生产安全未验收** |
| 依赖 | TASK-011、TASK-012、TASK-013（已具备 DEMO 业务后端与三端真实 API） |
| MOD | [MOD-05](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md)、[MOD-06](../../requirements/modules/MOD-06-用户与权限管理.md)、[MOD-07](../../requirements/modules/MOD-07-运输任务与过程管理.md) |
| 需求 | [REQ-M05-007](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-007)、[REQ-M06-008](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-008)、[REQ-M07-008](../../requirements/modules/MOD-07-运输任务与过程管理.md#req-m07-008) |
| TC | [TC-015](../../testing/cases/TC-015-任务历史访客安全与页面.md)（功能回归与已执行的三尺寸视觉对照） |
| 基准日期 | 2026-11-24，基于本次复查的 GitHub main 源码 |
| 优先级 | P0：忠实还原三个角色入口的布局/信息层级；P1：次级视觉细节 |

> **核心指令：现有 Vue 原型是已选定的视觉/交互目标，不是仅供自由发挥的“设计参考”。** 本任务不是重新设计产品，而是将原型既定的布局、导航、组件组织和交互迁移到已有正式前端，替换 Mock 状态为 TASK-011～013 的真实 API。未经说明不得重画为通用后台表单/卡片页。任何与真实数据、权限或产品范围发生冲突的原型内容必须按本文件记录“保留布局、调整内容或明确 Mock”，不能无依据造数据。

## 1. 目标、非目标与最终完成口径

### 1.1 目标

1. 企业端正式 `/enterprise` **以路线地图控制台（direction=route）为基准**：深蓝侧栏、顶部工具栏、地图主区域与运输任务列表/资产联动；仍能分配/收回箱体、查看任务详情。
2. 骑手端正式 `/rider` 以优化版骑手原型为基准：任务优先工作台、移动端四导航、无任务与进行中两种首屏、选箱→绑定物品→核对开始三步、任务/环境/异常/历史分层。
3. 普通用户正式 `/track` 以优化版访客原型为基准：独立验证入口，验证后先展示运输身份/状态与环境结论，再展示环境记录/异常入口及运输阶段；无骑手导航和任何设备控制。
4. 三端共享原 TASK-013 服务端任务与监测投影；保存原 JWT、访客短期 token、10 秒可控刷新、状态转换、错误码、数据质量和任务范围权限。
5. 浏览器提供**原型与正式页面相同视口/相同状态的实际截图对比**；关键布局与信息顺序由人工复核，不能只用“编译通过”“无横向溢出”代替设计验收。

### 1.2 不做

- 不修改 Spring Boot、MySQL V1～V15、MQTT、固件、TASK-009、访客验证码算法或后端业务规则。
- 不引入真实 GPS、路由规划、配送 ETA、签收、企业成员管理、人工告警确认、复杂通知或大型新组件库。
- 不“像素级”照抄独立原型的评审工具、Mock 账号、假任务、固定地图城市与 9–10px 小字；正式字体可为可读性作合理放大。
- 不重构原 `/boxes`、`/boxes/:boxId` 的设备控制页面；不改变旧设备权限以配合新视觉。
- 不因演示目标添加大量单元测试；只保证视觉基准、主要交互与关键安全路径可复验。

**完成不是**“三个 URL 能打开”。必须具备相同信息架构、明显接近原型的页面构图、真实业务行为、明确的数据来源与有证据的截图对照。

## 2. 已复查的真实源码：谁是视觉权威，谁是业务权威

| 分类 | 现有文件 | 可复用/必须纠正 |
|---|---|---|
| 企业原型 | [design-exploration/src/App.vue](../../../frontend/design-exploration/src/App.vue)、[styles.css](../../../frontend/design-exploration/src/styles.css)、[responsive-fixes.css](../../../frontend/design-exploration/src/responsive-fixes.css) | `direction='route'` 的侧栏、`route-dashboard-layout`、`route-map-panel`、`route-task-panel`、`MapScene` 视觉结构；**不要**使用 `command/industrial` 作为替代方向 |
| 企业原型数据 | [design-exploration/src/data.ts](../../../frontend/design-exploration/src/data.ts) | `SBX-24018` 等、城市、KPI、路线、时序均为 Mock，不能作为真实运输数据 |
| 骑手原型 | [role-design-exploration/src/RiderHome.vue](../../../frontend/role-design-exploration/src/RiderHome.vue)、[PrepareFlow.vue](../../../frontend/role-design-exploration/src/PrepareFlow.vue) | 当前运输环境嵌入主区、我的箱体/辅助入口、三步进度条，需还原 |
| 运输/访客原型 | [TransportOverview.vue](../../../frontend/role-design-exploration/src/TransportOverview.vue)、[Environment.vue](../../../frontend/role-design-exploration/src/Environment.vue)、[Journey.vue](../../../frontend/role-design-exploration/src/Journey.vue)、[Trend.vue](../../../frontend/role-design-exploration/src/Trend.vue) | 身份→环境→详情→进度的结构、历史展示与缺口；`Trend.vue` 生成的六点模拟曲线**不能进入生产数据层** |
| 原型外框和视觉规则 | [role-design-exploration/src/App.vue](../../../frontend/role-design-exploration/src/App.vue)、[style.css](../../../frontend/role-design-exploration/src/style.css)、[README](../../../frontend/role-design-exploration/README.md)、[UX-REVIEW](../../../frontend/role-design-exploration/UX-REVIEW.md) | 骑手四导航、访客无底栏、响应式布局及优化依据；`usePreview.ts` 仅模拟状态/动作，不可移植为生产 Store |
| 正式企业页面 | [frontend/src/business/Enterprise.vue](../../../frontend/src/business/Enterprise.vue) | 已请求企业箱体、任务、演示骑手并可分配/收回，但只是通用卡片列表，缺原型侧栏和地图/任务联动 |
| 正式骑手/详情 | [Rider.vue](../../../frontend/src/business/Rider.vue)、[TransportDetail.vue](../../../frontend/src/business/TransportDetail.vue) | 已完成创建/绑定/开始/结束/分享验证码请求，缺导航与三步结构；现有详情把物品编辑和开始混在一块 |
| 正式访客/监测 | [Track.vue](../../../frontend/src/business/Track.vue)、[MonitoringPanel.vue](../../../frontend/src/business/MonitoringPanel.vue) | 访客短期 token、失效清理、轮询和任务字段是对的；需改变页面顺序、样式与二级查看体验 |
| 正式外框/样式 | [BusinessShell.vue](../../../frontend/src/business/BusinessShell.vue)、[business.css](../../../frontend/src/business/business.css) | 一套横向顶栏同时用于企业与骑手是设计偏离的根源；应拆成 **EnterpriseLayout / RiderLayout / GuestLayout** 或等效分支 |
| 正式路由/会话 | [router/index.ts](../../../frontend/src/router/index.ts)、[app/App.vue](../../../frontend/src/app/App.vue)、[stores/session.ts](../../../frontend/src/stores/session.ts) | `/track` 是 `meta.public`，企业/骑手是受控 JWT 路由；`businessHome` 与旧 `box_ids` 不是新企业/骑手资产业务授权 |
| 真实类型/刷新 | [business/model.ts](../../../frontend/src/business/model.ts)、[api/http.ts](../../../frontend/src/api/http.ts) | `Task/Asset/Monitoring`、`usePolling`、`session.request`、`SerialRead` 是应保留的业务接入层 |

[现有 TASK-013 验证报告](../../testing/reports/TASK-013-三端运输业务联调报告.md)证实三端 DEMO 主流程通过，包括真实 MySQL、本地 MQTT 模拟节点与浏览器；**没有**以原型视觉一致性作为验收条件，因此“业务完成”与“UI 还原完成”必须分开记录。原型截图在本地 Git 忽略的 `artifacts/` 目录，不能把源码路径当成可在线查看的 PNG。

## 3. 视觉还原规则与优先级

| 级别 | 规则 | 允许的改动 |
|---|---|---|
| **不可替换（P0）** | 三端总体布局、导航方向、主要区块顺序、主操作位置、桌面/手机结构 | 仅为真实授权/空态/布局可读性进行最小调整 |
| **优先还原（P0）** | 颜色基调、主视觉权重、状态组件、卡片/分隔方式、按钮等级、详情层级与动线 | 保留设计语言，允许 CSS 细节的响应式重排 |
| **可轻调（P1）** | 图标形状、少量尺寸、阴影、过渡动画、可读字号 | 须由页面前后对比说明，不得破坏 P0 |
| **必须剔除/说明** | Mock 统计、演示身份、硬编码验证码、固定城市路线、原型模拟温湿度、无真实语义的“处理告警”按钮 | 删除假事实、显示缺数据，或保留**明确 Mock 的纯视觉示意区** |

**真实数据优先于原型装饰**：没有 GPS 时可以还原“地图面积及任务侧栏”构图，但不能让真实箱体落在上海/杭州等伪造坐标上；不能把原型中的“在途4箱/完成126单/待处理8条”映射成真实企业数据。仅能从真实列表计算“本企业资产数”“ACTIVE 任务数”等有定义的指标；无来源的数值使用“—/暂无数据/示意”。

原型中企业端 CSS 将不少辅助文字压到 9～12px，正式页面至少满足常规可读性，正文建议 13～16px；骑手/访客沿用原型 `README` 的 14～16px 正文、12～13px 辅助信息和 40～52px 关键读数。**还原结构，不继承原型不利于阅读的缩放**。

## 4. 技术架构：保留业务请求、替换视图壳

### 4.1 目标目录（推荐，不强制为目录拆分而拆分）

```text
frontend/src/business/
  model.ts                          # 原有 Task / Asset / Monitoring、状态、轮询，可增字段类型
  Enterprise.vue                    # 保留路由入口，替换为 route 方向的真实布局
  Rider.vue                         # 保留 /rider 入口，替换为任务优先首页
  TransportDetail.vue               # 保留任务详情路由，内部拆准备/概览
  Track.vue                         # 保留 /track 公共入口、访客 token 保护
  layouts/
    EnterpriseLayout.vue            # 左侧深蓝导航 + 顶栏 + 内容区
    RiderLayout.vue                 # 桌面导航 + 手机四项底部导航
    GuestLayout.vue                 # 无账号侧栏/底栏的独立查看布局
  components/
    TransportIdentity.vue           # 任务编号、物品、状态、开始/结束时间
    EnvironmentSummary.vue          # 数据质量、温湿度、观测时间与缺口提示
    JourneyTimeline.vue             # 绑定/开始/进行中/结束的真实阶段
    TransportOverview.vue           # 两端可复用：环境主区 + 进度侧区
    SampleHistory.vue               # 仅真实 samples；不足时空态
    EventList.vue                   # 白名单事件、只读说明
  rider/
    PrepareFlow.vue                 # 三步任务准备，直接复用真实 REST
    RiderBoxes.vue                  # 我的箱体（可复用旧数组查询）
    RiderHistory.vue                # 我的运输记录
    RiderAccount.vue                # 本人使用关系说明/退出
  enterprise/
    MapIllustration.vue             # route 风格纯地图示意，明确 MOCK
    EnterpriseTaskList.vue
    EnterpriseAssetList.vue
  styles/
    enterprise.css
    transport.css
    tokens.css
```

允许按现有组件复杂度适当合并，但三个布局壳和原型关键业务组件必须有清晰对应。**禁止为了改 UI 再造一套后端请求客户端或 Store**。保留 `session.request`、`usePolling`、`SerialRead`、`Track.vue` 的 token 生命周期；页面模板可以重构，数据/业务函数尽量迁移而不是删除重写。

### 4.2 统一的真实数据适配层

现有正式数据类型在 `business/model.ts`：

- `Task`：`task_id/transport_no/box_id/status/item_name/item_description/started_at/ended_at`。
- `Asset`：`box_id/display_name/source/rider_user_id/rider_username`。
- `Monitoring`：`task`、`environment.assessment/time_quality/data_state/latest_temperature/latest_humidity/observed_at`，以及 `samples[]/events[]`。

原型 `usePreview` 的 `ready/active/completed`、`item`、`start/end`、`scenario`、`selectedBoxId` 是**演示内存模型**，正式后端使用 `READY/ACTIVE/COMPLETED`、`item_name`、毫秒时间戳、`box_id` 和真实监测数据。实现小型纯函数/`computed` 适配视觉 ViewModel：

```ts
type UiTransportState = 'ready' | 'active' | 'completed';
type UiDataState = 'ready' | 'trusted' | 'insufficient' | 'invalid' | 'unknown';

function toUiState(status: 'READY' | 'ACTIVE' | 'COMPLETED'): UiTransportState {
  return status.toLowerCase() as UiTransportState;
}

// 环境 UI 只能呈现后端已给出的事实；不调用模型数组推导固定 4.8°C。
function safeReading(value: number | null): string {
  return value === null ? '—' : value.toFixed(1);
}
```

不要把 `assessment=UNKNOWN` 翻译为“当前正常”。`data_state=INSUFFICIENT/INVALID`、`time_quality` 不足时不能伪造正常、在线或完整历史；`samples` 无点则不画曲线。环境“异常”判断优先使用后端明确给出的可解释语义，**不能**仅因样本温度大于原型中固定的 5°C 就前端自判告警。当前服务端任务级 `assessment` 有不足时，展示“当前结论尚不可确认”和真实原始允许字段，避免作未提供的业务断言。

### 4.3 CSS 隔离

- 企业端从 `design-exploration/styles.css` 仅迁移 `direction-route` 和与其依赖的 `.sidebar/.topbar/.route-dashboard-layout/.route-map-panel/.route-task-panel` 等视觉样式，建议重命名为 `.sb-enterprise-*`；不要整份复制 60KB 三方向样式。
- 骑手/访客从 `role-design-exploration/style.css` 迁移主外框、`workspace-grid`、`transport-header`、`overview-grid`、`environment`、`stepper`、`mobile-nav`、`entry-layout` 等规则，建议统一挂在 `.sb-rider`、`.sb-guest` 根类下。
- `business.css` 当前有全局 `.page-heading/.section-head/.business-shell h2` 等规则；改造时逐步收敛为组件命名空间，避免污染旧设备 `/boxes` 和企业/骑手彼此样式。
- 不引入远程字体/大型地图 SDK；复用现有 SVG 图标或原型 `Icon.vue` 的本地图形，但不得把原型业务状态管理引进生产。

## 5. 企业端：direction=route 的具体还原设计

**视觉权威**：[企业原型 App.vue](../../../frontend/design-exploration/src/App.vue) 查询参数 `direction=route&page=dashboard`（同一原型还有 `boxes/detail/alarms/operations`）；正式权威：[Enterprise.vue](../../../frontend/src/business/Enterprise.vue) 与现有企业 API。

### 5.1 布局层次与交互

```text
┌ 深蓝 SideNav ┬ 顶部 Topbar：面包屑 / 刷新 / 用户 ───────────────────┐
│ SMARTBOX    │ 企业名、真实资产/运输摘要                              │
│ 运营总览    │ ┌ 地图示意主区（Mock） ────────┐ ┌ 运输任务列表 ────┐  │
│ 物流箱资产  │ │ 示意路网/无实际坐标提示      │ │ ACTIVE/COMPLETED │  │
│ 运输任务    │ │ 不按真箱体假造地图位置      │ │ 点击选中任务     │  │
│ 告警/事件   │ └─────────────────────────────┘ └───────────────────┘  │
│ 设备监测    │ 企业箱体列表：分配骑手 / 收回 / 查看对应任务              │
│ 工作空间    │ 选中任务详情或受限环境事件摘要                           │
└─────────────┴────────────────────────────────────────────────────────┘
```

P0 必须恢复原型的**侧边栏＋顶部工作区＋地图/运输双栏主构图**。侧栏原型中的“运输批次”小卡，只有真实后端对应的运输任务时才显示相应 `transport_no/item_name/status`；没有“批次”模型，不能展示硬编码的“华东冷链/0821”。未支持的“全屏地图/新建箱体/确认告警/通知”不得留下看似可执行的假按钮，可标示“演示示意”或隐藏其动作。

### 5.2 原型与正式页面映射

| 原型区域 | 正式改造对象 | 数据或可执行动作 |
|---|---|---|
| `.sidebar/.side-nav` | `EnterpriseLayout.vue` | 当前用户、企业名、真实 `/enterprise` 与 `/boxes` 导航 |
| `.topbar/.breadcrumbs` | 企业工作区头部 | 当前页面、刷新；不写固定 14:32 |
| `.kpi-row` | 企业资产/ACTIVE/COMPLETED 摘要 | 只从 `GET /enterprise/boxes`、`GET /enterprise/transports` 计算 |
| `.route-dashboard-layout` | 主页地图与任务主分栏 | 主图示意＋真实任务右栏 |
| `MapScene` | `MapIllustration.vue` | 纯示意底图，不使用原型固定城市坐标绑定真实 `box_id` |
| `.route-task-panel` | `EnterpriseTaskList.vue` | 真实任务 ID/状态/物品，点击选中或跳 `/enterprise/transports/:id` |
| `boxes` 表与详情抽屉 | `EnterpriseAssetList.vue` | 保留分配/收回现有 `Enterprise.vue` 请求、受控骑手列表 |
| `alarms/operations` 原型 | 可复用任务事件面板/Mock 地图区域 | 只读后端任务监测，不实现不存在的人工处置 |

现有 `Enterprise.vue` 已经用 `Promise.all` 同时读取 `/enterprise/boxes`、`/enterprise/transports`、`/enterprise/riders`，`change()` 分配/收回已写好。**保留这些数据函数和身份过滤**，不要为了外观改造把原业务变成原型数组。

### 5.3 地图与无位置数据处理

原型 `MapScene` 是在 `App.vue` 第二个 `<script lang="ts">` 中通过 `defineComponent` 内联创建，使用固定 `SBX-24018` 等点、城市、道路装饰。可以提取**底图 SVG/CSS 视觉形态**，但不能直接带走固定 points 并将它们视为实际位置。

首轮规定：显示“示意地图 · 无真实 GPS 数据”，地图只承担视觉布局；真实企业任务在右侧列表联动详情，**不在地图上用伪经纬度定位实际箱体**。当真实地点来源为空时，按列表选中任务而不是点击假位置标记。若后续有经过授权的可信位置数据，再制定独立 GPS 需求变更。

### 5.4 企业次级界面

以 `/enterprise` 为现行页面，不为原型五页一次性创建五条大而全的后台路由。可在页内使用“概览 / 资产 / 运输”局部 Tab 并保持企业侧栏不变；企业任务详情继续复用 `/enterprise/transports/:id`。告警页只展示任务事件，不擅自增加“确认/关闭”接口。**页面层次优先，功能不扩域**。

## 6. 骑手端：任务优先工作台、四导航和三步准备

**视觉权威**：[RiderHome.vue](../../../frontend/role-design-exploration/src/RiderHome.vue)、[PrepareFlow.vue](../../../frontend/role-design-exploration/src/PrepareFlow.vue)、[原型 App.vue](../../../frontend/role-design-exploration/src/App.vue)。正式现状：[Rider.vue](../../../frontend/src/business/Rider.vue)、[TransportDetail.vue](../../../frontend/src/business/TransportDetail.vue)。

### 6.1 首页必须恢复的构图

- 有 ACTIVE：主列“当前运输”中直接显示**物品、箱体、开始时间、可确认的环境结论/读数、更新时间、查看当前运输按钮**；第二列“我的箱体 / 运输记录 / 异常入口”。多个 ACTIVE 不合并数据，每个任务有自己的卡。
- 无 ACTIVE：主列显示清晰空态和**开始新运输**主操作；不要空放历史卡；箱体列表显示 `OWNED/ASSIGNED`，无箱体则解释需要企业分配。
- 手机（≤700px）：单列、主体任务优先，底部固定/粘性 **工作台 / 我的箱体 / 运输记录 / 我的** 四入口；为底栏增加安全区和内容底部留白。桌面与平板使用原型非对称双栏，不把手机放大成四个等宽卡。
- 当前 `Rider.vue` 把“准备新运输”的下拉表单直接插在首页中间，是结构性偏离。要把主首页变成任务视图，把创建交互放进独立三步 `PrepareFlow`。

### 6.2 三步准备与现有 API 的状态同步

| UI 步骤 | 要显示 | 真实 REST 动作与保存点 |
|---|---|---|
| Step 1：选择箱体 | 从 `GET /rider/boxes` 出来的真实 `OWNED/ASSIGNED` 行；选中和不可用说明 | 进入准备页时可仅保留选中 box；点击“下一步”时调用 `POST /rider/transports` 建立 READY，并持久持有返回的 `task_id`；避免重复点击建立多个草稿 |
| Step 2：绑定物品 | 对应 TASK 的编号、物品名称和简述，可返回上一步 | `POST /rider/transports/{id}/item` 保存；成功后重新读取任务作为权威状态。后端只允许 READY 编辑 |
| Step 3：核对并开始 | 箱体、物品、运输编号、使用资格、设备风险说明、确认按钮 | `POST /rider/transports/{id}/start`。只有后端 `ACTIVE` 成功返回后才进入运输概览；出现 `BOX_OFFLINE` 留在当前步骤，`RISK_CONFIRMATION_REQUIRED` 打开显式确认对话框后才允许传 `confirm_risk=true` |

**不可复用** `usePreview.chooseBox/bind/start` 的内存逻辑；原型本地“可开始”判断只是辅助展示，实际能否开始仍由后端决策。若用户重新打开已存在的 READY 任务，应从 `GET /rider/transports/{id}` 还原到相应步骤，而不是不加判断又新建一条任务。用户点击返回、刷新或多次提交时，不得丢失当前 `task_id` 并默默重复创建。

当前 `TransportDetail.vue` 的 `name/description` 初始为独立空 `ref`，异步加载任务后没有显式用服务端物品回填表单。拆出 `PrepareFlow` 时必须补上“数据加载时初始化、用户编辑时不被轮询覆盖、保存后以服务端响应确认”的规则，避免返回编辑误把已有内容清空。

### 6.3 运输概览与确认动作

从原型 `TransportOverview` 还原：

1. 顶部运输身份：编号、物品名称、状态、起止时间。
2. 主要区域：异常摘要（有真实可展示事件时）→ 环境结论和温湿度 → 查看环境记录/异常记录两个清晰入口。
3. 侧栏/下方：真实状态形成的 `JourneyTimeline`。只有“物品已绑定、已开始、正在进行、已结束”，**不生成签收/定位/预计时间**。
4. 骑手 ACTIVE 时展示**结束运输危险色主操作＋二次确认**；保留原 `TransportDetail.action('finish')` 的后端调用，成功后查询更新；重复点击不重写结束事实。
5. 分享验证码独立为可见但次级的“分享给查看者”区。继续使用现有 `access-code` 接口，原文只展示一次，复制后提示安全分享；绝不把验证码当路由参数/日志数据。

`/rider` 和 `/rider/transports/:id` **保持旧路径有效**。可以在 Router 中新增 `/rider/boxes`、`/rider/history`、`/rider/account`、`/rider/prepare` 作为界面局部路由，前提是所有页保留 `meta.business` 和 `role='COURIER'`，不错误配置为公开。也可以用明确的子页面状态实现，但要保持浏览器返回和移动底栏选中态可预期。不要把原型的 `page=...` query 状态评审切换器复制到正式产品。

## 7. 普通用户端：访客原型的运输身份＋环境主次顺序

**视觉权威**：[原型 TransportOverview](../../../frontend/role-design-exploration/src/TransportOverview.vue) 与 [原型 App](../../../frontend/role-design-exploration/src/App.vue)。正式现状：[Track.vue](../../../frontend/src/business/Track.vue)。

### 7.1 未验证时：独立验证布局

- 独立浅色品牌页，不使用企业侧边栏、骑手底栏或旧登录页。
- 原型的 `entry-layout` 左侧简洁介绍、右侧表单（手机单列），包含运输编号、验证码、主按钮与“仅可查看本任务”说明。
- 删除原型的“公开演示编号/DEMO-4821”等评审信息，不在生产页面展示有效凭据。
- 保持 `/track` 为 `meta.public`，**但“公开路由”只表示允许打开验证页面，不代表无需服务端验证便可读取运输内容**。

### 7.2 验证后：首页内容顺序冻结

```text
任务身份：物品名称 / 运输编号 / 运输中或已结束
       ↓
环境结论与最新可信温湿度（未知/不足时明确缺口）
       ↓
查看环境记录  /  异常记录（只读，按当前获准 task）
       ↓
运输进度：绑定、开始、运输中、结束
       ↓
退出查看 / 重新验证
```

手机端环境放在进度前；桌面端环境主区＋进度侧栏。首页**不要直接塞满一条完整曲线**，真实采样曲线/列表放在二级“环境记录”里。访客无管理、分配、结束运输、分享验证码、设备控制或任意箱体导航。

### 7.3 保留现有权限与轮询实现

`Track.vue` 现在使用 `Http.request('/public/transport-access/verify', {login:true})`，拿到专用 token 后将其只保留在内存；`fetch('/api/v1/public/transport-view')` 带 `X-Transport-View-Token`，通过 `SerialRead` 串行刷新，并有 `generation` 防止旧响应覆盖新会话、`clear()` 停止轮询和清除状态。**以上行为全部保留**，只改变 JSX/Vue 模板组织与样式。不要将 token 放进 URL、`localStorage`、Pinia 登录会话或原 `Authorization`。

访客二级“环境记录 / 异常记录”可以在同一个 `/track` 已验证会话里通过局部 `view='overview'|'environment'|'alerts'` 切换；只使用这次授权查询拿到的 `Monitoring.samples/events`，**不要**新开放 `/boxes/:id` 或通过猜测 `task_id` 获得更多数据。刷新或退出时返回验证表单并清空数据。错误验证码/失效也不可留显示旧任务卡片。

## 8. 三端共用组件与真实数据质量映射

### 8.1 UI 组件接口候选

| 组件 | 推荐输入 | 不应该承担 |
|---|---|---|
| `TransportIdentity` | `Task`、必要的真实展示用箱体名、只读/可操作模式 | 调用开始/结束或自行更改状态 |
| `EnvironmentSummary` | `Monitoring.environment`、`task.status`、`isHistorical` | 用固定阈值推断正常、伪造时间和采样 |
| `JourneyTimeline` | `Task` | 编造签收、ETA、位置和不存在的完成节点 |
| `SampleHistory` | `Monitoring.samples`，从后端确认的时间范围 | 产生模拟六点样本或把缺失点插值成正常 |
| `EventList` | `Monitoring.events` | 将原始事件误称为已确认告警，新增未授权处置 |
| `TransportOverview` | `Monitoring`、可见动作配置，业务事件由父级发出 | 持有全局登录 Token 或同一份模拟状态 |
| `EnterpriseTaskList` | 企业实际任务列表、选中 ID，`emit('select')` | 输出假运输路线、假告警计数 |

建议用 ViewModel 统一衔接 `READY/ACTIVE/COMPLETED`、`assessment` 和 `data_state`，不要让企业、骑手、访客各自实现一套状态文字/判断。首轮组件简洁，不为美观拆成几十个无内容文件。

### 8.2 数据质量与历史一致性不可牺牲

- **READY**：没有运输过程有效样本，显示尚未开始，不能沿用当前箱体的最新值替代。
- **ACTIVE**：展示任务范围内的最近可信样本；缺失、无效或时间质量不确定时用原 API 的状态字段表达，不画“正常”绿色盾牌。
- **COMPLETED**：只展示当前任务末次可信记录；不读取同箱下一运输的 `node_latest`。历史“最后一次读数正常”不等于“全程没有异常”。
- **样本图**：只按 `samples[]` 时间和值作图；无样本/只有一个采样点采用空态/单点，不用原型 `Trend.vue` 固定的 `temperature ±0.2` 插值。
- **事件**：`events[]` 为空时文案“暂无可确认设备事件”，不等于可以断言绝无异常。未支持的人工处理按钮移除。
- **权限失效**：原有 `usePolling` 在 403/404 清数据，访客 `clear()` 在失效时清 token 和详情；UI 组件不得保留可见的无权旧缓存。

### 8.3 保持实际 API 请求不变

| 用户 | 已存在业务请求（`/api/v1` 后缀） | 用于新视觉的区域 |
|---|---|---|
| 企业 | `GET /enterprise/boxes`、`GET /enterprise/riders`、`POST /enterprise/boxes/{boxId}/assign`、`POST .../unassign` | 资产统计、分配与收回 |
| 企业 | `GET /enterprise/transports`、`GET /enterprise/transports/{id}`、`GET .../{id}/monitoring` | 任务列表、选中任务和环境事件 |
| 骑手 | `GET /rider/boxes`、`GET /rider/transports`、`GET /rider/transports/{id}` | 工作台、我的箱体、历史 |
| 骑手 | `POST /rider/transports`、`POST .../{id}/item`、`POST .../{id}/start`、`POST .../{id}/finish` | 真实三步准备和结束 |
| 骑手 | `GET /rider/transports/{id}/monitoring`、`POST .../{id}/access-code` | 当前任务环境、分享 |
| 访客 | `POST /public/transport-access/verify`、`GET /public/transport-view`（专用 header） | 验证和单任务只读页 |

正式接口与数据字段以 [transport-view 增量契约](../../../contracts/http/transport-view/README.md) 及真实源码为准，本文不批准任何新的后端 URL。`session.request` 通过 `lossless-json` 编码大整数；保留 `Enterprise.vue` 当前 `rider_user_id` 的请求处理方式，不在视觉还原时改成不支持 `BigInt` 的裸 `JSON.stringify`。

## 9. 路由、轮询、加载与授权的兼容设计

1. 保持 `/login`、`/boxes`、`/boxes/:boxId`、`/enterprise`、`/enterprise/transports/:id`、`/rider`、`/rider/transports/:id`、`/track` 原路径可用。
2. `router/index.ts` 原有 `to.meta.public` 判断必须只对访客入口生效；`to.meta.role` 继续阻止骑手进入企业页以及反向访问；所有新骑手/企业子页补全对应 meta。
3. `app/App.vue` 当前按 `$route.meta.business` 将业务页与旧设备页布局隔离；重构时保留该分离，不让企业新 Sidebar 双重包裹旧设备的 `app-shell`。
4. `usePolling` 每 10 秒请求且卸载时取消；环境和企业列表依旧使用它或抽离的等效逻辑，页面切换和退出后不保留旧后台轮询。按任务 ID 切换时旧响应不得覆盖新任务。
5. 任务详情异步加载后要将物品初值映射到 UI，但在用户编辑过程中不得被 10 秒轮询覆盖；保存成功再使用服务端结果同步。
6. 表单提交期间防重复，开始/结束以 REST 成功为权威；不可由页面单方面把 READY 改 ACTIVE。
7. 未登录访客不加载 `BusinessShell`，访客过期/错误只可回到验证页；不得自动回旧登录页，亦不得触发设备页面控制请求。
8. 企业地图示意的交互不能引起对无权箱体的查询；选择任务由企业服务端返回的数据驱动。

## 10. 分阶段落地顺序（逐阶段可审核）

| 阶段 | 先改哪些文件 | 最小完成证据 |
|---|---|---|
| **S0 视觉基线** | 启动三个原型与正式前端，不改业务 | `390×844`、`768×1024`、`1440×900` 的基准截图及当前正式对照；记录原型状态、URL、运行数据 |
| **S1 样式/布局基座** | `BusinessShell.vue`、`business.css`、可新建 `layouts/*` | 企业深色 Sidebar、骑手四导航、访客独立 Layout 单独可见；旧 `/boxes` 未变化 |
| **S2 共享概览** | `MonitoringPanel.vue`、`TransportDetail.vue`、新的环境/进度组件 | 与原型 `TransportOverview/Environment/Journey` 布局相似；真实未知/无样本正确 |
| **S3 骑手主页与准备** | `Rider.vue`、`PrepareFlow.vue`、可增骑手子路由 | ACTIVE/空态正确，三步准备与真实 REST 一次跑通，移动四导航可用 |
| **S4 普通用户端** | `Track.vue`、访客样式、二级环境/事件内容 | 同视口还原验证入口/任务概览，错误验证码与退出清屏不退化 |
| **S5 企业路线方向** | `Enterprise.vue`、`EnterpriseLayout/MapIllustration/TaskList` | route 地图与任务双栏构图、真实资产分配仍能用，地图 MOCK 明示 |
| **S6 视觉回归与微调** | 上述所有页面，适量样式微调 | 三端原型/正式并排截图、信息层次逐页核对、无显著结构偏离 |
| **S7 业务主链路回归** | 前端项目测试、真实 API 浏览器交互 | 企业分配→骑手准备/开始/结束→访客验证/查看，旧设备页面仍可用 |
| **S8 文档/交付** | 本 TASK、TC-015 的 TASK-014 补充项、简短实施报告 | 列出已还原/与原型差异/受限 Mock/未执行项，报告有截图证据 |

**工作范围约束**：S0/S1 后先交付骑手/访客的主要页面，再做企业大地图；不应该把 UI 重构拆成大量独立架构工程。每阶段完成后就检查视觉和真实接口，避免直到全部开发完才发现 UI 偏离。

## 11. 可直接交给开发 Agent 的执行规则

- 开发前**实际运行并查看**独立原型（非只看 README）：企业 `http://127.0.0.1:5186/?direction=route&page=dashboard`；骑手 `http://127.0.0.1:5187/?role=rider&presentation=1`；访客 `http://127.0.0.1:5187/?role=customer&presentation=1`。若端口冲突或 Node 依赖缺失，按各自 `vite.config.ts` 和 README 调整，不假装已经截图。
- 首先捕获**原型、TASK-013 当前正式页**的相同视口截图，标注原型 Mock 输入和正式真实数据条件；布局对照基于同类状态，不能拿原型 ACTIVE 与正式 READY 直接比较成“视觉不一致”。
- **优先复制/提取原有组件结构和 CSS 规则，再写 API adapter**，不要照着功能需求重新生成新布局。禁止未经说明替换骑手四导航为统一横向顶栏、替换企业侧栏/地图为两张通用卡片、替换访客环境首页为一页长表单。
- 保持现有 `Http/session.request/Track` 安全及轮询行为；禁止把 `usePreview.ts`、`model.ts` 的 Mock 业务数据导入正式前端，禁止硬编码 `DEMO-4821`、`SBX-24018` 的真实显示内容。
- 原型某项功能后端不支持时，**先保留视觉容器并显示受控空态/示意说明**，不得擅自开发超出本任务的 backend/API，也不得放置可点击但没有业务的危险按钮。
- 前端真实环境/事件/运输信息必须按同一任务 ID 关联，不能把 `node_latest` 当已结束任务的末次状态；不能自行推导未由后端评价的安全结论。
- 开发时只调整与本任务直接有关的 Vue/CSS/前端路由及必要测试。若确实需要后端修改，先列出缺失接口与影响，不要顺手改数据库、协议、认证或板端代码。
- 不必为这次 UI 还原写大量单元测试，但**必须**手工核对原型对比、关键路径和权限失效，截图不能只放在 ignored artifacts 而不附验收结果摘要。

## 12. 页面级视觉验收清单（不能再只测“能点”）

| 页面/视口 | P0 必须与原型一致的部分 | 业务/数据不退化 |
|---|---|---|
| 企业 1440×900 | 左深蓝 Sidebar、顶部 Topbar、地图大主区、右任务栏、资产/任务层次 | 企业箱体分配/收回、任务列表、企业范围正确；地图清楚标 Mock |
| 企业 768×1024 | 收窄导航或自适应，不横向溢出；地图/任务保持可辨主次 | 选择任务、分配仍可用 |
| 骑手 390×844 | 当前运输环境在首屏、四项底栏、无任务时明确 CTA；准备流程有三步指示 | READY→ACTIVE→COMPLETED，后端离线/风险错误可见 |
| 骑手 768×1024/1440×900 | 主运输＋我的箱体非对称分栏，间距/字级沿用原型 | 多个任务不串任务 ID，历史可进入 |
| 普通用户 390×844 | 验证表单原型结构；验证后任务身份→环境→详情→进度；无底部骑手导航 | 错误验证码、超时清除、只读请求范围正确 |
| 普通用户 1440×900 | 验证区两栏；概览环境主区＋进度侧栏，主区不被无意义留白冲淡 | 无可信采样时显示缺口，历史不显示下一任务读数 |
| 三端共同 | 深蓝/青绿主视觉、主要按钮至少约 48px、12px+ 常规辅助字、自然留白 | 真实数据、空态、过期态、加载态、权限态清楚 |

**验收方式：** 用实际 Playwright/Chromium 运行原型与正式页，在 `390×844`、`768×1024`、`1440×900` 同视口采集 PNG；以整体结构、卡片相对顺序、主操作位置、导航模式和字体/间距为优先比较指标。**不设未经验证的“95% 像素相似”或 SSIM 数字目标**。实现者需保存 before/prototype/after 对照，并人工写出差异原因。未截图、未运行浏览器时只能标“待视觉验收”。

P0 不通过的典型情况：企业仍然只有顶部导航＋表格，没有地图/任务主构图；骑手没有底部四入口；三步准备缺失；访客环境在长列表底部；任何 Mock 数值被称作真实；访客可读其他运输/控制设备。P1 可接受：个别图标、字体字重、阴影半径小差异，前提不破坏层次与真实业务。

## 13. 最小功能回归与实施证据

**优先使用当前已有工具，不增加截图/测试新依赖。** 仓库已有 `frontend/package.json` 的 `typecheck/build/test/test:browser/test:live`、骑手原型 `tests/verify.cjs`、以及 [TASK-013 联调报告](../../testing/reports/TASK-013-三端运输业务联调报告.md)的真实软件闭环说明。

```powershell
# 根目录按现有依赖与本机运行条件执行
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run test

# 只在用户自己管理的受控测试环境中运行浏览器测试
npm --prefix frontend run test:browser

# 文档反向追踪/链接静态检查
.venv/Scripts/python tools/check_docs.py
```

需要真实浏览器主路径时使用受控 `smartbox_test` 和独立服务进程，参照现有 `TASK-013` 报告的环境隔离方式，不清空 `smartbox_dev`，不关闭不属于本 TASK 的 `8080/5173` 服务。自动化重点只有：**一次企业分配、一次骑手创建→开始→结束、一次访客验证与查看、一次错误凭据/权限失效、一次旧设备页回归**；截图还原需要人工验收，不能被测试数目掩盖。

建议证据保存在本地 ignored `artifacts/task014-ui-restore/`：

```text
prototype/
  enterprise-route-desktop.png
  rider-home-mobile.png
  rider-prepare-mobile.png
  customer-entry-mobile.png
  customer-overview-mobile.png
before/
  enterprise-desktop.png
  rider-home-mobile.png
  customer-overview-mobile.png
after/
  enterprise-desktop.png
  rider-home-mobile.png
  rider-prepare-mobile.png
  customer-entry-mobile.png
  customer-overview-mobile.png
review.md             # 哪些 P0 已还原、哪些是合理差异/Mock、谁看过哪张截图
checks.json           # 实际运行的浏览器/类型/构建/业务结果，可简化
```

`artifacts` 下的截图默认不入 Git，因此最终 GitHub 提交至少需要一份**文字视觉对照评审记录**（可以留在本 TASK 的实施结果小节或单独测试报告），不应声称 GitHub 有可点击图片；若用户后续希望共享实际截图，需明确选择图片提交/托管方式。

## 14. 完成定义与交接

只有同时满足以下条件，才可以把 TASK-014 标为“已完成（DEMO 三端 UI 还原）”：

- [x] 企业端以 **direction=route** 为视觉结构：侧栏/顶部/地图与任务列表、资产管理保留；地图及无 GPS 明确 Mock。
- [x] 骑手端还原**当前运输优先、四入口导航、三步准备**；运输身份、环境、历史与异常有合理分层。
- [x] 普通用户端还原**验证入口、环境优先的运输概览、只读二级详情与运输进度**；无骑手/企业功能。
- [x] 三端使用真实 TASK-011～013 接口，代码中不出现原型 Demo 任务、验证码、虚构实时读数和假地图位置关联。
- [x] 旧登录、资产分配、任务状态转换、验证码验证、任务范围、访问失效和 `/boxes` 设备页没有回归。
- [x] 同视口实际浏览器截图完成**原型→正式**人工视觉核对；明确记录 P0 还原结论、P1 允许差异和未覆盖项。
- [x] 前端类型检查/构建及必要的主流程回归结果有真实日志；未测试部分不标通过。
- [x] 文档追踪和本 TASK 最终状态已更新，保留 TASK-013 历史测试报告，不以新设计覆盖其证据。

以上章节保留原技术设计。最初设计提交仅登记任务；实际实施结果见下节，不以设计文本代替执行证据。


## 15. 实际实施结果（2026-11-24）

已从 `1b9b0bb` 快进同步 `76a2296`，完成三种角色布局、共享真实监测组件、骑手四导航/三步准备、访客环境优先与企业 route 地图主构图。保持 TASK-011～013 API、内存认证、权限及任务范围；不增加后端、迁移或依赖。地图仅示意，不绘制真实资产假位置。

类型检查、21 项单元/组件、构建、12 项旧设备浏览器检查通过；三尺寸受控浏览器完成 30 个视图、33 张截图，另有 3 张公开验证入口截图。已完成原型/改造前/改造后人工对照。用户明确授权仅清理 smartbox_test 后，真实 MySQL、本地 MQTT、双独立模拟节点及三端浏览器联调通过，自有进程已清理，原有 8080/5173 保留。实体硬件、真实 GPS、生产安全及完整候选需求未随本次验收。

差异、错误路径、证据索引及复验方式见[实施报告](../../testing/reports/TASK-014-三端UI原型还原与接入报告.md)。负责人仍为用户，Codex 执行实现与核验；不代填最终用户签字。
