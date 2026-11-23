<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import MapScene from './MapScene.vue';
import TransportDrawer from './TransportDrawer.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import { usePolling, errorText } from '../model';
import { assetSourceLabel, connectivityLabel, dateTime as date, environmentTitle, environmentValue, humidityValue, positionLabel, qualityText, riskText, taskStatusLabel, taskStatusTone, type Route, type TaskDetail } from './model';

const route = useRoute(), session = useSession();
const id = String(route.params.id);
const detail = ref<TaskDetail | null>(null), routes = ref<Route[]>([]), dialog = ref<'start' | 'risk' | 'finish' | null>(null), drawer = ref(false), busy = ref(false), actionError = ref('');
const { error, loading, refresh } = usePolling(async signal => {
  const [value, routeList] = await Promise.all([
    session.request<TaskDetail>(`/enterprise/console/transports/${encodeURIComponent(id)}`, { signal }),
    session.request<Route[]>('/enterprise/console/routes', { signal }),
  ]);
  if (signal.aborted) return;
  detail.value = value; routes.value = routeList;
}, () => { detail.value = null; }, 12000);
const task = computed(() => detail.value?.task ?? null), box = computed(() => detail.value?.box ?? null);
const positionText = computed(() => {
  const position = detail.value?.position;
  if (!position) return '未定位';
  return `${position.place} · ${Math.round(position.progress * 100)}%${position.stale ? ' · 最后已知 MOCK' : ''}${position.finished ? ' · 已到路线终点' : ''}`;
});
async function act(kind: 'start' | 'finish', risk = false) {
  if (busy.value || !task.value) return;
  busy.value = true; actionError.value = '';
  try {
    await session.request(`/enterprise/console/transports/${task.value.task_id}/${kind}`, { method: 'POST', body: kind === 'start' ? { confirm_risk: risk } : undefined });
    dialog.value = null; refresh();
  } catch (problem) {
    if (kind === 'start' && (problem as { code?: string }).code === 'RISK_CONFIRMATION_REQUIRED') dialog.value = 'risk';
    else { actionError.value = errorText(problem); dialog.value = null; }
  } finally { busy.value = false; }
}
function confirm() { if (dialog.value === 'finish') void act('finish'); else void act('start', dialog.value === 'risk'); }
</script>

