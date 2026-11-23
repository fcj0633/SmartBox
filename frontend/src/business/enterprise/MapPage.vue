<script setup lang="ts">
import { computed, ref } from 'vue';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import MapScene from './MapScene.vue';
import { usePolling } from '../model';
import { assetSourceLabel, connectivityLabel, dateTime, environmentValue, positionLabel, taskStatusLabel, taskStatusTone, type Overview } from './model';

const session = useSession();
const overview = ref<Overview | null>(null), selectedBoxId = ref(''), filter = ref('ALL'), query = ref('');
const { error, loading, refresh } = usePolling(async signal => {
  const value = await session.request<Overview>('/enterprise/console/overview', { signal });
  if (signal.aborted) return;
  overview.value = value;
  if (!selectedBoxId.value || !value.boxes.some(box => box.box_id === selectedBoxId.value)) {
    selectedBoxId.value = value.boxes.find(box => box.position)?.box_id ?? value.boxes[0]?.box_id ?? '';
  }
}, () => { overview.value = null; }, 12000);
const visible = computed(() => (overview.value?.boxes ?? []).filter(box => (filter.value === 'ALL' || (filter.value === 'ACTIVE' && box.current_transport?.status === 'ACTIVE') || (filter.value === 'ALARM' && box.alarm.active) || (filter.value === 'OFFLINE' && box.connectivity !== 'ONLINE')) && `${box.box_id} ${box.display_name}`.toLowerCase().includes(query.value.trim().toLowerCase())));
const selectedBox = computed(() => overview.value?.boxes.find(box => box.box_id === selectedBoxId.value) ?? null);
const selectedTask = computed(() => {
  const summary = selectedBox.value?.current_transport;
  return summary ? overview.value?.tasks.find(task => task.task_id === summary.task_id) ?? null : null;
});
</script>

<template>
  <BusinessShell>
    <div class="page-title enterprise-page-head"><div><p class="data-note">企业运输中心 · 位置</p><h1>位置与设备</h1><p class="data-note">位置为任务路线示意，全部标 MOCK；不是真实 GPS。</p></div><div class="page-actions"><span class="map-mock-label">MOCK MAP</span><button class="button secondary" @click="refresh">刷新</button></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="loading && !overview" role="status">正在读取地图数据…</p>
    <section v-if="overview" class="map-console">
      <div class="map-console-main panel">
        <div class="map-console-toolbar"><label class="list-search">搜索<input v-model="query" placeholder="箱体编号 / 名称" /></label><div class="filter-tabs"><button :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'">全部</button><button :class="{ active: filter === 'ACTIVE' }" @click="filter = 'ACTIVE'">在途</button><button :class="{ active: filter === 'ALARM' }" @click="filter = 'ALARM'">异常</button><button :class="{ active: filter === 'OFFLINE' }" @click="filter = 'OFFLINE'">离线</button></div><span class="map-mock-label">示意路网</span></div>
        <MapScene :boxes="overview.boxes" :tasks="overview.tasks" :selected-box-id="selectedBoxId" :selected-task-id="selectedTask?.task_id ?? null" @select-box="selectedBoxId = $event" />
        <div class="map-floating-summary"><span><small>在途箱体</small><strong>{{ overview.kpis.active_transports }}<i> 个</i></strong></span><span><small>活跃告警箱</small><strong class="summary-attention">{{ overview.kpis.active_alarm_boxes }}<i> 个</i></strong></span><span><small>未定位</small><strong>{{ overview.boxes.filter(b => !b.position).length }}<i> 个</i></strong></span></div>
      </div>
      <aside class="map-asset-panel panel">
        <div class="enterprise-panel-heading"><div><h2>企业箱体</h2><p>{{ visible.length }} 个结果 · 按来源/状态独立展示</p></div></div>
        <div class="map-asset-list">
          <button v-for="box in visible" :key="box.box_id" :class="['map-asset-row', { 'map-asset-selected': box.box_id === selectedBoxId }]" @click="selectedBoxId = box.box_id">
            <span :class="['map-status-dot', box.alarm.active ? 'alarm' : box.connectivity === 'ONLINE' ? 'online' : 'offline']" />
            <span class="map-asset-details"><strong>{{ box.box_id }}</strong><small>{{ box.display_name }}</small><span>{{ positionLabel(box.position) }} · {{ assetSourceLabel(box.asset_source) }} · {{ connectivityLabel(box.connectivity) }}</span></span>
            <span class="map-row-chevron">›</span>
          </button>
          <p v-if="!visible.length" class="empty-record">当前筛选下没有箱体。</p>
        </div>
        <div v-if="selectedBox" class="map-selected-card">
          <div class="selected-card-title"><div><span class="map-mock-label">MOCK</span><strong>{{ selectedBox.box_id }}</strong></div><RouterLink class="text-action" :to="`/enterprise/assets/${selectedBox.box_id}`">箱体总览 →</RouterLink></div>
          <p class="selected-card-name">{{ selectedBox.display_name }}</p>
          <div class="selected-card-grid"><div><small>来源</small><strong>{{ assetSourceLabel(selectedBox.asset_source) }}</strong></div><div><small>在线</small><strong>{{ connectivityLabel(selectedBox.connectivity) }}</strong></div><div><small>环境</small><strong>{{ environmentValue(selectedBox.environment) }}</strong></div><div><small>告警</small><strong>{{ selectedBox.alarm.active ? `${selectedBox.alarm.active_count} 条活跃` : selectedBox.alarm.assessment === 'EVALUATED' ? '无活动告警' : '判定未知' }}</strong></div></div>
          <div v-if="selectedBox.current_transport" class="selected-transport"><span :class="['status-pill', taskStatusTone(selectedBox.current_transport.status)]">{{ taskStatusLabel(selectedBox.current_transport.status) }}</span><strong>{{ selectedBox.current_transport.item_name || '未填写物品' }}</strong><small>{{ selectedBox.current_transport.transport_no }}</small><RouterLink class="text-action" :to="`/enterprise/transports/${selectedBox.current_transport.task_id}`">打开任务 →</RouterLink></div>
          <p v-else class="empty-record">该箱体没有进行中的任务；位置可能来自最近完成的历史。</p>
          <p class="data-note">最后位置 {{ positionLabel(selectedBox.position) }} · 心跳 {{ dateTime(selectedBox.last_seen_at) }}。离线时冻结最后已知 MOCK 位置。</p>
        </div>
      </aside>
    </section>
  </BusinessShell>
</template>
