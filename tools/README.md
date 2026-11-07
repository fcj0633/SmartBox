# 文档检查工具

运行 `python tools/check_docs.py`，识别六模块、模块内部子需求、50行追踪及Design/TASK/TC反向关联，检查链接与锚点、64条规则迁移、历史字节保护和协议示例。无需Git远程或业务服务。

运行 `python -m unittest discover -s tests -p "test_check_docs.py" -v` 执行工具回归；全部破坏性样例仅在临时目录副本中进行。测试不改真实文档，不运行应用或硬件。

固件同步、检查与构建工具已由TASK-002交付；模拟节点仍待TASK-004。

## 契约检查工具

[check_contracts.py](check_contracts.py)验证OpenAPI/JSON Schema、覆盖清单、正反样例和明确的静态语义。依赖由[requirements-contracts.txt](requirements-contracts.txt)精确锁定，使用独立.venv，安装步骤见[版本矩阵](../docs/development/environment/工具与依赖版本矩阵.md)。

运行 `.venv/Scripts/python tools/check_contracts.py`，回归运行 `.venv/Scripts/python -m unittest discover -s tests -p "test_check_contracts.py" -v`。Linux将解释器路径改为`.venv/bin/python`。成功返回0，失败返回非0及文件/样例/错误位置；日常检查不联网、不写文件、不启动服务。未知引用立即失败；不能用解析器默认联网兜底。

样例context只用于检查显式规则，不是MQTT/HTTP真实鉴权。契约工具测试不执行应用或硬件TC；[实际报告](../docs/testing/reports/TASK-001-契约验证报告.md)明确未验证范围。

## 固件工具（TASK-002）

均使用标准库；Windows沿用Python 3.10.8，通过已有密钥SSH调用Ubuntu Python 3.8/GCC/SDK，不安装或升级系统工具。连接值取各电脑自己的本地说明，通过参数传入，不写入脚本或Git。

| 入口 | 用途与操作边界 |
|---|---|
| [sync_firmware.py](sync_firmware.py) | 默认只读预览；`--apply`显式应用；`--restore-entry --apply`恢复入口。只同步C/H/GN/GNI与生成角色/源指纹，不复制SDK或日志 |
| [check_firmware.py](check_firmware.py) | 将源码和[测试替身](../tests/firmware/test_components.c)送入Ubuntu临时目录，分别编译/运行两角色组件测试；不修改SDK |
| [build_firmware.py](build_firmware.py) | 真实构建全流程：备份、默认预览并受控应用、核对映射与SSH、两角色编译链接/归档、恢复原环境 |
| [firmware_support.py](firmware_support.py)、[firmware_remote.py](firmware_remote.py) | SSH传输与Ubuntu工作器；由上述入口调用，不作为常驻服务 |

替换以下大写参数后执行，真实连接值仅留在本机忽略说明中。命令应整行执行：

```text
python tools/sync_firmware.py --sdk-root SDK_MAPPING --role ENV
python tools/check_firmware.py --host HOST --user USER --key KEY_PATH --report artifacts/firmware/TASK-002/components.log
python tools/build_firmware.py --sdk-root SDK_MAPPING --remote-sdk SDK_LINUX --host HOST --user USER --key KEY_PATH
```

构建工具在`artifacts/firmware/TASK-002/时间标识/`记录run.json、源码差异、包含新增文件的源码快照tar及逐文件指纹、原SDK的tar备份、按角色独立的完整构建日志、product.tar、bin/map/ELF、manifest与恢复清单。官方构建会删除原product out并改变厂商目录，所以不要绕过备份直接执行官方脚本。归档校验真实应用符号、最终ELF中的节点身份、最终map及新bin，不只检查某个日志字符串。

SDK同步使用三方内容指纹。未知同名副本、人工修改、越界/链接、意外入口变化均拒绝；未知文件保留。默认预览不创建目录/锁/日志。应用时写恢复日志，崩溃后可使用`--recover-sync --apply`；若遗留锁，先核对所属进程与日志，禁止盲删锁后覆盖。

