# 固件工程入口

TASK-002已交付可独立构建ENV/POSE的C/CMSIS-RTOS2骨架，两个角色均已在现有Ubuntu SDK实际编译和链接；未烧录、未板端运行。这是TASK-002交付时的历史范围。TASK-007当前已实现真实驱动、输出、时间、网络与启动代次适配，并完成双角色实际编译链接；仍在等待烧录及真实板端验证，不满足完整事件持久可靠性验收。

| 目录 | 职责 |
|---|---|
| app | 启动门禁、故障收敛及采样/处理/网络三线程 |
| domain | 样本处理与按值快照，后续业务规则接入点 |
| drivers | 真实IA1/SC2/输出与SDK适配；Mock保留给显式组件测试 |
| services | 角色配置、校时/启动代次、报文与真实网络；16项候选缓冲不等于持久事件 |
| include | [公共类型](include/sb_types.h)、[端口](include/sb_ports.h)、[角色配置](include/sb_config.h) |
| build | [GN入口](build/BUILD.gn)及ENV/POSE模板；选择文件只在受控SDK副本生成 |

唯一编辑源是本仓库。详细设计集中在[TASK-002](../docs/development/tasks/TASK-002-双角色固件骨架.md)，总体职责见[固件设计](../docs/design/firmware/固件架构与SDK接入.md)。[编译指南](../docs/development/guides/编译烧录与联调流程.md)与[工具说明](../tools/README.md)提供参数化操作入口，[实际报告](../docs/testing/reports/TASK-002-固件骨架验证报告.md)记录版本、产物与恢复证据。

首次使用先默认预览同步差异。完整构建必须使用备份/归档/恢复流程，不能仅手动切换入口后执行会清除旧out的官方构建命令。原学习入口、产品输出和厂商构建状态已恢复；SmartBox受控源码副本保留。

## TASK-007真实硬件交付

生产GN已链接IA1/SC2真实采集、真实输出、Wi-Fi/MQTT、SNTP及最小启动代次适配；ENV/POSE仍由同一源码互斥构建。宿主Mock测试使用明确源文件清单，不混入生产固件。详细实现与结果见[TASK-007](../docs/development/tasks/TASK-007-双板实际采集与输出接入.md)和[实际报告](../docs/testing/reports/TASK-007-双板接入验证报告.md)。

真实板已完成采集→Broker→后端→MySQL→网页及三项output动作/自动关闭验证。真实固件只提供output执行，其他业务命令明确拒绝；GPS固定Mock、业务状态UNKNOWN，事件storage=DEGRADED。最小boot元数据不代表Flash事件可靠性。

启动前按[指南](../docs/development/guides/编译烧录与联调流程.md)核对本地配置、节点与当前网络，再使用[硬件工具](../tools/hardware.py)及既有受控同步/构建工具。生成的include/sb_local_config.h、区间账本、密码和产物均不提交。不得删除/回退boot区间账本或整片擦除后重用旧配置；需要重新配置时预留新区间并重新构建。COM6/COM7只代表本次实测分配，重新插拔后再核对。
