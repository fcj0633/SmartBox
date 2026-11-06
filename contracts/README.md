# 接口契约

- [MQTT v1](mqtt/v1.md)：设备身份、能力、Topic、报文、时间、确认与错误。
- [MQTT 示例](mqtt/examples.json)：规范示例，P0 不代表实际链路通过。
- [REST / WebSocket v1](http/v1.md)：网页身份、查询、控制与推送。

TASK-001 补齐 JSON Schema/OpenAPI。当前为首版设计；不兼容变更需要 CR、协议版本和兼容策略。

## 业务需求关联

设备接入与控制语义见[MOD-03](../docs/requirements/modules/MOD-03-设备接入与控制.md)，事件确认与恢复见[MOD-04](../docs/requirements/modules/MOD-04-事件管理与可靠性.md)，访问限制见[MOD-06](../docs/requirements/modules/MOD-06-用户与权限管理.md)。本次仅迁移需求引用，不改变字段、Topic、容量或执行规则。
