# 固件架构与 SDK 接入

关联 REQ-001～007；TASK-002、007～009。

app 装配任务和角色，domain 实现纯规则，drivers 适配板件，services 包含事件/网络/配置/GPS，include 定义公共类型，build 存 GN 接入。

SensorFrame 内部包含有效位、单位、boot_id、uptime 和可空 UTC；事件身份完整。gps_get_fix 隔离 Mock 和未来真实驱动，不让 SDK 全局变量穿透到业务层。

采集/规则按周期执行，有界事件送存储任务，网络只发送已承诺持久事件。队列满报告背压和损失，不阻塞姿态采样。输出默认关闭并使用单调时间定时关闭。

P1 先实现角色骨架、模拟 HAL 和接口，不承诺 KV 持久可靠。TASK-009 依据 KV 容量、擦写和掉电实测，经 ADR 确定布局与恢复方案，禁止使用未知 Flash 地址。

SDK sample 下新增 logistics_box；一次只构建一个角色，构建后立即归档角色产物。保留当前 study_kernel_01_thread 选择和恢复方式，不删除旧实验。P0 不改入口。

软件规则可用模拟采样验证；旧 CMSIS 接口需实际链接，不以头文件声明作为可用证据。
