# 后端工程入口

技术方向：Java21、Spring Boot、Spring Security、MyBatis、Flyway、MySQL8，精确版本沿用TASK-001。[TASK-003详细设计](../docs/development/tasks/TASK-003-后端与数据库骨架.md)已完善，任务状态仍为待开始；本目录只有说明，尚无pom、Java源码、SQL、可运行服务或项目数据库。

详细设计集中在TASK-003，涵盖目录和调用关系、13张P1基础表字段字典与ER图、JWT/密码/箱体授权、配置、受控初始化、S1～S7及验收。总体模块职责见[后端设计](../docs/design/backend/后端模块与数据模型.md)，操作次序见[数据库初始化与启动指南](../docs/development/guides/后端数据库初始化与启动.md)。

未来TASK-003建立完整P1基础表，交付健康检查、登录、当前身份及授权节点基础查询；事件处理、遥测聚合、命令和WS闭环仍由TASK-005实现。建表、投影单元测试不等于完整业务出口上线。

默认Windows回环运行，项目开发库smartbox_dev、集成库smartbox_test，迁移与运行账号分离。真实配置只留本地，不提供默认密码；不能使用其他项目表或以H2/SQLite替代MySQL集成验收。当前不执行任何构建、建库或启动命令，正式开发从TASK-003的S1环境与数据库权限核对开始。
