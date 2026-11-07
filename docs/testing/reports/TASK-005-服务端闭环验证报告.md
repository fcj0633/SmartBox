# 服务端闭环验证报告（TASK-005）

日期：2026-11-07。执行者Codex，用户独立最终验收。结果：**已完成服务端与双模拟节点闭环；完整业务算法、网页及板端待后续。** 用户电脑重启后保留重启前有效结果，补跑短Broker发布恢复与开发库迁移核验，没有冒称全部测试在重启后重跑。

## 实现、规则与迁移

[任务设计](../../development/tasks/TASK-005-接入存储与查询闭环.md)、[CR-004](../../management/changes/CR-004-闭环时间语义与未知状态.md)、[启动复验](../../development/guides/服务端闭环启动与验收.md)、[有效契约](../../../contracts/effective/README.md)。现有单Maven应用新增live职责服务与LiveStore/LiveMapper持久层，保留11项HTTP操作及既有WS结构，无新增管理接口。

完成Broker注册归属/能力/严格报文校验、有界接收、遥测历史/latest/独立定位、事件事务后精确确认、持久查询投影及outbox租约、授权REST/CSV、五类模拟控制、持久幂等/发布恢复、固定操作终态与迟到事实、一次性票据/角色化WS。Controller不查询数据库，网络回调不做数据库业务。

依赖实际解析：Paho MQTT v3 1.2.5、networknt 1.5.9；Boot 3.5.16管理Spring WebSocket 6.2.19、Jackson 2.21.4和Tomcat 10.1.55。Java21及既有MyBatis/Flyway/MySQL不升级，解析树本地保存。

新增V5～V9：节点运行态/位置、事件视图、命令投递与原始回执、持久操作顺序、新增数据质量约束。18业务表，Flyway历史表另计；dev/test均已到V9，V1～V4不变，不clean/repair。V8区分同一毫秒创建的操作先后，V9补齐新表uint64/时间/布尔约束；不修改已经应用的迁移。

CR-004落实定位15秒/其他5秒、loc.observation、缺依据UNKNOWN、画像实际采用及后续计数/告警接续、接收时间历史检索、操作终态固定及迟到事实、告警assessment。原始输入、历史记录与174冻结文件保持字节不变；有效定义由受控补丁生成并校验一致。原64条迁移审计保留，MOD-05旧5秒原句明确标注为历史，现行规则按CR-004。

## 实际检查结果

| 检查 | 实际结果 | 本地证据索引（全部忽略） |
|---|---|---|
| Java单元 | 28通过，含严格JSON、规范化、质量/CSV、敏感投影、SQL绑定、MQTT队列及慢WS消费者 | artifacts/task005/backend-final.log及backend/target/surefire-reports |
| 真实MySQL/HTTP/WS集成 | 全量41通过；新增真实DB并发领取定向1通过，累计42项不同场景 | artifacts/task005/backend-final.log及reboot-concurrent-outbox.log |
| 独立主联调 | 42通过；真实Broker、Spring Boot、MySQL与两个独立模拟进程 | artifacts/task005/acceptance-live-02/summary.json及对应日志 |
| 重启后短Broker发布恢复 | 3通过：原有效期内恢复且request_id不变、只执行一次、自身进程清理 | artifacts/task005/reboot-dispatch-recovery/summary.json |
| 模拟器单元/临时配置 | 39通过，新增固定位置10秒/路线1秒观测标记 | artifacts/task005/simulator-final.log |
| 契约工具回归 | 24通过，临时样例故意破坏会失败 | artifacts/task005/contract-tool-tests.log |
| 文档工具回归 | 23通过，历史保护、64规则、引用/追踪及反向关联仍有效 | artifacts/task005/doc-tool-tests.log |
| 有效契约回归 | 6通过；另验证10个受控正反样例、生成一致、Draft2020-12及有效OpenAPI | artifacts/task005/effective-tests-final.log及effective-check.log |
| 开发库迁移/受控初始化 | 重启后成功从V4升级V9，重复已有账号初始化不覆盖；进程正常结束 | artifacts/task005/reboot-dev-bootstrap.log |

