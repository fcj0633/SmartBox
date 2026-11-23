# 骑手运输任务事务后端验证报告（TASK-012）

| 项目 | 实际记录 |
|---|---|
| 日期 | 2026-11-23 |
| 基线 | `bc89e23`（本轮代码提交前）；最终提交号由本次 Git 提交定位 |
| 范围 | CR-006 DEMO 骑手运输任务后端、V14、REST、真实 MySQL/HTTP；无页面/访客 |
| 执行 | 当前 AI 编码代理执行，用户独立开发与验收；无虚构人工签字 |
| 关联 | [TASK-012](../../development/tasks/TASK-012-运输任务状态与事务闭环.md)、[TC-014](../cases/TC-014-运输事务与幂等并发.md) |
| 结论 | 已完成（DEMO 后端运输事务闭环；开发库在线开始未演示；页面/访客待 TASK-013） |

## 1. 实现与数据库影响

- 新增[V14](../../../backend/src/main/resources/db/migration/V14__transport_tasks.sql)，只增加 `transport_tasks`；生成列 `active_box_key` 唯一索引保证同箱最多一个 ACTIVE。未改写 V1～V13。
- 新增[transport 包](../../../backend/src/main/java/cn/smartbox/transport/)：Controller、Service、Mapper、View 与 `TransportMapper.xml`，实现 6 个 JWT 骑手接口。
- [AssetService](../../../backend/src/main/java/cn/smartbox/asset/AssetService.java)增加事务内锁读使用资格 `requireRiderUseForUpdate`、`lockOwnershipForUpdate`，并在 `unassign` 持有 ownership 锁时检查 ACTIVE；开始、结束、收回统一 ownership → task 锁顺序。
- 新增增量契约[contracts/http/transport](../../../contracts/http/transport/README.md)、[check_transport_contracts.py](../../../tools/check_transport_contracts.py)及[契约工具测试](../../../tests/test_transport_contracts.py)；冻结 11 项 HTTP 操作未修改。
- 测试清理顺序加入 `transport_tasks`：AssetIT、BackendIT、LiveIT、WebSocketIT 与新增 TransportIT；只清理受 TestDatabaseGuard 保护的 `smartbox_test`。

## 2. 实际执行与结果

| 检查 | 结果与证据 |
|---|---|
| 后端单元 | 34 项通过，0 失败/错误/跳过 |
| 真实 MySQL/HTTP/WS 集成 | 73 项通过，0 失败/错误/跳过；含新增 9 项 `TransportIT` |
| TransportIT 覆盖 | 主流程与历史、同箱排他、2 并发启动、ACTIVE 收回 409、离线/无节点、风险确认、越权、物品边界、重复开始/结束、旧资产/设备查询 |
| Flyway | 测试库与开发库均 V13→V14；14 个迁移校验通过；当前 28 张业务表（历史表另计） |
| 增量契约 | 6 个操作、7 个结构定义、17 个正反样例；5 项契约工具测试通过 |
| 冻结契约 | 原 11 个 HTTP 操作、16 个 Schema、152 个原样例检查通过 |
| 开发库受控迁移 | `tools/assets.py bootstrap --profile dev` 升级 V14；账号、旧授权、节点与历史数据保留 |
| 开发库独立 HTTP（18080） | 分配→READY→物品→列表/详情通过；开始返回 409 `BOX_OFFLINE`（现有节点最近活动超过 90 秒）；READY 草稿清理、分配收回；18080 释放，8080/5173 未停止 |
| 失败与修正 | 首次集成发现 BackendIT 仍断言 27 张表/13 迁移，更新为 28/14 后全量重跑通过；修正前失败不计通过 |

`TransportIT` 的 `node_runtime` 在线行和 `alarm_state` 是显式测试夹具；开发库未写入夹具。开发库在线开始/结束因节点过期未演示，不伪造 ACTIVE。

## 3. 证据索引（本地忽略目录）

| 位置 | 用途 |
|---|---|
| `artifacts/task012-integration.log` | 最终 34 单元 + 73 真实集成完整日志 |
| `artifacts/task012-contract.log` | 运输契约、资产契约与冻结契约检查日志 |
| `artifacts/task012-dev-http/summary.json` | 开发库 18080 HTTP 演示及 `BOX_OFFLINE` 断言 |
| `backend/target/failsafe-reports/`、`backend/target/surefire-reports/` | Maven 测试明细 |
| `backend/target/smartbox-backend-0.1.0.jar` | 本轮构建产物；不是发布版本 |

## 4. 未覆盖边界与交接

- 未实现任何页面、访客验证码或任务历史查询；这些归 TASK-013。
- 开发库真实在线开始/结束未演示；测试夹具中的在线与告警不是真实设备证据。
- 创建严格幂等未实现：DEMO 允许同箱多个 READY，重复创建不保证返回同一任务；REQ-M07-001 仅部分覆盖。
- 紧急接管/撤权、20+ 并发压测、长期稳定性、真实设备离线补传未执行；TASK-009 的 Flash/补传保持独立。
- 完整 MOD-07 候选需求、整份 TC-014、BL-003 候选和页面验收不因本报告通过；WS/浏览器项为未执行。
- 原 8080/5173 用户服务与共享 MySQL 未停止或替换。

## 5. 复验入口

```powershell
.venv/Scripts/python tools/backend.py unit
.venv/Scripts/python tools/assets.py test --profile test --allow-test-reset
.venv/Scripts/python tools/check_transport_contracts.py
.venv/Scripts/python -m unittest discover -s tests -p test_transport_contracts.py -v
.venv/Scripts/python tools/check_assets_contracts.py
.venv/Scripts/python tools/check_contracts.py
```

测试库清理必须显式同意且只允许 `smartbox_test`；开发库只执行受控迁移和可恢复演示，不执行测试清库。详细接口与错误见[运输增量契约](../../../contracts/http/transport/README.md)，启动命令见[后端初始化指南](../../development/guides/后端数据库初始化与启动.md)。
