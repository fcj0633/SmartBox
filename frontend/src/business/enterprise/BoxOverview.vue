<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import MapScene from './MapScene.vue';
import { usePolling } from '../model';
import { assetSourceLabel, connectivityLabel, dateTime, environmentTitle, environmentValue, humidityValue, positionLabel, qualityText, taskStatusLabel, taskStatusTone, type Box, type Task } from './model';

const route = useRoute(), session = useSession();
const id = String(route.params.boxId);
const box = ref<Box | null>(null), tasks = ref<Task[]>([]);
const { error, loading, refresh } = usePolling(async signal => {
  const [value, taskList] = await Promise.all([
    session.request<Box>(`/enterprise/console/boxes/${encodeURIComponent(id)}`, { signal }),
    session.request<Task[]>('/enterprise/console/transports', { signal }),
  ]);
  if (signal.aborted) return;
  box.value = value; tasks.value = taskList;
}, () => { box.value = null; tasks.value = []; }, 12000);
const relatedTask = computed(() => {
  if (!box.value) return null;
  const summary = box.value.current_transport ?? box.value.last_completed_transport;
  return summary ? tasks.value.find(task => task.task_id === summary.task_id) ?? null : null;
});
</script>

<template>
  <BusinessShell>
    <div class="detail-topline"><RouterLink to="/enterprise/assets" class="back-link">‹ 返回资产列表</RouterLink><button class="button secondary" @click="refresh">刷新</button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="loading && !box" role="status">正在读取箱体快照…</p>
    <template v-if="box">
      <section class="detail-identity panel"><div class="detail-identity-main"><span class="detail-box-icon">▣</span><div><div class="detail-title-line"><h1>{{ box.box_id }}</h1><span :class="['status-pill', box.connectivity === 'ONLINE' ? 'good' : box.connectivity === 'OFFLINE' ? 'critical' : 'warn']">{{ connectivityLabel(box.connectivity) }}</span><span :class="['source-badge', box.asset_source === 'SIMULATED' ? 'simulated' : 'real']">{{ assetSourceLabel(box.asset_source) }}</span></div><p>{{ box.display_name }} <span>·</span> {{ box.nodes.length }} 个节点 <span>·</span> 最近更新 {{ dateTime(box.last_seen_at) }}</p></div></div><div class="detail-identity-actions"><RouterLink v-if="relatedTask" class="button primary" :to="`/enterprise/transports/${relatedTask.task_id}`">查看关联任务</RouterLink><RouterLink class="button secondary" to="/enterprise/map">在地图中查看</RouterLink></div></section>
      <div class="detail-columns">
        <div class="detail-main">
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>环境摘要</h2><p>最新 ENV 样本与服务端质量；不展示未查询的历史曲线</p></div><span :class="['status-pill', box.environment.assessment === 'EVALUATED' && !box.environment.level ? 'good' : 'warn']">{{ environmentTitle(box.environment) }}</span></div><div class="metric-row"><div><small>温度</small><strong>{{ environmentValue(box.environment) }}</strong></div><div><small>相对湿度</small><strong>{{ humidityValue(box.environment) }}</strong></div><div><small>光照</small><strong>{{ box.environment.lux == null ? '—' : `${box.environment.lux} lux` }}</strong></div><div><small>数据质量</small><strong>{{ qualityText(box.environment) }}</strong></div></div><p class="data-note">观测时间 {{ dateTime(box.environment.observed_at) }} · 接收时间 {{ dateTime(box.environment.received_at) }}。未知或过期不等于正常。</p></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>模拟位置</h2><p>{{ positionLabel(box.position) }} · 来自当前/最近任务路线</p></div><span class="map-mock-label">MOCK</span></div><MapScene :boxes="box ? [box] : []" :tasks="relatedTask ? [relatedTask] : []" :selected-box-id="box.box_id" :selected-task-id="relatedTask?.task_id ?? null" compact /></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>设备节点</h2><p>ENV / POSE 能力与必需标记</p></div></div><div class="node-list"><div v-for="node in box.nodes" :key="node.node_id" class="node-row"><span class="node-icon" :class="node.role.toLowerCase()">{{ node.role }}</span><div><strong>{{ node.node_id }}</strong><small>{{ node.role === 'ENV' ? '温湿度 · 光照' : '加速度 · 倾角' }} · caps {{ node.caps }} · {{ node.required ? '必需节点' : '可选节点' }}</small></div><span :class="['status-pill', node.online ? 'good' : 'critical']">{{ node.online ? '在线' : '离线/未知' }}</span><small>{{ dateTime(node.last_seen_at) }}</small></div><p v-if="!box.nodes.length" class="empty-record">该箱体没有登记设备节点；没有数据不假定正常。</p></div></section>
        </div>
        <aside class="detail-side">
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>运输摘要</h2><p>当前或最近任务</p></div></div><div v-if="box.current_transport" class="summary-block"><span :class="['status-pill', taskStatusTone(box.current_transport.status)]">{{ taskStatusLabel(box.current_transport.status) }}</span><strong>{{ box.current_transport.item_name || '未填写物品' }}</strong><small>{{ box.current_transport.transport_no }}</small><RouterLink class="text-action" :to="`/enterprise/transports/${box.current_transport.task_id}`">打开任务 →</RouterLink></div><div v-else-if="box.last_completed_transport" class="summary-block"><span class="status-pill done">历史任务</span><strong>{{ box.last_completed_transport.item_name || '未填写物品' }}</strong><small>{{ box.last_completed_transport.transport_no }}</small><RouterLink class="text-action" :to="`/enterprise/transports/${box.last_completed_transport.task_id}`">打开历史 →</RouterLink></div><p v-else class="empty-record">该箱体暂无运输任务。</p></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>告警摘要</h2><p>设备异常事实</p></div></div><div class="check-list"><span :class="box.alarm.active ? 'bad' : box.alarm.assessment === 'EVALUATED' ? 'ok' : 'bad'">{{ box.alarm.active ? `活跃告警 ${box.alarm.active_count} 条 · ${box.alarm.level || ''}` : box.alarm.assessment === 'EVALUATED' ? '无活动告警' : '告警判定未知' }}</span><span>人工处置与恢复确认不在首切片</span></div><RouterLink class="text-action" to="/enterprise/alarms">查看告警中心 →</RouterLink></section>
          <section class="panel"><div class="enterprise-panel-heading"><div><h2>后续详情</h2><p>八分区详情将在后续切片提供</p></div></div><p class="data-note">环境趋势、姿态、定位轨迹、告警事件、控制中心、运行日志和 CSV 当前不在首切片；本页只提供总览摘要，不放置假 Tab。</p></section>
        </aside>
      </div>
    </template>
  </BusinessShell>
</template>
