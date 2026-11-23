# SmartBox 前端

Vue 3 + TypeScript + Vite 企业物流运营控制台。

## 启动

```powershell
cd frontend
npm ci
$env:SMARTBOX_API_TARGET = "http://127.0.0.1:8080"
npm run dev
```

打开 http://127.0.0.1:5173。登录预填可复制 `.env.example` 到 `.env.development.local`。

## 构建

```powershell
npm run typecheck
npm run build
```

## 页面

- 运营总览
- 物流箱资产：创建/编辑、模拟场景、启停
- 运输任务：创建、开始、结束
- 箱体详情八分区：环境、姿态、定位、告警、节点、控制、日志
- 告警中心（只读）
- 位置与设备（MOCK 地图）

## 边界

- 不包含前端测试、Playwright/Vitest 配置或原型探索工程。
- 地图为 MOCK；真实 GPS 不在本仓库范围。
- 不提交本地 `.env.*`、依赖或构建产物。
