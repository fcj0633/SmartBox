<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { alarmRows, badgeClass, boxes, directions, events, pages, trendA, trendB, type DirectionId, type PageId } from './data';

const params = new URLSearchParams(window.location.search);
const direction = ref<DirectionId>((['command', 'industrial', 'route'].includes(params.get('direction') ?? '') ? params.get('direction') : 'command') as DirectionId);
const page = ref<PageId>((pages.some(item => item.id === params.get('page')) ? params.get('page') : 'dashboard') as PageId);
const selectedBox = ref(boxes[0]);
const search = ref('');
const statusFilter = ref('全部状态');
const detailTab = ref('总览');
const selectedAlarm = ref<(typeof alarmRows)[number] | null>(null);
const notificationOpen = ref(false);
const toast = ref('');
const tick = ref(0);
let toastTimer = 0;

const currentDirection = computed(() => directions.find(item => item.id === direction.value)!);
const filteredBoxes = computed(() => boxes.filter(box => {
  const query = search.value.trim().toLowerCase();
  const matchText = !query || `${box.id} ${box.name} ${box.route} ${box.profile}`.toLowerCase().includes(query);
  const matchStatus = statusFilter.value === '全部状态' || (statusFilter.value === '在线' ? box.online : !box.online);
  return matchText && matchStatus;
}));
const selectedPage = computed(() => pages.find(item => item.id === page.value)!);

watch([direction, page], ([nextDirection, nextPage]) => {
  const url = new URL(window.location.href);
  url.searchParams.set('direction', nextDirection);
  url.searchParams.set('page', nextPage);
  window.history.replaceState({}, '', url);
  document.title = `SmartBox · ${currentDirection.value.name} · ${selectedPage.value.label}`;
});

function notify(message: string) {
  toast.value = message;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => { toast.value = ''; }, 2400);
}

function openAlarm(alarm: (typeof alarmRows)[number]) { selectedAlarm.value = alarm; }
function closeAlarm() { selectedAlarm.value = null; }
function acknowledgeAlarm() {
  if (!selectedAlarm.value) return;
  notify(`${selectedAlarm.value.id} 已在演示界面中标记为已确认`);
  selectedAlarm.value = null;
}
function chooseBox(box: (typeof boxes)[number]) {
  selectedBox.value = box;
  if (page.value === 'operations') notify(`已选中 ${box.id} · 位置为 Mock Data`);
}
function pageStatus(item: PageId) {
  return item === 'dashboard' || item === 'operations' ? '规划 / Demo' : item === 'alarms' ? '演示模拟' : '现有基础 + 扩展';
}

const directionClass = computed(() => `direction-${direction.value}`);
const nodeTitle = computed(() => selectedBox.value.id === 'SBX-24026' ? 'ENV 节点' : 'ENV + POSE 节点');
const locationText = computed(() => selectedBox.value.id === 'SBX-24018' ? '上海市 · 虹桥片区' : selectedBox.value.id === 'SBX-24021' ? '杭州市 · 钱江新城' : selectedBox.value.id === 'SBX-24026' ? '南京市 · 江宁区' : '嘉兴市 · 南湖区');

const onKey = (event: KeyboardEvent) => {
  if (event.key === 'Escape') { closeAlarm(); notificationOpen.value = false; }
};
onMounted(() => { window.addEventListener('keydown', onKey); });
onUnmounted(() => { window.removeEventListener('keydown', onKey); window.clearTimeout(toastTimer); });
</script>

