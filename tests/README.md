# 跨端测试入口

此处未来放机器可执行的契约、集成和端到端测试；后端/前端/固件各自单元测试随实现就近放置。本轮不为尚未实现业务创建伪测试。

人工用例和验收标准在 [用例目录](../docs/testing/cases/README.md)，P0 文档检查为 python tools/check_docs.py。

## 文档检查回归

[test_check_docs.py](test_check_docs.py)覆盖正向及故意破坏编号、归属、追踪、链接、历史白名单、原始哈希、迁移覆盖和协议示例的负向场景，均在临时样例目录执行。命令：`python -m unittest discover -s tests -p "test_check_docs.py" -v`。这是文档工具测试，不是SmartBox业务TC通过。
