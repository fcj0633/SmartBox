# 演示部署数据

本目录只保留前后端演示所需的账号/模拟数据：

- [`demo/bootstrap.example.json`](demo/bootstrap.example.json)：本地演示管理员账号；
- [`demo/enterprise-v2.seed.example.json`](demo/enterprise-v2.seed.example.json)：企业、箱体、节点和模拟场景。

使用方式见[前后端启动](../docs/operations/前后端启动.md)。

`bootstrap.example.json` 中的 `admin/admin` 仅用于本地演示，部署到共享环境前必须替换密码。仓库不包含 EMQX、Mosquitto、SDK、真实板或测试配置。
