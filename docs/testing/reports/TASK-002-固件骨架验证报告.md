# 固件骨架验证报告（TASK-002）

日期：2026-11-06（Asia/Shanghai）。实际执行者：Codex；任务负责人及最终验收者：用户。按用户授权执行，不代填人工签字。任务状态：**已完成（软件完成，待板端验证）**。

## 交付结论与范围

[任务设计](../../development/tasks/TASK-002-双角色固件骨架.md)的S1～S7已执行，交付[固件骨架](../../../firmware/README.md)、[同步](../../../tools/sync_firmware.py)、[组件检查](../../../tools/check_firmware.py)、[构建](../../../tools/build_firmware.py)与工具测试。ENV/POSE分别实际编译和链接，最终map与ELF含应用符号及对应节点身份，两个角色镜像不同并独立归档。原学习入口、产品输出及厂商构建状态已按完整哈希和Unix权限恢复。

本任务只实现Mock输入/输出、时间端口、线程门禁、易失工作缓冲、快照和禁用网络。没有真实驱动、GPIO、Wi-Fi/MQTT、业务告警/融合/哨兵、校时、持久boot_id或Flash可靠事件；不将8帧/16项工作数据解释成至少64条持久化未确认事件。

## S1～S7实际记录

| 步骤 | 本次动作与产出 | 实际结果 |
|---|---|---|
| S1 | 经专用密钥SSH核对工具与SDK入口；核对CMSIS实现和旧输出；使用过程内PATH | 沿用原环境，无安装/升级；映射与SSH通过受控副本指纹和入口哈希交叉核验 |
| S2 | 公共类型、单位、错误码、集中身份能力配置和角色宏互斥 | 两合法角色构建成功；缺失/双角色/非法值的C编译负例拒绝 |
| S3 | 固定序列Mock、质量标记、默认关闭及期限输出、tick换算/回绕和UNSYNCED | 组件检查通过；不访问真实外设，不伪造UTC、校准或测量成绩 |
| S4 | 三线程各4KB初始栈、8帧队列、16项候选、快照、禁用网络、启动门禁/失败收敛 | 可控CMSIS替身覆盖创建失败/收敛超时；不是实际RTOS调度或RAM峰值测量 |
| S5 | GN角色模板、三方指纹同步、只读预览、冲突/受控删除、入口恢复及备份工作器 | 13项同步与8项恢复回归通过，负例仅用临时SDK样例 |
| S6 | ENV构建即归档，再POSE构建即归档；核验应用archive、最终map/ELF、节点身份与新bin | 两角色退出码0，真实产物与SHA256如下；原环境恢复通过 |
| S7 | 本报告、任务/迭代/进度、工具/固件入口、版本事实及TC准备记录同步 | 状态不升级REQ或整份TC；无发布、烧录或业务闭环通过声明 |

## 环境与SDK定位

沿用Ubuntu现有Python 3.8.0、宿主GCC 7.5.0、GN 1523（5bd8e26b）、Ninja 1.9.0、SCons 3.0.1、RISC-V GCC 7.3.0及GNU ld 2.31.1。实际命令为SDK根目录下`python build.py BearPi-HM_Nano`，仅在构建子进程增加已有工具PATH。SCons原环境有SyntaxWarning；没有升级以隐藏警告。

SDK没有可验证的完整Git提交信息，继续使用文件定位指纹：

| 文件 | SHA256 |
|---|---|
| bundle.json | `e55074413a241049881169b86d5d2cc8db9977213d7ac03979557aa06c3c953f` |
| build.py | `e613c31286fc0b835cac92bf11fb37b0effed415a36e9bd8524ab93b9a4f16c5` |
| 原sample/BUILD.gn | `c4ad1e2a0caf3bea080b2e6cc7852842307356c25de9d8e72b7cbd9ade84ed71` |

核对CMSIS源码发现osThreadExit为空、调度锁恢复语义存在嵌套差异；骨架采用线程函数返回及只释放本次获取的锁，不使用osMessageQueueReset。核对kernel/liteos_m/kernel/base/core/los_task.c的OsTaskEntry调用线程函数返回后执行LOS_TaskDelete；实际板端线程退出仍未测。正式GN未链接任何宿主CMSIS替身。

