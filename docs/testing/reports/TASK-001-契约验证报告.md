# 契约验证报告（TASK-001）

日期：2026-11-06；执行者：Codex；范围：离线结构、样例及静态语义检查。输入为提交2215749之后的TASK-001工作区，不代表已提交代码或业务部署。冻结文件指纹见[基线清单](../../../contracts/baseline-v1.json)。

## 环境与输入

- Windows、Python 3.10.8，独立.venv；系统Java/Node/MySQL/Mosquitto未升级，未连接项目数据库、启动服务或改SDK。
- 精确工具依赖见[锁定文件](../../../tools/requirements-contracts.txt)，应用拟采用版本与官方来源见[版本矩阵](../../development/environment/工具与依赖版本矩阵.md)。
- 输入包括[共享定义](../../../contracts/common/v1.schema.json)、[OpenAPI](../../../contracts/http/openapi.yaml)、8个设备与3个WS Schema入口、[152个样例及覆盖清单](../../../contracts/coverage.json)、原始MQTT examples.json。原始示例未改写。

## 实际检查记录

以下均为本次实际执行结果；TASK-001已完成接口准备。具体交付版本以包含本报告的Git提交定位，2215749仅为本次工作的起点。

| 检查 | 当前实际结果 | 边界 |
|---|---|---|
| 独立环境pip check | 通过，无依赖冲突 | 不代表Java或Node业务依赖安装通过 |
| 契约与样例检查 | 通过：12个Schema文件、60个公共定义、11个HTTP操作、152个样例（69正/83反） | 静态上下文不是真实身份/服务 |
| 契约工具回归 | 最终24项通过，包含故意损坏与边界回归 | 临时目录故意破坏，未操作真实服务 |
| 文档工具回归 | 23项通过 | 不证明业务或板端通过 |
| 文档检查/差异检查 | 通过：6模块、47需求、50行追踪、64条迁移规则、2033个链接；git diff --check无错误 | 原始哈希、64条规则、链接及追踪保护 |

## 验证了什么

1. Schema及OpenAPI自身有效；引用仅从本地注册表/文件解析，设备8类Topic、5类命令、11个HTTP操作和WS五类推送覆盖完整。OpenAPI认证及请求/响应映射与公共定义一致。
2. 正样例被接受；反样例按指定错误类别及准确JSON位置被拒绝。覆盖版本、必填、枚举、角色字段、各命令参数、错误时间、UTF-8多字节、1536/1537字节、Topic/帧容量、序号/批次、重复JSON键及截断输入。
3. 明确上下文下检查身份/能力、retained、到期/未校时控制、画像恢复区间、精确确认集合、丢失区间、查询范围、角色投影、幂等键及响应凭据字段；CSV样例检查授权、范围和公式前缀。
4. 故意损坏的临时副本验证工具确实失败，不修改真实业务数据。P0文字契约既定阈值和原始examples保持。

## 未验证与遗留

没有Broker传输、账号ACL、JWT签名/ticket消费、真实数据库事务/唯一键/业务确认、设备命令执行、网页、SDK编译/链接或板端通过证据。原TC-006/007/008/009/012仅完成准备性静态检查，整份用例保持未执行。

Q-01～Q-14保留原义，接口影响分类见[清单](../../../contracts/接口覆盖与待决清单.md)。冻结只覆盖结构与明确静态约束，Q-02迟到结果、Q-08有效期等行为不由本报告擅自决定。工具链精确执行版本随TASK-002编译核验；官方IA1/SC2例程通过是用户反馈，不是本次项目实测。

## 复现命令与结果

```text
.venv/Scripts/python -m pip check
No broken requirements found.

.venv/Scripts/python tools/check_contracts.py
PASS {"schema_files": 12, "definitions": 60, "fixtures": 152, "positive": 69, "negative": 83, "http_operations": 11}

.venv/Scripts/python -m unittest discover -s tests -p test_check_contracts.py -v
Ran 24 tests ... OK

python -m unittest discover -s tests -p test_check_docs.py -v
Ran 23 tests ... OK

python tools/check_docs.py
PASS: {"modules": 6, "requirements": 47, "trace_rows": 50, "migration_rules": 64, "links": 2033, "markdown": 96}

git diff --check
无输出，退出码0。
```

已对照HEAD核验：所有需求文档、CR-002及原始examples.json未修改；MQTT规则正文和REST通用规范至导出审计原文保留；五份TC原有正文完整保留，仅追加准备性检查记录。没有SDK或业务代码改动。