<template>
  <div class="explorer" :class="directionClass">
    <div class="direction-strip">
      <div class="direction-intro">
        <span class="micro-label">SMARTBOX · UI EXPLORATION</span>
        <strong>选择视觉方向</strong>
        <span class="prototype-warning">界面概念 · 全部为演示数据</span>
      </div>
      <div class="direction-switcher" role="tablist" aria-label="视觉方向">
        <button v-for="item in directions" :key="item.id" :class="{ active: direction === item.id }" role="tab" :aria-selected="direction === item.id" @click="direction = item.id">
          <span class="direction-switch-name">{{ item.name }}</span><small>{{ item.audience }}</small>
        </button>
      </div>
    </div>

    <div class="app-frame">
      <aside class="sidebar">
        <div class="brand-row">
          <div class="brand-symbol"><span></span><span></span><span></span></div>
          <div><strong>SMARTBOX</strong><small>{{ currentDirection.eyebrow }}</small></div>
        </div>
        <div class="workspace-select"><span class="workspace-avatar">SB</span><span><small>工作空间</small><strong>演示运营中心</strong></span><span class="chevron">⌄</span></div>
        <div class="nav-group-title">运营工作台</div>
        <nav class="side-nav" aria-label="页面导航">
          <button v-for="item in pages" :key="item.id" :class="{ selected: page === item.id }" @click="page = item.id">
            <span class="nav-glyph" aria-hidden="true">{{ item.id === 'dashboard' ? '⌂' : item.id === 'boxes' ? '▤' : item.id === 'detail' ? '◫' : item.id === 'alarms' ? '◉' : '⌖' }}</span>
            <span class="nav-copy"><span>{{ item.label }}</span><small v-if="direction === 'industrial' || direction === 'route'">{{ pageStatus(item.id) }}</small></span>
            <span v-if="item.id === 'alarms'" class="nav-count">3</span>
          </button>
        </nav>
        <template v-if="direction === 'industrial'">
          <div class="nav-group-title tree-title">资产树 <span>⌄</span></div>
          <div class="asset-tree">
            <button class="tree-row selected-tree"><span>⌄</span> SmartBox Fleet</button>
            <button class="tree-row indent"><span class="tree-dot dot-teal"></span>ENV · 4 节点</button>
            <button class="tree-row indent"><span class="tree-dot dot-violet"></span>POSE · 3 节点</button>
            <button class="tree-row indent"><span class="tree-dot dot-orange"></span>待确认事件 · 2</button>
          </div>
        </template>
        <template v-else-if="direction === 'route'">
          <div class="route-mini-heading">运 输 批 次 <button aria-label="选择运输批次">⌄</button></div>
          <div class="route-mini-card"><span class="route-line"></span><small>在途 · 4 个箱体</small><strong>华东冷链 / 批次 0821</strong><div>上海 → 苏州 → 南京</div></div>
          <div class="route-mini-card muted-route"><span class="route-line"></span><small>待发运 · 2 个箱体</small><strong>生物样本 / 批次 0819</strong><div>杭州 → 嘉兴</div></div>
        </template>
        <div class="sidebar-bottom">
          <div class="service-row"><span class="service-dot"></span><span>应用服务状态</span><strong>界面演示</strong></div>
          <div class="user-row"><span class="user-avatar">LW</span><span><strong>林婉清</strong><small>物流运营 · 示例身份</small></span><button @click="notify('用户菜单：演示界面')">···</button></div>
          <small class="sidebar-note">不是在线服务数据 · DEMO</small>
        </div>
      </aside>

      <div class="main-column">
        <header class="topbar">
          <div class="breadcrumbs"><span>运营中心</span><i>/</i><strong>{{ selectedPage.label }}</strong><template v-if="page === 'detail'"><i>/</i><span>{{ selectedBox.id }}</span></template></div>
          <div class="top-actions">
            <label class="global-search"><span>⌕</span><input v-model="search" aria-label="全局搜索箱体" placeholder="搜索箱体编号 / 设备" /><kbd>⌘ K</kbd></label>
            <span class="sync-state"><span class="sync-dot"></span>演示数据 <i>·</i> 14:32:08</span>
            <div class="notification-wrap"><button class="icon-button notification-button" aria-label="通知中心" :aria-expanded="notificationOpen" @click="notificationOpen = !notificationOpen">♧<span></span></button><div v-if="notificationOpen" class="notification-popover"><strong>通知中心 <small>Demo</small></strong><p>3 条待处理事件</p><button @click="page = 'alarms'; notificationOpen = false">前往告警中心 →</button></div></div>
            <button class="top-avatar" aria-label="用户菜单" @click="notify('林婉清 · 物流运营（示例身份）')">LW</button>
          </div>
        </header>

        <main class="content-area">
          <div class="page-heading">
            <div><div class="heading-eyebrow">{{ currentDirection.eyebrow }} <span>·</span> 14:32 更新 <b class="demo-badge">DEMO DATA</b></div><h1>{{ selectedPage.label }}</h1><p>{{ page === 'dashboard' ? '跨区域物流箱运行状态、异常待办与运输态势。' : page === 'boxes' ? '管理授权范围内的物流箱资产与节点状态。' : page === 'detail' ? '查看箱体环境、设备状态、运输信息与运行记录。' : page === 'alarms' ? '跟进活跃异常、处置进度与关联资产。' : direction === 'route' ? '位置为示意坐标，路线不代表实际运输轨迹。' : '管理物流箱位置与 ENV / POSE 设备节点。' }}</p></div>
            <div class="heading-actions"><span class="page-state-tag">{{ pageStatus(page) }}</span><button class="refresh-button" @click="tick++">↻ <span>刷新</span></button><button v-if="page === 'boxes'" class="primary-button" @click="notify('创建箱体 · Planned')">＋ 新建物流箱</button></div>
          </div>

          <template v-if="page === 'dashboard'">
            <section class="kpi-row" aria-label="运营关键指标">
              <article class="kpi-block"><div class="kpi-label">物流箱总数 <span>ⓘ</span></div><div class="kpi-value">248 <small>个</small></div><div class="kpi-foot"><span class="subtle-dot"></span>示例资产总量 <b>Mock</b></div></article>
              <article class="kpi-block"><div class="kpi-label">在线设备 <span class="kpi-info">ENV / POSE</span></div><div class="kpi-value">231 <small>/ 248</small></div><div class="kpi-foot positive">在线率 93.1% <span>↑ 2.4%</span></div></article>
              <article class="kpi-block"><div class="kpi-label">活跃告警 <span class="kpi-info">待确认</span></div><div class="kpi-value alert-value">08 <small>条</small></div><div class="kpi-foot danger-foot"><i></i>严重 02 <span>·</span> 警告 06</div></article>
              <article class="kpi-block"><div class="kpi-label">今日完成运输 <span class="kpi-info">较昨日</span></div><div class="kpi-value">126 <small>单</small></div><div class="kpi-foot positive">完成率 87.5% <span>↑ 4.8%</span></div></article>
            </section>

            <section v-if="direction === 'route'" class="route-dashboard-layout">
              <div class="route-map-panel"><div class="panel-header"><div><h2>在途资产分布</h2><p>华东区域 · 位置示意 <span class="mock-chip">地图 Mock</span></p></div><button class="text-action" @click="page = 'operations'">打开地图全屏 ↗</button></div><div class="map-canvas large-map"><MapScene :active-box="selectedBox.id" @select="chooseBox" /><div class="map-zoom"><button @click="tick++">＋</button><button @click="tick++">−</button></div><div class="map-legend"><span><i class="legend-green"></i>运输中</span><span><i class="legend-orange"></i>待处理</span><span><i class="legend-gray"></i>已签收</span></div></div></div>
              <div class="route-task-panel"><div class="panel-header"><div><h2>运输任务</h2><p>4 个箱体 · 3 个批次</p></div><button class="more-button" aria-label="更多运输任务">···</button></div><button v-for="box in boxes" :key="box.id" class="route-task-row" :class="{ 'active-route-task': selectedBox.id === box.id }" @click="chooseBox(box)"><span class="task-marker" :class="box.online ? 'marker-green' : 'marker-orange'"></span><span class="task-main"><strong>{{ box.id }}</strong><small>{{ box.route }}</small></span><span class="task-time"><span :class="['status-pill', badgeClass(box.health)]">{{ box.health }}</span><small>{{ box.updated }}</small></span></button><div class="route-task-footer"><button class="text-action" @click="page = 'boxes'">查看全部资产 →</button></div></div>
            </section>
            <section v-else-if="direction === 'industrial'" class="industrial-dashboard-layout">
              <div class="industrial-health-panel panel"><div class="panel-header"><div><h2>资产健康矩阵</h2><p>按节点层级聚合 · 示例数据</p></div><span class="health-window">最近 24 小时⌄</span></div><div class="health-grid"><div class="health-grid-head"><span>资产 / 节点</span><span>连通</span><span>环境</span><span>姿态</span><span>健康</span></div><div v-for="box in boxes" :key="box.id" class="health-grid-row" @click="chooseBox(box); page = 'detail'"><strong>{{ box.id }}<small>{{ box.name }}</small></strong><span :class="['matrix-state', box.online ? 'matrix-ok' : 'matrix-error']">{{ box.online ? '在線' : '离线' }}</span><span class="matrix-state matrix-ok">{{ box.online ? '正常' : '未知' }}</span><span class="matrix-state matrix-ok">{{ box.id === 'SBX-24026' ? '—' : '正常' }}</span><span class="health-ring" :class="box.online ? 'ring-green' : 'ring-orange'">{{ box.online ? '96' : '—' }}</span></div></div><div class="panel-footer-note"><span class="tiny-dot"></span>健康分是原型演示字段，未接入业务计算</div></div>
              <div class="industrial-trend-panel panel"><div class="panel-header"><div><h2>环境遥测</h2><p>SBX-24018 · ENV</p></div><span class="health-window">温度 · 24h⌄</span></div><div class="industrial-chart-legend"><span><i class="chart-swatch teal-swatch"></i>温度 °C</span><span><i class="chart-swatch lime-swatch"></i>参考范围</span><span class="mock-chip">Mock</span></div><TrendChart :line="trendA" :secondary="true" :industrial="true" /><div class="chart-axis"><span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>现在</span></div><div class="trend-current"><span>最新读数 <strong>4.8 °C</strong></span><span>参考范围 <strong>2–5 °C</strong></span></div></div>
              <div class="industrial-events panel"><div class="panel-header"><div><h2>规则与事件</h2><p>异常规则命中记录</p></div><button class="text-action" @click="page = 'alarms'">全部事件 →</button></div><div v-for="event in events.slice(0, 3)" :key="event.time" class="industrial-event-row"><span class="event-timeline-dot" :class="`tone-${event.tone}`"></span><span><strong>{{ event.title }}</strong><small>{{ event.box }} · {{ event.time }}</small></span><span class="mock-chip">Demo</span></div></div>
            </section>
            <section v-else class="dashboard-grid">
              <div class="panel trend-panel"><div class="panel-header"><div><h2>环境状态趋势</h2><p>在途物流箱温度监测 · 最近 24 小时</p></div><div class="panel-actions"><span class="mock-chip">Mock Data</span><button class="select-button" @click="tick++">最近 24 小时⌄</button></div></div><div class="chart-legend"><span><i class="chart-swatch teal-swatch"></i>温度变化</span><span><i class="chart-swatch line-swatch"></i>画像区间</span></div><TrendChart :line="trendA" /><div class="chart-axis"><span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>现在</span></div><div class="trend-summary"><div><small>当前均值</small><strong>4.9 <i>°C</i></strong><span class="trend-up">↑ 0.3 °C</span></div><div><small>监测箱体</small><strong>186 <i>个</i></strong><span>冷链画像</span></div><div><small>超限时长</small><strong>12 <i>分钟</i></strong><span class="subtle-note">演示统计</span></div></div></div>
              <div class="panel event-panel"><div class="panel-header"><div><h2>需要关注</h2><p>未完成处置的事件</p></div><button class="text-action" @click="page = 'alarms'">查看全部 →</button></div><div class="focus-callout"><div class="focus-icon">!</div><div><strong>2 条严重事件待确认</strong><small>最早发生于 14:26 · SBX-24018</small></div><button @click="page = 'alarms'">处理</button></div><div v-for="event in events.slice(0, 3)" :key="event.time" class="event-row"><span class="timeline-line" :class="`tone-${event.tone}`"></span><div class="event-content"><small>{{ event.time }} · {{ event.box }}</small><strong>{{ event.title }}</strong><span>{{ event.detail }}</span></div><span class="status-dot" :class="`tone-${event.tone}`"></span></div></div>
              <div class="panel state-panel"><div class="panel-header"><div><h2>设备节点在线情况</h2><p>ENV / POSE 通信状态</p></div><span class="mock-chip">Mock</span></div><div class="node-state-row"><div class="donut-chart"><span>93.1%<small>在线率</small></span></div><div class="node-legend"><span><i class="legend-green"></i>在线 <strong>231</strong></span><span><i class="legend-gray"></i>离线 <strong>17</strong></span><span><i class="legend-orange"></i>未知 <strong>—</strong></span></div></div><div class="node-progress"><span><i></i></span><small>ENV 节点 <b>238 / 248</b></small><span><i></i></span><small>POSE 节点 <b>216 / 231</b></small></div></div>
              <div class="panel recent-panel"><div class="panel-header"><div><h2>最近动态</h2><p>设备事件与运输更新</p></div><button class="more-button" aria-label="最近动态更多" @click="page = 'alarms'">···</button></div><div v-for="event in events" :key="event.time" class="recent-row"><span class="recent-mark" :class="`tone-${event.tone}`"></span><div><strong>{{ event.title }}</strong><small>{{ event.box }} <i>·</i> {{ event.time }}</small></div><button aria-label="查看事件" @click="page = 'alarms'">↗</button></div></div>
            </section>
          </template>

          <template v-else-if="page === 'boxes'">
            <section class="asset-summary-row"><div><small>授权物流箱</small><strong>24 <i>/ 演示列表 4</i></strong></div><div><small>在线节点</small><strong>7 <i>/ 8</i></strong></div><div><small>需关注</small><strong class="summary-attention">2 <i>个箱体</i></strong></div><div class="asset-view-switch"><button class="view-active" aria-label="表格视图">☷</button><button aria-label="卡片视图" @click="notify('卡片视图 · 设计稿预留')">▦</button></div></section>
            <section class="panel asset-table-panel"><div class="asset-toolbar"><label class="table-search"><span>⌕</span><input v-model="search" placeholder="搜索编号、名称或运输路线" aria-label="搜索物流箱" /></label><select v-model="statusFilter" aria-label="在线状态筛选"><option>全部状态</option><option>在线</option><option>离线</option></select><button class="filter-button" @click="notify('更多资产筛选条件 · Planned')">⫶ 筛选 <span>2</span></button><button class="filter-button" @click="notify('排序 · 最近上报')">⇅ 排序</button><div class="toolbar-fill"></div><button class="export-button" @click="notify('批量导出 · Planned')">⇩ 导出</button></div><div class="asset-table-wrap"><table class="asset-table"><thead><tr><th><input type="checkbox" aria-label="选择全部资产" @change="notify('批量选择为原型交互')" /></th><th>物流箱</th><th>运输路线</th><th>设备节点</th><th>环境摘要 <small>MOCK</small></th><th>健康状态</th><th>最近上报</th><th></th></tr></thead><tbody><tr v-for="box in filteredBoxes" :key="box.id" @click="chooseBox(box); page = 'detail'"><td @click.stop><input type="checkbox" :aria-label="`选择 ${box.id}`" /></td><td><div class="asset-name-cell"><span class="box-avatar" :class="box.online ? '' : 'offline-avatar'">▣</span><span><strong>{{ box.id }}</strong><small>{{ box.name }}</small></span></div></td><td><span class="route-cell">{{ box.route }}</span><small class="table-subline">{{ box.status }}</small></td><td><span class="node-pair"><i class="env-node">E</i>{{ box.node }}<i v-if="box.node.includes('POSE')" class="pose-node">P</i></span><small class="table-subline">{{ box.online ? '在线 · 双节点' : '节点离线' }}</small></td><td><strong>{{ box.temp === '—' ? '未知' : `${box.temp} °C` }}</strong><small class="table-subline">{{ box.humidity === '—' ? '湿度未知' : `${box.humidity}% RH` }} <span v-if="box.id === 'SBX-24018'">· {{ box.light }} lux</span></small></td><td><span :class="['status-pill', badgeClass(box.health)]"><i></i>{{ box.health }}</span><small class="table-subline">{{ box.online ? '告警待确认 1' : '数据已过期' }}</small></td><td><strong class="last-seen">{{ box.updated }}</strong><small class="table-subline">{{ box.online ? '刚刚' : '1 小时前' }}</small></td><td><button class="row-arrow" aria-label="查看物流箱详情">↗</button></td></tr><tr v-if="!filteredBoxes.length"><td colspan="8" class="empty-state"><span>⌕</span><strong>没有匹配的物流箱</strong><small>尝试修改搜索词或状态筛选</small></td></tr></tbody></table></div><div class="table-pagination"><span>显示 1–{{ filteredBoxes.length }} 条 <i>·</i> 示例数据</span><div><button disabled>‹</button><button class="page-number">1</button><button disabled>›</button></div></div></section>
            <div class="capability-note"><span>i</span><span><strong>资产能力分层</strong> 当前项目支持已授权箱体列表与详情查看；创建、分组、标签、批量操作和生命周期管理为规划扩展。表中示例值为 Mock Data，真实页面必须保留未知与过期状态。</span></div>
          </template>

          <template v-else-if="page === 'detail'">
            <section class="detail-identity"><div class="detail-box-icon">▣</div><div class="detail-title"><div><h2>{{ selectedBox.id }}</h2><span :class="['status-pill', badgeClass(selectedBox.health)]"><i></i>{{ selectedBox.health }}</span><span class="asset-code">{{ selectedBox.profile }}</span></div><p>{{ selectedBox.name }} <span>·</span> {{ selectedBox.route }} <span>·</span> {{ nodeTitle }}</p></div><div class="detail-identity-actions"><span class="last-sync-pill"><span class="sync-dot"></span>更新于 {{ selectedBox.updated }} <i>· MOCK</i></span><button class="secondary-button" @click="page = 'boxes'">返回资产</button><button class="primary-button" @click="notify('远程控制保留当前权限、确认和实际执行回执流程')">设备控制</button></div></section>
            <nav class="detail-tabs" aria-label="箱体详情分区"><button v-for="tab in ['总览', '环境监测', '姿态监测', '定位轨迹', '告警事件', '设备节点', '控制中心', '运行日志']" :key="tab" :class="{ 'tab-current': detailTab === tab }" @click="detailTab = tab">{{ tab }}<span v-if="['定位轨迹', '告警事件', '控制中心'].includes(tab)" class="planned-dot"></span></button></nav>
            <div class="tab-state-note"><span class="live-indicator"></span>{{ detailTab }} <span>·</span> 当前页面演示视图 <b>{{ ['总览', '环境监测', '设备节点', '控制中心'].includes(detailTab) ? '现有能力 + Mock 表达' : 'Planned / Mock' }}</b></div>
            <section v-if="detailTab === '总览' || detailTab === '环境监测'" class="detail-grid">
              <div class="panel environment-panel"><div class="panel-header"><div><h2>当前环境</h2><p>最后有效采样 · ENV 节点</p></div><span class="quality-pill"><i></i>质量：{{ selectedBox.online ? '有效 · Mock' : '过期 · Unknown' }}</span></div><div class="environment-values"><div class="environment-value"><div class="sensor-icon temp-icon">⌁</div><div><small>温度</small><strong>{{ selectedBox.temp }}<i v-if="selectedBox.temp !== '—'">°C</i></strong><span>画像范围 2–5 °C <b>Mock</b></span></div></div><div class="environment-value"><div class="sensor-icon humidity-icon">◌</div><div><small>相对湿度</small><strong>{{ selectedBox.humidity }}<i v-if="selectedBox.humidity !== '—'">%</i></strong><span>画像范围 45–70% <b>Mock</b></span></div></div><div class="environment-value"><div class="sensor-icon light-icon">☼</div><div><small>光照</small><strong>{{ selectedBox.light }}<i v-if="selectedBox.light !== '—'">lux</i></strong><span>当前状态按回传字段显示</span></div></div></div><div class="detail-chart-heading"><strong>环境变化</strong><div class="panel-actions"><span class="mock-chip">Demo Trend</span><button class="select-button" @click="tick++">最近 6 小时⌄</button></div></div><TrendChart :line="trendB" :industrial="direction === 'industrial'" /><div class="chart-axis"><span>08:00</span><span>10:00</span><span>12:00</span><span>14:00</span><span>现在</span></div></div>
              <div class="panel health-panel"><div class="panel-header"><div><h2>设备节点</h2><p>箱体与采样节点关系</p></div><button class="text-action" @click="page = 'operations'">设备管理 →</button></div><div class="node-card"><div class="node-icon env-node-icon">ENV</div><div class="node-main"><strong>环境采集节点</strong><small>温湿度 · 光照 <span>·</span> {{ selectedBox.online ? '实时状态 Mock' : '节点状态已过期' }}</small></div><span :class="['status-pill', selectedBox.online ? 'good' : 'critical']"><i></i>{{ selectedBox.online ? '在线' : '离线' }}</span></div><div class="node-card"><div class="node-icon pose-node-icon">POSE</div><div class="node-main"><strong>姿态采集节点</strong><small>加速度 · 倾角 <span>·</span> {{ selectedBox.node.includes('POSE') ? '遥测演示' : '未绑定 / 未知' }}</small></div><span :class="['status-pill', selectedBox.node.includes('POSE') ? 'good' : 'neutral']"><i></i>{{ selectedBox.node.includes('POSE') ? '在线' : '未知' }}</span></div><div class="node-meta-grid"><div><small>固件版本</small><strong>v0.9.7 <i>Mock</i></strong></div><div><small>最近心跳</small><strong>{{ selectedBox.updated }}</strong></div><div><small>通信信号</small><strong>— <i>未接入</i></strong></div><div><small>电池状态</small><strong>— <i>未接入</i></strong></div></div><div class="unknown-note">门状态 <strong>UNKNOWN</strong><span>当前无完整门状态判断器；不将未知显示为关闭。</span></div></div>
              <div class="panel travel-panel"><div class="panel-header"><div><h2>运输信息</h2><p>当前任务与位置摘要</p></div><span class="planned-pill">定位扩展 · Mock</span></div><div class="travel-route"><span class="route-dot origin-dot"></span><span class="travel-line"></span><span class="route-dot destination-dot"></span><div class="route-origin"><small>起运地</small><strong>上海 · 虹桥转运中心</strong><span>10:12 装载</span></div><div class="route-destination"><small>目的地</small><strong>苏州 · 工业园区</strong><span>预计 16:40 到达 · Mock</span></div></div><div class="location-meta"><span>当前位置</span><strong>{{ locationText }}</strong><button @click="page = 'operations'">查看演示地图 ↗</button></div><div class="location-disclaimer">地图、坐标与 ETA 为规划能力演示，不是 GPS 实测。</div></div>
              <div class="panel recent-detail-panel"><div class="panel-header"><div><h2>最近事件</h2><p>箱体关联事件时间线</p></div><button class="text-action" @click="page = 'alarms'">全部事件 →</button></div><div v-for="event in events.slice(0, 3)" :key="event.time" class="detail-event-row"><span class="detail-timeline-dot" :class="`tone-${event.tone}`"></span><span><strong>{{ event.title }}</strong><small>{{ event.time }} · {{ event.detail }}</small></span></div></div>
            </section>
            <section v-else class="detail-placeholder panel"><div class="placeholder-visual"><span>{{ detailTab === '定位轨迹' ? '⌖' : detailTab === '姿态监测' ? '⌁' : detailTab === '控制中心' ? '⏻' : '◷' }}</span></div><div class="placeholder-copy"><span class="planned-pill">{{ detailTab === '设备节点' ? 'Mock Fields' : 'Planned' }}</span><h2>{{ detailTab }}工作区</h2><p>此分区展示完整产品原型的结构方向。地图、冲击事件、告警工作流与控制任务需要分别以真实接口或明确标注的演示数据接入；当前不会伪装成已连通能力。</p><div class="placeholder-tags"><span>数据来源说明</span><span>节点状态分层</span><span>权限角色裁剪</span></div><button class="secondary-button" @click="detailTab = '总览'">返回总览</button></div></section>
          </template>

          <template v-else-if="page === 'alarms'">
            <section class="alarm-kpi-row"><div><small>活跃告警</small><strong>08 <i>条</i></strong><span class="alarm-kpi-note">全部为演示数据</span></div><div><small>严重</small><strong class="severity-critical">02 <i>条</i></strong><span>需优先处理</span></div><div><small>待确认</small><strong class="severity-warning">05 <i>条</i></strong><span>等待操作人员</span></div><div><small>已恢复</small><strong>12 <i>条</i></strong><span>过去 24 小时</span></div><div class="alarm-distribution"><span class="severity-stack"><i></i><i></i><i></i></span><small>事件等级分布 <b>Mock</b></small></div></section>
            <section class="panel alarm-workspace"><div class="alarm-workspace-heading"><div><h2>事件处置队列</h2><p>待确认、处理中与已恢复事件</p></div><div class="alarm-actions"><button class="filter-button" @click="notify('筛选器：等级 / 状态 / 设备 / 时间')">⫶ 过滤 <span>3</span></button><button class="filter-button" @click="notify('排序：最近发生')">⇅ 最近发生⌄</button><button class="export-button" @click="notify('告警导出 · Planned')">⇩ 导出</button></div></div><div class="alarm-filter-tabs"><button class="alarm-tab-current">全部事件 <span>08</span></button><button @click="notify('状态筛选：待确认')">待确认 <span>05</span></button><button @click="notify('状态筛选：处理中')">处理中 <span>03</span></button><button @click="notify('状态筛选：已恢复')">已恢复 <span>12</span></button><div class="toolbar-fill"></div><label class="alarm-search"><span>⌕</span><input v-model="search" placeholder="搜索事件 / 箱体编号" /></label></div><div class="alarm-table-wrap"><table class="alarm-table"><thead><tr><th>严重程度</th><th>告警与来源</th><th>关联资产</th><th>触发值</th><th>发生时间</th><th>持续时间</th><th>处置状态</th><th></th></tr></thead><tbody><tr v-for="alarm in alarmRows.filter(item => !search || `${item.id} ${item.title} ${item.box}`.toLowerCase().includes(search.toLowerCase()))" :key="alarm.id" @click="openAlarm(alarm)"><td><span :class="['severity-label', badgeClass(alarm.severity)]"><i></i>{{ alarm.severity }}</span></td><td><div class="alarm-title-cell"><strong>{{ alarm.title }}</strong><small>{{ alarm.source }} · {{ alarm.id }} <b>Demo</b></small></div></td><td><button class="asset-link" @click.stop="selectedBox = boxes.find(box => box.id === alarm.box)!; page = 'detail'">{{ alarm.box }} ↗</button></td><td>{{ alarm.value }}</td><td>{{ alarm.started }}<small class="table-subline">2026-11-17 · Mock</small></td><td>{{ alarm.duration }}</td><td><span :class="['status-pill', badgeClass(alarm.state)]"><i></i>{{ alarm.state }}</span></td><td><button class="row-arrow" aria-label="打开告警详情">↗</button></td></tr><tr v-if="!alarmRows.some(item => !search || `${item.id} ${item.title} ${item.box}`.toLowerCase().includes(search.toLowerCase()))"><td colspan="8" class="empty-state"><strong>没有匹配的事件</strong><small>调整搜索条件后重试</small></td></tr></tbody></table></div><div class="alarm-footnote">告警生命周期与规则阈值将在业务规则完成后接入；示例数据仅用于设计评审。</div></section>
          </template>

          <template v-else>
            <section v-if="direction === 'route'" class="map-console"><div class="map-console-main panel"><div class="map-console-toolbar"><label class="map-search"><span>⌕</span><input v-model="search" placeholder="搜索箱体编号或地点" /></label><div class="map-layer-switch"><button class="layer-active">路线图</button><button @click="notify('卫星底图 · Planned')">卫星图</button></div><button class="filter-button" @click="notify('地图过滤：在线 / 状态 / 批次')">⫶ 筛选</button><span class="mock-chip">Mock Map</span></div><div class="map-canvas console-map"><MapScene :active-box="selectedBox.id" @select="chooseBox" /><div class="map-floating-summary"><span class="summary-icon">⌖</span><span><small>在途箱体</small><strong>24 <i>个</i></strong></span><span class="summary-divider"></span><span><small>运输异常</small><strong class="summary-attention">02 <i>个</i></strong></span></div><div class="map-zoom"><button @click="tick++">＋</button><button @click="tick++">−</button><button @click="notify('回到全部资产范围')">⌖</button></div><div class="map-attribution">概念路线图 · 非地图服务 · MOCK</div></div></div><aside class="map-asset-panel panel"><div class="map-panel-title"><div><h2>在途物流箱</h2><p>按当前位置排序 · {{ filteredBoxes.length }} 个结果</p></div><button class="more-button" aria-label="资产列表设置">···</button></div><div class="map-asset-list"><button v-for="box in filteredBoxes" :key="box.id" class="map-asset-row" :class="{ 'map-asset-selected': selectedBox.id === box.id }" @click="chooseBox(box)"><span class="map-status-marker" :class="box.online ? 'marker-green' : 'marker-orange'"></span><span class="map-asset-details"><strong>{{ box.id }}</strong><small>{{ box.name }}</small><span>{{ box.route }} <i>·</i> {{ box.online ? '最近更新' : '数据已过期' }} {{ box.updated }}</span></span><span class="map-row-chevron">›</span></button></div><div class="map-selected-card"><div class="selected-card-title"><div><span class="mock-chip">Mock</span><strong>{{ selectedBox.id }}</strong></div><button aria-label="收起资产详情" @click="selectedBox = boxes[0]">×</button></div><div class="selected-card-place"><small>演示位置</small><strong>{{ locationText }}</strong><span>31.2304° N, 121.4737° E <i>· 示例坐标</i></span></div><div class="selected-card-grid"><div><small>环境温度</small><strong>{{ selectedBox.temp }}<i v-if="selectedBox.temp !== '—'">°C</i></strong></div><div><small>设备状态</small><strong>{{ selectedBox.online ? '在线' : '离线' }}</strong></div></div><button class="primary-button wide-button" @click="page = 'detail'">查看箱体详情 ↗</button></div><div class="map-planned-note">真实地图、轨迹回放、围栏和定位异常均为 Planned。</div></aside></section>
            <section v-else class="device-workspace">
              <aside class="device-tree-panel panel"><div class="panel-header"><div><h2>设备层级</h2><p>资产与采样节点关系</p></div><button class="more-button" @click="notify('设备绑定 / 解绑 · Planned')">＋</button></div><div class="device-tree-search"><span>⌕</span><input v-model="search" placeholder="搜索箱体 / 节点" /></div><div class="device-tree-group"><small>物流箱资产 <i>4</i></small><button v-for="box in filteredBoxes" :key="box.id" class="device-tree-box" :class="{ 'device-tree-selected': selectedBox.id === box.id }" @click="chooseBox(box)"><span class="tree-caret">⌄</span><span class="box-avatar small-box-avatar">▣</span><span><strong>{{ box.id }}</strong><small>{{ box.name }}</small></span></button></div><div class="device-node-subtree"><button class="node-tree-row" :class="{ 'node-tree-selected': detailTab === '环境监测' }" @click="detailTab = '环境监测'"><i class="env-node">E</i><span><strong>ENV · 环境节点</strong><small>{{ selectedBox.online ? '在线 · Mock' : '离线 · Mock' }}</small></span><i class="node-health">{{ selectedBox.online ? '●' : '○' }}</i></button><button class="node-tree-row" @click="detailTab = '姿态监测'"><i class="pose-node">P</i><span><strong>POSE · 姿态节点</strong><small>{{ selectedBox.node.includes('POSE') ? '在线 · Mock' : '未绑定 / 未知' }}</small></span><i class="node-health">{{ selectedBox.node.includes('POSE') ? '●' : '○' }}</i></button></div><div class="device-tree-hint">资产树和设备绑定功能为 Planned。</div></aside>
              <div class="device-detail-panel panel"><div class="device-detail-header"><div class="device-large-icon">{{ detailTab === '姿态监测' ? 'P' : 'E' }}</div><div><div class="device-title-line"><h2>{{ detailTab === '姿态监测' ? 'POSE 姿态采集节点' : 'ENV 环境采集节点' }}</h2><span :class="['status-pill', selectedBox.online ? 'good' : 'critical']"><i></i>{{ selectedBox.online ? '在线 · Mock' : '离线 · Mock' }}</span></div><p>所属箱体 <button @click="page = 'detail'">{{ selectedBox.id }} ↗</button> <span>·</span> 节点编号 ENV-0018 <span>·</span> 固件 v0.9.7 <b>Demo</b></p></div><div class="device-detail-actions"><button class="secondary-button" @click="notify('连接诊断 · Planned')">诊断设备</button><button class="more-button" aria-label="设备更多操作" @click="notify('设备绑定 / 编辑 · Planned')">···</button></div></div><div class="device-tabs"><button :class="{ active: detailTab === '总览' }" @click="detailTab = '总览'">概览</button><button :class="{ active: detailTab === '环境监测' }" @click="detailTab = '环境监测'">遥测</button><button :class="{ active: detailTab === '运行日志' }" @click="detailTab = '运行日志'">设备日志</button><button @click="notify('能力描述 · Planned')">能力信息</button><span class="mock-chip">设备管理 Planned</span></div><div class="device-metric-row"><div><small>最近心跳</small><strong>{{ selectedBox.online ? selectedBox.updated : '12:58:13' }}</strong><span>{{ selectedBox.online ? '时间为演示数据' : '超过演示心跳窗口' }}</span></div><div><small>上报频率</small><strong>5 <i>秒 / 帧</i></strong><span>Demo configuration</span></div><div><small>信号强度</small><strong>— <i>未接入</i></strong><span>不展示虚构数值</span></div><div><small>电池状态</small><strong>— <i>未接入</i></strong><span>当前没有电量字段</span></div></div><div class="device-chart-block"><div class="panel-header"><div><h2>{{ detailTab === '姿态监测' ? '姿态采样窗口' : '遥测数据趋势' }}</h2><p>绑定到 {{ selectedBox.id }} · 示例时序</p></div><div class="panel-actions"><span class="mock-chip">Mock Series</span><button class="select-button" @click="tick++">最近 1 小时⌄</button></div></div><TrendChart :line="detailTab === '姿态监测' ? trendB : trendA" :industrial="direction === 'industrial'" /><div class="chart-axis"><span>13:30</span><span>13:45</span><span>14:00</span><span>14:15</span><span>14:30</span></div></div><div class="device-log-block"><div class="panel-header"><div><h2>最近设备事件</h2><p>连接与上报记录</p></div><button class="text-action" @click="notify('完整设备运行日志 · Planned')">查看日志 →</button></div><div v-for="event in events.slice(0, 2)" :key="event.time" class="device-log-row"><span class="log-time">{{ event.time }}</span><span class="log-state-dot" :class="`tone-${event.tone}`"></span><strong>{{ event.title }}</strong><span>{{ event.detail }}</span><b>Demo</b></div></div><div class="device-footer-note">设备独立管理、诊断、绑定、版本和能力信息尚未接入生产接口。</div></div>
            </section>
          </template>
        </main>

        <footer class="app-footer"><span>SMARTBOX · 智能物流箱平台概念稿</span><span>所有数值、事件与位置均为 DEMO / MOCK · 当前能力以项目服务端实际支持为准</span></footer>
      </div>
    </div>

    <div v-if="selectedAlarm" class="drawer-backdrop" @click.self="closeAlarm"><aside class="alarm-drawer" role="dialog" aria-modal="true" :aria-label="`告警详情 ${selectedAlarm.id}`"><div class="drawer-top"><div><span class="drawer-eyebrow">ALARM DETAIL · DEMO</span><h2>{{ selectedAlarm.title }}</h2></div><button class="close-button" aria-label="关闭告警详情" @click="closeAlarm">×</button></div><div class="drawer-status-row"><span :class="['severity-label', badgeClass(selectedAlarm.severity)]"><i></i>{{ selectedAlarm.severity }}</span><span :class="['status-pill', badgeClass(selectedAlarm.state)]"><i></i>{{ selectedAlarm.state }}</span><span>{{ selectedAlarm.id }}</span></div><div class="drawer-section"><small>关联物流箱</small><button class="drawer-asset-link" @click="selectedBox = boxes.find(box => box.id === selectedAlarm!.box)!; closeAlarm(); page = 'detail'">{{ selectedAlarm.box }} · 查看详情 →</button></div><div class="drawer-section"><small>告警来源与触发值</small><strong>{{ selectedAlarm.source }}</strong><p>{{ selectedAlarm.value }}</p></div><div class="drawer-section"><small>事件说明</small><p>{{ selectedAlarm.note }}</p></div><div class="drawer-section timeline-section"><small>生命周期</small><div class="lifecycle-item"><i class="life-active"></i><span><strong>告警触发</strong><small>{{ selectedAlarm.started }} · 规则数据为 Mock</small></span></div><div class="lifecycle-item"><i></i><span><strong>{{ selectedAlarm.state }}</strong><small>操作轨迹为演示内容 · Planned</small></span></div><div class="lifecycle-item"><i></i><span><strong>恢复确认</strong><small>等待真实规则生命周期接入</small></span></div></div><div class="drawer-comment"><label for="alarm-comment">处置备注</label><textarea id="alarm-comment" placeholder="输入处置说明（本原型不会保存到后端）"></textarea></div><div class="drawer-footer"><button class="secondary-button" @click="notify('转交处理 · Planned')">转交</button><button class="primary-button" @click="acknowledgeAlarm">确认告警</button></div></aside></div>
    <Transition name="toast"><div v-if="toast" class="toast-message" role="status"><span>✓</span>{{ toast }}</div></Transition>
  </div>
