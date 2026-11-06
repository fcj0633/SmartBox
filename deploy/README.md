# 部署配置入口

TASK-004 提供项目专用 Broker 配置/ACL 示例，TASK-003 提供数据库初始化与迁移，TASK-010 补完整部署手册。TASK-004已提供项目Broker运行工具；完整生产部署仍由TASK-010补齐。

默认 Windows 业务服务、Ubuntu 交叉编译，见 [部署拓扑](../docs/design/architecture/部署与网络拓扑.md)。实际秘密不入库，示例只用占位符；不覆盖既有全局服务配置。

## TASK-004项目Broker交付

[部署入口](mosquitto/README.md)提供准备/启动说明及2.0.14帧容量兼容参数；[TASK-004](../docs/development/tasks/TASK-004-MQTT与模拟节点.md)维护详细设计。实际使用独立进程127.0.0.1:1883，未启用旧Windows服务、修改全局配置/防火墙或接入真实开发板。五账号凭据/散列/运行配置仅本地保留，Git只保存模板。端口占用即拒绝，不结束其他进程。后端项目库沿用TASK-003，本轮MQTT开发未操作数据库。