<template>
  <BusinessShell>
    <div class="detail-topline"><RouterLink to="/enterprise/transports" class="back-link">‹ 返回运输任务</RouterLink><button class="button secondary" @click="refresh">刷新</button></div>
    <p v-if="error || actionError" class="form-error" role="alert">{{ error || actionError }}</p>
    <p v-if="loading && !detail" role="status">正在读取运输任务…</p>
    <template v-if="task && box && detail">
      <section class="detail-identity panel">
        <div class="detail-identity-main"><span class="detail-box-icon">▣</span><div><div class="detail-title-line"><h1>{{ task.transport_no }}</h1><span :class="['status-pill', taskStatusTone(task.status)]">{{ taskStatusLabel(task.status) }}</span><span class="source-badge" :class="box.asset_source === 'SIMULATED' ? 'simulated' : 'real'">{{ assetSourceLabel(box.asset_source) }}</span></div><p>{{ task.item_name || '未填写物品' }} <span>·</span> {{ task.box_id }} {{ task.box_display_name }} <span>·</span> 操作者 {{ task.operator_username || '—' }}</p></div></div>
        <div class="detail-identity-actions"><RouterLink class="button secondary" :to="`/enterprise/assets/${task.box_id}`">查看箱体</RouterLink><button v-if="task.status === 'READY'" class="button secondary" @click="drawer = true">编辑</button><button v-if="task.status === 'READY'" class="button primary" @click="dialog = 'start'">开始运输</button><button v-if="task.status === 'ACTIVE'" class="button danger" @click="dialog = 'finish'">结束运输</button></div>
      </section>
      <div class="detail-columns">
        <div class="detail-main">
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>模拟路线与位置</h2><p>{{ task.route?.name || '无模拟路线' }} · {{ positionText }}</p></div><span class="map-mock-label">MOCK</span></div><MapScene :boxes="box ? [box] : []" :tasks="task ? [task] : []" :selected-box-id="box.box_id" :selected-task-id="task.task_id" compact /><div class="route-detail-meta"><span>起点 <strong>{{ task.route?.from || '—' }}</strong></span><span>终点 <strong>{{ task.route?.to || '—' }}</strong></span><span>进度 <strong>{{ detail.position ? `${Math.round(detail.position.progress * 100)}%` : '—' }}</strong></span><span>位置时间 <strong>{{ date(detail.position?.position_at) }}</strong></span></div></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>环境摘要</h2><p>只展示服务端已给出的事实；未知不等于正常</p></div><span :class="['status-pill', detail.environment.assessment === 'EVALUATED' && !detail.environment.level ? 'good' : 'warn']">{{ environmentTitle(detail.environment) }}</span></div><div class="metric-row"><div><small>温度</small><strong>{{ environmentValue(detail.environment) }}</strong></div><div><small>相对湿度</small><strong>{{ humidityValue(detail.environment) }}</strong></div><div><small>数据质量</small><strong>{{ qualityText(detail.environment) }}</strong></div><div><small>观测时间</small><strong>{{ date(detail.environment.observed_at) }}</strong></div></div></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>运输事件</h2><p>仅任务时间范围内的设备事件；只读</p></div></div><div class="event-list"><div v-for="event in detail.events" :key="event.id" class="event-row"><span class="activity-dot device" /><div><strong>{{ event.description }}</strong><small>{{ event.type }} · {{ event.level }} · {{ date(event.observed_at) }}</small></div></div><p v-if="!detail.events.length" class="empty-record">暂无可确认设备事件；空列表不等于“没有异常”。</p></div></section>
        </div>
        <aside class="detail-side">
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>开始条件</h2><p>服务端在开始时重新校验</p></div></div><div class="check-list"><span :class="task.start_check.online ? 'ok' : 'bad'">{{ task.start_check.online ? '必需节点在线' : '必需节点不在线' }}</span><span :class="task.start_check.risk ? 'bad' : 'ok'">{{ task.start_check.risk ? '存在风险/告警未知' : '告警检查通过' }}</span></div><p class="data-note">{{ riskText(task) }}</p></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>时间线</h2><p>服务端事实</p></div></div><dl class="definition-list"><div><dt>创建</dt><dd>{{ date(task.created_at) }}</dd></div><div><dt>开始</dt><dd>{{ date(task.started_at) }}</dd></div><div><dt>结束</dt><dd>{{ date(task.ended_at) }}</dd></div><div><dt>物品说明</dt><dd>{{ task.item_description || '—' }}</dd></div></dl></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>箱体状态</h2><p>{{ box.box_id }}</p></div></div><div class="check-list"><span :class="box.connectivity === 'ONLINE' ? 'ok' : 'bad'">{{ connectivityLabel(box.connectivity) }}</span><span>{{ positionLabel(box.position) }}</span><span>{{ box.nodes.filter(n => n.required).length }} 个必需节点</span></div><p class="data-note">地图位置是任务路线投影，不代表 GPS 实测；设备离线时冻结最后已知位置。</p></section>
        </aside>
      </div>
    </template>
    <TransportDrawer v-if="drawer && task && box" :boxes="box ? [box] : []" :routes="routes" :task="task" @close="drawer = false" @saved="refresh" />
    <ConfirmDialog v-if="dialog" :title="dialog === 'finish' ? '确认结束运输？' : dialog === 'risk' ? '确认风险后开始？' : '确认开始运输？'" :busy="busy" :danger="dialog === 'finish'" :confirm-label="dialog === 'finish' ? '结束运输' : dialog === 'risk' ? '我已知晓风险，继续开始' : '开始运输'" @close="dialog = null" @confirm="confirm">
      <p>{{ dialog === 'finish' ? '结束后保留本次任务、物品、时间和模拟轨迹；不代表签收、告警恢复或数据已补齐。' : dialog === 'risk' ? '设备环境有活动告警或告警判定未知。服务端不会因确认而改变告警结论。' : '开始后进入 ACTIVE；同箱唯一 ACTIVE 由数据库约束保证。' }}</p>
      <p class="data-note">{{ task?.transport_no }} · {{ task?.box_id }}</p>
    </ConfirmDialog>
  </BusinessShell>
</template>