</template>

<script lang="ts">
import { defineComponent, h } from 'vue';

const TrendChart = defineComponent({
  name: 'TrendChart',
  props: { line: { type: String, required: true }, secondary: Boolean, industrial: Boolean },
  setup(props) {
    return () => h('svg', { class: ['trend-svg', props.industrial ? 'industrial-svg' : ''], viewBox: '0 0 720 140', preserveAspectRatio: 'none', role: 'img', 'aria-label': '演示时序曲线' }, [
      h('defs', null, [h('linearGradient', { id: 'area-fill', x1: '0', y1: '0', x2: '0', y2: '1' }, [h('stop', { offset: '0%', 'stop-color': '#15a88a', 'stop-opacity': '.20' }), h('stop', { offset: '100%', 'stop-color': '#15a88a', 'stop-opacity': '0' })])]),
      ...[24, 54, 84, 114].map(y => h('line', { x1: 0, x2: 720, y1: y, y2: y, class: 'chart-grid' })),
      h('polyline', { points: props.secondary ? '0,74 720,74' : '0,43 720,43', class: 'chart-threshold' }),
      h('polygon', { points: `0,140 ${props.line.split(' ').map(p => { const [x, y] = p.split(',').map(Number); return `${x * 2},${y + 16}`; }).join(' ')} 680,140`, class: 'chart-area' }),
      h('polyline', { points: props.line.split(' ').map(p => { const [x, y] = p.split(',').map(Number); return `${x * 2},${y + 16}`; }).join(' '), class: 'chart-line' }),
      h('circle', { cx: 680, cy: Number(props.line.split(' ').at(-1)?.split(',')[1]) + 16, r: 4, class: 'chart-end-dot' }),
    ]);
  },
});

