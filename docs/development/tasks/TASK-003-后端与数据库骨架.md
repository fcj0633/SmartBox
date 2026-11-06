# TASK-003 后端与数据库骨架

| 属性 | 内容 |
|---|---|
| 阶段 | P1 |
| 负责人 | B |
| 状态 | 待开始 |
| 依赖 | TASK-001 |
| MOD | [MOD-03](../../requirements/modules/MOD-03-设备接入与控制.md)、[MOD-05](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md)、[MOD-06](../../requirements/modules/MOD-06-用户与权限管理.md) |
| 需求 | [REQ-M03-001](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-001)、[REQ-M03-006](../../requirements/modules/MOD-03-设备接入与控制.md#req-m03-006)、[REQ-M05-001](../../requirements/modules/MOD-05-物流箱管理与状态聚合.md#req-m05-001)、[REQ-M06-001](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-001)、[REQ-M06-002](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-002)、[REQ-M06-003](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-003)、[REQ-M06-004](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-004)、[REQ-M06-005](../../requirements/modules/MOD-06-用户与权限管理.md#req-m06-005)、[NFR-003](../../requirements/quality/安全与可维护性.md) |

## 工作与产出

建立 Maven/Spring Boot 应用、配置隔离、统一异常校验、认证与角色/箱体授权、MyBatis/Flyway。定义并建立身份/节点/事件/命令/outbox 所需基础表；记录独立项目库和账号初始化操作。

## 完成标准

空项目库迁移成功、再次启动不重建数据；健康检查可用；登录成功/失败与跨箱体拒绝通过。保存 MySQL 实际结果，不用内存数据库替代集成验收。

## 验证与收尾

按 [追踪矩阵](../../management/需求追踪矩阵.md) 关联 TC 执行，保留实际版本和脱敏日志，更新任务/迭代/当前进度。若需跨人实施，开工前拆成各有单一负责人的子任务，父任务仍保留总负责人。

## 实际记录

尚未执行；无编译、运行或板端通过证据。
