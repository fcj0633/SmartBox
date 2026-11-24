# 演示部署数据

本目录包含开箱即用所需的固定本地配置和演示数据：

| 文件 | 用途 |
|---|---|
| [`demo/mysql-init.sql`](demo/mysql-init.sql) | 创建 `smartbox_dev` 和两个固定 MySQL 账号 |
| [`demo/mqtt.cloud.json`](demo/mqtt.cloud.json) | 固定远程 MQTT Broker、后端账号和密码 |
| [`demo/emqxsl-ca.crt`](demo/emqxsl-ca.crt) | 远程 Broker 的公开 CA |
| [`demo/bootstrap.example.json`](demo/bootstrap.example.json) | 演示管理员和箱体身份 |
| [`demo/enterprise-v2.seed.example.json`](demo/enterprise-v2.seed.example.json) | 企业、5 个模拟箱体和历史数据 |
| [`backend.env.example`](backend.env.example) | 环境变量参考；应用不会自动加载它 |

默认后端只使用远程 MQTT TLS Broker：

```text
z789ffe0.ala.cn-hangzhou.emqxsl.cn:8883
```

没有本地 Mosquitto、1883 或 LAN Broker 回退。

> 本目录中的数据库/MQTT 配置是为开箱即用提交的公开演示凭据；任何能访问仓库的人都能使用。不要用于生产环境，部署到共享网络前必须更换。

使用方式见[前后端启动](../docs/operations/前后端启动.md)。
