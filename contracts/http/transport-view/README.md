# 运输任务监测与访客接口

这是TASK-013的八个独立增量接口，不修改原冻结11接口及资产/运输契约。[OpenAPI](openapi.yaml)、[严格DTO结构](responses.schema.json)、[正反样例](fixtures.json)、[只读校验](../../../tools/check_transport_view_contracts.py)共同描述当前实现。

| 方法与路径（前缀/api/v1） | 权限 |
|---|---|
| GET /enterprise/riders | 登记企业管理员，仅演示白名单启用骑手ID/用户名 |
| GET /enterprise/transports | 登记企业管理员，本企业箱体最近50项任务 |
| GET /enterprise/transports/{id} | 本企业任务详情 |
| GET /enterprise/transports/{id}/monitoring | 本企业任务监测 |
| GET /rider/transports/{id}/monitoring | 骑手本人历史，不依赖当前分配或旧设备授权 |
| POST /rider/transports/{id}/access-code | 本人ACTIVE或结束后7天内任务，签发/重生成验证码 |
| POST /public/transport-access/verify | 编号＋验证码兑换单任务会话 |
| GET /public/transport-view | 专用X-Transport-View-Token头，只能读取令牌固定任务，不接受查询参数 |

监测范围为ACTIVE的[started_at,查询时间)、COMPLETED的[started_at,ended_at)，采样/事件必须SNTP且整个误差区间落入范围；相同设备时间按ID稳定排序，最多30采样和20事件，最近在前。UNSYNCED/跨边界不推测归属，不以接收时间代替。READY没有过程历史。已结束任务不读node_latest。温湿度保留有效性，环境assessment当前保持UNKNOWN，不把设备事件当作人工告警结案。访客任务DTO不含box_id/rider_id，事件不含原始证据或设备身份。

验证码为安全随机10位字母数字，BCrypt仅存散列；ACTIVE时valid_until为null（结束时再按结束后7天窗口限制），COMPLETED返回截止时间。原文仅当次响应显示，重生成使旧码和旧会话失效。访客token为32字节随机值的Base64URL无填充编码，只存SHA-256摘要；30分钟或结束后7天，以先到者为准。再次验证替换同任务旧会话。刷新/退出清空前端内存，不在URL或日志携带凭据。

错误使用现有error/request_id：401 INVALID_TRANSPORT_ACCESS不区分错误编号/码/失效；429 ACCESS_RATE_LIMITED为来源IP或编号＋来源IP在15分钟内5次失败后的限制，内存容量约4096项，单实例重启不保留计数，不信任客户端转发头。JWT角色错误403，越权任务404，READY/超过窗口签发409 INVALID_TASK_STATE，无效参数400，依赖故障503。

这是本机演示方案，不包含访客WS、多人会话、生产分布式限流或公网部署安全验收。完整复验见[指南](../../../docs/development/guides/三端运输业务启动与验收.md)。
