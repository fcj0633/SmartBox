# 开发工具导航：检查、构建、运行与联调

## 新环境与完整部署

先读[完整部署指南](../docs/delivery/deployment/开发者完整部署指南.md)。以下为新增可复用入口；真实密码不使用命令行参数。

| 入口 | 作用 | 写入与限制 |
|---|---|---|
| [project.py](project.py) doctor / status | 环境检查、运行状态 | 只读；doctor 不等于完整版本兼容认证 |
| project.py init / db-init | 私有配置、首次项目库及账号 | secrets；显式输入管理员密码；不接管未知同名对象 |
| project.py start / stop | Broker、后端、节点、网页的开发生命周期 | artifacts 与本轮子进程；端口占用失败，不杀外来进程 |
| project.py recover-state | 强制中断后的死会话记录恢复 | 核对进程创建身份、子进程及端口，只归档记录，不结束进程 |
| [development_config.py](development_config.py) | 随机项目凭据、可移植初始化路径与建库保护 | 不覆盖既有秘密；DDL 按归属记录续办 |
| [firmware_ssh.py](firmware_ssh.py) / project.py firmware-build | SSH 复用三方同步算法与构建恢复 | SDK 项目副本、受控构建入口、备份与独立角色归档 |
| [sdk_bundle.py](sdk_bundle.py) inspect / export / verify / install / source | SDK 只读盘点、清理快照、校验、Linux 安装及提交源码包 | 独立新目录；本地交付包，不自动上传发布 |
| [deployment_smoke.py](deployment_smoke.py) | 验证统一启动、精确事件确认、输出控制和停止 | 用于显式隔离演练，不作为真实板验证 |
| [sdk_bundle_remote.py](sdk_bundle_remote.py) | Ubuntu Python 3.8 打包/解压工作器 | 校验清单、保留权限、拒绝越界与覆盖 |
| [deployment_rehearsal.py](deployment_rehearsal.py) | 新中文空格路径与独立 MySQL 的部署复验 | 显式新 artifacts 目录、13306；不停止共享 MySQL |
| [test_deployment.py](../tests/test_deployment.py) | 部署保护与错误路径测试 | 临时目录与替身，不操作真实 SDK 或数据库 |

`project.py install-deps` 只使用仓库 .venv 和已有锁文件；`build` 调用后端及网页工具；`check` 运行部署工具测试、文档与有效/冻结契约检查。真实集成仍需独立明确的测试配置与清理同意，不隐含在日常 check 中。


本目录把重复的开发操作组织为可复用入口。**先选择目标和影响范围，再运行工具**：只读契约检查不启动服务，构建会产生本地文件，集成测试可能清理项目测试数据，固件同步/构建会受控写入SDK副本。不能把某个工具返回成功等同于业务需求全部验收。

下列命令均从仓库根目录运行，Python依赖使用项目`.venv`；Windows可使用`.venv/Scripts/python`，Ubuntu环境使用对应虚拟环境路径。工具参数以各入口`--help`和已链接指南为准。真实连接值在忽略的本地配置中，不作为命令行密码或源码常量。

## 1. 项目检查与契约

| 工具 | 什么时候用／检查什么 | 写入与依赖边界 |
|---|---|---|
| [check_docs.py](check_docs.py) | Markdown链接/锚点、6模块47需求、50行追踪、历史保护和示例一致性 | 日常检查只读，不需要Git远程、服务或设备 |
| [check_contracts.py](check_contracts.py) | 冻结Schema/OpenAPI、覆盖、正反样例、容量/身份/精确确认等静态语义 | 只读，本地解析引用；不证明真鉴权、事务或动作 |
| [effective_contracts.py](effective_contracts.py) | 默认验证当前有效定义等于冻结基线＋CR-004；`--write`才重生成 | 默认只读；生成会写有效契约文件，协议修改需先有批准依据 |
| [requirements-contracts.txt](requirements-contracts.txt) | 契约检查依赖锁定 | 安装只进入项目虚拟环境，不升级系统Python |

```powershell
.venv/Scripts/python tools/check_docs.py
.venv/Scripts/python tools/check_contracts.py
.venv/Scripts/python tools/effective_contracts.py
```

