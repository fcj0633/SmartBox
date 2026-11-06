# 文档检查工具

运行 `python tools/check_docs.py`，识别六模块、模块内部子需求、50行追踪及Design/TASK/TC反向关联，检查链接与锚点、64条规则迁移、历史字节保护和协议示例。无需Git远程或业务服务。

运行 `python -m unittest discover -s tests -p "test_check_docs.py" -v` 执行工具回归；全部破坏性样例仅在临时目录副本中进行。测试不改真实文档，不运行应用或硬件。

本地同步/模拟工具随后续TASK交付，覆盖操作默认预览差异。

## 契约检查工具

[check_contracts.py](check_contracts.py)验证OpenAPI/JSON Schema、覆盖清单、正反样例和明确的静态语义。依赖由[requirements-contracts.txt](requirements-contracts.txt)精确锁定，使用独立.venv，安装步骤见[版本矩阵](../docs/development/environment/工具与依赖版本矩阵.md)。

运行 `.venv/Scripts/python tools/check_contracts.py`，回归运行 `.venv/Scripts/python -m unittest discover -s tests -p "test_check_contracts.py" -v`。Linux将解释器路径改为`.venv/bin/python`。成功返回0，失败返回非0及文件/样例/错误位置；日常检查不联网、不写文件、不启动服务。未知引用立即失败；不能用解析器默认联网兜底。

样例context只用于检查显式规则，不是MQTT/HTTP真实鉴权。契约工具测试不执行应用或硬件TC；[实际报告](../docs/testing/reports/TASK-001-契约验证报告.md)明确未验证范围。
