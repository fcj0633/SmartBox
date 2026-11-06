# 固件架构与 SDK 接入

关联 REQ-M01-001、REQ-M01-002、REQ-M01-003、REQ-M01-004、REQ-M01-005、REQ-M01-006、REQ-M01-007、REQ-M01-008、REQ-M01-009、REQ-M01-010、REQ-M01-011、REQ-M02-001、REQ-M02-002、REQ-M02-003、REQ-M02-004、REQ-M02-005、REQ-M02-006、REQ-M03-006、REQ-M01-012、REQ-M01-013、REQ-M01-014、REQ-M04-001、REQ-M04-002、REQ-M04-003、REQ-M04-004、REQ-M04-005、REQ-M04-006、REQ-M04-007、REQ-M04-008、REQ-M03-001、REQ-M03-002、REQ-M03-003、REQ-M03-004、REQ-M03-005、REQ-M03-006、REQ-M03-007、REQ-M03-008；TASK-002、007～009。

app 装配任务和角色，domain承接规则入口并在后续阶段实现纯规则，drivers先提供Mock后接真实板件，services包含事件/网络/配置/GPS，include定义公共类型，build存GN接入。

SensorFrame 内部包含有效位、单位、boot_id、uptime 和可空 UTC；事件身份完整。gps_get_fix 隔离 Mock 和未来真实驱动，不让 SDK 全局变量穿透到业务层。

采集/规则按周期执行，有界事件送存储任务，网络只发送已承诺持久事件。队列满报告背压和损失，不阻塞姿态采样。输出默认关闭并使用单调时间定时关闭。

P1 先实现角色骨架、模拟 HAL 和接口，不承诺 KV 持久可靠。TASK-009 依据 KV 容量、擦写和掉电实测，经 ADR 确定布局与恢复方案，禁止使用未知 Flash 地址。

SDK sample 下新增 logistics_box；一次只构建一个角色，构建后立即归档角色产物。保留当前 study_kernel_01_thread 选择和恢复方式，不删除旧实验。P0 不改入口。

软件规则可用模拟采样验证；旧 CMSIS 接口需实际链接，不以头文件声明作为可用证据。

## 业务模块与需求映射

六模块是跨端业务边界，不改变本文既有技术层次、包划分、部署或数据模型。节点在线归MOD-03，箱体综合状态归MOD-05；防护业务归MOD-02，通用通信归MOD-03，事件可靠性归MOD-04，授权归MOD-06。

| MOD | 需求 |
|---|---|
| MOD-01 | [REQ-M01-001](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-001) |
| MOD-01 | [REQ-M01-002](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-002) |
| MOD-01 | [REQ-M01-003](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-003) |
| MOD-01 | [REQ-M01-004](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-004) |
| MOD-01 | [REQ-M01-005](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-005) |
| MOD-01 | [REQ-M01-006](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-006) |
| MOD-01 | [REQ-M01-007](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-007) |
| MOD-01 | [REQ-M01-008](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-008) |
| MOD-01 | [REQ-M01-009](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-009) |
| MOD-01 | [REQ-M01-012](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-012) |
| MOD-01 | [REQ-M01-013](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-013) |
| MOD-01 | [REQ-M01-014](../../requirements/modules/MOD-01-物流箱运行监测.md#req-m01-014) |
| MOD-02 | [REQ-M02-001](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-001) |
| MOD-02 | [REQ-M02-003](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-003) |
| MOD-02 | [REQ-M02-004](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-004) |
| MOD-02 | [REQ-M02-005](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-005) |
| MOD-02 | [REQ-M02-006](../../requirements/modules/MOD-02-安全防护与哨兵.md#req-m02-006) |
| MOD-03 | [REQ-M03-002](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-002) |
| MOD-03 | [REQ-M03-003](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-003) |
| MOD-03 | [REQ-M03-004](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-004) |
| MOD-03 | [REQ-M03-005](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-005) |
| MOD-03 | [REQ-M03-006](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-006) |
| MOD-03 | [REQ-M03-007](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-007) |
| MOD-03 | [REQ-M03-008](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-008) |
| MOD-04 | [REQ-M04-001](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-001) |
| MOD-04 | [REQ-M04-002](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-002) |
| MOD-04 | [REQ-M04-005](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-005) |
| MOD-04 | [REQ-M04-006](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-006) |
| MOD-04 | [REQ-M04-007](../../requirements/modules/MOD-04-事件管理与可靠性.md#req-m04-007) |

全局质量：[NFR-001](../../requirements/quality/性能与资源约束.md)、[NFR-002](../../requirements/quality/可靠性与数据完整性.md)。

## TASK-002详细设计入口

骨架目录、公共接口、三线程/工作队列、启动失败处理、互斥角色GN、默认预览同步、构建归档及入口恢复统一维护在[TASK-002](../../development/tasks/TASK-002-双角色固件骨架.md)，本文不重复详细参数。当前由用户独立开发；本轮仅完善设计，尚无源码或SDK构建证据。P1骨架使用Mock及禁用网络桩，不声称已有持久事件或真实MQTT发送；前文持久化后发送为后续目标。