权威来源见[有效契约导读](../contracts/effective/README.md)。不要改冻结文件或削弱检查规则来消除错误。工具自身回归及负例入口见[测试导读](../tests/README.md)。

## 2. 固件同步、编译与恢复

| 工具 | 使用场景与主要参数 | 影响范围和恢复要求 |
|---|---|---|
| [sync_firmware.py](sync_firmware.py) | `--sdk-root`、`--role ENV/POSE`；默认差异预览，`--apply`才应用 | 仅项目应用及受控构建入口；冲突/越界拒绝，未知文件不静默删 |
| [build_firmware.py](build_firmware.py) | `--host/--user/--key`、映射/远程SDK根；完整双角色真实构建 | 备份旧入口/产品输出/厂商状态→同步构建→独立归档→恢复；异常保留状态，不盲删备份 |
| [check_firmware.py](check_firmware.py) | 经SSH用已有Ubuntu GCC执行Mock/生命周期组件 | 写临时宿主目录和本地报告，不修改SDK，不证明板端调度 |
| [check_hardware.py](check_hardware.py) | `--sdk-root`、SSH及`--report`；真实核心、SDK适配和报文检查 | Ubuntu临时组件/本地证据；不是烧录或物理动作 |
| [firmware_support.py](firmware_support.py)、[firmware_remote.py](firmware_remote.py) | SSH和远程备份/同步/恢复的内部实现 | 给入口工具调用，不作为常驻服务 |

```text
python tools/sync_firmware.py --sdk-root SDK_MAPPING --role ENV
python tools/build_firmware.py --sdk-root SDK_MAPPING --remote-sdk SDK_LINUX --host HOST --user USER --key KEY_PATH
python tools/build_firmware.py --recover RUN_DIRECTORY --sdk-root SDK_MAPPING --remote-sdk SDK_LINUX --host HOST --user USER --key KEY_PATH
```

大写为占位符，先按本地连接说明替换；映射可读不等于可执行Ubuntu命令。预览不创建目录/锁/状态，应用时使用受控清单和三方指纹；SDK人工改动、未知同名目录、路径链接或并发占用需先定位。同步恢复入口使用`--restore-entry --apply`，同步中断恢复用`--recover-sync --apply`，不是删除整个SDK。

官方脚本会清空产品输出并改写厂商子树，不能绕过完整工具直接执行再假称环境未变。每角色立即保存源码快照、工具版本、日志、应用符号和镜像SHA256；恢复按字节及Unix权限核对。详见[固件导读](../firmware/README.md)、[编译烧录指南](../docs/development/guides/编译烧录与联调流程.md)。

## 3. MQTT模拟器与专用Broker

[mqtt.py](mqtt.py)支持`prepare/broker/node/unit/integration`，内部实现见[模拟器导读](simulator/README.md)。`prepare`创建或校验本地账号/ACL/身份状态，不重置已有状态；`broker`运行独立进程；`node --role ENV/POSE`运行一个节点；`unit`只跑临时状态测试；`integration`启动专用Broker、双节点和测试端，再清理自身进程。

运行配置默认在忽略的`secrets/mqtt.local.json`，依赖由[requirements-simulator.txt](requirements-simulator.txt)锁定。不接受命令行明文密码。真实Broker测试会使用1883，要求端口空闲、指定新证据目录；缺Broker失败，不静默跳过。测试控制端的event_ack不是数据库保存证据，主机内存事件队列不是Flash。

## 4. 后端构建、初始化与启动

[backend.py](backend.py)提供以下操作，详情见[后端导读](../backend/README.md)与[初始化指南](../docs/development/guides/后端数据库初始化与启动.md)：

