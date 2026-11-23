# 演示部署数据

本目录只保留前后端演示所需的账号/模拟数据：

- [`demo/bootstrap.example.json`](demo/bootstrap.example.json)：本地演示管理员账号；
- [`demo/enterprise-v2.seed.example.json`](demo/enterprise-v2.seed.example.json)：企业、箱体、节点和模拟场景；
- [`backend.env.example`](backend.env.example)：后端环境变量占位。

使用方式见[前后端启动](../docs/operations/前后端启动.md)。

## 演示箱体

| 箱体 | 类型 | 场景 |
|---|---|---|
| `BOX-0001` | 固定实物 | 企业资产 |
| `BOX-SIM-01` | 模拟 | normal |
| `BOX-SIM-02` | 模拟 | env_high |
| `BOX-SIM-03` | 模拟 | offline |
| `BOX-SIM-04` | 模拟 | impact |
| `BOX-SIM-05` | 模拟 | completed（历史任务） |

新数据库完成初始化和种子加载后，总览应是 **1 个实物 + 5 个模拟箱体 = 6 个**。多次导入旧测试数据会累积更多箱体；重建数据库即可恢复。

`bootstrap.example.json` 中的 `admin/admin` 仅用于本地演示，部署到共享环境前必须替换密码。仓库不包含 EMQX、Mosquitto、SDK、真实板或测试配置。