const MapScene = defineComponent({
  name: 'MapScene',
  props: { activeBox: { type: String, required: true } },
  emits: ['select'],
  setup(props, { emit }) {
    const points = [
      { id: 'SBX-24018', x: 67, y: 42, label: '01', tone: 'pin-green', city: '上海' },
      { id: 'SBX-24021', x: 48, y: 63, label: '02', tone: 'pin-orange', city: '杭州' },
      { id: 'SBX-24026', x: 39, y: 28, label: '03', tone: 'pin-gray', city: '南京' },
      { id: 'SBX-24031', x: 60, y: 70, label: '04', tone: 'pin-green', city: '嘉兴' },
    ];
    return () => h('div', { class: 'map-scene', role: 'img', 'aria-label': '演示路线地图，位置为 Mock Data' }, [
      h('svg', { class: 'map-art', viewBox: '0 0 800 480', preserveAspectRatio: 'none', 'aria-hidden': 'true' }, [
        h('rect', { width: 800, height: 480, fill: '#eef3f1' }),
        h('path', { d: 'M0 78 C120 120 100 170 220 178 S340 120 430 164 S530 240 650 205 S740 110 800 140', class: 'map-river' }),
        h('path', { d: 'M-15 315 C105 288 160 335 272 305 S415 240 500 284 S655 346 810 300', class: 'map-river map-river-2' }),
        h('path', { d: 'M70 0 L140 480 M188 0 L232 480 M365 0 L324 480 M512 0 L552 480 M678 0 L644 480', class: 'map-road minor-road' }),
        h('path', { d: 'M0 124 L800 190 M0 240 L800 248 M0 394 L800 355', class: 'map-road minor-road' }),
        h('path', { d: 'M310 82 C355 156 385 208 443 238 S525 277 560 338 S608 382 660 386', class: 'route-underlay' }),
        h('path', { d: 'M310 82 C355 156 385 208 443 238 S525 277 560 338 S608 382 660 386', class: 'route-path' }),
        ...[[170,106,'苏州'],[250,224,'无锡'],[410,90,'南京'],[565,107,'嘉兴'],[365,358,'杭州'],[690,276,'上海']].map(([x,y,label]) => h('text', { x, y, class: 'map-place-label' }, String(label))),
      ]),
      ...points.map(point => h('button', { class: ['map-pin', point.tone, props.activeBox === point.id ? 'active-pin' : ''], style: { left: `${point.x}%`, top: `${point.y}%` }, 'aria-label': `选择 ${point.id} ${point.city}位置`, onClick: () => emit('select', boxes.find(box => box.id === point.id)) }, [h('span', point.label), h('i')])),
      h('span', { class: 'map-watermark' }, '示意路网 · MOCK'),
    ]);
  },
});

export default { components: { TrendChart, MapScene } };
</script>
