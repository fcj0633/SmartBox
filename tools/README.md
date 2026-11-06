# 文档检查工具

运行 `python tools/check_docs.py`，识别六模块、模块内部子需求、50行追踪及Design/TASK/TC反向关联，检查链接与锚点、64条规则迁移、历史字节保护和协议示例。无需Git远程或业务服务。

运行 `python -m unittest discover -s tests -p "test_check_docs.py" -v` 执行工具回归；全部破坏性样例仅在临时目录副本中进行。测试不改真实文档，不运行应用或硬件。

本地同步/模拟工具随后续TASK交付，覆盖操作默认预览差异。
