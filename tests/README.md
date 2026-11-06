# 跨端测试入口

此处未来放机器可执行的契约、集成和端到端测试；后端/前端/固件各自单元测试随实现就近放置。本轮不为尚未实现业务创建伪测试。

人工用例和验收标准在 [用例目录](../docs/testing/cases/README.md)，P0 文档检查为 python tools/check_docs.py。

## 文档检查回归

[test_check_docs.py](test_check_docs.py)覆盖正向及故意破坏编号、归属、追踪、链接、历史白名单、原始哈希、迁移覆盖和协议示例的负向场景，均在临时样例目录执行。命令：`python -m unittest discover -s tests -p "test_check_docs.py" -v`。这是文档工具测试，不是SmartBox业务TC通过。

## 契约工具回归

[test_check_contracts.py](test_check_contracts.py)在临时contracts副本中验证通过及故意破坏场景，涵盖引用、覆盖、认证、映射、命令、精确确认、投影、容量和错误预期。运行`.venv/Scripts/python -m unittest discover -s tests -p "test_check_contracts.py" -v`。依赖及入口见[工具说明](../tools/README.md)，结果见[报告](../docs/testing/reports/TASK-001-契约验证报告.md)。

文档回归的临时输入现包含实际contracts和链接到的工具文件，并跳过.venv，避免漏复制有效契约或复制本地依赖。未改变文档检查规则、业务用例或历史保护。