| 操作 | 作用与副作用 |
|---|---|
| `unit` | Maven单元测试，不依赖项目秘密或启动完整业务服务 |
| `build --profile dev` | Maven验证/打包，写入target并从私有配置加载进程环境；不自动建库或启动业务服务 |
| `bootstrap --profile dev` | 执行已有JAR的非Web初始化，事务写账号/箱体/节点/授权；冲突拒绝，不重置密码 |
| `run --profile dev` | 普通运行，应用启动可执行待应用的Flyway迁移；按配置连接项目库 |
| `run ... --mqtt-config ...` | 额外从项目MQTT配置注入后端账号，启动真实接入；硬件模式还需`--hardware-mqtt` |
| `test --profile test --allow-test-reset` | 真MySQL集成，要求测试库校验及清理同意；不能并行操作同库 |
| `smoke --profile dev` | [backend_smoke.py](backend_smoke.py)启动自身JAR执行基础HTTP/Schema验证并收尾，可能使用显式初始化配置 |

配置默认取忽略的`secrets/backend.local.json`。运行账号不是root，结构迁移与应用权限分离；工具不为缺少配置猜密码，不替用户停止占用端口的其他进程。

## 5. 服务端真实联调

[live_integration.py](live_integration.py)组合真实Broker、后端、MySQL及两个独立模拟进程，检查接入保存、精确确认、Outbox、查询/导出、控制/WS及恢复。它不同于单独MQTT测试：确认来自事务提交后的后端，而非测试端伪造。

```powershell
.venv/Scripts/python tools/live_integration.py --allow-test-reset --run-dir artifacts/server/NEW-RUN
```

按[服务端指南](../docs/development/guides/服务端闭环启动与验收.md)准备test配置；此入口只操作校验过的smartbox_test及自身进程，需新目录和空闲端口。可用`--scenario dispatch-recovery`定向复验，但不替代完整主场景。故障屏障由受限测试配置启用，不新增测试HTTP接口，不停共享MySQL。

## 6. 网页与浏览器

[frontend.py](frontend.py)统一处理Windows兼容的Node/npm入口和前端环境隔离，避免本地秘密变成公开VITE变量。详见[前端导读](../frontend/README.md)及[网页指南](../docs/development/guides/网页启动与首轮验收.md)。

| 操作 | 作用／会写入或启动什么 |
|---|---|
| `install` | 锁文件安装node_modules，准备匹配Chromium到artifacts，不改日常浏览器 |
| `dev` | 启动自己的Vite，固定回环5173，退出清理自身进程 |
| `check` / `build` | 类型/单元/组件；或生成dist，不证明实际服务闭环 |
| `browser` | 隔离浏览器＋自身Vite，使用受控响应，产物存忽略目录 |
| `integration --profile test --allow-test-reset --run-dir ...` | [frontend_integration.py](frontend_integration.py)启动真实软件链路和浏览器；可能清理测试数据/临时撤权后恢复，使用test库和专用进程 |

定向`--scenario authorization`只补查权限/Schema。网页、服务端和Java集成不能同时使用smartbox_test。手动dev进程与自动集成也不要争用1883/8080/5173。

## 7. 真实硬件准备与串口

[hardware.py](hardware.py)的`check`只核对私有Wi-Fi字段；`prepare`校验指定网卡、只读核对已有代次并预留新区间，生成私有头文件和硬件Broker配置；`broker --run-dir ...`复用已准备配置。**仅启动Broker不要重复prepare，不能删除或回退代次账本。** 网络地址改变后按指南重新核验配置与固件，不能假设修改电脑文件就会改变已烧录内容。

[capture_serial.ps1](capture_serial.ps1)以115200只读指定COM，要求新artifacts输出和限定时长，不发送数据/自动复位；烧录工具需先退出释放端口。每次插拔重新识别目标，不能把COM号当角色身份。硬件模式显式使用一个LAN IPv4和回环监听，原本机安全限制保留，不自动关闭防火墙。

工具代码可验证协议和组件，但HiBurn选择文件、复位、断电接板、倾斜及实物观察仍需用户按[固件指南](../docs/development/guides/编译烧录与联调流程.md)完成，编译/烧录成功不等于实际动作已确认。

## 8. 结果与退出约定

证据写入新的[artifacts运行目录](../artifacts/README.md)，受控[报告](../docs/testing/reports)保存脱敏摘要和索引；成功返回0、失败非零，缺必需依赖不静默跳过。配置、身份状态、密码散列及日志不入Git。自动入口只终止自己持有的子进程，异常或中断后先核对PID/路径/端口和恢复清单，不按端口号随意杀进程或删除备份。
