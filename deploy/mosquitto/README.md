# 项目Mosquitto：账号隔离与两种监听模式

Broker是节点与后端之间的MQTT中转，不做业务保存或JSON权限判断。当前使用现有Windows Mosquitto独立项目进程，不启动旧Windows服务、不覆盖全局配置。端口1883占用时拒绝，不停止其他项目。

## 1. 受控模板与私有运行文件

| 文件/来源 | 用途 |
|---|---|
| [mosquitto.conf.template](mosquitto.conf.template) | 本机模拟Broker，仅回环监听 |
| [mosquitto.hardware.conf.template](mosquitto.hardware.conf.template) | 显式硬件模式，回环＋一个经过校验的LAN IPv4 |
| [acl.example](acl.example) | ENV方向的权限示例，不是完整运行ACL |
| 工具生成配置 | 账号密码、散列、完整Topic ACL和实际网卡地址均在忽略目录，不提交 |

[模拟准备器](../../tools/simulator/config.py)为ENV、POSE、backend、control、observer逐Topic生成完整规则；[硬件工具](../../tools/hardware.py)基于已准备账号生成指定网卡配置。硬件模式不会把默认本机模式的地址校验整体放宽。

## 2. 账号与Topic方向

| 身份 | 发布 | 订阅 |
|---|---|---|
| ENV或POSE节点 | 自己五类上行 | 自己三类下行 |
| 后端 | 已配置节点下行 | 已配置节点上行 |
| 测试控制端 | 场景需要的下行 | 场景需要的上行 |
| 只读观察端 | 无业务发布权限 | 已配置项目上行观察范围 |

Topic、QoS和retain以[MQTT规范](../../contracts/mqtt/v1.md)为准。禁止匿名，`use_username_as_clientid`将认证账号与客户端标识绑定，节点用户名等于node_id；测试端使用独立账号，不挤掉节点身份。ACL只限制Topic，不能解析JSON证明payload身份，节点/后端仍需校验。

后端账号与测试控制账号不混用；测试端event_ack仅是场景输入，真正数据库保存确认由后端事务提交后生成。私有密码通过项目内临时文件转换为散列，不放命令行；临时明文失败也需清理，散列文件同样不提交。

## 3. 启动本机模式

从仓库根目录、项目虚拟环境执行：

```powershell
.venv/Scripts/python tools/mqtt.py prepare
.venv/Scripts/python tools/mqtt.py broker --config secrets/mqtt.local.json
```

prepare首次创建账号/配置/身份，重复只校验不重置；Broker监听127.0.0.1:1883，不用于板端访问。节点、后端和网页另见[根入口](../../README.md)。Ctrl+C停止本入口启动的Broker，释放端口，不停止共享服务。

## 4. 启动硬件模式

前提是私有Wi-Fi、Broker网卡及角色固件已经按[烧录联调指南](../../docs/development/guides/编译烧录与联调流程.md)准备，电脑与板可互访，原模拟Broker及同身份模拟节点停止。复用已生成配置：

```powershell
.venv/Scripts/python tools/hardware.py check
.venv/Scripts/python tools/hardware.py broker --run-dir artifacts/hardware/NEW-RUN
.venv/Scripts/python tools/backend.py run --profile dev --mqtt-config secrets/hardware/mqtt.local.json --hardware-mqtt
```

NEW-RUN是新的证据目录；后端仍从回环访问Broker，网页仍回环5173。板端用电脑实际LAN地址，不用127.0.0.1；只有Broker增加指定网卡监听。`check`只检查私有Wi-Fi字段，不能证明热点已可达或固件已更新。

不要为开Broker重复执行hardware prepare：它会预留新启动代次区间并生成需要重新构建的配置。不要删除区间账本/板端启动记录来修复网络；网卡或Wi-Fi改变先核对固件配置及恢复范围。工具不自动改防火墙，部署模板也不是来源网段过滤规则；必要的网段限制应由明确的网络/防火墙配置实施。

## 5. 容量、验证与生产边界

现有工具核验Mosquitto2.0.14。模板`max_packet_size=2046`来自该版本remaining_length比较方式，用于约束公开完整帧2048字节；payload仍1536字节，Topic仍128字节，不能因配置数字不同改业务阈值。升级Broker需要重新验证，不能套用新版专属选项。

`tools/mqtt.py integration`使用独立状态/账号运行真实认证、ACL、保留投递、断线和事件确认场景；发布调用/PUBACK不证明越权消息是否转发，需观察端与Broker证据。真实后端保存看服务端测试；真实动作看双板报告。模板关闭Broker持久化，不是可靠业务库；TLS、证书、生产托管和运维留后续。清理只针对本轮持有进程，不能按端口号杀全部进程。
