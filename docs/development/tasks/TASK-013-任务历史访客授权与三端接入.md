# TASK-013 任务历史访客授权与三端接入

| 属性 | 内容 |
|---|---|
| 阶段 | CR-006 / 候选 BL-003 |
| 负责人 | 用户 |
| 状态 | 待开始 |
| 依赖 | TASK-011、TASK-012；完整补传另依赖 TASK-009 |
| MOD | [MOD-06](../../requirements/modules/MOD-06-用户与权限管理.md)、[MOD-07](../../requirements/modules/MOD-07-运输任务与过程管理.md) |
| 需求 | [REQ-M06-008](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-008)、[REQ-M07-005](../../requirements/modules/MOD-07-运输任务与过程管理.md#req-m07-005)、[REQ-M07-006](../../requirements/modules/MOD-07-运输任务与过程管理.md#req-m07-006)、[REQ-M07-008](../../requirements/modules/MOD-07-运输任务与过程管理.md#req-m07-008) |
| TC | [TC-015](../../testing/cases/TC-015-任务历史访客安全与页面.md) |

## 工作内容

实现按可信时间的任务历史查询、访客凭据/会话/撤销及三端正式 API/页面接入。

## 实施路径与交付

1. 核对 [CR-006](../../management/changes/CR-006-多角色物流箱与运输任务平台.md) 与 [数据库设计](../../design/backend/CR-006-企业箱体与运输任务数据设计.md) 的约束和既有迁移兼容性。
2. 先新增失败/越权/并发测试，再补充独立 Spring Boot 业务服务和仅追加的 Flyway 迁移；不得改 V1～V12。
3. 验证真实 MySQL、HTTP/WS、必要的云端模拟与浏览器行为，并记录失败和跳过情况。
4. 未通过关联 TC 前不能登记整项需求已验收；TASK-009/010 原有职责保持不变。

## 实际执行状态

尚未执行，暂无编译、数据库、浏览器或硬件通过证据。
