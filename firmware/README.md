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

## 当前骨架设计

详细设计集中在[TASK-002](../docs/development/tasks/TASK-002-双角色固件骨架.md)，包含计划文件、端口、三线程、角色配置、SDK同步和验收；当前由用户独立开发。P1驱动采用Mock、网络为禁用桩，不能代替真实传感器或持久化后上报。本文不复制详细设计。

本轮仅更新文档，没有创建固件源码、GN或同步工具；TASK-002仍待开始。
