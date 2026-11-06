# MQTT与双模拟节点验收报告（TASK-004）

## 范围与结论

日期：2026-11-07；实际执行者：Codex；开发及最终验收责任人：用户。不代填人工签字。依据[TASK-004](../../development/tasks/TASK-004-MQTT与模拟节点.md)执行S1～S7，实现项目Broker、ENV/POSE独立进程、模拟命令、事件确认及真实MQTT场景。源基于78451e5，本报告所在提交确定最终交付版本；本地逐文件指纹定位实际测试输入。

主机模拟与真实MQTT切片完成，不代表真实设备/Flash、后端落库、90秒在线判定、命令聚合、WS/网页或整体业务需求通过。本轮未启动后端、连接数据库、修改SDK或接板，TC-007/012仅追加部分结果。

## 规则与交付

[CR-003](../../management/changes/CR-003-布防有效期限计时起点.md)记录用户确认：实际ARMED时刻开始duration_s，3秒缓冲不计入，0无自动到期。MOD-02两项规则及TC-004补充已同步，新[MQTT行为补充](../../../contracts/mqtt/sentry-timing-v1.md)不更改字段或v1版本，不回写174个冻结文件。Q-01～Q-14及原始/历史资料保持，尤其不解决重启期限恢复和迟到操作结果。

| 交付 | 实际内容 |
|---|---|
| Broker | Windows既有Mosquitto2.0.14，项目专用进程，127.0.0.1:1883，匿名禁止 |
| 权限 | ENV/POSE、后端、测试控制、只读观察五账号，逐Topic ACL，账号绑定clientId |
| 凭据 | 32随机字节生成的URL安全密码、本地散列转换，无密码命令行参数或Git凭据 |
| 模拟器 | Python3.10.8、paho-mqtt2.1.0、Callback VERSION2、单线程手动网络循环 |
| 状态 | 主机boot_id/revision独占锁与原子保存；请求缓存32、接收队列32、事件队列64 |
| 协议 | 八类消息，角色/能力/时间/容量校验，五类模拟命令与精确确认 |
| 入口 | [mqtt.py](../../../tools/mqtt.py)及[使用说明](../../../tools/simulator/README.md)，[Broker模板说明](../../../deploy/mosquitto/README.md) |

固定角色身份为BOX-0001-ENV/POSE、caps71/120。初始测试事件为DEVICE_FAULT、src=SIMULATOR、code=TEST_INPUT，仅用于链路输入，不代表真实设备故障。POSE定位标识MOCK，模拟校准不代表传感器精度。模拟已校时借主机UTC，不声称进行了SNTP。

## 实际检查

| 层次 | 结果 | 边界 |
|---|---|---|
| 单元/临时配置 | 37项通过 | 临时目录、假时钟、故障注入；不替代真实网络 |
| 真实Broker场景 | 55项检查通过，覆盖M01～M13 | Broker与8个先后启动的实际节点进程；不连接数据库 |
| 依赖 | pip check通过 | 只在项目.venv新增paho-mqtt2.1.0，没有升级系统Python或Broker |
| 契约 | 12个Schema、60个定义、152样例、11个HTTP操作检查通过 | 离线结构检查，不冒充后端鉴权 |
| 文档/历史/差异 | 收尾检查结果记录在本地final-checks.json | 文档回归只补齐新源码/模板输入，不削弱检查器 |

### M01～M13证据对应

| 场景 | 实际覆盖及证据层次 |
|---|---|
| M01 | 真实双角色身份/能力/字段；每节点实际观察两个30秒心跳间隔；时序JSON记录间隔和uptime |
| M02 | 真Broker拒绝匿名/错密、跨节点订阅/发布、节点下行发布、观察/后端上行伪造；合法消息对照和Broker日志；假clientId不能挤掉ENV |
| M03 | 真实无效样本、未校时、MOCK；后端专用账号向节点发送合法box_state，仅接收不补TTL |
| M04 | 真实非法UTF-8/JSON/重复键/NaN、未知版本/参数；1536允许/1537拒绝；2048帧进入应用再拒绝超payload、2049被Broker断开；Topic128/129及超4事件另有单元边界检查 |
| M05 | 五类模拟命令；3秒后ARMED才回执，完整有效时长、0无到期、主动撤防及输出到期关闭 |
| M06 | 真MQTT旧revision、能力不支持、过期和UNSYNCED拒绝；部分能力/画像恢复区间另有单元检查 |
| M07 | 先保存保留命令、后订阅实际接收；retained_command拒绝且actions不变，结束清理保留值 |
| M08 | 真实重复/冲突、ARMING重复不提前完成；单元验证32缓存和动作次数 |
| M09 | 真PUBACK不清理；错身份/boot不清理；精确/重复确认与非累计部分确认；确认均为测试输入 |
| M10 | 真实70条初始输入覆盖最旧6条、剩64，心跳损失可见；丢确认重传保留原时间，批次/字节上限单元检查 |
| M11 | 停本任务Broker再恢复，等待两个节点各自重订阅，boot不变，恢复后真实下行可执行 |
| M12 | 真实正常offline、异常退出Broker LWT、重启boot递增与锁释放；状态损坏/耗尽/写失败用临时单元负例 |
| M13 | 真实1883占用拒绝且不停止所有者；配置路径/链接/旧状态及失败清理用临时单元检查；最终自身进程停止、端口释放 |

