# 前端工程入口

采用Vue3、TypeScript、Vite、Vue Router、Pinia、Element Plus，沿用[版本矩阵](../docs/development/environment/工具与依赖版本矩阵.md)选定版本；正式实施时核对并生成精确依赖锁。当前没有package.json或启动脚本，网页尚未开发。

详细设计和执行计划集中在[TASK-006](../docs/development/tasks/TASK-006-网页与首轮闭环验收.md)，总体边界见[页面与状态设计](../docs/design/frontend/页面与状态设计.md)，接口使用[当前有效契约](../contracts/effective/README.md)。

首轮仅面向电脑浏览器：登录、授权列表、详情、实时状态、控制与命令结果；主要尺寸1440×900，检查1280×720。完整历史、轨迹、CSV页面仍由TASK-010完善。本轮只整理文档，不创建工程、安装依赖或启动服务；TASK-006仍待开始。

正式开发从S1输入与接口盘点开始，复用TASK-005真实服务与双模拟节点。开发计划采用5173端口代理HTTP/WS至回环8080，保留明确Origin校验；Token仅内存，凭据不得进入前端公开配置。所有定位保持MOCK，门/告警未知不能显示正常，前端隐藏不能替代服务端授权。

启动、构建和浏览器复验命令将在实际工程建立后据实补充；当前没有可宣称运行成功的命令或浏览器证据。
