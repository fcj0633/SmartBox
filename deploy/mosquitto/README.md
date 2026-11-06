# 项目Mosquitto入口

使用Windows现有Mosquitto 2.0.14的独立进程，不安装/启动Windows服务，不改全局配置或防火墙。详细实现与验收边界见[TASK-004](../../docs/development/tasks/TASK-004-MQTT与模拟节点.md)。

从仓库根目录依次执行：

```powershell
.venv/Scripts/python tools/mqtt.py prepare
.venv/Scripts/python tools/mqtt.py broker
```

prepare随机生成五个独立账号、本地密码散列、精确Topic ACL、运行配置及初始身份状态，默认保存在忽略的secrets/mqtt.local.json及其关联目录；重跑只校验，不重置。Broker固定127.0.0.1:1883，端口占用时拒绝；Ctrl+C停止本次启动的实际进程。后端账号供TASK-005使用，测试控制账号不能作为数据库保存证据。

[配置模板](mosquitto.conf.template)中max_packet_size=2046是2.0.14兼容参数：该版本接收端比较remaining_length+1，没有加两字节长度编码；此参数在边界处对应公开的完整帧2048字节。客户端/模拟器仍按完整帧2048、payload1536检查，不降低或提高业务阈值。工具拒绝未经核对的其他Broker版本；升级须重新验证边界，不能直接复用这个参数。

[ACL示例](acl.example)只示例ENV，实际准备器为ENV、POSE、后端、测试控制和只读观察账号逐Topic生成全部规则。use_username_as_clientid绑定认证账号与连接标识；节点用户名等于node_id。只读观察端与控制端账号不同，避免测试互相顶替。密码不经命令行参数，passwd.tmp在项目私有目录转换并在失败时清理；真实密码和散列都不入Git。

集成检查要求1883空闲，自行创建独立配置与身份状态，完成后停止自身进程；不重置日常节点。运行期间不要另启服务占用1883，不用该回环配置连接真实板端。TLS、生产部署和真实后端保存由后续任务验证。
