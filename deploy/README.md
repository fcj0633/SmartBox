# 部署配置入口

TASK-004 提供项目专用 Broker 配置/ACL 示例，TASK-003 提供数据库初始化与迁移，TASK-010 补完整部署手册。本轮无可执行部署脚本。

默认 Windows 业务服务、Ubuntu 交叉编译，见 [部署拓扑](../docs/design/architecture/部署与网络拓扑.md)。实际秘密不入库，示例只用占位符；不覆盖既有全局服务配置。

## TASK-004项目Broker设计

详细配置和权限边界统一见[TASK-004](../docs/development/tasks/TASK-004-MQTT与模拟节点.md)。计划使用Windows现有Mosquitto的项目专用进程，默认127.0.0.1:1883；不启动/修改旧Windows服务、全局配置或防火墙。受控模板拟放deploy/mosquitto/，真实密码/散列/运行配置保留本地忽略目录，本轮未创建。端口占用即报告，不结束其他进程或悄悄换端口。TASK-003后端与项目库已实际交付，启动见[指南](../docs/development/guides/后端数据库初始化与启动.md)，不将本页历史规划理解为后端仍未创建。
