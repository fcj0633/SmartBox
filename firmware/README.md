# 固件工程入口

当前只有目录与设计，尚未创建可编译项目。TASK-002 实现骨架，TASK-007 接入真实采集，TASK-009 实现持久化。

| 目录 | 职责 |
|---|---|
| app | ENV/POSE角色装配、任务入口 |
| domain | 可用模拟输入测试的业务规则 |
| drivers | IA1、SC2硬件适配 |
| services | 网络、事件存储、配置、GPS HAL |
| include | 公共类型及单位 |
| build | 项目GN配置和SDK接入 |

本目录是源码唯一编辑源，SDK仅编译集成。接入流程见 [固件设计](../docs/design/firmware/固件架构与SDK接入.md) 和 [编译烧录](../docs/development/guides/编译烧录与联调流程.md)。
