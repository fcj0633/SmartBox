<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../stores/session';
import BusinessShell from './BusinessShell.vue';
import MapScene from './enterprise/MapScene.vue';
import TransportDrawer from './enterprise/TransportDrawer.vue';
import { usePolling } from './model';
import { activityLabel, alarmLevelLabel, assetSourceLabel, dateTime, positionLabel, taskStatusLabel, taskStatusTone, type Overview, type Route, type Task } from './enterprise/model';

const session = useSession(), route = useRoute(), router = useRouter();
const overview = ref<Overview | null>(null), routes = ref<Route[]>([]), selectedBoxId = ref(String(route.query.box ?? '')), selectedTaskId = ref<number | null>(route.query.task ? Number(route.query.task) : null);
const drawerOpen = ref(false), editingTask = ref<Task | null>(null);
const { error, loading, refresh } = usePolling(async signal => {
  const [data, routeList] = await Promise.all([
    session.request<Overview>('/enterprise/console/overview', { signal }),
    session.request<Route[]>('/enterprise/console/routes', { signal }),
  ]);
  if (signal.aborted) return;
  overview.value = data; routes.value = routeList;
  if (!selectedBoxId.value && data.boxes[0]) selectedBoxId.value = data.boxes[0].box_id;
  if (selectedTaskId.value == null) {
    const current = data.boxes.find(box => box.box_id === selectedBoxId.value)?.current_transport;
    if (current) selectedTaskId.value = current.task_id;
  }
}, () => { overview.value = null; routes.value = []; }, 12000);
const selectedTask = computed(() => overview.value?.tasks.find(task => task.task_id === selectedTaskId.value) ?? null);
const activeAlarmBoxes = computed(() => overview.value?.boxes.filter(box => box.alarm.active) ?? []);
const alarmsUnknown = computed(() => overview.value?.kpis.unknown_alarm_boxes ?? 0);
function selectBox(boxId: string) {
  selectedBoxId.value = boxId;
  const current = overview.value?.boxes.find(box => box.box_id === boxId)?.current_transport;
  selectedTaskId.value = current?.task_id ?? null;
  void router.replace({ query: { ...(selectedTaskId.value ? { box: boxId, task: String(selectedTaskId.value) } : { box: boxId }) } });
}
function selectTask(task: Task) {
  selectedTaskId.value = task.task_id; selectedBoxId.value = task.box_id;
  void router.replace({ query: { box: task.box_id, task: String(task.task_id) } });
}
function openCreate() { editingTask.value = null; drawerOpen.value = true; }
function openEdit() { if (selectedTask.value?.status === 'READY') { editingTask.value = selectedTask.value; drawerOpen.value = true; } }
function saved() { refresh(); }
</script>