## 组件与工具测试

| 检查 | 实际结果与边界 |
|---|---|
| Linux C组件 | 两角色各9组运行检查，加4个非法配置编译检查，共22组通过 |
| 配置/Mock/时间 | 身份、能力、可复现输入、无效标记、角色不支持、UNSYNCED、零频率、向上取整、溢出、32位tick回绕和嵌套锁通过 |
| 快照/工作缓冲/输出 | 快照按值、无效状态保留、8帧背压及计数、16项满后拒绝且保留顺序、输出默认关闭及期限、错误能力拒绝通过 |
| 启动与生命周期 | 时钟/队列及第1/2/3线程创建失败、READY前门禁、协作退出后才释放、无法收敛时保留资源与复位标记通过 |
| 网络桩 | 返回未实现，不伪造保存/发送成功；网络线程不消费候选缓冲通过 |
| 同步工具 | 13项，Windows及Ubuntu均通过，含预览零写入、三方冲突、链接/重叠/状态越界、受控删除、未知文件保留、入口再修改与崩溃日志 |
| 构建恢复工具 | 8项，Windows及Ubuntu均通过，含完整备份、校验、互斥、并发/损坏/归属未知拒绝、内容和Unix权限恢复 |
| 契约检查 | 12份Schema、60个定义、152个样例（69正/83反）、11个HTTP操作通过；未改174个冻结文件 |
| 文档检查 | 六模块47项需求、50行追踪、64条规则迁移及链接通过；本机最终99份Markdown、2110个链接（含本机忽略说明） |
| 文档工具回归 | 23项全部通过；只补齐临时样例的firmware和tests/firmware输入，不削弱检查规则 |
| 差异/历史保护 | git diff --check通过；174个冻结文件字节一致；原始输入、历史记录、原TC场景及追踪表逐项比对保持不变 |

上述“组”不是业务TC数量。组件测试通过不代表真实CMSIS调度通过；TC-007/012仅登记已完成部分，整份集成用例仍未执行。

## 实际构建与产物

最终完整运行标识：`20261106-214728`。构建时基础提交：`a66774bbdf3dfad440e93bbf16b117f3127ed4ef`，工作区有本次新增/修改；为保留新增文件，除source.diff外另归档source-files.tar及逐文件指纹。最终源码由本轮提交固定，不能仅按基础提交复现新固件。

| 角色 | 节点 | 能力 | 构建退出码 | 最终allinone.bin SHA256 |
|---|---|---|---|---|
| ENV | BOX-0001-ENV | 71 | 0 | `2f6ecb033b023bf8ad9d415fe759f468109df4affccb82ff658186b9c4498a0f` |
| POSE | BOX-0001-POSE | 120 | 0 | `b00ac54a0a6cbd95cfad1ba13c1ba18cb612f104c48c5992994d4dd0e4872042` |

两个角色分别具有build.log、manifest.json、product.tar、product/Hi3861_wifiiot_app_allinone.bin、最终map、libsmartbox.a及linked/Hi3861_wifiiot_app.out。脚本核验SmartBoxMain、sb_app_start、sb_config_get、sb_sensor_read、sb_clock_get、sb_network_poll等符号进入应用archive与最终链接，最终ELF含对应BOX-0001-ENV或BOX-0001-POSE，不能只将同一bin改名。

源码同步指纹（包含所选角色生成配置）：

- ENV：`34d6fdecc428bb8b27906ec46bc8f690e4281e41389ffa09de5be23bd9dc04b1`。
- POSE：`b9cad88c52c59e6d993d89acb0665f79cb53783e027f90a9a23377defe7c667c`。

本次源码快照tar SHA256：`8ee310229329914f613584eca9b8179412a3171d3585e80ee98736f2b1be7e30`。每个角色manifest还记录起止时间、版本、命令、退出码、归档/镜像/map哈希及同步指纹。原始产物与日志均Git忽略。