失败后保留本地与Ubuntu备份，构建恢复入口为：

```text
python tools/build_firmware.py --recover RUN_DIRECTORY --sdk-root SDK_MAPPING --remote-sdk SDK_LINUX --host HOST --user USER --key KEY_PATH
```

恢复只接受工具能确认归属的输出与入口版本，核对所有文件字节及Unix权限；映射盘恢复入口只允许记录过的原始/最后受控权限组合，最终由Unix归档恢复原权限。硬中断导致输出归属未知、恢复中断或发现新变化时拒绝自动覆盖，需要先检查状态与差异；备份不自动删除。构建互斥是本工具的协作锁，另检查活动构建进程；不允许同时从别的终端构建相同SDK。

工具回归（临时SDK样例，不操作真实SDK）：

```text
python -m unittest discover -s tests -p test_sync_firmware.py -v
python -m unittest discover -s tests -p test_build_firmware.py -v
```

上述两组也已在Ubuntu Python 3.8执行。组件测试替身只进入宿主测试，生产GN只链接真实SDK CMSIS。当前8帧采样/16项候选均为易失工作缓冲，网络固定返回未实现，不满足MOD-04或真实通信验收。[实际报告](../docs/testing/reports/TASK-002-固件骨架验证报告.md)记录交付结果和限制。

## 后端基础工具

[backend.py](backend.py)统一执行unit/build/bootstrap/run/test/smoke；[backend_smoke.py](backend_smoke.py)启动真实JAR并离线解析冻结Schema校验响应。详细配置、命令、测试清理保护与Windows进程兼容处理见[后端初始化与启动指南](../docs/development/guides/后端数据库初始化与启动.md)。工具不保存/打印密码或JWT，不停止已有端口程序；smoke结束检查自身实际进程与端口已释放。

真实集成必须使用test配置和显式--allow-test-reset，检查实际数据库与账号后只清理项目测试业务数据。运行命令使用dev配置，不自动初始化或重置账号。依赖复用既有.venv契约工具，不安装系统工具。实际结果见[TASK-003报告](../docs/testing/reports/TASK-003-后端与数据库验证报告.md)。

## TASK-004模拟工具入口

[mqtt.py](mqtt.py)提供prepare、broker、node、unit、integration；实现分层和命令见[模拟器说明](simulator/README.md)，[Broker模板](../deploy/mosquitto/README.md)保持与旧服务隔离。项目.venv新增paho-mqtt2.1.0并单独锁定，不改系统Python。默认本地配置在忽略的secrets/mqtt.local.json，不打印密码或以命令行传密码。真实集成自建配置和子进程，确认1883空闲后执行，结束检查进程和端口释放。测试event_ack不能证明数据库保存，storage=DEGRADED不能证明Flash可靠性。实际结果见[TASK-004报告](../docs/testing/reports/TASK-004-MQTT与模拟节点验证报告.md)。

## TASK-005当前实现入口

[启动指南](../docs/development/guides/服务端闭环启动与验收.md)、[有效契约生成/检查](effective_contracts.py)、[真实联调](live_integration.py)。接入/查询/命令/WS职责在live包分离，LiveStore/LiveMapper隔离MyBatis持久访问；Controller不查数据库。V5～V9新增运行态、位置、事件视图、命令恢复/回执、操作顺序及质量约束，V1～V4保持。告警判断与门融合仍未知；网页及板端待后续，实际验收结果以任务报告为准。

## TASK-006前端统一入口

[frontend.py](frontend.py)提供install/dev/check/build/browser/integration；[frontend_integration.py](frontend_integration.py)复用现有真实Broker/后端/双模拟进程管理。命令和安全边界见[网页指南](../docs/development/guides/网页启动与首轮验收.md)，实际结果见[报告](../docs/testing/reports/TASK-006-网页与首轮闭环验证报告.md)。集成要求--profile test、--allow-test-reset和新的artifacts目录；--scenario authorization仅定向补查，不替代完整验收。测试库授权撤销在收尾恢复，缺少配置不跳过，只清理自身进程。
