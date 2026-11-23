<script setup lang="ts">
import { computed, ref } from 'vue';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import { usePolling } from '../model';
import { alarmLevelLabel, alarmMessage, dateTime, type AlarmList, type AlarmRow } from './model';

const session = useSession();
const alarms = ref<AlarmList>({ assessment_unknown_boxes: 0, rows: [] }), filter = ref('ACTIVE'), query = ref(''), selected = ref<AlarmRow | null>(null);
const { error, loading, refresh } = usePolling(async signal => {
  const value = await session.request<AlarmList>('/enterprise/console/alarms', { signal });
  if (!signal.aborted) alarms.value = value;
}, () => { alarms.value = { assessment_unknown_boxes: 0, rows: [] }; }, 12000);
const visible = computed(() => alarms.value.rows.filter(row => (filter.value === 'ALL' || (filter.value === 'ACTIVE' ? row.active : filter.value === 'RECOVERED' ? !row.active : row.level === filter.value)) && `${row.box_id} ${row.box_display_name} ${row.alarm_key}`.toLowerCase().includes(query.value.trim().toLowerCase())));
const counts = computed<Record<string, number>>(() => ({ ALL: alarms.value.rows.length, ACTIVE: alarms.value.rows.filter(r => r.active).length, CRITICAL: alarms.value.rows.filter(r => r.active && r.level === 'CRITICAL').length, WARNING: alarms.value.rows.filter(r => r.active && r.level === 'WARNING').length, RECOVERED: alarms.value.rows.filter(r => !r.active).length }));
</script>

<template>
  <BusinessShell>
    <div class="page-title enterprise-page-head"><div><p class="data-note">企业运输中心 · 异常</p><h1>告警中心</h1><p class="data-note">设备异常与恢复事实只读；人工确认/处理中/已处理留给后续切片。</p></div><div class="page-actions"><button class="button secondary" @click="refresh">刷新</button></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <section class="alarm-kpi-row"><div><small>活跃告警</small><strong>{{ counts.ACTIVE }}<i> 条</i></strong></div><div><small>严重</small><strong class="summary-attention">{{ counts.CRITICAL }}<i> 条</i></strong></div><div><small>警告</small><strong>{{ counts.WARNING }}<i> 条</i></strong></div><div><small>已恢复</small><strong>{{ counts.RECOVERED }}<i> 条</i></strong></div><p>告警判定未知箱体 <strong>{{ alarms.assessment_unknown_boxes }}</strong> 个；未知不等于正常。</p></section>
    <section class="panel alarm-panel">
      <div class="list-toolbar"><div class="filter-tabs"><button :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'">全部 <small>{{ counts.ALL }}</small></button><button :class="{ active: filter === 'ACTIVE' }" @click="filter = 'ACTIVE'">活跃 <small>{{ counts.ACTIVE }}</small></button><button :class="{ active: filter === 'CRITICAL' }" @click="filter = 'CRITICAL'">严重 <small>{{ counts.CRITICAL }}</small></button><button :class="{ active: filter === 'WARNING' }" @click="filter = 'WARNING'">警告 <small>{{ counts.WARNING }}</small></button><button :class="{ active: filter === 'RECOVERED' }" @click="filter = 'RECOVERED'">已恢复 <small>{{ counts.RECOVERED }}</small></button></div><label class="list-search">搜索<input v-model="query" placeholder="箱体 / 告警键" /></label></div>
      <p v-if="loading && !alarms.rows.length" role="status">正在读取告警…</p>
      <div class="table-wrap"><table class="enterprise-table"><thead><tr><th>等级</th><th>告警</th><th>箱体</th><th>触发 / 更新</th><th>恢复</th><th>来源事件</th><th></th></tr></thead><tbody>
        <tr v-for="row in visible" :key="`${row.box_id}-${row.alarm_key}`" @click="selected = row">
          <td><span :class="['status-pill', row.level === 'CRITICAL' ? 'critical' : 'warn']">{{ alarmLevelLabel(row.level) }}</span></td>
          <td><strong>{{ row.alarm_key }}</strong><small class="table-subline">{{ row.active ? '设备异常活跃' : '设备异常已恢复' }}</small></td>
          <td>{{ row.box_id }}<small class="table-subline">{{ row.box_display_name }}</small></td>
          <td>{{ dateTime(row.started_at) }}<small class="table-subline">{{ dateTime(row.updated_at) }}</small></td>
          <td>{{ row.active ? '未恢复' : dateTime(row.recovered_at) }}</td>
          <td>{{ row.source_event_type || '—' }}<small class="table-subline">{{ dateTime(row.source_event_at) }}</small></td>
          <td><button class="text-button" @click.stop="selected = row">详情</button></td>
        </tr>
      </tbody></table><p v-if="!visible.length && !loading" class="empty-record">当前筛选下没有告警。告警事实为空不代表绝无异常。</p></div>
    </section>
    <div v-if="selected" class="drawer-backdrop" @click.self="selected = null"><aside class="enterprise-drawer" role="dialog" aria-modal="true" aria-label="告警详情"><header class="drawer-head"><div><span class="drawer-eyebrow">ALARM DETAIL · READ ONLY</span><h2>{{ selected.alarm_key }}</h2></div><button class="icon-close" aria-label="关闭" @click="selected = null">×</button></header><div class="drawer-status-row"><span :class="['status-pill', selected.level === 'CRITICAL' ? 'critical' : 'warn']">{{ alarmLevelLabel(selected.level) }}</span><span :class="['status-pill', selected.active ? 'critical' : 'good']">{{ selected.active ? '活跃' : '已恢复' }}</span><span>{{ selected.box_id }}</span></div><dl class="definition-list"><div><dt>触发</dt><dd>{{ dateTime(selected.started_at) }}</dd></div><div><dt>更新</dt><dd>{{ dateTime(selected.updated_at) }}</dd></div><div><dt>恢复</dt><dd>{{ selected.active ? '尚未恢复' : dateTime(selected.recovered_at) }}</dd></div><div><dt>来源事件</dt><dd>{{ selected.source_event_type || '—' }} · {{ dateTime(selected.source_event_at) }}</dd></div><div><dt>说明</dt><dd>{{ alarmMessage(selected.details) || '—' }}</dd></div></dl><p class="data-note">人工确认/处理中/已处理不在首切片；点击“已处理”不会让设备异常恢复。</p><footer class="drawer-footer"><RouterLink class="button secondary" :to="`/enterprise/assets/${selected.box_id}`" @click="selected = null">打开箱体总览</RouterLink><RouterLink class="button primary" to="/enterprise/alarms" @click="selected = null">留在告警中心</RouterLink></footer></aside></div>
  </BusinessShell>
</template>