### 资源记录的适用范围

对已归档的同哈希镜像对应应用对象执行RISC-V size/nm检查，两个角色libsmartbox.a各对象合计text=6322字节、data=52字节、bss=1282字节（仅对象级统计，链接可回收/对齐）。实际交叉编译sizeof：SbSensorFrame=72、SbEventCandidate=56、SbSnapshot=232字节。8帧有效载荷576字节，16项候选有效载荷896字节，RTOS队列元数据和三线程各4096字节栈均另计。

这些数据不能证明NFR应用增量RAM或Flash指标；没有实际栈水位、运行峰值、SDK基线差值与24小时结果。对象大小证据在本地20261106-214255/object-sizes.log，两个该轮镜像哈希与最终轮相同。

## 原学习环境恢复

保护范围包含原sample/BUILD.gn、out/BearPi-HM_Nano及vendor/hisi/hi3861/hi3861。官方脚本会删除原out并重写厂商库/中间文件，故使用保留Unix权限的tar备份，逐个读取归档内容核对后才构建；本地副本另核对SHA256。

原备份记录18,626个清单项、16,591个文件、189,177,376个文件内容字节。最终恢复结果：

- 原入口字节与0755权限恢复，启用study_kernel_01_thread。
- 原product输出245个文件恢复。
- 厂商子树、文件内容、目录/文件Unix权限与完整原清单一致。
- 未删除学习实验；受控logistics_box源码副本和同步状态保留，默认入口恢复为学习项目。
- 构建锁释放，备份和恢复日志保留；恢复未通过时不标任务完成。

首次恢复检测到Samba原子替换令入口权限由0755变为0664，虽然原内容已恢复仍被冲突检查拦下。确认只有该受控入口差异后，修正规则：只接受原始内容与原始/最后写入两种已记录权限组合，最后由Unix tar恢复0755；补充回归，执行恢复，再完整重跑流程通过。没有放宽其他文件冲突检查。失败尝试的日志和备份保留。

硬中断导致输出归属未知、恢复中断、并发修改或损坏备份时，工具拒绝盲目覆盖并保留证据，需先人工核查状态。这是明确的恢复限制；协作锁也不能代替避免其他终端同时构建。

## 本地证据索引

以下均相对仓库根目录，仅本机保存，不含连接密码或私钥：

| 位置 | 内容 |
|---|---|
| `artifacts/firmware/TASK-002/20261106-214728/run.json` | 最终运行、基础提交、源码快照哈希、双角色结果和恢复摘要 |
| `artifacts/firmware/TASK-002/20261106-214728/source-files.tar`及source-files.json | 包含新增源码的构建输入快照和SHA256清单 |
| `artifacts/firmware/TASK-002/20261106-214728/original-sdk.tar` | 原SDK输出/厂商子树及入口的Unix归档，保持私有，不提交 |
| `artifacts/firmware/TASK-002/20261106-214728/recovery-state.json` | 原始/恢复完整指纹、权限与执行阶段 |
| `artifacts/firmware/TASK-002/20261106-214728/ENV/`、`artifacts/firmware/TASK-002/20261106-214728/POSE/` | 各角色完整日志、产物、map/ELF及manifest |
| artifacts/firmware/TASK-002/components.log | Ubuntu GCC组件检查结果 |
| artifacts/firmware/TASK-002/python-tools-ubuntu.log | Ubuntu工具回归结果 |

## 未执行项与下一步

未烧录/接板，未验证真实50Hz、RTOS时序/退出、IA1/SC2、GPIO、资源峰值、联网/重连、命令执行或Flash可靠性；不因官方例程通过而关闭这些项目风险。Q-01～Q-14保持未决，不改变任何业务阈值、权限、身份、确认或容量规则。

按原依赖启动TASK-003后端与数据库骨架，随后TASK-004/005/006完成实际Broker、服务、MySQL与网页闭环。TASK-007替换Mock接入真实驱动并执行板端验证，TASK-009再实现持久事件及补传。本轮不启动服务、不建库、不烧录、不推送。
