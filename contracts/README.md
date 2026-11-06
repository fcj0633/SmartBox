# 接口契约

- [MQTT v1](mqtt/v1.md)：设备身份、能力、Topic、报文、时间、确认与错误。
- [MQTT 示例](mqtt/examples.json)：规范示例，P0 不代表实际链路通过。
- [REST / WebSocket v1](http/v1.md)：网页身份、查询、控制与推送。

TASK-001已提供JSON Schema/OpenAPI和静态检查。当前为接口准备交付，运行链路未实现；不兼容变更需要 CR、协议版本和兼容策略。

## 业务需求关联

设备接入与控制语义见[MOD-03](../docs/requirements/modules/MOD-03-设备接入与控制.md)，事件确认与恢复见[MOD-04](../docs/requirements/modules/MOD-04-事件管理与可靠性.md)，访问限制见[MOD-06](../docs/requirements/modules/MOD-06-用户与权限管理.md)。本次仅迁移需求引用，不改变字段、Topic、容量或执行规则。

## 机器契约与验证入口

- [覆盖及待决清单](接口覆盖与待决清单.md)、[机器样例清单](coverage.json)、[共享定义](common/v1.schema.json)。
- [MQTT Schema](mqtt/schemas/README.md)、[OpenAPI](http/openapi.yaml)、[WS Schema](http/ws/README.md)、[权限投影](http/权限投影与行为检查.md)。
- [检查工具](../tools/check_contracts.py)、[依赖锁定](../tools/requirements-contracts.txt)、[实际报告](../docs/testing/reports/TASK-001-契约验证报告.md)、[冻结清单](baseline-v1.json)。

安装后执行 `.venv/Scripts/python tools/check_contracts.py`；Ubuntu使用`.venv/bin/python`。检查仅离线结构/静态语义，不访问Broker、数据库或设备。
