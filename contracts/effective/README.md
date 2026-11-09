# 当前有效契约：怎样理解冻结基线与补充

这里是新开发者理解**当前接口**的入口。冻结v1保存接口准备阶段的原始定义，CR-003补充布防计时行为，CR-004增加当前闭环所需的明确规则/结构；使用时必须结合阅读，不能把冻结README的当时“链路未实现”当成当前系统状态。

当前后端/网页消费本目录有效定义，模拟器/真实固件按适用协议生成消息；基础真实双板已验证，但并非所有定义对应的业务算法均已实现。真实固件本期只执行output，GPS仍MOCK，门/告警判断器缺失时保持UNKNOWN。

## 1. 权威来源与关系

| 来源 | 回答什么 | 修改边界 |
|---|---|---|
| [冻结清单](../baseline-v1.json)、[冻结入口](../README.md) | 174份原始定义/样例及哈希，接口准备时的结构和静态规则 | 本轮及日常说明整理不回写；含三份受保护README |
| [MQTT文字v1](../mqtt/v1.md)、[HTTP/WS文字v1](../http/v1.md) | 原Topic、方向、时间/确认、路由和权限语义 | 与有效补充一起理解，不用README另造协议 |
| [CR-003](../../docs/management/changes/CR-003-布防有效期限计时起点.md)、[计时补充](../mqtt/sentry-timing-v1.md) | 3秒缓冲后实际ARMED才开始duration_s，0不自动到期 | 行为补充，不增加字段或改变版本，不解决其他重启未决项 |
| [CR-004](../../docs/management/changes/CR-004-闭环时间语义与未知状态.md) | 定位独立15秒、固定操作终态与迟到事实、画像采用、历史接收时间及UNKNOWN语义 | 只应用已批准差异，保留原未决问题 |
| 本目录机器定义 | 冻结v1＋CR-004的可执行结构 | 由同一工具生成并核对，不手工维护第二套大Schema |
| [业务需求](../../docs/requirements/README.md) | 业务为什么这样定义、谁可用、如何验收 | 技术补齐不能代替业务变更批准 |

冻结的[WS机器入口](../http/ws/README.md)、[MQTT Schema入口](../mqtt/schemas/README.md)保持原样；本页补充阅读路径，不修改其内容或哈希。

## 2. 开发时选择哪些文件

- HTTP操作看[有效OpenAPI](openapi.yaml)，公共类型看[common.schema.json](common.schema.json)。仍为现有11个HTTP操作，不新增管理路由。
- WS消息看[客户端](ws-client.schema.json)与[服务端](ws-server.schema.json)；握手票据、Origin、权限与控制帧行为仍需文字契约，Schema不能证明真实鉴权。
- MQTT Topic与八类基础结构看冻结定义，并同时遵守计时补充、CR-004及本公共定义的兼容扩展；固件/模拟器输出需运行适用检查。
- [fixtures.json](fixtures.json)保存有效定义正反样例；原冻结样例继续验证旧基线，两类检查互补而非互相替代。

定位`loc.observation`使用独立sample_seq和现有时间字段，同一次观测重复发送标记不变；缺标记的旧消息可保存，但位置新鲜度UNKNOWN、stale=true，不伪造位置采样UTC。环境/姿态默认5秒、位置15秒，心跳不能刷新旧观测。

告警`assessment=UNKNOWN`时active/level均null；本期无完整判断器，不产生假EVALUATED或alarm推送。门仍UNKNOWN/INSUFFICIENT，不伪造conclusion_id。历史按received_at＋唯一ID检索，设备原始时间/质量保持。命令整体终态固定，迟到执行事实独立保存；精确规则以CR和有效定义为准，不把本段摘要当额外规则。

## 3. 验证与生成必须区分

在项目虚拟环境从仓库根目录执行只读检查，不需要Broker或数据库：

```powershell
.venv/Scripts/python tools/check_contracts.py
.venv/Scripts/python tools/effective_contracts.py
```

[生成/验证工具](../../tools/effective_contracts.py)默认核对生成结果、Schema/OpenAPI和样例；`--write`才会写有效定义文件：

```powershell
.venv/Scripts/python tools/effective_contracts.py --write
```

生成不是日常启动步骤，也不能用来批准新接口。需要改接口时先查REQ与CR，区分业务变化和兼容技术调整，确定版本/兼容边界后再修改受控生成逻辑、有效样例及各消费端，并检查原基线保护。不能直接改冻结文件、`baseline-v1.json`或跳过校验来“更新哈希”。

## 4. 已实现与待实现

后端可靠保存/精确event_ack、Outbox事件视图、查询/权限、命令持久幂等和WS已通过软件验证；三项真实输出与采集见[双板报告](../../docs/testing/reports/TASK-007-双板接入验证报告.md)。定义存在不证明真实固件已实现profile/calibrate/sentry等，也不证明设备Flash可靠存储、完整融合/告警、离线抑制和长期稳定性已验收。开发需沿[追踪矩阵](../../docs/management/需求追踪矩阵.md)查适用任务、TC和实际报告。