单元源：[test_model.py](../../../tests/simulator/test_model.py)。真实场景源：[scenarios.py](../../../tools/simulator/scenarios.py)。统计以每次工具实际输出和本地summary为准，不把55项检查等同于55个业务TC。真实周期未缩短；静态边界、故障注入及主机限制在本表分别说明。

## 发现与修正

- Windows虚拟环境启动器PID与实际Python节点不同，导致早期就绪检查失败。集成工具改为直接管理实际Python进程，并仅在子进程路径中复用.venv库；没有修改全局环境。失败实例已清理。
- Mosquitto2.0.14接收端比较remaining_length+1，没有包含两字节长度编码。[该版本源码](https://raw.githubusercontent.com/eclipse-mosquitto/mosquitto/v2.0.14/lib/packet_mosq.c)与真实边界验证后，项目配置使用2046对应公开完整帧2048限制；业务阈值未变。工具核对版本，未经兼容复验的其他版本拒绝启动。
- JSON对象字段顺序不改变请求含义。幂等比较使用已校验对象内容，不因params键顺序不同将相同请求误判冲突；单元和真实发送场景覆盖重排输入。
- Windows状态文件被读取时可能短暂拒绝替换，曾导致节点退出。原子写增加最多200ms有界重试，持续失败仍拒绝；单元验证旧完整快照保留和临时文件清理，不通过截断文件绕过原子性。
- 恢复测试等待条件明确要求两个节点分别完成重连/订阅，允许既定退避及抖动时间；不能ENV恢复后仅凭短等待判定POSE失败。清理异常单独记录，不能进程最终消失就将异常正常化为成功。

这些修正均有失败轮次证据和后续实际复验，不删除或篡改失败日志。没有通过修改协议周期、放宽权限或降低验收阈值通过测试。

## 证据、复验与限制

本地忽略目录artifacts/mqtt/TASK-004/delivery/保存最终summary.json、Broker日志、节点状态/日志、ENV/POSE-heartbeat-timing.json、source-fingerprints.json和final-checks.json。unit.log在其上一级。verification-01/02、final、final-02等保存早期失败证据，verification-03和final-03保存中间通过证据；最终采用delivery结果，不以中间成功掩盖后续失败。

summary记录各断言的场景、结果、单调时间、启动节点数、清理异常、进程退出及端口释放；原始报文仅在检查进程内比较，摘要不写密码或完整敏感载荷。Broker日志记录Topic/QoS/长度/收发，节点快照记录身份/动作/队列/拒绝等可观察值。源指纹覆盖实际模拟器、入口、模板和测试，不包含秘密。

复验从仓库根目录执行：

```powershell
.venv/Scripts/python tools/mqtt.py unit
.venv/Scripts/python tools/mqtt.py integration
.venv/Scripts/python -m pip check
.venv/Scripts/python tools/check_contracts.py
python -m unittest discover -s tests -p test_check_docs.py -v
python tools/check_docs.py
git diff --check
```

integration前必须1883空闲，自建独立配置/身份，不复用日常配置。默认本地日常配置secrets/mqtt.local.json及身份状态保留，prepare重跑不覆盖或重置。查看凭据仅本机进行，不复制到命令行或报告。

事件、损失计数仅内存，storage=DEGRADED；身份文件不是Flash掉电能力。序号耗尽安全停止，未实现自动跨代次迁移。重启哨兵不恢复期限，box_state不启用未确认抑制，画像无告警算法。完整MOD-04持久化、服务端event_ack/outbox、90秒判离线、查询/命令结果、WS/CSV、网页和板端仍待适用后续任务。

本轮自启动Broker、节点和测试端已停止、1883释放，旧Windows Mosquitto服务保持Stopped，无其他项目服务或数据变更。仅中文本地提交，不推送；下一项TASK-005。

最终收尾实测：ENV心跳间隔[30.031, 30.047]秒，POSE[30.032, 30.078]秒。文档检查通过（6模块、47子需求、50追踪行、64迁移规则、2241链接），文档回归23项通过；174冻结哈希和50行追踪保持，待提交文件及最终日志未检出本机已知MQTT密码。完整收尾摘要见本地delivery/final-checks.json。
