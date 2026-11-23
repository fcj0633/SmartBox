# SmartBox 后端

Spring Boot 后端，提供登录、企业资产/运输、箱体详情、控制命令和只读告警接口。

## 结构

```text
src/main/java/cn/smartbox/
├── auth/          登录、JWT、当前身份
├── authorization/ 权限与箱体访问
├── asset/         演示资产种子
├── enterprise/    企业控制台 API、资产、运输、详情、控制
├── live/          MQTT/事件/查询/命令基础设施
├── config/        Security、Bootstrap、时钟
└── common/        统一响应、异常、request_id
src/main/resources/
├── application.yml
├── mapper/
└── db/migration/  V1 当前完整 schema
```

## 启动

完整环境变量和 MySQL 初始化见[前后端启动](../docs/operations/前后端启动.md)。

```powershell
# 首次初始化（单次执行后退出）
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--smartbox.bootstrap=true --smartbox.assets.bootstrap=true"

# 启动 Web 后端
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080 --smartbox.demo.fleet=true"
```

## 配置边界

- 数据库、JWT、演示种子通过环境变量提供，不提交真实密码。
- `SMARTBOX_MQTT_ENABLED=false` 时不需要 MQTT Broker；本地演示由 `DemoFleetService` 产生模拟数据。
- 后端启动时读取 `src/main/resources/contracts/effective/common.schema.json`；不要删除该文件。
- 本仓库不包含后端测试、测试夹具或开发工具。
- 旧 V1–V17 数据库需按启动文档备份后重建，不能直接套用 V1 基线。
