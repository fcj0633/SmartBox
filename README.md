# SmartBox 企业物流运营控制台

SmartBox 是一个面向企业物流运营的前后端演示项目：

- **后端**：Java 21 + Spring Boot + MySQL + Flyway；
- **前端**：Vue 3 + TypeScript + Vite；
- **演示数据**：固定实物箱体 + 模拟箱体、MOCK 地图、环境/姿态/告警/运输/控制台。

> 本仓库主要面向前端/后端开发者与本地演示，不包含硬件操作、自动化测试或完整协议夹具。`firmware/` 仅作为源码参考保留。

## 1. 快速启动

完整步骤见 [前后端启动](docs/operations/前后端启动.md)。

```powershell
# 1. 创建 MySQL 数据库和账号（见文档）
# 2. 首次初始化数据库：账号和演示数据（单次执行后退出）
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--smartbox.bootstrap=true --smartbox.assets.bootstrap=true"

# 3. 启动 Web 后端
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080 --smartbox.demo.fleet=true"

# 4. 启动前端
cd frontend
npm ci
npm run dev
```

默认地址：

- 前端：http://127.0.0.1:5173
- 后端：http://127.0.0.1:8080
- 演示账号：`admin/admin`（仅本地演示）

运行前需要设置数据库、JWT、演示种子环境变量；`SMARTBOX_MQTT_ENABLED=false` 时不需要 Broker。

## 2. 目录

```text
backend/       Spring Boot 后端
frontend/      Vue 前端
firmware/      固件源码参考（不提供构建/烧录工具）
deploy/demo/   演示账号与模拟数据示例
docs/          需求、设计、任务、启动说明和项目演进
```

## 3. 当前能力

- 企业总览、物流箱资产、模拟资产创建/编辑/场景/启停；
- 运输任务创建/开始/结束、MOCK 地图；
- 箱体详情八分区：环境、姿态、定位、告警、设备节点、控制历史、日志；
- 企业控制中心命令提交与回执展示；
- 只读告警与运行记录。

## 4. 文档

- [文档入口](docs/README.md)
- [当前进度](docs/project/当前进度.md)
- [项目演进](docs/project/项目演进.md)
- [V2 需求](docs/requirements/V2-企业端物流运营控制台需求规格.md)

## 5. 边界

- 没有 `tools/`、`tests/`，也不提供自动化测试、模拟器、硬件烧录或真实板联调。
- 主程序只保留企业端页面和 `/api/v1/enterprise/console/**`；旧骑手、访客、`/boxes` 设备工作台及其 API 已移除。
- 后端 MQTT 可选；本地演示默认关闭，使用 `DemoFleetService` 生成模拟数据。启用时只连接远程 TLS Broker，不提供本地回退。
- 真实设备、生产安全、长期可靠性和完整协议验证不在本仓库范围；前后端不会直接连接开发板。
- 不提交密码、JWT、数据库连接或本地 secrets。
