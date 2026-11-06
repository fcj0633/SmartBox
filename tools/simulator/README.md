# Python MQTT双节点模拟器

本目录实现配置、协议、身份文件、模拟状态、MQTT运行器和真实Broker场景。统一入口为[mqtt.py](../mqtt.py)，详细业务/验收范围见[TASK-004](../../docs/development/tasks/TASK-004-MQTT与模拟节点.md)。不连接MySQL，不实现后端接入，不访问外设。

## 安装与运行

从仓库根目录使用项目.venv，初次依赖安装：

```powershell
.venv/Scripts/python -m pip install -r tools/requirements-contracts.txt -r tools/requirements-simulator.txt
.venv/Scripts/python -m pip check
.venv/Scripts/python tools/mqtt.py prepare
```

在三个终端分别运行Broker、ENV、POSE：

```powershell
.venv/Scripts/python tools/mqtt.py broker
.venv/Scripts/python tools/mqtt.py node --role ENV
.venv/Scripts/python tools/mqtt.py node --role POSE
```

每个终端Ctrl+C正常退出。--config指定仓库内本地配置，--run-dir指定仓库内证据目录；角色节点只使用自身状态锁。--scenario可选normal、invalid、unsynced；--seed固定数据种子，--events为初始测试事件数0～1000，默认1。正常模式每秒遥测、30秒心跳，定位始终MOCK；种子只保证输入可重复，不保证墙钟相同。每个节点仅一个线程手动驱动客户端，消息队列32、请求缓存32、待确认事件64。

## 检查

先停止手工启动的Broker和节点，确认1883空闲，再执行：

```powershell
.venv/Scripts/python tools/mqtt.py unit
.venv/Scripts/python tools/mqtt.py integration
```

unit只在临时目录测试状态/配置/协议，不启动服务。integration独立启动Broker、两个节点及测试端；真实检查两段30秒心跳间隔，通常需要约两分钟；过程输出进度，成功退出0，失败非零。默认在artifacts/mqtt/TASK-004/独立运行编号中保存原始证据。--config用于integration时必须等于该运行目录中的integration.local.json，禁止复用日常配置。集成结束检查自身进程退出和1883释放。

状态JSON保存boot_id与sentry_revision，锁避免同状态目录并发；缺失/损坏不自动重建。boot预留完成后才连接；保存失败/计数耗尽安全停止，不回绕。序号耗尽尚未实现自动跨代次搬迁，停止生成并报错，不丢弃未确认内容后继续冒充可靠传输。

[CR-003](../../docs/management/changes/CR-003-布防有效期限计时起点.md)规定：3秒缓冲后实际ARMED再计duration_s，0不自动到期。主机只持久身份与修订号，重启后的哨兵显示DEGRADED，不宣称期限恢复。事件和损失计数仅内存、storage=DEGRADED；主机文件不代表Flash掉电一致性。

测试端event_ack只验证精确序号清理，不证明后端保存。profile不运行告警算法，calibrate仅固定静置样本的模拟完成，box_state不启用尚未确认的抑制TTL。模拟成功不证明设备动作或系统闭环。

Windows集成进程直接使用实际Python可执行文件，并在子进程PYTHONPATH中复用.venv库，避免venv启动器的PID与真实节点PID不一致；不修改全局Python或用户环境。原始配置/日志/状态只保留本机，不提交秘密或散列文件。
