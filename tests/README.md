# 跨端测试入口

此处未来放机器可执行的契约、集成和端到端测试；后端/前端/固件各自单元测试随实现就近放置。本轮不为尚未实现业务创建伪测试。

人工用例和验收标准在 [用例目录](../docs/testing/cases/README.md)，P0 文档检查为 python tools/check_docs.py。

## 文档检查回归

[test_check_docs.py](test_check_docs.py)覆盖正向及故意破坏编号、归属、追踪、链接、历史白名单、原始哈希、迁移覆盖和协议示例的负向场景，均在临时样例目录执行。命令：`python -m unittest discover -s tests -p "test_check_docs.py" -v`。这是文档工具测试，不是SmartBox业务TC通过。

## 契约工具回归

[test_check_contracts.py](test_check_contracts.py)在临时contracts副本中验证通过及故意破坏场景，涵盖引用、覆盖、认证、映射、命令、精确确认、投影、容量和错误预期。运行`.venv/Scripts/python -m unittest discover -s tests -p "test_check_contracts.py" -v`。依赖及入口见[工具说明](../tools/README.md)，结果见[报告](../docs/testing/reports/TASK-001-契约验证报告.md)。

文档回归的临时输入现包含实际contracts和链接到的工具文件，并跳过.venv，避免漏复制有效契约或复制本地依赖。未改变文档检查规则、业务用例或历史保护。

## 固件组件与同步/构建恢复回归

[组件测试](firmware/test_components.c)与[CMSIS替身](firmware/cmsis_os2.h)只在Ubuntu宿主测试使用，通过tools/check_firmware.py执行；生产GN只使用真实SDK。覆盖时钟/回绕、Mock、输出、快照、工作缓冲、禁用网络、启动门禁、创建失败与收敛超时。两角色22组检查不代表真实RTOS调度通过。

[test_sync_firmware.py](test_sync_firmware.py)验证预览零写入、受控删除、未知文件、链接/边界、冲突与恢复；[test_build_firmware.py](test_build_firmware.py)验证Unix备份、归属不明/并发/损坏拒绝与恢复权限。所有负例仅在临时SDK样例执行，Windows和Ubuntu实际结果见[报告](../docs/testing/reports/TASK-002-固件骨架验证报告.md)。不替代TC-007/012完整集成场景。

文档工具临时样例已加入实际firmware与tests/firmware文件，仅补齐新链接输入，没有改变检查规则或历史保护。
