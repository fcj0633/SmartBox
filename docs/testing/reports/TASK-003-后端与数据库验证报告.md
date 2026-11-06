# 后端与数据库基础验收报告（TASK-003）

## 范围、版本与结论

记录日期：2026-11-07（客户端日期）；实际执行者：Codex；开发与最终验收责任人：用户。底层主机的原始构建/服务日志日期为2026-11-06晚间至2026-11-07凌晨，原始时间保持不变。本报告不代填人工签字。

依据[TASK-003](../../development/tasks/TASK-003-后端与数据库骨架.md)执行S1～S7，源码基于4731262并包含本轮新增实现；精确交付提交由本报告所在提交确定。TASK-003已完成后端与数据库基础：可编译/启动的应用、真实MySQL隔离库、13业务表、4次迁移、显式初始化、认证授权和节点查询。17项单元、26项集成及23项独立HTTP检查通过。

没有实现MQTT接入、事件保存后确认、outbox消费、真实命令、聚合/历史查询、WS、CSV或网页；没有修改SDK、接板或烧录。表约束、投影策略和数据库事务测试不等于业务闭环，整份TC-009/012及需求不自动升级为已验收。

## 环境与依赖

| 项目 | 实际结果 | 证据 |
|---|---|---|
| JDK | 21.0.10+8-LTS-217 | 已安装工具，只为本任务子进程配置兼容选项 |
| Maven | 3.9.14 | 实际编译、打包、单元及Failsafe执行 |
| MySQL | 8.0.44，原MySQL80服务 | 两个真实项目库，不使用H2/SQLite |
| Spring Boot / Security | 3.5.16 / 6.5.11 | Maven解析结果，Security由Boot管理 |
| MyBatis Starter / MyBatis / MyBatis-Spring | 3.0.5 / 3.5.19 / 3.0.5 | 解析结果与TASK-001一致 |
| Flyway core/mysql / Connector/J | 11.7.2 / 9.7.0 | 实际迁移及JDBC访问 |
| JUnit / Surefire / Failsafe | Boot管理测试依赖 / 3.5.6 / 3.5.6 | XML测试报告和Maven日志 |

完整依赖解析在本地artifacts/backend-dependencies.log，不重新覆盖既有冻结版本。固定的[Maven工程](../../../backend/pom.xml)、[初始化/启动指南](../../development/guides/后端数据库初始化与启动.md)提供复验入口。

## 实现与数据库实际结果

| 能力 | 本次交付 | 未包含 |
|---|---|---|
| 应用 | 单Maven应用、统一响应/错误/request_id、参数校验、最小健康 | 完整业务接口或成功占位数据 |
| 认证 | 真实账号、PBKDF2带盐散列、原生Nimbus JWT HS256、30分钟、严格声明/到期校验 | 注册、刷新令牌、账号管理 |
| 授权 | 当前DB角色/状态/箱体范围、默认拒绝、先授权后查询、节点诊断限制、投影白名单 | 实际WS/CSV/聚合出口 |
| 数据 | 13业务表及FK/UK/CHECK/index、uint64精确映射、4次迁移 | 事件/命令工作器与MQTT业务确认 |
| 初始化 | 显式非Web事务初始化，可重跑，不覆盖密码/授权；冲突回滚 | 换绑、停用管理或权限移交 |
| 审计 | 登录失败/拒绝访问、独立事务；数据库故障时脱敏降级 | 生产审计平台和全业务操作 |

数据库摘要在本地artifacts/backend/TASK-003/database-summary.json：

| 环境 | 业务表 / 迁移版本 | 用户 / 箱体 / 节点 | events / outbox / node_commands |
|---|---|---|---|
| smartbox_dev | 13 / 1、2、3、4均成功 | 4 / 1 / 2 | 均0 |
| smartbox_test | 13 / 1、2、3、4均成功 | 8 / 2 / 4 | 均0（负例数据回滚） |

Flyway历史表另计。运行账号分别smartbox_app和smartbox_test_app，仅对应库SELECT/INSERT/UPDATE/DELETE；实际CREATE TABLE拒绝已测试。两个迁移账号仅各自项目库DDL/DML，不使用root运行。管理员密码仅首次操作时在子进程环境短暂使用，未保存到文件、应用或命令行。未操作其他项目库。

迁移分组：V1身份/箱体/节点，V2遥测/最新/原始事件/outbox，V3命令/幂等/审计。真实负例发现MySQL CHECK条件为UNKNOWN时可放行部分空回执时间，因此新增V4显式要求ack_ts_src非空；不修改已应用V1～V3，不改变既定时间规则。完整UNSYNCED/SNTP回执可通过，不完整回执被拒绝。

## 自动与真实检查

| 检查入口 | 数量和结果 | 实际覆盖 |
|---|---|---|
| Maven Surefire：FoundationTest | 17项，0失败/错误/跳过 | 角色策略、Unicode码点/密码、PBKDF2盐、JWT边界、uint64、配置、授权顺序、敏感字段投影、测试清理保护 |
| Maven Failsafe：BackendIT | 25项，0失败/错误/跳过 | 真实MySQL迁移/重复启动、初始化/回滚、权限、约束、数值/时间、4角色双箱体HTTP、JWT、CORS、独立审计 |
| Maven Failsafe：BackendFailureIT | 1项，0失败/错误/跳过 | 运行中的HTTP应用内Mapper故障注入，login/me安全503，不停止共享MySQL |
| 独立打包JAR的HTTP/冻结Schema检查 | 23项通过 | health、四角色login/me/nodes、跨箱体403、401、非法类型400、未知路由404、允许Origin预检；响应结构本地解析引用 |
| 重复dev初始化 | 成功退出 | 同样输入不重置既有密码、角色或授权 |

