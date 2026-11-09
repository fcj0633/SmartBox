# 测试体系：从静态检查到真实双板

本目录已有文档/契约工具回归、固件宿主组件、模拟器和工具安全测试。Java测试随backend、网页测试随frontend放置。人工TC描述业务场景和通过标准；可执行测试、实际报告与原始证据分别承担不同职责，**工具通过不自动验收整条REQ或整份TC**。

## 1. 选择正确的验证层次

| 层次 | 测试位置／运行入口 | 验证什么；不能证明什么 |
|---|---|---|
| 文档结构/链接 | [check_docs](../tools/check_docs.py)，[工具回归](test_check_docs.py) | 需求/追踪/锚点/历史哈希及示例；不是软件或硬件业务通过 |
| 冻结与有效契约 | [契约回归](test_check_contracts.py)、[有效契约回归](test_effective_contracts.py)，对应tools检查入口 | 结构、正反样例、容量和静态语义；不是实际鉴权/落库/执行 |
| Java单元 | [FoundationTest](../backend/src/test/java/cn/smartbox/FoundationTest.java)、[LiveUnitTest](../backend/src/test/java/cn/smartbox/LiveUnitTest.java)、[WS单元](../backend/src/test/java/cn/smartbox/live/LiveWebSocketTest.java)，`tools/backend.py unit` | 纯策略、边界与受控时间；不替代真MySQL |
| Java真实MySQL/HTTP/WS | [BackendIT](../backend/src/test/java/cn/smartbox/BackendIT.java)、[失败IT](../backend/src/test/java/cn/smartbox/BackendFailureIT.java)、[LiveIT](../backend/src/test/java/cn/smartbox/LiveIT.java)、[WebSocketIT](../backend/src/test/java/cn/smartbox/WebSocketIT.java) | 迁移、约束、事务、权限及真实接口；显式test和清理同意，不用H2/SQLite替代 |
| 固件Mock/生命周期 | [组件](firmware/test_components.c)、[CMSIS替身](firmware/cmsis_os2.h)，`tools/check_firmware.py` | 宿主时钟/队列/启动与故障收敛；不是实际RTOS调度 |
| 固件真实核心/SDK适配 | [硬件组件目录](firmware/hardware)，`tools/check_hardware.py` | CRC/I2C/输出/协议/启动元数据等受控故障；不是烧录/物理动作 |
| SDK同步/恢复工具 | [同步](test_sync_firmware.py)、[构建恢复](test_build_firmware.py)、[硬件配置](test_hardware_tools.py) | 临时SDK/状态中的预览、冲突、边界、恢复；不拿真实实验故意破坏 |
| 模拟器单元 | [test_model](simulator/test_model.py)，`tools/mqtt.py unit` | 状态、时间、幂等、事件确认和临时配置；无实物 |
| 真实Broker双模拟节点 | [MQTT场景](../tools/simulator/scenarios.py)，`tools/mqtt.py integration` | 真MQTT/ACL/恢复及模拟动作；测试端event_ack不是数据库确认 |
| 服务端闭环 | [live_integration](../tools/live_integration.py) | 真Broker、后端、MySQL、双模拟器及业务确认/查询/命令/WS；不是设备Flash |
| 前端单元/组件 | [core.test](../frontend/tests/unit/core.test.ts)，`tools/frontend.py check` | 角色、状态、JSON精度、刷新和交互；不替代浏览器真实链路 |
| 隔离浏览器 | [ui.spec](../frontend/tests/browser/ui.spec.ts)，`tools/frontend.py browser` | 桌面尺寸、错误/乱序/会话等受控响应；不是后端真实授权 |
| 全系统浏览器 | [closed-loop.spec](../frontend/tests/live/closed-loop.spec.ts)，`tools/frontend.py integration` | 真网页/服务/MySQL/Broker/双模拟进程，五类模拟控制；不能证明真实物理动作 |
| 真实双板 | [双板报告](../docs/testing/reports/TASK-007-双板接入验证报告.md)及[操作指南](../docs/development/guides/编译烧录与联调流程.md) | 真实采集/三项输出/310秒/恢复及用户实物确认；未完成全部算法/长期可靠性 |
| 可靠性与长期 | [TC-011](../docs/testing/cases/TC-011-断网掉电与稳定性.md)、[TASK-009](../docs/development/tasks/TASK-009-持久化补传与可靠性.md) | 10次掉电、30分钟断网、24小时等仍需适用后续实测，不把短测算作通过 |

## 2. 常用只读检查与工具回归

仓库根目录使用项目虚拟环境，契约依赖按已有锁文件准备，不升级系统工具：

```powershell
.venv/Scripts/python tools/check_docs.py
.venv/Scripts/python -m unittest discover -s tests -p test_check_docs.py -v
.venv/Scripts/python tools/check_contracts.py
.venv/Scripts/python tools/effective_contracts.py
.venv/Scripts/python -m unittest discover -s tests -p test_check_contracts.py -v
.venv/Scripts/python -m unittest discover -s tests -p test_effective_contracts.py -v
```

文档回归会复制必要受控文件到临时样例，在副本中故意破坏编号/链接/历史/追踪并断言失败；不会修改真实业务文档。夹具补齐前端源码、测试和清单，以及唯一受控的artifacts说明，不复制依赖/产物/秘密，不放宽检查器或断言。

## 3. 会写入或启动服务的检查

```powershell
.venv/Scripts/python tools/backend.py test --profile test --allow-test-reset
.venv/Scripts/python tools/live_integration.py --allow-test-reset --run-dir artifacts/tests/server-NEW-RUN
.venv/Scripts/python tools/frontend.py integration --profile test --allow-test-reset --run-dir artifacts/tests/browser-NEW-RUN
```

**以上按需分别执行，不能并行使用同一smartbox_test。** 前提是测试配置、项目账号、MySQL、Broker与浏览器依赖均已准备，相关端口空闲；NEW-RUN必须新建且唯一。可能清理/初始化项目测试数据、临时撤权并恢复、启动自身进程，不能对smartbox_dev或其他项目执行。缺配置失败而不是跳过。[TestDatabaseGuard](../backend/src/test/java/cn/smartbox/TestDatabaseGuard.java)和后端测试重置入口提供额外库名/账号校验，不能用命令参数绕过。

后端`smoke`是基础真实HTTP/Schema补查；前端定向authorization、服务端dispatch-recovery也只是局部复验，不替代完整场景。固件宿主命令需要既有Ubuntu执行通道与GCC；实际SDK双角色构建/恢复另使用受控构建入口。详细参数见[工具导读](../tools/README.md)。

## 4. 报告、证据与判定

[TC索引](../docs/testing/cases/README.md)定义应测业务，报告在[reports](../docs/testing/reports)记录实际版本、命令、场景、结果与限制，原始日志/镜像/脱敏截图在[artifacts](../artifacts/README.md)。同一TC可被多个任务分层覆盖，必须保留“仅软件/仅模拟/实际板端/未执行”的区分。

真实输出需要操作号、匹配回执及用户实物确认；在线、观测有效、校时、新鲜度和业务判断分别核验。LWT离线与真实90秒静默分开，受控时钟边界不冒充实际等待。TASK-007已完成基础真实双板，不再说整个项目从未接板；仍没有全量Flash掉电、完整告警/门融合和最终系统验收。

不得保存密码、JWT、ticket、设备凭据或完整原始浏览器HAR/trace。失败证据保留，不修改历史报告或已应用迁移来制造成功。受控报告的结论必须能追溯到[证据规则](../docs/testing/evidence/README.md)。
