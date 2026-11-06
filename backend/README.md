# 后端工程入口

TASK-003已完成后端与数据库基础：Java21 / Spring Boot3.5.16、Spring Security6.5.11、MyBatis、Flyway、真实MySQL。交付[pom.xml](pom.xml)、[应用源码](src/main/java/cn/smartbox/SmartBoxApplication.java)、13张业务表与4次迁移、认证授权基础及测试。

可运行接口：GET /actuator/health、POST /api/v1/auth/login、GET /api/v1/me、GET /api/v1/boxes/{id}/nodes。JWT有效期30分钟，角色和箱体范围以数据库为准；节点尚未接入，online=false且启动标识/接收时间为空，不伪造实时数据。

在仓库根目录执行：

```powershell
python tools/backend.py unit
python tools/backend.py build --profile dev
python tools/backend.py bootstrap --profile dev
python tools/backend.py run --profile dev
```

默认127.0.0.1:8080；本轮验收结束已停止后端，MySQL仍运行。配置从本地忽略的secrets/backend.local.json读取，演示登录信息在secrets/backend-dev.bootstrap.local.json；不要提交、打印到报告或复制真实秘密进命令行。配置格式、真实集成及复验见[初始化与启动指南](../docs/development/guides/后端数据库初始化与启动.md)。

详细字段与实现边界集中在[TASK-003](../docs/development/tasks/TASK-003-后端与数据库骨架.md)；[实际报告](../docs/testing/reports/TASK-003-后端与数据库验证报告.md)记录17项单元、26项集成和23项独立HTTP检查。总体职责见[后端设计](../docs/design/backend/后端模块与数据模型.md)。

TASK-005继续实现MQTT接入、遥测聚合、事件/outbox保存后确认、命令、WS及业务查询。表和公共投影存在不表示业务闭环完成；不包含前端、真实节点或板端验收。运行账号无DDL权限，数据库迁移与运行账号分离，禁止用其他项目库或H2/SQLite替代集成验证。

## TASK-005详细设计入口

接入、可靠保存、查询/导出、命令和WebSocket详细设计集中在[TASK-005](../docs/development/tasks/TASK-005-接入存储与查询闭环.md)，包括事务提交点、outbox/发布恢复、13表用途及结构缺口、统一授权/投影、S1～S7与验收。此次只完善文档，未创建源码/迁移、安装依赖、启动服务或连接数据库；TASK-005仍待开始。Q-01/Q-02/Q-09/Q-11保留，受影响子项在正式实施前确认，不因任务3/4通过而宣称服务端闭环已实现。