单元与集成合计43项；独立HTTP的23项不计入JUnit数量。测试源码：[单元](../../../backend/src/test/java/cn/smartbox/FoundationTest.java)、[真实MySQL/HTTP](../../../backend/src/test/java/cn/smartbox/BackendIT.java)、[故障注入](../../../backend/src/test/java/cn/smartbox/BackendFailureIT.java)。

特定证据边界：

- 授权通过而资源缺失的404顺序用替身验证，正常数据库外键不允许通过制造悬空授权来测这个分支。
- 字段白名单测试用含敏感嵌套字段的样例，不宣称实际聚合接口已运行。
- 同事务原始事件/outbox回滚验证数据库边界，不包含接收MQTT后提交再确认。
- 过期、篡改、错误算法/声明、角色改变、权限撤销和账号禁用均有对应测试；JWT仅证明身份，当前权限仍查询数据库。
- 校验失败负例仅改临时迁移副本，未修改真实受控迁移或执行repair。
- 测试清理仅在显式test配置、实际库/账号与开关共同满足后执行，不删除库，不清理dev。

### 独立HTTP产物

打包JAR：backend/target/smartbox-backend-0.1.0.jar。

SHA256：`55d85b27c9d0c0ba172aedf8d7cbf8965ad7c3e869ac7ddfb75b83f7900447aa`。

本地artifacts/backend/TASK-003/final-http/http-summary.json记录23项状态/Schema、JAR校验值及进程清理；不保存密码、JWT或完整登录响应。摘要中server_stopped=true，并检查8080释放。检查前拒绝占用中的端口，不停止其他服务。

## 发现、修正与限制

1. MyBatis同会话空查询缓存影响初始化新账号后读取ID，改为同事务JdbcTemplate读取新ID；重跑和冲突回滚测试通过。
2. 请求过滤器与框架同名Bean冲突、CORS来源Bean名称未被框架使用，均已修正；真实HTTP允许Origin预检与非白名单拒绝通过。
3. 本机JDK内部AF_UNIX连接失败影响Tomcat/Java HTTP。已核对本机JDK源码，以本地开关仅对任务子进程启用JDK内置TCP回退，不升级或修改系统。该机制依据[OpenJDK PipeImpl回退逻辑](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/ch/PipeImpl.java)，不代表生产主机兼容性通过。
4. Windows PATH的Oracle Java启动器会派生实际JVM，最初只等待父进程退出不足以证明服务停止。工具改用JAVA_HOME中的真实java.exe，并验证实际PID退出及端口释放；确认过的本轮残留JVM已清理，最终独立HTTP复验通过。
5. MySQL CHECK的UNKNOWN问题用新增V4解决，保留已应用迁移历史，不自动clean/repair。

Q-01～Q-14保持未决；数据库/接口实现不决定换绑、围栏入口、迟到回执、告警人工处置或保留期限。无TLS/生产部署、负载性能、真实WS/CSV、网络接入或板端验收结论。

## 证据索引、复验与收尾

所有原始文件位于本地忽略目录，不提交依赖、数据库密码、JWT或日志：

| 本地位置（相对仓库） | 内容 |
|---|---|
| artifacts/backend-integration.log | 最终mvn verify -Pit：17单元、26集成、BUILD SUCCESS |
| backend/target/surefire-reports及failsafe-reports | JUnit XML与文本结果 |
| artifacts/backend-dependencies.log | 实际依赖解析 |
| artifacts/backend-bootstrap-dev.log | 最终重复dev初始化成功 |
| artifacts/backend-http-check.log | 独立HTTP最终通过摘要 |
| artifacts/backend/TASK-003/final-http/ | 服务日志、脱敏HTTP摘要 |
| artifacts/backend/TASK-003/database-summary.json | 版本、表/数据计数、迁移及运行账号权限 |
| artifacts/backend/TASK-003/final-checks.json | 文档/契约、历史保护、冻结指纹和秘密检查摘要 |

可复验命令（仓库根目录）：

```powershell
python tools/backend.py unit
python tools/backend.py test --profile test --allow-test-reset
python tools/backend.py bootstrap --profile dev
.venv/Scripts/python tools/backend.py smoke --profile dev --report-dir artifacts/backend/TASK-003/final-http
.venv/Scripts/python tools/check_contracts.py
python -m unittest discover -s tests -p test_check_docs.py -v
python tools/check_docs.py
git diff --check
```

首次构建先执行python tools/backend.py build --profile dev。上述命令适用检查均已执行成功。最终文档检查通过：6模块、47子需求、50追踪行、64规则迁移记录、2165链接；文档工具回归23项通过。契约检查通过：12个Schema、60定义、152正反样例、11个HTTP操作。Git差异空白检查通过；174个冻结文件、原始需求/历史保护路径及50行追踪关系未变，待提交文件与最终日志未检出本机已知秘密值。摘要保存在本地final-checks.json。文档工具测试仅补充新后端源码/pom到临时输入，未削弱规则或更改检查器。

收尾已停止本轮启动的应用，8080无监听，原MySQL80仍运行；项目库、四账号、本地配置和随机演示密码保留供后续开发。秘密仅在secrets/backend.local.json及dev/test初始化文件；Git忽略，不写入受控文档。提交按用户要求使用中文标题和正文，仅本地，不推送。下一步TASK-004，再由TASK-005接通MQTT、保存确认、实时聚合与命令闭环。
