# MQTT v1 Schema入口

八个同名Schema对应[MQTT文字契约](../v1.md)Topic表，复用[共享定义](../../common/v1.schema.json)。采用Draft 2020-12，$id使用smartbox.invalid仅作稳定标识，检查工具不会联网解析。

正反样例在[fixtures](../fixtures)，每项定义、上下文和预期错误位置见[coverage.json](../../coverage.json)。MQTT样例使用紧凑UTF-8编码，容量按文件实际字节检查，包含1536/1537边界。未知字段可忽略，非法已知字段仍拒绝。

PUBACK、精确业务确认、角色身份、时间、消息容量和命令执行语义见文字契约；Schema不能验证数据库是否提交或硬件是否执行。新增字段编码依据[ADR-002](../../../docs/management/decisions/ADR-002-接口契约细化与自动检查.md)。
