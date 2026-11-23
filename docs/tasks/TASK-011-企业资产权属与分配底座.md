# TASK-011 企业资产权属与分配底座

| 属性 | 内容 |
|---|---|
| 阶段 | CR-006 / 候选 BL-003 |
| 负责人 | 用户 |
| 状态 | 已完成（DEMO后端资产与分配闭环；页面及运输业务待后续） |
| 依赖 | 现有权限基础，CR-006 |
| MOD | [MOD-05](../requirements/MOD-05-物流箱管理与状态聚合.md)、[MOD-06](../requirements/MOD-06-用户与权限管理.md) |
| 需求 | [REQ-M05-006](../requirements/MOD-05-物流箱管理与状态聚合.md#req-m05-006)、[REQ-M05-007](../requirements/MOD-05-物流箱管理与状态聚合.md#req-m05-007)、[REQ-M06-007](../requirements/MOD-06-用户与权限管理.md#req-m06-007) |

## 1. 任务目标与交付边界

**定位：CR-006 / BL-003 候选需求的 DEMO 级后端业务底座。** 本次只做“企业看到自己的箱体 → 分配给骑手 → 骑手看到自己拥有/获分配的箱体 → 企业收回分配”。后续的物品绑定、运输任务开始/结束是 TASK-012；任务级环境、访客验证码和三个正式完整界面是 TASK-013。

- **需要交付**：受控演示企业/个人权属初始化；3 张新增业务表；4 个最小 REST 接口；基本的角色/资源校验；一条真实 MySQL + HTTP 分配/收回闭环；本轮按用户确认仅开发后端，不开发页面。
- **不在本任务做**：企业入驻、成员邀请/岗位系统、权属转让、配送订单、紧急接管、复杂权限引擎、分配历史中心、高并发压力测试、24 小时稳定性、业务任务表、访客凭据和完整三端 UI。
- **保留底线**：不能因为演示需求而绕过已有 JWT、扩大设备控制权限、泄露其他企业箱体，或将纯 Mock 数据冒充真实设备状态。
- **当前状态**：三表迁移、四个接口和真实 MySQL/HTTP 验证已完成，测试库通过后已应用开发库；本轮无页面。实际记录见第11节及验证报告。

权威范围参考：演示优先实施方案、[CR-006](../changes/CR-006-多角色物流箱与运输任务平台.md)、[候选需求 MOD-05](../requirements/MOD-05-物流箱管理与状态聚合.md) 和 [MOD-06](../requirements/MOD-06-用户与权限管理.md)。完整可扩展模型见[CR-006 数据设计](../design/CR-006-企业箱体与运输任务数据设计.md)，**与完整模型相比，本任务以三表简化方案为准**。

## 2. 基于真实代码的现状

| 当前源代码/表 | 已具备的机制 | 对本任务的含义 |
|---|---|---|
| `backend/src/main/resources/db/migration/V1__identity_and_nodes.sql` | `users(id BIGINT, role_code)`、`boxes(box_id VARCHAR(48))`、`user_box_access(user_id, box_id)`；角色包括 `LOGISTICS_OPERATOR` 与 `COURIER` | **复用账号和箱体主键**；不要把已有访问授权误判为所有权 |
| `backend/src/main/java/cn/smartbox/auth/CurrentUser.java`、`LoginService.java` | 已登录用户含 `id/username/role`，JWT 由服务端认证 | 不从请求体读取 `admin_user_id`、操作者身份或允许客户端伪造权限 |
| `backend/src/main/java/cn/smartbox/authorization/BoxAccessService.java` | 当前设备接口使用 `PermissionPolicy + AuthMapper.hasBox` 校验 | **现有箱体查询/设备命令不会自动识别新分配**；此点要留给 TASK-012/013 接入 |
| `backend/src/main/resources/mapper/AuthMapper.xml` | `boxes` 和 `hasBox` 仅查询 `user_box_access` | TASK-011 新建资产查询，不直接改这两个旧查询的语义 |
| `backend/src/main/java/cn/smartbox/config/BootstrapService.java`、`BootstrapRunner.java` | 启动时按受控文件创建旧用户、箱体和节点，并对重复初始化做冲突核对 | 先正常创建旧基础记录，再用独立演示资产种子初始化归属；不把新字段硬塞进旧 Bootstrap JSON |
| `backend/src/main/java/cn/smartbox/audit/AuditService.java` | 已有 `audit_logs` 与尽力而为的独立事务审计 | 可复用简要操作日志；**不声称**其与资产事务原子提交 |
| `backend/src/main/java/cn/smartbox/live/QueryService.java` | `boxes()` 按旧授权列箱体，`snapshot()` 也会做旧 `BoxAccessService` 校验 | 当前任务接口先展示**资产元数据**，不能先调用 `snapshot()` 再绕过访问异常 |
| `backend/src/main/java/cn/smartbox/config/SecurityConfig.java` | `/api/v1/**` 默认需 JWT，旧少数接口公开 | 新企业与骑手接口继续需要登录，避免为了 DEMO 放到 `permitAll` |

**关键区分：**

- **所有权**：箱体属于某个企业或某个人（本任务新表 `box_ownerships`）。
- **使用分配**：企业把自己拥有的箱体临时交给骑手使用（本任务新表 `box_assignments`）。
- **旧访问权**：旧页面对一个箱体的读取/控制许可（已有 `user_box_access`）。它既不自动意味着所有权，也不随着新分配自动变化。

> **本任务明确不修改旧的设备权限与 `user_box_access`。** 新分配仅在新资产接口中生效。TASK-012 的运输资格应读取新资产关系；TASK-013 接入实时监测时，必须显式设计“从资产分配到业务只读权限”的校验，不得让收回后的旧授权继续冒充有效分配；也不可因骑手被分配而自动获得 `CONFIGURE/EXPORT` 等设备管理权限。原有人工授予的旧权限保留其现行语义，不能在“收回分配成功”时声称旧授权也被撤销。

## 3. DEMO 业务对象与规则

| 对象 | V1 简化实现 | 首轮不做 |
|---|---|---|
| 企业 | 一条企业记录对应一个管理员 `admin_user_id`，用于当前受控 DEMO；管理员必须是启用的 `LOGISTICS_OPERATOR` | 成员表、邀请、多个管理员、复杂岗位 |
| 箱体所有者 | 当前所有者只能是 `ENTERPRISE` 或 `PERSONAL`，一箱至多一条归属 | 所有权转让、完整权属历史 |
| 骑手 | 现有启用的 `COURIER` 账号 | 骑手注册、外部组织、企业员工目录 |
| 使用分配 | 一台企业箱体同一时间最多分配一名骑手；同一骑手可被分配多个箱体 | 多人共享、排班、交接审批 |
| 收回 | 管理员可收回自己企业箱体的当前分配；TASK-012 上线后，进行中运输时应拒绝收回 | 强制收回、紧急接管 |
| 个人箱体 | 个人箱主在 `owner_user_id` 中登记，骑手账号可在“我的箱体”看到自己的箱体 | 输入箱号自助抢占、交易和转让 |

**分配资格的 DEMO 边界**：本次不引入企业成员或骑手隶属表；管理员只能从**受控演示账号集合**选择已启用的 `COURIER`。演示账号由受控种子配置提供，不对真实用户开放全站骑手搜索。该简化不能宣称具备生产级企业骑手归属验证。

### 3.1 成功流程

```text
旧 Bootstrap 初始化 boxes、users 和原有授权
    ↓
受控种子登记一个企业、管理员、箱体所有者及个人箱主
    ↓
管理员登录 → GET 企业箱体列表（含当前分配）
    ↓
管理员选择一个未分配企业箱体与演示骑手
    ↓
POST 分配 → 服务端核验 → MySQL 保存
    ↓
骑手登录 → GET 我的箱体（source=ASSIGNED）
    ↓
管理员 POST 收回 → MySQL 删除当前分配
    ↓
骑手再次查询 → 被收回箱体不再出现在新资产列表
```

个人箱体路径不经过企业分配：骑手查询返回 `source=OWNED`。

## 4. MySQL 数据设计：只增加三张表

本节保留实施设计依据，实际迁移见 [V13](../../backend/src/main/resources/db/migration/V13__demo_asset_ownership.sql)，已在项目测试库与开发库应用。沿用现有 `users.id BIGINT`、`boxes.box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin`、InnoDB 与毫秒 UTC 时间戳。本次新增 `V13__demo_asset_ownership.sql`，未修改已部署 V1～V12。

### 4.1 `enterprises`：演示企业

```sql
CREATE TABLE enterprises (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  admin_user_id BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_enterprise_demo_admin (admin_user_id),
  CONSTRAINT fk_enterprise_admin FOREIGN KEY (admin_user_id)
    REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
```

- 一个演示管理员最多管理一家企业；不实现成员系统。
- 业务层必须核查管理员账号 `enabled=true` 且角色为 `LOGISTICS_OPERATOR`；外键只保证用户存在。

### 4.2 `box_ownerships`：当前资产所有者

```sql
CREATE TABLE box_ownerships (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  owner_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enterprise_id BIGINT NULL,
  owner_user_id BIGINT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT fk_ownership_box FOREIGN KEY (box_id)
    REFERENCES boxes(box_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ownership_enterprise FOREIGN KEY (enterprise_id)
    REFERENCES enterprises(id) ON DELETE RESTRICT,
  CONSTRAINT fk_ownership_user FOREIGN KEY (owner_user_id)
    REFERENCES users(id) ON DELETE RESTRICT,
  CONSTRAINT chk_one_owner CHECK (
    (owner_type='ENTERPRISE' AND enterprise_id IS NOT NULL AND owner_user_id IS NULL)
    OR (owner_type='PERSONAL' AND enterprise_id IS NULL AND owner_user_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
```

- `box_id` 主键确保当前一个明确所有者；同一箱体不能同时属于两家企业或企业与个人。
- **不在 V1 允许在线修改所有者**；初始化时若记录已存在但归属不同，应报冲突，不要静默覆盖。
- 没登记所有者的既有箱体，不自动归属于任何当前登录用户；应显示“未配置资产”，待受控初始化处理。

### 4.3 `box_assignments`：当前有效分配

```sql
CREATE TABLE box_assignments (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  rider_user_id BIGINT NOT NULL,
  assigned_by BIGINT NOT NULL,
  assigned_at BIGINT NOT NULL,
  CONSTRAINT fk_assignment_box FOREIGN KEY (box_id)
    REFERENCES box_ownerships(box_id) ON DELETE RESTRICT,
  CONSTRAINT fk_assignment_rider FOREIGN KEY (rider_user_id)
    REFERENCES users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_assignment_actor FOREIGN KEY (assigned_by)
    REFERENCES users(id) ON DELETE RESTRICT,
  INDEX ix_assignment_rider (rider_user_id,box_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
```

- `PRIMARY KEY(box_id)` 是“单箱只允许一个当前分配”的最简数据库保护，够用，不另建活动分配指针表。
- 仅允许为 `ENTERPRISE` 所有的箱体建立分配；个人所有箱体不允许被企业管理员拿来分配。
- 收回时 `DELETE` 当前分配；不做完整分配历史表。简要操作可记入已有 `audit_logs`。

**实施顺序**：先 V13 建表，再创建演示数据（先 `users/boxes` 后 `enterprises` → `box_ownerships` → `box_assignments`）。不要把演示凭据、真实用户密码或云端 MQTT 秘钥写进迁移文件。

## 5. Java 后端模块组织

推荐在现有 Spring Boot 模块化单体里新增一个 `cn.smartbox.asset` 业务包，不拆微服务：

```text
backend/src/main/java/cn/smartbox/asset/
  AssetController.java       # REST 参数/返回，委托服务
  AssetService.java          # 企业管理员校验、分配/收回与查询
  AssetMapper.java           # MyBatis 数据读取/更新（或少量 JdbcTemplate）

backend/src/main/resources/mapper/
  AssetMapper.xml            # 按现有 AuthMapper.xml 风格实现参数化 SQL

backend/src/main/resources/db/migration/
  V13__demo_asset_ownership.sql
```

**推荐职责分工：**

- `AssetController`：读取当前 `@AuthenticationPrincipal CurrentUser`；校验 `boxId`、JSON 字段；返回 `ApiEnvelope.success`；不在 Controller 拼 SQL。
- `AssetService`：确认 `LOGISTICS_OPERATOR + enterprises.admin_user_id` 的双重条件；检查箱体归属、骑手 `COURIER/enabled` 及受控 DEMO 候选资格；实现分配和收回事务。
- `AssetMapper`：按当前用户过滤 SQL，使用预编译参数；不要使用客户端提交的 `enterprise_id` 决定资源范围。
- `AuditService`：成功修改后可复用现有日志服务，记录 actor / box / 动作 / 结果；原实现使用 `REQUIRES_NEW`，不要描述成随资产事务原子提交。
- `CurrentUser` / `AuthMapper`：优先复用当前认证能力，不增加另一套账号和密码。

### 5.1 最小操作事务

**分配时：**

1. 从 JWT 当前用户查自己的企业；必须为其指定的 `admin_user_id`。
2. 校验 `box_id` 格式及箱体属于该企业；校验骑手为受控 DEMO 中启用的 `COURIER`。
3. 锁定 `box_ownerships` 目标行，再以 `SELECT ... FOR UPDATE` 当前读查询当前分配（避免 MySQL REPEATABLE READ 旧快照）：若已有同一骑手，返回现有分配；若其他骑手占用，返回 `409`。
4. 尝试 `INSERT box_assignments`；主键冲突由数据库保障，不直接覆盖已有骑手，必要时重查并映射 `409`。
5. 提交后返回当前分配，并尽力写操作审计；不更新 `user_box_access`。

**收回时：**

1. 同样校验操作者与箱体的企业归属，不能跨企业操作。
2. TASK-011 尚无 `transport_tasks` 表，暂时仅删除当前分配；TASK-012 引入 ACTIVE 任务后，在同一授权/事务路径增加“正在运输不得普通收回”的校验。
3. 删除后返回“已收回”或“原本未分配”；重复收回可安全返回未分配状态，不影响其他用户。
4. 后续旧设备箱体权限不由本次删除操作自动吊销；新业务接口必须按当前分配重新校验。

**个人箱体**不提供 `assign/unassign`，仅由受控归属初始化建立。

## 6. REST API（本 TASK 已实现）

全部接口默认走现有 Spring Security JWT；保持 `/api/v1` 风格，不新增匿名访问。

| API | 使用者 | 输入 | 成功输出最少字段 |
|---|---|---|---|
| `GET /api/v1/enterprise/boxes` | 被登记的企业管理员 | 无 | 企业名、企业箱体列表、箱体名称、分配骑手 ID/名称或空 |
| `POST /api/v1/enterprise/boxes/{boxId}/assign` | 本企业管理员 | `{"rider_user_id": 12}`（仅示例） | `box_id, rider_user_id, assigned_at` |
| `POST /api/v1/enterprise/boxes/{boxId}/unassign` | 本企业管理员 | 无 | `box_id, assigned=false` |
| `GET /api/v1/rider/boxes` | 启用的 `COURIER` | 无 | `box_id, display_name, source`（`OWNED` 或 `ASSIGNED`） |

实际骑手列表返回示例（只展示资产元数据，非传感器快照）：

```json
{
  "request_id": "example-request",
  "data": [
      {
        "box_id": "BOX-0001",
        "display_name": "演示物流箱",
        "source": "ASSIGNED",
        "enterprise_name": "演示物流企业"
      }
  ]
}
```

实际顶层使用现有 `ApiEnvelope`；完整字段、错误和正反样例见增量契约，与原冻结11项接口分开维护。

可分配骑手的选择 **不强制新增公共用户列表 API**：受控演示账户由本地种子和前端受控演示配置提供，避免开放“枚举全部 COURIER 用户”的接口。若页面确实需要动态候选列表，应限制为显式配置的演示骑手且仅管理员可读，作为最小增补，不发展成完整成员管理。

### 6.1 关键返回与异常

| 情况 | 实际结果 | 客户端含义 |
|---|---|---|
| 非法 boxId 或 rider ID | 400 / `VALIDATION_ERROR` | 输入不合法 |
| 未登录 / JWT 过期 | 401 | 请重新登录 |
| 不是企业管理员，或跨企业箱体 | 403 / `FORBIDDEN` | 无权操作 |
| 箱体已分配给其他骑手 | 409 / 如 `BOX_ALREADY_ASSIGNED` | 该箱体已分配 |
| 骑手不存在、已停用或不是 COURIER | 400 / `VALIDATION_ERROR` | 不可选择该骑手 |
| 重复分配给同一骑手 | 200，返回当前分配 | 已分配 |
| 重复收回 | 200，`assigned=false` | 已收回 |

**注意：** 当前 `ApiException.conflict()` 的默认文案用于“初始化输入与现有数据不一致”，不要原样复用来描述箱体分配。可以构造现有 `ApiException(409, code, message)`，保持 API 响应结构一致。

## 7. 真实 DEMO 数据与旧权限的衔接

建议准备以下**虚构数据**（不提交真实账号凭据）：

| 数据 | 示例 | 说明 |
|---|---|---|
| 企业 A | 演示物流企业 | 与一个有效 `LOGISTICS_OPERATOR` 管理员绑定 |
| 企业箱体 | `BOX-0001` | 仅示例；实际以现有已初始化的真实 ENV/POSE 逻辑箱体为准 |
| 骑手 R | 受控演示 `COURIER` 账号 | 从本地受控种子选择，不在页面硬编码真实数据库 ID |
| 骑手个人箱体 | `BOX-DEMO-02` | 可选，仅为展示个人/企业来源区别；无实物时明确模拟或离线 |
| 企业 B / 未授权账号 | 一个对照样本 | 用于验证不能操作企业 A 的箱体 |

**如何初始化：**

1. 先运行现有受控 Bootstrap 创建必要的用户、箱体、节点；不改原登录/密码结构。若测试账号已存在，不必再初始化。
2. 再执行单独、可重复检查的演示资产种子（配置文件/开发脚本，或受控测试数据 SQL）：通过用户名找到实际 `users.id`，创建演示企业及所有权；已存在相同归属跳过、不同归属报错。
3. 首轮可以让企业箱体处于“未分配”，通过实际 HTTP 操作演示第一次分配；不要默认把全部箱体分给骑手。
4. 只在隔离的 `smartbox_test` / 受控 `smartbox_dev` 环境执行，不污染现有数据；禁止自动给任何历史账号添加管理员权限。
5. 骑手新资产接口按归属/分配获取列表；**目前旧 `GET /api/v1/boxes` 和 `/boxes/{id}/latest` 仍按 `user_box_access` 工作**，不能因此称旧监测入口已完成动态授权。需在 TASK-013 统一处理新的业务只读访问，并防止撤回后继续访问。

如果使用额外演示箱体，务必标注设备来源。当前真实硬件仍对应原逻辑箱体，不要向 `BOX-DEMO-02` 伪造已连接的 ENV/POSE 数据。

## 8. 实施步骤与交付记录入口

| 步骤 | 具体工作 | 完成证据 |
|---|---|---|
| S1 兼容检查 | 读取 `V1__identity_and_nodes.sql`、`BootstrapService`、`AuthMapper.xml` 和现有 API 响应 | 明确旧表及权限不变 |
| S2 数据迁移 | 仅新增三张业务表，主键/外键约束和角色业务校验保持清晰 | 隔离 `smartbox_test` 可启动并看到新表 |
| S3 受控种子 | 准备演示企业、管理员、企业箱、个人箱、骑手，不含真实密码 | 相同种子重运行不产生重复/错误归属 |
| S4 后端接口 | 新增 `AssetController / AssetService / AssetMapper`，完成四个 REST 端点 | Postman/curl 或现有 HTTP 测试可真实操作 |
| S5 使用资格 | `AssetService.requireRiderUse` 查询当前个人归属或企业分配；TASK-012须在自身状态事务内重新校验 | 收回后服务端判断拒绝，原设备授权不改变 |
| S6 真实验证与开发库 | 测试库完成单元/集成/HTTP检查后，迁移开发库并复验；独立18080、关闭MQTT | 两库分配→可见→收回通过；原8080不动 |
| S7 收尾 | 运行相关最小检查，更新 TASK 状态和实际报告，列出未覆盖项 | 可复验的简短报告 |

**不要一次性开发** TASK-012 的运输任务、TASK-013 的验证码/历史、完整三端路由，或重构全套 `AuthMapper`、`PermissionPolicy`、WebSocket。与已有界面样式一致即可，先保证数据来自真实 API。

## 9. 精简测试与最低验收

关联 历史验证。建议只做少量自动化和一次可见的真实服务操作：

- [x] **数据库**：Flyway 新迁移在隔离 MySQL 8 成功；演示种子可重复执行且不覆盖已有不一致的归属。
- [x] **正常流程**：企业 A 管理员分配 `BOX-0001` 给骑手 R → 骑手查询看见 `ASSIGNED` → 企业收回 → 骑手查询不再出现该资产。
- [x] **个人箱体**：骑手能看到自己个人拥有的箱体，`source=OWNED`，该箱体不能通过企业分配接口变更。
- [x] **关键越权**：企业 B 账号或普通用户不能给企业 A 的箱体分配/收回；骑手不能将其他人资产查为自己的。
- [x] **单箱排他**：再次将已分配箱体分给另一名骑手，返回冲突而不覆盖当前分配。
- [x] **兼容性抽查**：已有登录、`GET /api/v1/boxes` 和一个设备快照查询仍保持原有行为。

不要求此时覆盖 20 并发竞争、全异常矩阵、长期运行、多节点重新连接或新角色性能压测。测试通过仅代表此处**执行过的 DEMO 切片**，不能直接将完整候选 REQ-M05-006/007、REQ-M06-007 的未来高级能力全部记为已验收。

## 10. 本任务与 TASK-012/013 的交付协议

TASK-011 完成时应明确留给后续开发的接口/事实：

1. `box_ownerships` 能够可靠回答“箱体属于企业还是个人”；`box_assignments` 回答“当前骑手是谁”。
2. `AssetService` 有一个清晰、可复用的“当前骑手有权使用此箱体吗”的服务端判断，供 TASK-012 开始运输时**重新检查**；不能把 `GET /rider/boxes` 当权限证明。
3. TASK-012 引入 `transport_tasks` 后，应补充“存在 ACTIVE 任务时不可普通收回”，避免分配回收与运输占用冲突。
4. TASK-013 接入箱体实时监测时，不可直接把 `user_box_access` 等同新资产使用资格；需要统一业务只读接口或受限扩展 `BoxAccessService`，且与原设备权限分开。
5. 任何为了演示临时创建的 Mock 资产必须与实际真实箱体数据明确区分。

## 11. 实际执行记录与交付判断（2026-11-23）

本轮按用户确认只实现后端和接口，不开发页面。基线从83aca91快进同步至0209f70，保留未跟踪的 `ui-design/`。S1～S7均已执行；代码、迁移、接口样例和测试入口可从[后端导读](../../backend/README.md)及初始化指南进入。

| 步骤 | 实际产出与结果 |
|---|---|
| S1 | 同步、迁移版本及项目配置核对通过，原8080服务保留 |
| S2 | V13三表在真实MySQL测试库先通过，再应用开发库；V1～V12不变 |
| S3 | 独立资产种子以用户名解析账号；重复不覆盖，冲突事务回滚 |
| S4 | 四个JWT接口、显式DTO、企业归属与骑手白名单、事务锁及提交后尽力审计 |
| S5 | 当前使用资格服务可复用；收回不修改 `user_box_access`；运输ACTIVE检查留TASK-012 |
| S6 | 34项单元、64项真实集成（含7项资产用例）；测试库22项、开发库19项实际HTTP检查通过，18080实例均已停止 |
| S7 | 增量契约、工具、TC适用结果及实际报告同步；提交与保护性检查结果见报告 |

开发库已登记演示企业与BOX-0001企业权属；BOX-DEMO-02仅是无设备个人演示箱。分配复验结束后恢复未分配，账号、密码、授权与原节点保持不变。日常8080仍是此前进程；使用新接口可先通过资产工具在18080启动新版本，不声称原进程已热更新。

详细证据和未覆盖项见实际验证报告。TASK-011完成仅覆盖DEMO后端切片；不等于完整候选BL-003、企业成员、权属转让、运输业务、页面或整份历史验证已验收。TASK-009、012、013未启动。