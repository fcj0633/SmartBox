# 接口冻结与交接（TASK-001）

日期：2026-11-06；执行者：Codex。沿用原计划文件路径便于追踪；按用户明确“不用评审”，本次无人工评审或A/B签字。替代完成条件见[ADR-002](../../management/decisions/ADR-002-接口契约细化与自动检查.md)。

## 冻结范围

协议仍为v1，MQTT/REST/WS明确结构和编码按本次机器契约冻结。范围为8类设备消息、5类命令、11个HTTP操作及WS消息；不是业务规则新基线或应用发布。文件及SHA256见[baseline-v1.json](../../../contracts/baseline-v1.json)，来源与样例映射见[覆盖清单](../../../contracts/接口覆盖与待决清单.md)。

本次没有C类结构阻塞项；Q-01～Q-14保留，不冻结它们尚未决定的运行行为。改变已确认协议语义需CR，不能因TASK-001完成而自动获批。

## 后续交接

| 任务 | 采用的输入 | 开工与完成边界 |
|---|---|---|
| TASK-002，A | [设备Schema](../../../contracts/mqtt/schemas/README.md)、能力/时间/命令及回执、版本矩阵与下述SDK指纹 | 可开始双角色骨架；实际编译记录Linux工具版本，验证SDK接口可链接；不覆盖旧实验 |
| TASK-003，B | [版本矩阵](../environment/工具与依赖版本矩阵.md)、[OpenAPI](../../../contracts/http/openapi.yaml)、认证/错误/事件业务确认规则 | 可开始后端/数据库骨架；迁移、登录与真实MySQL检查在该任务执行 |
| TASK-004，B | 设备Topic、紧凑UTF-8样例、命令与精确确认、[coverage.json](../../../contracts/coverage.json) | 可准备Broker/模拟节点；集成仍依赖TASK-003，不能把静态context当成Broker鉴权 |
| TASK-005，B | 原始事件保存/确认、查询/聚合、REST与[WS](../../../contracts/http/ws/README.md)、角色投影 | 仍依赖TASK-003/004；在相关行为实现前确认Q-01/02/09/11；事务失败不得发stored |
| TASK-006，B | OpenAPI、WS与权限投影及字段质量、MOCK标识 | 可按契约准备页面；最终闭环仍需TASK-005，不把字段隐藏代替后端鉴权 |

P3监测算法、围栏配置和门融合、P4持久化及掉电、P5长期运行与交付仍按原任务推进；具体待决项负责人及解除条件见接口清单。

## SDK文件证据

本次从映射目录读取，未在Linux执行构建、未写SDK。bundle.json声明1.0.0、hi3861、risc-v；sample/BUILD.gn启用study_kernel_01_thread。以下仅定位所读文件，不代表整个SDK源码提交一致。

| SDK相对路径 | SHA256 |
|---|---|
| `bundle.json` | `e55074413a241049881169b86d5d2cc8db9977213d7ac03979557aa06c3c953f` |
| `build.py` | `e613c31286fc0b835cac92bf11fb37b0effed415a36e9bd8524ab93b9a4f16c5` |
| `build/lite/config/component/lite_component.gni` | `af119c23f395fef39c100a23ec7509acd0a703893713a00313df2b6ccd9dae92` |
| `applications/BearPi/BearPi-HM_Nano/sample/BUILD.gn` | `c4ad1e2a0caf3bea080b2e6cc7852842307356c25de9d8e72b7cbd9ade84ed71` |

已只读核对映射Z:/etc/os-release为Ubuntu 18.04.4 LTS；没有取得编译器执行版本，不能推断实际编译器位置或安装状态。TASK-002首次构建必须记录精确工具链版本，若不兼容另报问题，不由本次升级SDK。

## 验收证据与状态

实际工具检查见[报告](../../testing/reports/TASK-001-契约验证报告.md)。本次仅完成接口准备；业务需求未标已验收，运行和板端子项未执行。TASK-001状态为已完成（接口准备）；24项契约工具回归、23项文档工具回归、全部契约/样例及文档检查通过。
