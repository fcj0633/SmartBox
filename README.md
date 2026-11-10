# SmartBox：物流箱监测与控制原型

SmartBox 是一个基于 **两块 BearPi-HM Nano、Java 后端、MySQL、Vue 和 MQTT** 的物流箱状态监测与控制原型。项目把环境、姿态等设备观测汇总到服务端，再向不同角色展示授权信息，并提供远程输出控制。

如果你第一次接触这个仓库，建议按本文顺序阅读：先理解项目和两块开发板，再看系统如何协作，最后选择软件模拟或真实双板部署。**不需要一开始就阅读所有源码，也不需要先安装开发板 SDK。**

> **当前状态：**软件与模拟闭环已完成，基础真实双板采集和输出闭环已验证；完整业务算法、设备侧 Flash 事件可靠性和最终系统验收仍在后续阶段。项目目前是可演示、可继续开发的原型，不是可直接投入真实物流运营的产品。

- 仓库：[fcj0633/SmartBox](https://github.com/fcj0633/SmartBox)
- 第一次部署：[开发者完整部署指南](docs/delivery/deployment/开发者完整部署指南.md)
- 开发板 SDK：[SDK 官网下载与 SmartBox 接入](docs/development/guides/SDK官网下载与接入.md)
- 当前进度：[项目进度](docs/management/当前进度.md) · [路线图](docs/management/路线图.md)

---

## 1. SmartBox 要解决什么问题？

在物流运输和外卖配送场景中，箱体内部的环境和运输状态不容易被外部人员持续了解。SmartBox 希望通过设备采集和服务端管理，形成一条可查看、可追溯的状态链路：

1. 设备采集箱体周围的环境和运动数据。
2. 设备通过 Wi-Fi 和 MQTT 把观测发送到服务端。
3. 服务端校验设备身份和数据格式，将观测及相关业务记录保存到 MySQL。
4. 网页根据登录用户的角色和箱体授权范围，展示对应信息。
5. 有权限的用户可以提交控制操作，并查看设备实际返回的执行结果。

项目同时考虑物流和外卖两类使用场景，例如物流寄收件人查看授权箱体状态、物流操作者管理设备，以及外卖用户和骑手查看各自有权访问的信息。

**当前实现边界：**项目使用两块开发板代表一个逻辑物流箱，尚未制作真实物流箱体；没有真实门磁、真实 GPS 或完整防盗判断能力。光照变化和开发板转动可用于演示输入现象，但不能据此宣称已经准确识别真实箱门开合、整箱翻倒或运输安全。

## 2. 两块开发板分别负责什么？

项目使用两块 BearPi-HM Nano。两块板运行同一套固件源码，通过 ENV / POSE 两种编译角色生成不同固件。

<details>
<summary><strong>ENV 节点：环境采集与输出控制</strong></summary>

**硬件组成：** BearPi-HM Nano + E53_IA1 扩展板。

| 硬件/功能 | 当前作用 |
|---|---|
| SHT30 | 采集温度和湿度 |
| BH1750 | 采集光照强度 |
| 补光灯 | 接收远程 output 命令，执行限时开关 |
| 风扇 | 接收远程 output 命令，执行限时开关 |

ENV 节点的主要采样调度周期约为 1 秒。传感器数据经过驱动层读取和换算后，形成统一的样本结构，再交给上层处理和网络模块。

</details>

<details>
<summary><strong>POSE 节点：姿态采集与指示输出</strong></summary>

**硬件组成：** BearPi-HM Nano + E53_SC2 扩展板。

| 硬件/功能 | 当前作用 |
|---|---|
| MPU6050 | 读取 X/Y/Z 三轴加速度 |
| 基础倾角计算 | 根据加速度数据计算基础倾角 |
| 指示灯 | 接收远程 output 命令，执行限时开关 |
| GPS | 当前使用固定的 GPS Mock 数据，不是真实定位模块 |

POSE 的采样调度目标为 20ms，即目标频率约 50Hz。这个值是调度目标，不代表任何运行环境下都能保证每次采样严格间隔 20ms。真实测试中，项目也记录了启动阶段的无效读取和错过周期情况。

</details>

两块板使用独立节点身份，但归属于同一个逻辑箱体：

| 角色 | 节点身份 | 主要职责 | 当前真实输出 |
|---|---|---|---|
| ENV | BOX-0001-ENV | 温湿度、光照 | 补光灯、风扇 |
| POSE | BOX-0001-POSE | 加速度、基础倾角、GPS Mock | 指示灯 |

两块板的身份、能力和采样周期由固件角色配置决定。更多硬件接线、驱动和调用关系见[固件导读](firmware/README.md)及[硬件节点说明](docs/design/hardware/节点与演示动作.md)。

## 3. 系统整体如何工作？

SmartBox 由设备端、消息代理、后端、数据库和网页组成。

~~~mermaid
flowchart LR
  E["ENV + IA1<br/>环境采集与输出"] <-->|"Wi-Fi / MQTT"| M["Mosquitto<br/>MQTT Broker"]
  P["POSE + SC2<br/>姿态采集与输出"] <-->|"Wi-Fi / MQTT"| M
  S["Python 模拟节点<br/>可替代真实设备输入"] <-->|"相同消息契约"| M
  M <-->|"设备消息"| B["Spring Boot<br/>接入、授权、查询、控制"]
  B <--> D[("MySQL<br/>历史与业务数据")]
  V["Vue 网页"] <-->|"REST 查询/操作<br/>WebSocket 通知"| B
~~~

可以把它理解为五个部分：

| 部分 | 使用的技术 | 通俗解释 |
|---|---|---|
| 设备端 | C、CMSIS-RTOS2、BearPi SDK | 读取传感器、控制 GPIO、连接网络 |
| 消息代理 | Mosquitto、MQTT | 负责转发设备和服务端之间的消息 |
| 后端 | Java、Spring Boot、MyBatis | 验证设备消息、处理业务、检查用户权限、提供接口 |
| 数据库 | MySQL、Flyway | 保存观测、事件、设备运行状态和控制记录 |
| 网页 | Vue、TypeScript、Element Plus | 展示箱体信息、数据质量和操作结果 |

几个常见名词：

- **MQTT Broker：**消息代理。设备把消息发送给 Broker，服务端订阅相应消息；Broker 负责转发，不负责判断业务数据是否正确。
- **REST API：**网页向后端查询信息或提交操作时使用的 HTTP 接口。
- **WebSocket：**服务端向网页发送状态变化通知的连接。网页收到通知后还会重新查询权威数据，不把一条推送直接当成完整状态。
- **模拟节点：**用 Python 程序模拟 ENV / POSE 的数据和行为。它使用相同的消息协议，因此没有开发板时也能验证大部分软件链路。

网页不直接连接 MQTT Broker 或 MySQL。设备身份、用户权限和业务数据处理都集中在后端。

## 4. 当前已经实现了什么？

下面区分软件模拟能力和真实开发板能力，避免把模拟结果误认为硬件功能。

| 功能 | 软件/模拟环境 | 真实双板 | 当前边界 |
|---|---|---|---|
| 温湿度、光照采集 | 已支持 | IA1 已完成基础实测 | 完整环境异常阈值、去抖和恢复规则待实现 |
| 加速度与倾角 | 已支持 | SC2 已完成基础实测 | 姿态校准、姿态分级和冲击检测待实现 |
| GPS | Mock | 固定 Mock | 没有真实 GPS 硬件和真实定位 |
| MQTT 通信 | 已完成真实 Broker 联调 | 两板已联网并上报 | 不是公网生产部署 |
| 数据保存和查询 | 已完成服务端闭环 | 真实采集已入库并显示 | 完整历史、轨迹页面仍待完善 |
| 用户登录与权限 | 已实现 | 共用同一服务端 | 没有自助注册和完整账号管理后台 |
| 远程控制 | 模拟器支持五类命令 | 真实固件仅支持 output | 模拟成功不代表真实板支持该命令 |
| 真实输出 | 模拟输出 | 补光灯、风扇、指示灯已实测 | 仅验证当前三项 output |
| 事件可靠性 | 后端事务保存、去重和 Outbox 已实现；模拟器可产生事件 | 设备侧 Flash 事件持久化和补传未完成 | 后端保存可靠不等于设备掉电不丢事件 |
| 门、告警和哨兵 | 部分模拟和接口基础 | 没有完整真实判断器 | 无结论时必须显示 UNKNOWN，不能当作正常 |

### 4.1 已验证的真实双板链路

TASK-007 已完成基础实物联调，验证范围包括：

- ENV + IA1 的真实温湿度和光照采集。
- POSE + SC2 的真实三轴加速度和基础倾角。
- 两块板通过 Wi-Fi / MQTT 接入 Broker。
- 数据经 Spring Boot 保存到 MySQL，并在网页展示。
- 网页分别控制 ENV 补光灯、风扇和 POSE 指示灯。
- 三项真实输出均完成限时动作和自动关闭确认。
- 双板共同运行约 310 秒，并验证了节点断电、离线判断和 Broker 恢复后的重新连接。

这代表基础真实设备链路已经跑通，不代表全部需求、全部测试用例或长期可靠性已经验收。详细环境、操作、数据和限制见[TASK-007 双板验证报告](docs/testing/reports/TASK-007-双板接入验证报告.md)。

### 4.2 当前明确尚未完成的内容

1. **完整监测算法：**温湿度异常与恢复、姿态分级、冲击检测、光照与倾角证据融合等。
2. **安全防护业务：**完整门状态判断、哨兵规则、告警分级和恢复流程。
3. **真实定位：**当前只有 GPS Mock，不包含真实 GPS 接收模块。
4. **设备侧事件可靠性：**Flash 事件存储、掉电恢复、断网补传和容量管理。
5. **完整网页交付：**完整历史筛选、轨迹和 CSV 导出页面。
6. **长期可靠性：**资源峰值、长时间运行、反复掉电和断网恢复等系统级验收。
7. **生产部署：**TLS、证书轮换、生产秘密管理、数据库备份、监控和服务托管等。

这些内容分别进入后续 TASK-008、TASK-009 和 TASK-010，具体以[任务索引](docs/development/tasks/README.md)和[路线图](docs/management/路线图.md)为准。

## 5. 一条观测和一条控制命令是怎样流转的？

### 5.1 设备观测上报

~~~mermaid
flowchart TD
  A["传感器读取"] --> B["固件校验与换算"]
  B --> C["形成统一样本"]
  C --> D["MQTT 发布"]
  D --> E["Broker 转发"]
  E --> F["后端校验身份、Topic 和数据契约"]
  F --> G["保存 MySQL"]
  G --> H["网页 REST 查询 / WebSocket 通知"]
~~~

设备不会直接写 MySQL。后端收到消息后，需要核对节点注册关系、Topic、消息身份、字段格式和数据质量，再决定如何保存和更新当前状态。

项目还区分几种不同的时间和状态：

- 设备运行时长（uptime）用于计算采样周期和动作期限。
- UTC 时间用于表达真实世界时间；未校时就不能伪造有效 UTC。
- 接收时间由服务端记录。
- 连接在线、样本有效和数据新鲜度是不同概念。设备在线不代表传感器数据一定有效或足够新。

### 5.2 远程控制

~~~mermaid
flowchart TD
  A["用户在网页确认操作"] --> B["后端检查角色、箱体权限和参数"]
  B --> C["保存操作与待发布命令"]
  C --> D["通过 MQTT 下发"]
  D --> E["设备校验命令"]
  E --> F["执行支持的动作"]
  F --> G["设备发布 command_ack"]
  G --> H["后端保存执行事实"]
  H --> I["网页查询并展示结果"]
~~~

控制不是“网页点击成功就代表设备执行成功”。

例如 HTTP 202 表示后端已受理操作，不代表 GPIO 已经动作。网页需要继续查询整体操作和逐节点执行结果。

目前模拟器支持五类命令；真实固件只执行 output。真实 output 会校验设备角色、能力、有效期和时间状态，并在设定期限后自动关闭。

### 5.3 事件保存与确认

服务端已实现事件身份、重复消息处理、事务保存和 Outbox 工作机制。简单来说，后端会先把原始事件和后续待处理工作放进同一数据库事务，事务成功后才向发送方返回精确的业务确认。

但目前真实固件还没有完成设备侧 Flash 事件存储和补传。因此，不能把后端事务可靠性描述为真实开发板已经具备完整的断电事件保护能力。

## 6. 用户角色与权限

系统不是所有用户都能看到所有设备信息。后端根据登录身份和箱体授权范围决定可查询字段和可执行操作。

| 角色 | 主要使用者 | 权限概述 |
|---|---|---|
| LOGISTICS_VIEWER | 物流寄件人/收件人 | 查看被授权箱体的信息 |
| LOGISTICS_OPERATOR | 物流操作者 | 查看授权信息，并执行允许的设备控制和管理操作 |
| DELIVERY_USER | 外卖用户 | 查看环境、定位及允许展示的箱体结论 |
| COURIER | 骑手 | 查看配送相关信息和证据，并执行允许的布撤防操作 |

权限在服务端检查，不是简单地把网页按钮隐藏起来。外卖用户不会因为修改 URL 或请求参数就获得姿态原始数据、设备诊断或其他角色的控制权限。

完整角色与字段规则见[角色与权限说明](docs/requirements/product/角色与权限.md)。

## 7. 技术栈

| 工程 | 主要技术 | 负责的工作 |
|---|---|---|
| 固件 | C、CMSIS-RTOS2、BearPi-HM Nano SDK | 采集、调度、硬件适配、Wi-Fi、MQTT 和 GPIO |
| 后端 | Java 21、Spring Boot 3.5、Spring Security、MyBatis | 认证授权、设备接入、数据处理、命令与查询 |
| 数据库 | MySQL 8、Flyway | 业务数据持久化和结构迁移 |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Element Plus | 网页交互、状态管理和实时展示 |
| 消息通信 | Mosquitto、MQTT 3.1.1 | 设备与服务端消息传输 |
| 模拟器和工具 | Python 3、Paho MQTT | 模拟节点、环境检查、构建、联调和验证 |
| 接口规范 | JSON Schema、OpenAPI | 约束跨端消息和 HTTP 接口格式 |

具体版本以[工具与依赖版本矩阵](docs/development/environment/工具与依赖版本矩阵.md)、各工程依赖文件和锁文件为准，不建议仅凭本文中的主版本号升级环境。

## 8. 快速开始：先运行软件模拟环境

**如果你只是想了解项目、启动网页、查看数据和体验模拟控制，不需要开发板，也不需要先下载 BearPi SDK。**

下面是 Windows + PowerShell 的简化流程。第一次部署需要 MySQL、Mosquitto 和相应开发工具。完整前置条件、安装来源和错误处理见[开发者完整部署指南](docs/delivery/deployment/开发者完整部署指南.md)。

### 8.1 准备基础环境

当前已验证的开发环境基线如下，实际使用前请对照[环境清单](docs/development/environment/环境清单.md)和[版本矩阵](docs/development/environment/工具与依赖版本矩阵.md)。

| 工具 | 已验证版本 | 用途 |
|---|---:|---|
| Windows | Windows 开发环境 | 运行后端、网页和项目工具 |
| Python | 3.10.8 | 项目工具和模拟器 |
| JDK | 21.0.10 | 编译、运行 Java 后端 |
| Maven | 3.9.14 | 后端构建 |
| Node.js / npm | 24.14.0 / 11.9.0 | 前端安装和构建 |
| MySQL | 8.0.44 | 项目数据库 |
| Mosquitto | 2.0.14 | MQTT Broker |

如果只运行软件模拟环境，不需要安装 Ubuntu、交叉编译工具链或 BearPi SDK。真实开发板开发才需要这些额外环境。

### 8.2 克隆项目并安装项目依赖

在 PowerShell 中进入你准备存放项目的目录：

~~~powershell
git clone https://github.com/fcj0633/SmartBox.git
cd SmartBox

python -m venv .venv
.\\.venv\\Scripts\\python.exe tools/project.py install-deps
~~~

这一步使用项目自己的 Python 虚拟环境，安装仓库锁定的 Python 依赖和前端依赖。首次安装前端依赖时还会准备对应版本的浏览器测试环境，需要网络。

不要复制其他电脑的 .venv、node_modules 或本机私有配置。

### 8.3 初始化本地配置和数据库

先生成当前电脑使用的本地配置：

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py init
~~~

然后确保本机 MySQL 服务已经启动，再执行首次数据库初始化：

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py db-init
~~~

该命令会提示输入本机 MySQL 管理员密码，并为 SmartBox 准备独立的开发库、测试库及受限账号。不要把管理员账号作为后端日常运行账号。

**注意：**数据库初始化是首次部署步骤，不是每次启动项目都要执行的命令。已有项目库或已有配置时，不要为了“重新开始”随意删除 secrets、数据库或设备身份记录；先阅读部署指南中的恢复和迁移说明。

### 8.4 构建并初始化演示数据

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py build
.\\.venv\\Scripts\\python.exe tools/backend.py bootstrap
~~~

第一条命令构建后端和前端。第二条命令根据本地初始化配置创建演示用户、箱体、节点和授权关系。

演示账号由本地初始化文件生成，不在 README 中提供固定默认密码。请在本机忽略的 secrets 目录中查看对应的 bootstrap 配置。

### 8.5 启动完整软件模拟环境

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py start --mode simulator
~~~

统一启动器会按顺序启动：

1. Mosquitto Broker。
2. Spring Boot 后端。
3. ENV 和 POSE 两个 Python 模拟节点。
4. Vue 前端开发服务器。

浏览器打开：

**http://127.0.0.1:5173**

登录后可以查看授权箱体、模拟设备数据和控制结果。GPS 会标注为 MOCK；门和告警没有完整判断器时可能显示 UNKNOWN，这不代表系统已经判断为安全或正常。

### 8.6 查看运行状态和停止项目

在另一个 PowerShell 窗口中：

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py status
.\\.venv\\Scripts\\python.exe tools/project.py stop
~~~

也可以在启动窗口按 Ctrl+C 正常退出。启动器只管理本轮由它创建的项目进程；如果端口被其他程序占用，会先报错，不会擅自结束其他进程。

如果电脑异常退出，先核对进程和端口，再按[完整部署指南](docs/delivery/deployment/开发者完整部署指南.md)中的恢复步骤处理，不要直接按旧 PID 杀进程。

## 9. 真实双板开发与部署

真实硬件开发是在软件模拟环境之外增加的步骤。你需要：

- 两块 BearPi-HM Nano 开发板。
- 一块 E53_IA1 扩展板和一块 E53_SC2 扩展板。
- Windows 端的烧录工具及串口驱动。
- 一套可通过 SSH 访问的 Ubuntu 编译环境。
- 与当前项目适配的 BearPi-HM Nano SDK 和 RISC-V 工具链。
- 开发板可以访问的 2.4GHz WPA2 Wi-Fi 网络。

### 9.1 先准备 SDK

**SmartBox 仓库没有把完整 OpenHarmony / BearPi SDK 源码放进 Git。**

本仓库的 firmware 目录保存 SmartBox 自己的 C 应用源码；SDK 提供操作系统、内核、底层驱动、厂商组件和构建工具。两者不是同一份代码。

SDK 需要单独获取。官方来源、版本差异、工具链准备、SSH 接入和兼容性检查见：

- [SDK 官网下载与 SmartBox 接入指南](docs/development/guides/SDK官网下载与接入.md)
- [公开依赖来源记录](deploy/sdk/sources.json)
- [开发者完整部署指南](docs/delivery/deployment/开发者完整部署指南.md)

不要默认认为官方仓库最新 master 一定与当前项目兼容。官网获取的新版本必须经过目录检查、双角色构建和链接验证，才能登记为本项目已验证的 SDK 版本。

### 9.2 配置并构建两个角色固件

完成 SDK、Ubuntu 和 SSH 准备后，在本地填写忽略的硬件配置文件。具体字段、网络要求和安全注意事项见[编译烧录与联调流程](docs/development/guides/编译烧录与联调流程.md)。

首次准备真实硬件配置时，需要检查并生成本地配置及启动代次预留：

~~~powershell
.\\.venv\\Scripts\\python.exe tools/hardware.py check
.\\.venv\\Scripts\\python.exe tools/hardware.py prepare
~~~

其中 prepare 会预留新的 boot_id 区间，不要把它当成普通启动命令反复执行，也不要随意删除本地账本或清零设备身份。

配置完成后，通过 SSH 在 Ubuntu SDK 环境中构建 ENV 和 POSE：

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py firmware-build
~~~

构建会分别生成两个角色的产物、日志和校验信息，并按工具流程恢复 SDK 原有构建入口和受保护输出。日常固件开发应修改仓库中的 firmware 源码，而不是长期直接修改 SDK 内的同步副本。

### 9.3 烧录并启动真实双板

分别选择 ENV 和 POSE 对应的固件镜像，按照[烧录与联调指南](docs/development/guides/编译烧录与联调流程.md)完成串口识别、烧录、复位和启动检查。

停止同身份的模拟节点后，启动真实硬件模式：

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py start --mode hardware
~~~

硬件模式会启动 Broker、后端和网页，但不会启动模拟节点，也不会自动替你烧录固件或修改防火墙。

**网络注意：**开发板连接 Broker 时必须使用电脑实际的局域网 IPv4 地址。开发板上的 127.0.0.1 指开发板自己，不是运行 Broker 的电脑。网络变化后，需要按硬件指南重新检查配置和固件。

## 10. 仓库目录结构

~~~text
SmartBox/
├── README.md                 # 项目总入口：先看这里
├── AGENTS.md                 # 开发协作和工程约定
├── firmware/                 # ENV / POSE 共用的 C 固件源码
├── backend/                  # Spring Boot 后端、数据库迁移和测试
├── frontend/                 # Vue 网页、状态管理和前端测试
├── tools/                    # 统一入口、构建、模拟、部署和检查工具
├── contracts/                # MQTT、HTTP、WebSocket 契约及有效定义
├── deploy/                   # 开发拓扑、Mosquitto 模板和 SDK 来源记录
├── tests/                    # 跨端、固件组件及工具测试
├── docs/                     # 需求、设计、任务、部署、验收和报告
├── artifacts/                # 本地构建产物、日志和证据；默认忽略
└── secrets/                  # 本机密码、私钥和配置；不提交 Git
~~~

各目录的详细结构和核心文件说明：

| 想了解或修改什么 | 从哪里开始 |
|---|---|
| 开发板采集、任务调度、Wi-Fi、MQTT、GPIO | [firmware/README.md](firmware/README.md) |
| 后端接入、数据库、权限、命令和 WebSocket | [backend/README.md](backend/README.md) |
| 登录页面、箱体详情、实时刷新和控制交互 | [frontend/README.md](frontend/README.md) |
| 项目启动、模拟节点、固件构建和开发工具 | [tools/README.md](tools/README.md) |
| 模拟器的状态、身份和事件处理 | [tools/simulator/README.md](tools/simulator/README.md) |
| MQTT / HTTP / WebSocket 的字段和规则 | [contracts/effective/README.md](contracts/effective/README.md) |
| 部署拓扑和 Broker 配置 | [deploy/README.md](deploy/README.md) |
| 测试层次、测试命令和证据边界 | [tests/README.md](tests/README.md) |
| 需求、设计、开发任务和验收文档 | [docs/README.md](docs/README.md) |

## 11. 开发者应该按什么顺序阅读？

如果你不是项目原开发者，不建议直接从某个 Java 类或 C 文件开始猜系统行为。可以按以下顺序建立认识：

| 顺序 | 阅读内容 | 需要理解的问题 |
|---|---|---|
| 1 | 本 README | 项目目标、硬件分工、系统结构和当前边界 |
| 2 | [产品范围与场景](docs/requirements/product/产品范围与场景.md) | 系统服务什么场景，明确不做什么 |
| 3 | [角色与权限](docs/requirements/product/角色与权限.md) | 不同用户能查看什么、能操作什么 |
| 4 | [系统总体设计](docs/design/architecture/系统总体设计.md) | 设备、Broker、后端、数据库和网页如何协作 |
| 5 | [模块边界与数据流](docs/design/architecture/模块边界与数据流.md) | 一条数据经过哪些模块，模块之间如何分工 |
| 6 | 对应工程 README | 进入固件、后端或前端，理解核心调用关系 |
| 7 | [当前有效契约](contracts/effective/README.md) | 核对跨端消息和接口的实际字段要求 |
| 8 | [任务索引](docs/development/tasks/README.md) | 了解已完成和待开发的工作，不重复做已完成任务 |
| 9 | [测试体系](tests/README.md) 和测试报告 | 了解如何验证，以及哪些结论有真实证据 |

项目的需求、设计、开发和测试不是同一类文档：

- **需求（REQ）：**规定系统应该具备什么行为。
- **设计（Design）：**解释准备怎样实现。
- **任务（TASK）：**说明某一阶段实际要交付的工作。
- **测试用例（TC）：**规定具体场景和通过标准。
- **测试报告：**记录某次执行的环境、结果、失败和限制。

一个 TASK 完成，不代表整个业务模块或全部需求已经验收。涉及协议字段时以当前有效契约为准；涉及业务规则变更时按项目的 CR 流程处理，不要只修改 README 来改变规则。

## 12. 常用检查与开发约定

在仓库根目录使用项目虚拟环境执行。

### 12.1 日常检查

~~~powershell
.\\.venv\\Scripts\\python.exe tools/project.py check
.\\.venv\\Scripts\\python.exe tools/backend.py unit
.\\.venv\\Scripts\\python.exe tools/frontend.py check
.\\.venv\\Scripts\\python.exe tools/mqtt.py unit
git diff --check
~~~

其中 project.py check 主要执行部署工具回归、文档检查、有效契约检查和冻结契约检查。它不是所有业务需求的完整验收，也不会替代真实开发板测试。

### 12.2 真实集成测试

后端和跨端集成测试可能清理专用的 smartbox_test 测试库。运行前必须阅读[测试体系说明](tests/README.md)，确认测试配置、数据库归属和清理范围，并使用工具要求的显式 test 配置及清理确认。

**不要对 smartbox_dev 或其他项目数据库执行测试清理。** 不要为了让测试通过而修改冻结协议、历史迁移或测试断言。

### 12.3 本地配置和秘密信息

以下内容属于本机配置，不应提交到 Git：

- Wi-Fi 密码、MQTT 用户名和密码。
- MySQL 账号密码、JWT 密钥和演示用户凭据。
- SSH 私钥和本机 SDK 路径。
- 生成的固件私有配置头文件。
- 设备启动代次账本、运行日志和构建产物。

项目已通过 .gitignore 忽略相关目录和文件。不要把真实密码、Token、私钥、ticket 或包含秘密的原始日志贴进 Issue、PR 或公开文档。

## 13. 项目阶段与后续计划

| 阶段/任务 | 当前结论 | 说明 |
|---|---|---|
| P0：工程与需求基线 | 已完成 | 建立目录、需求、接口、文档和追踪体系 |
| P1：软件与模拟闭环 | 已完成 | 后端、数据库、MQTT 模拟节点和电脑网页形成闭环 |
| P2：基础真实双板接入 | TASK-007 基础验证已完成 | 真实采集、三项输出、入库展示和基础恢复验证完成 |
| P3：核心业务规则 | 待开发 | 完善环境、姿态、证据融合和哨兵规则 |
| P4：持久化与可靠性 | 待开发 | 完成设备侧 Flash 事件存储、补传、掉电恢复和资源验证 |
| P5：完整交付 | 待开发 | 完善历史/轨迹/导出页面，完成系统级回归和交付 |

详细进度和依赖关系以[当前进度](docs/management/当前进度.md)、[路线图](docs/management/路线图.md)及[任务索引](docs/development/tasks/README.md)为准。

---

## 相关资料

- [BearPi-HM Nano 官方开发板介绍](https://www.bearpi.cn/dev_board/bearpi/hm/nano/)
- [BearPi-HM Nano 官方源码仓库](https://gitee.com/bearpi/bearpi-hm_nano)
- [开发者完整部署指南](docs/delivery/deployment/开发者完整部署指南.md)
- [SDK 官网下载与接入](docs/development/guides/SDK官网下载与接入.md)
- [编译、烧录与联调流程](docs/development/guides/编译烧录与联调流程.md)
- [测试报告目录](docs/testing/reports)
- [需求追踪矩阵](docs/management/需求追踪矩阵.md)

