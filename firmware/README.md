# 固件工程入口

TASK-002已交付可独立构建ENV/POSE的C/CMSIS-RTOS2骨架，两个角色均已在现有Ubuntu SDK实际编译和链接；未烧录、未板端运行。当前只有确定性Mock输入、Mock输出、内存候选缓冲和禁用网络，不满足可靠事件存储与MQTT闭环。

| 目录 | 职责 |
|---|---|
| app | 启动门禁、故障收敛及采样/处理/网络三线程 |
| domain | 样本处理与按值快照，后续业务规则接入点 |
| drivers | Mock采集/输出与CMSIS平台适配；真实驱动留TASK-007 |
| services | 角色配置、时间、16项易失候选缓冲、禁用网络桩 |
| include | [公共类型](include/sb_types.h)、[端口](include/sb_ports.h)、[角色配置](include/sb_config.h) |
| build | [GN入口](build/BUILD.gn)及ENV/POSE模板；选择文件只在受控SDK副本生成 |

唯一编辑源是本仓库。详细设计集中在[TASK-002](../docs/development/tasks/TASK-002-双角色固件骨架.md)，总体职责见[固件设计](../docs/design/firmware/固件架构与SDK接入.md)。[编译指南](../docs/development/guides/编译烧录与联调流程.md)与[工具说明](../tools/README.md)提供参数化操作入口，[实际报告](../docs/testing/reports/TASK-002-固件骨架验证报告.md)记录版本、产物与恢复证据。

首次使用先默认预览同步差异。完整构建必须使用备份/归档/恢复流程，不能仅手动切换入口后执行会清除旧out的官方构建命令。原学习入口、产品输出和厂商构建状态已恢复；SmartBox受控源码副本保留。
