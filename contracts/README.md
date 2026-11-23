# 运行时契约

本目录只保留后端启动时实际加载的 effective schema：

- [`effective/common.schema.json`](effective/common.schema.json)：后端 `ContractValidator` 读取的 JSON Schema；
- [`effective/README.md`](effective/README.md)：最小说明。

HTTP/MQTT 协议夹具、OpenAPI、历史冻结定义和契约校验工具已不在本仓库维护。前后端 API 以代码和运行时行为为准；真实设备/生产协议不在本仓库范围。