<template>
  <BusinessShell>
    <div class="page-title enterprise-page-head">
      <div><p class="data-note">企业运输中心 · {{ overview?.enterprise_name ?? '企业空间' }}</p><h1>运营总览</h1><p class="data-note">跨箱体运行状态、运输任务与异常态势；地图位置为 MOCK。</p></div>
      <div class="page-actions"><span class="refresh-state"><span class="sync-dot" />{{ overview ? `更新于 ${dateTime(overview.refreshed_at)}` : '读取中…' }}</span><button class="button secondary" @click="refresh">刷新</button><button class="button primary" @click="openCreate">＋ 新建运输</button></div>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="loading && !overview" role="status">正在读取企业运营总览…</p>
    <template v-if="overview">
      <section class="enterprise-kpis enterprise-kpis--five" aria-label="运营关键指标">
        <div><span>本企业资产</span><strong>{{ overview.kpis.box_total }}<small> 个</small></strong><p>真实/模拟同列表</p></div>
        <div><span>在线 / 部分 / 离线</span><strong>{{ overview.kpis.online }}<small> / {{ overview.kpis.partial }} / {{ overview.kpis.offline }}</small></strong><p>另 {{ overview.kpis.unknown }} 个未知</p></div>
        <div><span>在途运输</span><strong>{{ overview.kpis.active_transports }}<small> 项</small></strong><p>当前 ACTIVE</p></div>
        <div><span>活跃告警箱</span><strong class="summary-attention">{{ overview.kpis.active_alarm_boxes }}<small> 个</small></strong><p>另有 {{ overview.kpis.unknown_alarm_boxes }} 个判定未知</p></div>
        <div><span>今日完成</span><strong>{{ overview.kpis.completed_today }}<small> 项</small></strong><p>按本地日界统计</p></div>
      </section>
      <section class="route-dashboard-layout">
        <div class="route-map-panel panel">
          <div class="enterprise-panel-heading"><div><h2>在途资产分布</h2><p>选中任务查看模拟路线；无任务箱体显示未定位</p></div><span class="map-mock-label">MOCK / 示意图</span></div>
          <MapScene :boxes="overview.boxes" :tasks="overview.tasks" :selected-box-id="selectedBoxId" :selected-task-id="selectedTaskId" @select-box="selectBox" />
        </div>
        <div class="route-task-panel panel">
          <div class="enterprise-panel-heading"><div><h2>运输任务</h2><p>{{ overview.tasks.length }} 项 · 最近创建</p></div><button class="text-action" @click="openCreate">＋ 新建</button></div>
          <div class="enterprise-task-scroll">
            <button v-for="task in overview.tasks" :key="task.task_id" :class="['route-task-row', { 'active-route-task': selectedTaskId === task.task_id }]" @click="selectTask(task)">
              <span :class="['task-marker', taskStatusTone(task.status)]" />
              <span class="task-main"><strong>{{ task.item_name || '未填写物品' }}</strong><small>{{ task.transport_no }} · {{ task.box_id }}</small><small>{{ task.box_display_name }}</small></span>
              <span :class="['status-pill', taskStatusTone(task.status)]">{{ taskStatusLabel(task.status) }}</span>
            </button>
            <p v-if="!overview.tasks.length" class="empty-record">暂无运输任务。选择箱体创建第一条 READY。</p>
          </div>
          <div v-if="selectedTask" class="selected-task-summary">
            <div><strong>{{ selectedTask.item_name || '未填写物品' }}</strong><small>{{ selectedTask.transport_no }} · {{ taskStatusLabel(selectedTask.status) }}</small></div>
            <p class="data-note">{{ selectedTask.route?.name || '无模拟路线' }} · {{ positionLabel(selectedTask.position) }}</p>
            <div class="inline-actions"><button class="button secondary" @click="openEdit" :disabled="selectedTask.status !== 'READY'">编辑待开始</button><RouterLink class="button primary" :to="`/enterprise/transports/${selectedTask.task_id}`">查看任务</RouterLink></div>
          </div>
        </div>
      </section>
      <section class="route-lower-grid">
        <div class="panel activity-panel">
          <div class="enterprise-panel-heading"><div><h2>最近动态</h2><p>任务、告警与设备事件 · 服务端时间</p></div><RouterLink class="text-action" to="/enterprise/alarms">告警中心 →</RouterLink></div>
          <div class="activity-list">
            <div v-for="item in overview.recent_activity" :key="`${item.type}-${item.at}-${item.title}`" class="activity-row">
              <span class="activity-dot" :class="item.source" /><div><strong>{{ item.title }}</strong><small>{{ item.box_display_name }} · {{ dateTime(item.at) }}</small></div><span class="activity-type">{{ activityLabel(item.type) }}</span>
            </div>
            <p v-if="!overview.recent_activity.length" class="empty-record">暂无近期动态。</p>
          </div>
        </div>
        <div class="panel focus-panel">
          <div class="enterprise-panel-heading"><div><h2>需要关注</h2><p>活跃告警箱与来源说明</p></div><span class="map-mock-label">只读</span></div>
          <div v-if="activeAlarmBoxes.length" class="focus-list">
            <button v-for="box in activeAlarmBoxes" :key="box.box_id" class="focus-row" @click="selectBox(box.box_id)">
              <span class="focus-level">{{ alarmLevelLabel(box.alarm.level) }}</span><span><strong>{{ box.box_id }} · {{ box.display_name }}</strong><small>{{ box.alarm.active_count }} 条活跃告警 · {{ assetSourceLabel(box.asset_source) }}</small></span>
            </button>
          </div>
          <p v-else class="empty-record">当前没有已判定的活跃告警箱。</p>
          <p v-if="alarmsUnknown" class="unknown-note">{{ alarmsUnknown }} 个箱体的告警判定未知；未知不等于正常。</p>
          <div class="quick-links"><RouterLink to="/enterprise/assets">全部资产</RouterLink><RouterLink to="/enterprise/transports">全部运输</RouterLink><RouterLink to="/enterprise/map">位置与设备</RouterLink></div>
        </div>
      </section>
    </template>
    <TransportDrawer v-if="drawerOpen" :boxes="overview?.boxes ?? []" :routes="routes" :task="editingTask" @close="drawerOpen = false" @saved="saved" />
  </BusinessShell>
</template>