最终文档检查通过：6模块、47功能需求、50追踪行、64迁移规则；具体链接数见本地检查日志；契约检查通过15份Schema、152样例、11个HTTP操作，有效契约另行通过。最终差异与保护检查结果另存本地final-audit.json；不把检查工具通过解释为应用或硬件通过。源码及产物指纹见本地source-fingerprints.json，基于本次提交前实际工作树；Git提交保存最终版本。

## I01～I14覆盖及观察结果

- I01～I02：合法双节点、Topic/归属/能力/版本/UTF-8/重复键/容量检查；队列256条非阻塞拒绝可观察。真实ENV发布姿态事件被拒绝，未入投影或收到业务确认。
- I03～I04：同键同内容/replayed变化去重，混合新事件与同键冲突整批回滚；真实提交后通过测试屏障抑制业务确认，节点仍保留待确认数据，取消屏障后重传获得后端精确确认。控制端不代发event_ack。
- I05：持久查询视图与DONE同事务，租约回收、错误owner及幂等受真实数据库测试覆盖；重启后两个并发工作线程领取同一条记录只有一个owner成功，效果只保存一次；未知未来算法不创建空DONE工作。
- I06～I07：旧boot/旧LWT不覆盖当前，独立位置标记、缺标记UNKNOWN、5秒/15秒边界；真实静默观察93.781秒后离线，恢复重新在线。未校时UTC不补造，位置保留MOCK。
- I08～I09：真实分页/历史/轨迹/CSV，四角色双箱体、诊断/导出/控制拒绝及字段白名单；并发相同key只产生一个操作，异请求409，未校时控制拒绝。
- I10：五类命令通过REST→Broker→模拟执行→回执→MySQL→操作查询；固定整体终态与迟到节点事实分别验证。短Broker中断后在原15秒内恢复同请求且只执行一次；本机冷启动超过15秒的进程重启场景正确EXPIRED，未补发或延长。另验证暂停发布超过有效期后不会执行。
- I11～I12：真实票据重用/并发消费/30秒过期、Origin、混合越权、JWT到期及撤权关闭；REST与WS外卖投影一致。慢消费者采用受控阻塞传输替身检查64条队列关闭，未冒充真实网络吞吐测试。
- I13～I14：真实后端重启保留历史与操作、Broker重启重新订阅、部分节点及新boot恢复；WS不保证重放，断线后REST补查。测试只清理smartbox_test、只管理自身进程，原服务与SDK保持。

测试故障屏障同时要求test配置、显式SMARTBOX_TEST_FAULTS与本地标记文件，没有HTTP入口；dev默认忽略。独立联调仅为降低测试冷启动成本使用进程级-XX:TieredStopAtLevel=1，10秒/15秒/90秒墙钟规则未更改，不据此声称启动或性能指标通过。

## 隔离、收尾及问题记录

项目Broker、后端、模拟节点与测试端已停止，1883/8080无本任务监听；旧Mosquitto服务仍Stopped，MySQL80仍Running。项目库、专用账号及忽略的本地配置保留；未操作SDK、接板或其他项目库。密码/JWT/ticket只在本地配置及检查进程内使用，不存入受控报告或Git。

保留全部中间失败记录：早期校准夹具增加非法参数、测试Clock替身重置、跨测试上下文工作器干扰均已修正；首次故障重启检查错误地等待已过期命令成功，改为分别验证有效期内恢复和过期不补发，没有放宽协议时限。电脑重启本身不作为板端掉电/设备KV测试。

## 未验收范围与下一步

无真实板端、GPIO、Flash、实际校准、完整环境/姿态/门融合/告警算法、网页或24小时稳定性结果。告警UNKNOWN且active/level=null，门UNKNOWN/INSUFFICIENT；不伪造alarm/box_state生产者。节点事件仍内存DEGRADED，主机boot文件不等于事件掉电可靠性。画像计数/已有告警接续规则由后续TASK-008实现，本期只验证模拟参数采用。

Q-01及Q-02本次范围已明确；Q-09参数采用和后续算法接续明确，Q-11历史检索明确而历史双源质量未决；其余Q项仍按原范围。整份TC及业务REQ不自动通过。下一项TASK-006，ITER-001仍进行中，不创建REL-001；本轮中文本地提交，不推送。
