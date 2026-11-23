# Effective Schema

`common.schema.json` 是后端 `ContractValidator` 在运行时读取的 JSON Schema。仓库不再维护独立的 HTTP/MQTT 协议文档、夹具或生成工具。

修改 schema 必须同步后端校验代码和实际接口行为；不要把未验证的字段写成已支持。
