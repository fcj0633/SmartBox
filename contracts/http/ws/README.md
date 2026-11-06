# WebSocket v1机器契约

[client.schema.json](client.schema.json)定义subscribe/unsubscribe；[server.schema.json](server.schema.json)定义五类推送并复用REST数据；[error.schema.json](error.schema.json)定义拒绝响应结构。

正反样例集中在[HTTP fixtures](../fixtures)，机器关联见[coverage.json](../../coverage.json)。握手/ws?ticket=...、Origin、票据一次性、会话过期及重连行为以[文字契约](../v1.md)与[权限检查说明](../权限投影与行为检查.md)为准。30秒ping/pong采用WebSocket控制帧，不定义JSON ping消息。
