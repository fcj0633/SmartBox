# 当前有效契约：冻结v1与CR-004补充

业务决定见[CR-004](../../docs/management/changes/CR-004-闭环时间语义与未知状态.md)。当前HTTP/WS消费[有效OpenAPI](openapi.yaml)、[公共定义](common.schema.json)、[客户端](ws-client.schema.json)与[服务端](ws-server.schema.json)；原冻结文件作为历史输入保留，不回写。

[正反样例](fixtures.json)包含10项可执行输入与预期结果，由同一工具生成和验证。

用项目Python运行`tools/effective_contracts.py --write`生成，用不带参数的同一入口验证。工具仅应用CR-004明确差异，不复制手工维护另一套规则；原合同检查仍验证旧基线，新检查同时验证本有效版本。

## 明确变化

- Alarm增加assessment；UNKNOWN时active/level均null，EVALUATED才返回布尔active。本期无判断器，不产生EVALUATED或虚假alarm推送。
- 采样组增加freshness：UNKNOWN表示缺数据/独立观测标记/当前代次依据；stale为true。FRESH/STALE分别对应明确年龄判断。
- loc.observation兼容扩展，含sample_seq、ts、ts_src、uptime_ms、time_uncertainty_ms；时间关系和uint32范围与既有定义一致。序号、观测时间和内容在同一次采样重复发送时不变。位置15秒、其他5秒；同boot单调时间计算年龄并保守考虑首次接收经过时长，心跳不刷新采样。
- 旧消息可保存，缺位置标记不构造位置发生UTC。历史统一received_at+唯一ID顺序，observed_at保留真实设备时间或null；轨迹保持MOCK。
- 操作终态固定，迟到的逐节点执行事实与当前状态独立。原15秒命令时效、布撤防10秒截止及ARMED后起算duration_s不变。

## 未接入能力

门UNKNOWN/INSUFFICIENT；完整告警UNKNOWN。box_state只有真实已实现结论才发布，不为UNKNOWN伪造conclusion_id；完整融合、告警、离线抑制及历史双源质量仍由后续任务完成。
