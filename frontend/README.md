# SmartBox电脑端网页

Vue3、TypeScript、Vite、Vue Router、Pinia与Element Plus工程已建立，TASK-006已完成电脑端网页与双模拟节点闭环。详细设计集中在[任务6](../docs/development/tasks/TASK-006-网页与首轮闭环验收.md)，实际结果见[验证报告](../docs/testing/reports/TASK-006-网页与首轮闭环验证报告.md)，精确版本见[版本矩阵](../docs/development/environment/工具与依赖版本矩阵.md)。

## 安装与启动

在项目根目录执行统一入口，Windows使用现有Node/npm：

```powershell
.venv/Scripts/python tools/frontend.py install
.venv/Scripts/python tools/frontend.py dev
```

install按锁文件安装依赖并准备匹配Chromium到忽略的artifacts；dev固定监听127.0.0.1:5173，访问[工作台](http://127.0.0.1:5173)。实际数据需要先按[网页启动指南](../docs/development/guides/网页启动与首轮验收.md)启动现有项目Broker、后端和两个模拟节点。前端通过代理访问回环8080，不直接连接数据库或MQTT。端口占用不静默切换。

## 检查与验收

```powershell
.venv/Scripts/python tools/frontend.py check
.venv/Scripts/python tools/frontend.py build
.venv/Scripts/python tools/frontend.py browser
.venv/Scripts/python tools/frontend.py integration --profile test --allow-test-reset --run-dir artifacts/task006/新的唯一目录
```

check为类型/单元/组件检查，browser使用受控接口进行隔离浏览器验证；integration才连接真实Broker、后端、MySQL及独立双节点。所有检查入口失败返回非零，不静默跳过必需项。集成必须使用显式test和清理同意，不能与其他使用smartbox_test的检查并行；可加--scenario authorization运行定向授权/Schema补查，不能替代完整场景。

## 范围与安全

首轮电脑端包含登录、授权箱体列表、详情、实时状态、五类授权控制及命令结果。主要尺寸1440×900，检查1280×720；完整历史筛选、轨迹和CSV页面仍由TASK-010完善，不要求手机布局。

HTTP/WS依据[当前有效契约](../contracts/effective/README.md)。Token仅内存，刷新需重新登录；前端不接收数据库/Broker密码或签名密钥。服务端授权为最终依据，前端白名单投影及清理避免敏感缓存残留。所有定位保持MOCK，未知门/告警不能显示正常；控制只操作模拟节点，编译和模拟结果不等于板端通过。

依赖/构建输出、浏览器、完整日志、截图及私有测试输入保存在忽略目录；受控报告只保留脱敏摘要和索引。自动入口清理自己启动的进程并核对端口，不停止共享MySQL。后续进入TASK-007真实采集与输出接入。
