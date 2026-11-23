# EMQX Cloud Serverless 接入

日常运行默认连接云端，显式 `--broker-profile local` 仍使用原本地 Mosquitto。云端与输入来源分别选择：`--mode server` 只开后端/网页，`hardware` 等待真实板，`simulator` 启动双模拟节点。浏览器通过后端 REST/WS 获取数据，不持有 MQTT 账号。

完整步骤见[云端部署指南](../../docs/delivery/deployment/EMQX云端部署与迁移.md)，配置结构见[示例](mqtt.cloud.example.json)，检查工具见[cloud_mqtt.py](../../tools/cloud_mqtt.py)。服务器根 CA [emqxsl-ca.crt](emqxsl-ca.crt)为公开证书，来源为 EMQX 官方 https://assets.emqx.com/data/emqxsl-ca.crt ，2026-11-23取得；不是客户端私钥。正式接入时应与自己部署 Overview 下载的 CA 核对，轮换时重新检查有效期与链，再同步本地配置及固件。

## 启动与检查

在仓库根目录使用项目虚拟环境：

```powershell
.venv\Scripts\python.exe tools/cloud_mqtt.py prepare --host YOUR_DEPLOYMENT_DOMAIN --deployment-id YOUR_DEPLOYMENT_ID
.venv\Scripts\python.exe tools/cloud_mqtt.py check
.venv\Scripts\python.exe tools/project.py start --mode server
```

`prepare`只生成本地忽略配置和控制台导入CSV，不在云端创建部署或账号。已有配置只验证、不轮换密码。先在控制台完成认证/授权导入及兜底拒绝，再运行连接检查。认证CSV含明文密码，导入成功后删除该CSV；长期凭据留在 `secrets/mqtt.cloud.local.json`，不发送到聊天或提交Git。

```powershell
.venv\Scripts\python.exe tools/cloud_mqtt.py smoke --allow-probes --report-dir artifacts/cloud/NEW-ACL-RUN
.venv\Scripts\python.exe tools/cloud_integration.py --allow-test --browser --report-dir artifacts/cloud/NEW-LOOP-RUN
```

两项检查前停止此部署的真实板、模拟节点和后端。ACL检查发布非业务标记，不允许运行中的后端消费；闭环检查使用真实云端、独立双模拟进程、smartbox_test及隔离18080/15173，不清库、不停共享MySQL。缺依赖、配置或断言失败返回非零。每轮使用新证据目录，检查结束清理自身客户端/进程，不删除云部署。

## 身份与边界

云端精确授权五个用户名；节点8条、后端16条、控制16条、观察10条允许规则，另加All Users `#` pubsub deny。观察端只订阅上行，不发布。不能只看PUBACK判断ACL转发成功。节点身份仍由Broker账号/ACL、Topic、注册归属与payload一致性共同约束。

本地Mosquitto的 `use_username_as_clientid` 不是Serverless默认保证；客户端必须使用唯一Client ID，不应把Client ID本身当作身份认证。真实板与模拟节点共用同一node_id时必须二选一，禁止多台开发电脑同时运行同一节点或同一后端Client ID。

云端默认启用TLS与域名验证，不提供跳过验证或公网1883降级。仅Broker远程化，后端和数据库仍需运行才能入库/展示/确认。Serverless免费额度与消费限额在控制台查看，额度耗尽、网络中断、账号或CA失效都会影响实时数据。
