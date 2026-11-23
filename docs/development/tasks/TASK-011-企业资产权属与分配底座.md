# TASK-011 企业资产权属与分配底座

| 属性 | 内容 |
|---|---|
| 阶段 | CR-006 / 候选 BL-003 |
| 负责人 | 用户 |
| 状态 | 待开始 |
| 依赖 | 现有权限基础，CR-006 |
| MOD | [MOD-05](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md)、[MOD-06](../../requirements/modules/MOD-06-用户与权限管理.md) |
| 需求 | [REQ-M05-006](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-006)、[REQ-M05-007](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-007)、[REQ-M06-007](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-007) |
| TC | [TC-013](../../testing/cases/TC-013-企业资产与权限隔离.md) |

## 工作内容

受控登记企业/个人所有权、企业成员资格、单箱骑手有效分配/撤销以及全程审计。

## 实施路径与交付

1. 核对 [CR-006](../../management/changes/CR-006-多角色物流箱与运输任务平台.md) 与 [数据库设计](../../design/backend/CR-006-企业箱体与运输任务数据设计.md) 的约束和既有迁移兼容性。
2. 先新增失败/越权/并发测试，再补充独立 Spring Boot 业务服务和仅追加的 Flyway 迁移；不得改 V1～V12。
3. 验证真实 MySQL、HTTP/WS、必要的云端模拟与浏览器行为，并记录失败和跳过情况。
4. 未通过关联 TC 前不能登记整项需求已验收；TASK-009/010 原有职责保持不变。

## 实际执行状态

尚未执行，暂无编译、数据库、浏览器或硬件通过证据。
