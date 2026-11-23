<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import MapScene from './MapScene.vue';
import SampleChart from './SampleChart.vue';
import ControlCenter from './ControlCenter.vue';
import { usePolling } from '../model';
import {
  alarmLevelLabel, assetSourceLabel, connectivityLabel, dateTime, demoEnabledLabel,
  environmentTitle, environmentValue, humidityValue, logSourceLabel, poseStateLabel, positionLabel,
  qualityText, scenarioLabel, scenarioTone, taskStatusLabel, taskStatusTone,
  type AlarmRow, type Box, type CommandRow, type Event, type LogRow, type SamplePoint, type Task,
} from './model';

const route = useRoute(), router = useRouter(), session = useSession();
const id = String(route.params.boxId);
const tabs = [
  { id: 'overview', label: '总览' }, { id: 'environment', label: '环境监测' }, { id: 'pose', label: '姿态监测' },
  { id: 'location', label: '定位轨迹' }, { id: 'alarms', label: '告警事件' }, { id: 'nodes', label: '设备节点' },
  { id: 'control', label: '控制中心' }, { id: 'logs', label: '运行日志' },
] as const;
type TabId = typeof tabs[number]['id'];
const fromQuery = (): TabId => tabs.some(tab => tab.id === route.query.tab) ? route.query.tab as TabId : 'overview';
const tab = ref<TabId>(fromQuery());
const sampleHours = ref(6);
const box = ref<Box | null>(null), tasks = ref<Task[]>([]), events = ref<Event[]>([]), alarms = ref<AlarmRow[]>([]), commands = ref<CommandRow[]>([]), logs = ref<LogRow[]>([]);
const envSamples = ref<SamplePoint[]>([]), poseSamples = ref<SamplePoint[]>([]), sampleError = ref('');
let sampleSeq = 0;
const { error, loading, refresh } = usePolling(async signal => {
  const [value, taskList, eventList, alarmList, commandList, logList] = await Promise.all([
    session.request<Box>(`/enterprise/console/boxes/${encodeURIComponent(id)}`, { signal }),
    session.request<Task[]>('/enterprise/console/transports', { signal }),
    session.request<Event[]>(`/enterprise/console/boxes/${encodeURIComponent(id)}/events?limit=50`, { signal }),
    session.request<AlarmRow[]>(`/enterprise/console/boxes/${encodeURIComponent(id)}/alarms`, { signal }),
    session.request<CommandRow[]>(`/enterprise/console/boxes/${encodeURIComponent(id)}/commands`, { signal }),
    session.request<LogRow[]>(`/enterprise/console/boxes/${encodeURIComponent(id)}/logs?limit=50`, { signal }),
  ]);
  if (signal.aborted) return;
  box.value = value; tasks.value = taskList.filter(task => task.box_id === id); events.value = eventList; alarms.value = alarmList; commands.value = commandList; logs.value = logList;
}, () => { box.value = null; tasks.value = []; events.value = []; alarms.value = []; commands.value = []; logs.value = []; }, 12000);
const relatedTask = computed(() => {
  if (!box.value) return null;
  const summary = box.value.current_transport ?? box.value.last_completed_transport;
  return summary ? tasks.value.find(task => task.task_id === summary.task_id) ?? null : null;
});
const completedTasks = computed(() => tasks.value.filter(task => task.status === 'COMPLETED'));
const poseEvents = computed(() => events.value.filter(event => ['POSE_CHANGED', 'IMPACT'].includes(event.type)));
const sampleTitle = computed(() => tab.value === 'pose' ? '姿态趋势（倾角）' : '温度趋势');
const envTableTitle = computed(() => `环境采样（${sampleHours.value} 小时）`);
async function loadSamples(kind: 'env' | 'pose') {
  const sequence = ++sampleSeq; sampleError.value = '';
  try {
    const value = await session.request<SamplePoint[]>(`/enterprise/console/boxes/${encodeURIComponent(id)}/samples?kind=${kind}&hours=${sampleHours.value}`);
    if (sequence !== sampleSeq) return;
    if (kind === 'env') envSamples.value = value; else poseSamples.value = value;
  } catch (problem) { if (sequence === sampleSeq) sampleError.value = problem instanceof Error ? problem.message : '采样读取失败'; }
}
function selectTab(next: TabId) {
  tab.value = next;
  void router.replace({ query: { ...route.query, tab: next === 'overview' ? undefined : next } });
  if (next === 'environment') void loadSamples('env');
  if (next === 'pose') void loadSamples('pose');
}
function refreshAll() { refresh(); if (tab.value === 'environment') void loadSamples('env'); if (tab.value === 'pose') void loadSamples('pose'); }
watch(sampleHours, () => { if (tab.value === 'environment') void loadSamples('env'); if (tab.value === 'pose') void loadSamples('pose'); });
watch(tab, () => { if (tab.value === 'environment' && !envSamples.value.length) void loadSamples('env'); if (tab.value === 'pose' && !poseSamples.value.length) void loadSamples('pose'); }, { immediate: true });
</script>

