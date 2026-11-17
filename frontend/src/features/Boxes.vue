<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import { useSession } from '../stores/session';
import { label, quality } from '../domain';
import type { Box } from '../api/types';

const session = useSession();
const filterText = ref('');
onMounted(() => session.openScope());
onBeforeUnmount(() => session.stopScope());

const onlineCount = computed(() => session.boxes.filter(box => box.connectivity === 'ONLINE').length);
const partialCount = computed(() => session.boxes.filter(box => box.connectivity === 'PARTIAL').length);
const offlineCount = computed(() => session.boxes.filter(box => box.connectivity === 'OFFLINE').length);
const unknownAlarmCount = computed(() => session.boxes.filter(box => box.alarm.assessment !== 'EVALUATED').length);
const activeAlarmCount = computed(() => session.boxes.filter(box => box.alarm.assessment === 'EVALUATED' && box.alarm.active === true).length);
const filteredBoxes = computed(() => {
  const q = filterText.value.trim().toLowerCase();
  return q ? session.boxes.filter(box => box.box_id.toLowerCase().includes(q)) : session.boxes;
});

function metric(value: unknown, digits = 1) {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : '—';
}
function nodeOnline(box: Box, role: 'ENV' | 'POSE') {
  return (box.online_mask & (role === 'ENV' ? 1 : 2)) !== 0;
}
function sampleTag(box: Box) {
  return quality(box.env, session.synchronized);
}
</script>

<template>
  <section class="page-intro">
    <div>
      <div class="page-eyebrow"><span class="eyebrow-line"></span> FLEET MONITORING</div>
      <h1>箱体运行总览</h1>
      <p>统一查看授权箱体的连接状态、环境观测与当前业务判断。</p>
    </div>
    <div class="intro-actions">
      <span class="view-mode-label"><i></i>实时监测视图</span>
      <button class="button-primary" type="button" :disabled="session.loading" @click="session.refresh"><span class="button-icon">↻</span> 刷新数据</button>
    </div>
  </section>

  <div v-if="session.error" class="inline-alert" role="alert"><strong>数据读取失败</strong><span>{{ session.error }}</span></div>

  <section class="overview-kpis" aria-label="箱体统计">
    <article class="kpi-card">
      <div class="kpi-top"><span>授权箱体</span><span class="kpi-icon kpi-icon-slate">▦</span></div>
      <div class="kpi-value">{{ session.boxes.length }}<small>个</small></div>
      <div class="kpi-foot">当前账号可访问的箱体</div>
    </article>
    <article class="kpi-card">
      <div class="kpi-top"><span>在线箱体</span><span class="kpi-icon kpi-icon-green">⌁</span></div>
      <div class="kpi-value">{{ onlineCount }}<small>个</small></div>
      <div class="kpi-foot"><span class="mini-dot mini-dot-green"></span> 双节点连接完整的箱体</div>
    </article>
    <article class="kpi-card">
      <div class="kpi-top"><span>部分 / 离线</span><span class="kpi-icon kpi-icon-amber">⌁</span></div>
      <div class="kpi-value">{{ partialCount + offlineCount }}<small>个</small></div>
      <div class="kpi-foot">部分可用 {{ partialCount }} · 离线 {{ offlineCount }}</div>
    </article>
    <article class="kpi-card">
      <div class="kpi-top"><span>告警评估</span><span class="kpi-icon kpi-icon-violet">!</span></div>
      <div class="kpi-value">{{ unknownAlarmCount }}<small>项待评估</small></div>
      <div class="kpi-foot">已评估活动告警 {{ activeAlarmCount }} 项；未知不等于正常</div>
    </article>
  </section>

  <section class="section-block">
    <div class="section-heading-row">
      <div><span class="section-eyebrow">ASSET INVENTORY</span><h2>我的箱体</h2><p>按箱体查看 ENV 与 POSE 双节点的最新状态。</p></div>
      <label class="search-box"><span>⌕</span><input v-model="filterText" type="search" aria-label="搜索箱体编号" placeholder="搜索箱体编号"></label>
    </div>

    <div v-if="session.loading && !session.boxes.length" class="loading-surface"><span class="loading-ring"></span><strong>正在同步设备状态</strong><small>读取授权范围内的箱体快照</small></div>
    <div v-else-if="!session.boxes.length && !session.error" class="empty-state"><div class="empty-illustration">▦</div><h3>暂无授权箱体</h3><p>当前账号尚未分配可查看的物流箱。</p></div>
    <div v-else-if="!filteredBoxes.length" class="empty-state compact-empty"><h3>没有匹配的箱体</h3><p>请尝试其他箱体编号。</p></div>

    <div v-else class="fleet-grid">
      <RouterLink v-for="box in filteredBoxes" :key="box.box_id" :to="`/boxes/${encodeURIComponent(box.box_id)}`" class="fleet-card">
        <div class="fleet-card-header">
          <div class="asset-id-group"><span class="asset-glyph">▣</span><div><span class="asset-label">SMART CONTAINER</span><h3>{{ box.box_id }}</h3></div></div>
          <span class="state-tag" :class="box.connectivity === 'ONLINE' ? 'status-good' : box.connectivity === 'PARTIAL' ? 'status-warning' : 'status-neutral'"><i></i>{{ label(box.connectivity) }}</span>
        </div>
        <div class="fleet-metrics">
          <div class="fleet-metric"><span>温度</span><strong>{{ metric(box.env?.value?.temp_c) }}<small>°C</small></strong></div>
          <div class="fleet-metric"><span>湿度</span><strong>{{ metric(box.env?.value?.humi_pct) }}<small>%RH</small></strong></div>
          <div class="fleet-metric"><span>光照</span><strong>{{ metric(box.env?.value?.lux, 0) }}<small>lx</small></strong></div>
        </div>
        <div class="fleet-node-row">
          <div class="node-chip"><span class="node-chip-dot" :class="nodeOnline(box, 'ENV') ? 'online' : ''"></span><strong>ENV</strong><small>{{ nodeOnline(box, 'ENV') ? '在线' : '离线' }}</small></div>
          <div class="node-chip"><span class="node-chip-dot" :class="nodeOnline(box, 'POSE') ? 'online' : ''"></span><strong>POSE</strong><small>{{ nodeOnline(box, 'POSE') ? '在线' : '离线' }}</small></div>
          <span class="state-tag state-tag-small" :class="box.env?.freshness === 'FRESH' && session.synchronized && box.env.valid ? 'status-good' : 'status-warning'"><i></i>{{ sampleTag(box) }}</span>
        </div>
        <div class="fleet-card-footer">
          <span>门状态：{{ label(box.door.state) }}</span>
          <span>告警：{{ box.alarm.assessment === 'UNKNOWN' ? '待评估' : box.alarm.active ? '存在活动告警' : '无活动告警' }}</span>
          <span class="card-enter">查看详情 <b>→</b></span>
        </div>
      </RouterLink>
    </div>
  </section>

  <section class="disclaimer-strip">
    <span class="disclaimer-icon">i</span>
    <div><strong>状态说明</strong><p>当前页面展示的是服务端实际返回的最新快照。告警与门状态在判断器未接入时保持“未知”；GPS 为模拟定位，不代表真实运输位置。</p></div>
  </section>
</template>