<template>
  <BusinessShell>
    <div class="detail-topline"><RouterLink to="/enterprise/assets" class="back-link">‹ 返回资产列表</RouterLink><button class="button secondary" @click="refreshAll">刷新</button></div>
    <p v-if="error || sampleError" class="form-error" role="alert">{{ error || sampleError }}</p>
    <p v-if="loading && !box" role="status">正在读取箱体快照…</p>
    <template v-if="box">
      <section class="detail-identity panel"><div class="detail-identity-main"><span class="detail-box-icon">▣</span><div><div class="detail-title-line"><h1>{{ box.box_id }}</h1><span :class="['status-pill', box.connectivity === 'ONLINE' ? 'good' : box.connectivity === 'OFFLINE' ? 'critical' : 'warn']">{{ connectivityLabel(box.connectivity) }}</span><span :class="['source-badge', box.asset_source === 'SIMULATED' ? 'simulated' : 'real']">{{ assetSourceLabel(box.asset_source) }}</span><span v-if="box.asset_source === 'SIMULATED'" :class="['status-pill', scenarioTone(box.demo_scenario)]">{{ scenarioLabel(box.demo_scenario) }} · {{ demoEnabledLabel(box.demo_enabled) }}</span></div><p>{{ box.display_name }} <span>·</span> {{ box.nodes.length }} 个节点 <span>·</span> 最近更新 {{ dateTime(box.last_seen_at) }}</p><p v-if="box.purpose" class="data-note">用途：{{ box.purpose }}</p></div></div><div class="detail-identity-actions"><RouterLink v-if="relatedTask" class="button primary" :to="`/enterprise/transports/${relatedTask.task_id}`">查看关联任务</RouterLink><RouterLink class="button secondary" to="/enterprise/map">在地图中查看</RouterLink></div></section>
      <nav class="detail-tabs" aria-label="箱体详情分区"><button v-for="item in tabs" :key="item.id" :class="{ 'tab-current': tab === item.id }" @click="selectTab(item.id)">{{ item.label }}</button></nav>

      <section v-if="tab === 'overview'" class="detail-columns">
        <div class="detail-main">
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>环境摘要</h2><p>最新 ENV 样本与服务端质量</p></div><span :class="['status-pill', box.environment.assessment === 'EVALUATED' && !box.environment.level ? 'good' : 'warn']">{{ environmentTitle(box.environment) }}</span></div><div class="metric-row"><div><small>温度</small><strong>{{ environmentValue(box.environment) }}</strong></div><div><small>相对湿度</small><strong>{{ humidityValue(box.environment) }}</strong></div><div><small>光照</small><strong>{{ box.environment.lux == null ? '—' : `${box.environment.lux} lux` }}</strong></div><div><small>数据质量</small><strong>{{ qualityText(box.environment) }}</strong></div></div><p class="data-note">观测时间 {{ dateTime(box.environment.observed_at) }} · 接收时间 {{ dateTime(box.environment.received_at) }}。未知或过期不等于正常。</p></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>姿态摘要</h2><p>POSE 最近状态；无数据为未知</p></div><span :class="['status-pill', box.pose?.valid ? 'good' : 'warn']">{{ box.pose ? poseStateLabel(box.pose.state) : '未知' }}</span></div><div class="metric-row"><div><small>倾角</small><strong>{{ box.pose?.tilt_deg == null ? '—' : `${box.pose.tilt_deg.toFixed(1)}°` }}</strong></div><div><small>加速度</small><strong>{{ box.pose?.acc_g?.length ? box.pose.acc_g.map(v => v.toFixed(2)).join(' / ') : '—' }}</strong></div><div><small>质量</small><strong>{{ box.pose?.data_state ?? 'UNKNOWN' }}</strong></div><div><small>观测</small><strong>{{ dateTime(box.pose?.observed_at) }}</strong></div></div></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>模拟位置</h2><p>{{ positionLabel(box.position) }} · MOCK</p></div><span class="map-mock-label">MOCK</span></div><MapScene :boxes="box ? [box] : []" :tasks="relatedTask ? [relatedTask] : []" :selected-box-id="box.box_id" :selected-task-id="relatedTask?.task_id ?? null" compact /></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>最近设备事件</h2><p>只读；完整日志见运行日志</p></div></div><div class="event-list"><div v-for="event in events.slice(0, 5)" :key="event.id" class="event-row"><span class="activity-dot device" /><div><strong>{{ event.description }}</strong><small>{{ event.type }} · {{ event.level }} · {{ dateTime(event.observed_at) }}</small></div></div><p v-if="!events.length" class="empty-record">暂无可确认设备事件。</p></div></section>
        </div>
        <aside class="detail-side">
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>资产信息</h2><p>元信息与当前状态</p></div></div><dl class="definition-list"><div><dt>来源</dt><dd>{{ assetSourceLabel(box.asset_source) }}</dd></div><div><dt>用途</dt><dd>{{ box.purpose || '—' }}</dd></div><div><dt>备注</dt><dd>{{ box.notes || '—' }}</dd></div><div><dt>场景</dt><dd>{{ box.asset_source === 'SIMULATED' ? scenarioLabel(box.demo_scenario) : '—' }}</dd></div><div><dt>模拟输入</dt><dd>{{ box.asset_source === 'SIMULATED' ? demoEnabledLabel(box.demo_enabled) : '不适用' }}</dd></div></dl></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>运输摘要</h2><p>当前或最近任务</p></div></div><div v-if="box.current_transport" class="summary-block"><span :class="['status-pill', taskStatusTone(box.current_transport.status)]">{{ taskStatusLabel(box.current_transport.status) }}</span><strong>{{ box.current_transport.item_name || '未填写物品' }}</strong><small>{{ box.current_transport.transport_no }}</small><RouterLink class="text-action" :to="`/enterprise/transports/${box.current_transport.task_id}`">打开任务 →</RouterLink></div><div v-else-if="box.last_completed_transport" class="summary-block"><span class="status-pill done">历史任务</span><strong>{{ box.last_completed_transport.item_name || '未填写物品' }}</strong><small>{{ box.last_completed_transport.transport_no }}</small><RouterLink class="text-action" :to="`/enterprise/transports/${box.last_completed_transport.task_id}`">打开历史 →</RouterLink></div><p v-else class="empty-record">该箱体暂无运输任务。</p></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>告警摘要</h2><p>设备异常事实</p></div></div><div class="check-list"><span :class="box.alarm.active ? 'bad' : box.alarm.assessment === 'EVALUATED' ? 'ok' : 'bad'">{{ box.alarm.active ? `活跃告警 ${box.alarm.active_count} 条 · ${box.alarm.level || ''}` : box.alarm.assessment === 'EVALUATED' ? '无活动告警' : '告警判定未知' }}</span></div><RouterLink class="text-action" to="/enterprise/alarms">查看告警中心 →</RouterLink></section>
        </aside>
      </section>

      <section v-else-if="tab === 'environment'" class="panel detail-tab-panel">
        <div class="enterprise-panel-heading"><div><h2>环境监测</h2><p>真实/模拟 ENV 样本；无效点在曲线断开</p></div><div class="panel-actions"><span class="map-mock-label">ENV</span><button v-for="hours in [1,6,24]" :key="hours" :class="['text-button', { selected: sampleHours === hours }]" @click="sampleHours = hours">{{ hours }}h</button></div></div>
        <div class="metric-row"><div><small>最新温度</small><strong>{{ environmentValue(box.environment) }}</strong></div><div><small>最新湿度</small><strong>{{ humidityValue(box.environment) }}</strong></div><div><small>光照</small><strong>{{ box.environment.lux == null ? '—' : `${box.environment.lux} lux` }}</strong></div><div><small>质量</small><strong>{{ qualityText(box.environment) }}</strong></div></div>
        <div class="detail-chart-wrap"><div class="detail-chart-title"><strong>{{ sampleTitle }}</strong><small>{{ sampleHours }} 小时 · 最近 {{ envSamples.length }} 个样本</small></div><SampleChart :points="envSamples" kind="env" /></div>
        <div class="table-wrap"><table class="enterprise-table"><thead><tr><th>观测时间</th><th>温度</th><th>湿度</th><th>光照</th><th>有效</th><th>时间质量</th></tr></thead><tbody><tr v-for="sample in [...envSamples].reverse().slice(0, 30)" :key="sample.id"><td>{{ dateTime(sample.observed_at) }}</td><td>{{ sample.temperature == null ? '—' : `${sample.temperature.toFixed(1)} °C` }}</td><td>{{ sample.humidity == null ? '—' : `${sample.humidity.toFixed(1)} %` }}</td><td>{{ sample.lux == null ? '—' : `${sample.lux} lux` }}</td><td>{{ sample.valid ? '是' : '否' }}</td><td>{{ sample.time_quality }}</td></tr></tbody></table><p v-if="!envSamples.length" class="empty-record">当前时间窗口没有环境样本；没有数据不假定正常。</p></div>
      </section>

      <section v-else-if="tab === 'pose'" class="panel detail-tab-panel">
        <div class="enterprise-panel-heading"><div><h2>姿态监测</h2><p>POSE 最新状态、倾角与事件；无数据为未知</p></div><div class="panel-actions"><span class="map-mock-label">POSE</span><button v-for="hours in [1,6,24]" :key="hours" :class="['text-button', { selected: sampleHours === hours }]" @click="sampleHours = hours">{{ hours }}h</button></div></div>
        <div class="metric-row"><div><small>状态</small><strong>{{ box.pose ? poseStateLabel(box.pose.state) : '未知' }}</strong></div><div><small>倾角</small><strong>{{ box.pose?.tilt_deg == null ? '—' : `${box.pose.tilt_deg.toFixed(1)}°` }}</strong></div><div><small>加速度</small><strong>{{ box.pose?.acc_g?.length ? box.pose.acc_g.map(v => v.toFixed(2)).join(' / ') : '—' }}</strong></div><div><small>质量</small><strong>{{ box.pose?.data_state ?? 'UNKNOWN' }}</strong></div></div>
        <div class="detail-chart-wrap"><div class="detail-chart-title"><strong>{{ sampleTitle }}</strong><small>{{ sampleHours }} 小时 · 最近 {{ poseSamples.length }} 个样本</small></div><SampleChart :points="poseSamples" kind="pose" /></div>
        <div class="event-list"><div v-for="event in poseEvents" :key="event.id" class="event-row"><span class="activity-dot device" /><div><strong>{{ event.description }}</strong><small>{{ event.type }} · {{ event.level }} · {{ dateTime(event.observed_at) }}</small></div></div><p v-if="!poseEvents.length" class="empty-record">当前时间窗口没有姿态变化/冲击事件。</p></div>
      </section>

      <section v-else-if="tab === 'location'" class="detail-columns">
        <div class="detail-main"><section class="panel"><div class="enterprise-panel-heading"><div><h2>模拟路线与位置</h2><p>{{ positionLabel(box.position) }} · 所有位置为 MOCK</p></div><span class="map-mock-label">MOCK</span></div><MapScene :boxes="box ? [box] : []" :tasks="tasks" :selected-box-id="box.box_id" :selected-task-id="relatedTask?.task_id ?? null" compact /><div class="route-detail-meta"><span>起点 <strong>{{ relatedTask?.route?.from || '—' }}</strong></span><span>终点 <strong>{{ relatedTask?.route?.to || '—' }}</strong></span><span>当前 <strong>{{ positionLabel(box.position) }}</strong></span><span>进度 <strong>{{ relatedTask?.position ? `${Math.round(relatedTask.position.progress * 100)}%` : '—' }}</strong></span></div></section></div>
        <aside class="detail-side"><section class="panel"><div class="enterprise-panel-heading"><div><h2>任务与轨迹</h2><p>已完成任务保留路线摘要；不自动回放</p></div></div><div v-for="task in tasks" :key="task.task_id" class="summary-block"><span :class="['status-pill', taskStatusTone(task.status)]">{{ taskStatusLabel(task.status) }}</span><strong>{{ task.item_name || '未填写物品' }}</strong><small>{{ task.transport_no }} · {{ task.route?.name || '无模拟路线' }}</small><RouterLink class="text-action" :to="`/enterprise/transports/${task.task_id}`">打开任务 →</RouterLink></div><p v-if="!tasks.length" class="empty-record">该箱体暂无运输任务。</p></section></aside>
      </section>

      <section v-else-if="tab === 'alarms'" class="detail-tab-panel">
        <section class="panel"><div class="enterprise-panel-heading"><div><h2>箱体告警</h2><p>设备异常/恢复事实；人工处置不在本切片</p></div></div><div class="table-wrap"><table class="enterprise-table"><thead><tr><th>等级</th><th>告警键</th><th>状态</th><th>触发</th><th>恢复</th><th>来源事件</th></tr></thead><tbody><tr v-for="alarm in alarms" :key="alarm.alarm_key"><td><span :class="['status-pill', alarm.level === 'CRITICAL' ? 'critical' : 'warn']">{{ alarmLevelLabel(alarm.level) }}</span></td><td>{{ alarm.alarm_key }}</td><td>{{ alarm.active ? '活跃' : '已恢复' }}</td><td>{{ dateTime(alarm.started_at) }}</td><td>{{ alarm.active ? '未恢复' : dateTime(alarm.recovered_at) }}</td><td>{{ alarm.source_event_type || '—' }}</td></tr></tbody></table><p v-if="!alarms.length" class="empty-record">该箱体没有告警记录；空列表不代表绝无异常。</p></div></section>
        <section class="panel"><div class="enterprise-panel-heading"><div><h2>设备事件</h2><p>最近 50 条，只读</p></div></div><div class="event-list"><div v-for="event in events" :key="event.id" class="event-row"><span class="activity-dot device" /><div><strong>{{ event.description }}</strong><small>{{ event.type }} · {{ event.level }} · {{ dateTime(event.observed_at) }}</small></div></div><p v-if="!events.length" class="empty-record">暂无设备事件。</p></div></section>
      </section>

      <section v-else-if="tab === 'nodes'" class="detail-tab-panel">
        <section class="panel"><div class="enterprise-panel-heading"><div><h2>设备节点</h2><p>ENV/POSE 能力、必需标记、在线和最后心跳</p></div></div><div class="node-list"><div v-for="node in box.nodes" :key="node.node_id" class="node-row"><span class="node-icon" :class="node.role.toLowerCase()">{{ node.role }}</span><div><strong>{{ node.node_id }}</strong><small>caps {{ node.caps }} · {{ node.required ? '必需节点' : '可选节点' }} · 最后心跳 {{ dateTime(node.last_seen_at) }}</small></div><span :class="['status-pill', node.online ? 'good' : 'critical']">{{ node.online ? '在线' : '离线/未知' }}</span></div><p v-if="!box.nodes.length" class="empty-record">该箱体没有登记设备节点。</p></div></section>
        <section class="panel"><div class="enterprise-panel-heading"><div><h2>最近可用状态</h2><p>仅展示服务端字段；固件/电量/信号未接入时不编造</p></div></div><div class="metric-row"><div><small>ENV 观测</small><strong>{{ dateTime(box.environment.observed_at) }}</strong></div><div><small>ENV 质量</small><strong>{{ qualityText(box.environment) }}</strong></div><div><small>POSE 观测</small><strong>{{ dateTime(box.pose?.observed_at) }}</strong></div><div><small>POSE 质量</small><strong>{{ box.pose?.data_state ?? 'UNKNOWN' }}</strong></div></div></section>
      </section>

      <section v-else-if="tab === 'control'" class="detail-tab-panel">
        <ControlCenter :box="box" :commands="commands" @changed="refresh" />
      </section>

      <section v-else-if="tab === 'logs'" class="panel detail-tab-panel">
        <div class="enterprise-panel-heading"><div><h2>运行日志</h2><p>设备事件、任务状态、审计与命令合并；只读</p></div><span class="map-mock-label">只读</span></div>
        <div class="activity-list"><div v-for="(row, index) in logs" :key="`${row.at}-${row.source}-${index}`" class="activity-row"><span class="activity-dot" :class="row.source" /><div><strong>{{ row.title }}</strong><small>{{ row.detail }} · {{ dateTime(row.at) }}</small></div><span class="activity-type">{{ logSourceLabel(row.source) }}</span></div><p v-if="!logs.length" class="empty-record">暂无运行日志。</p></div>
      </section>
    </template>
  </BusinessShell>
</template>
