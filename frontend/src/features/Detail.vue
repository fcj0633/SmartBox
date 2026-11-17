<script setup lang="ts">
import { computed, watch, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../stores/session';
import { canDiagnose, label, time, canCommand } from '../domain';
import SampleCard from '../components/SampleCard.vue';
import Controls from './Controls.vue';

const route = useRoute();
const session = useSession();
watch(() => route.params.boxId, id => session.openScope(String(id)), { immediate: true });
onBeforeUnmount(() => session.stopScope());

const envFields = [
  { key: 'temp_c', label: '温度', unit: '°C' },
  { key: 'humi_pct', label: '相对湿度', unit: '%RH' },
  { key: 'lux', label: '光照强度', unit: 'lx' },
];
const poseFields = [
  { key: 'tilt_deg', label: '倾角', unit: '°' },
  { key: 'acc_g', label: '三轴加速度', unit: 'g' },
];
const locFields = [
  { key: 'lat', label: '纬度', unit: '°' },
  { key: 'lon', label: '经度', unit: '°' },
];
const connectivityClass = computed(() => session.box?.connectivity === 'ONLINE' ? 'status-good' : session.box?.connectivity === 'PARTIAL' ? 'status-warning' : 'status-neutral');
const alarmText = computed(() => {
  const alarm = session.box?.alarm;
  if (!alarm || alarm.assessment !== 'EVALUATED') return '尚未完成评估';
  return alarm.active ? '存在活动告警' : '无活动告警';
});
function nodeStatusClass(online: boolean) { return online ? 'status-good' : 'status-warning'; }
function capText(caps: number) {
  const items = [
    [1, '环境画像'], [2, '补光控制'], [4, '风扇控制'], [8, '姿态校准'], [16, '指示灯控制'], [32, '定位模式'], [64, '哨兵'],
  ] as const;
  return items.filter(([bit]) => (caps & bit) === bit).map(([, name]) => name).join(' · ') || '未声明能力';
}
</script>

<template>
  <div v-if="session.error" class="inline-alert" role="alert"><strong>状态读取异常</strong><span>{{ session.error }}</span></div>
  <div v-if="session.loading && !session.box" class="loading-surface"><span class="loading-ring"></span><strong>正在读取箱体状态</strong><small>正在同步遥测快照与节点诊断</small></div>
  <div v-else-if="!session.box" class="empty-state"><div class="empty-illustration">▣</div><h3>暂时无法显示箱体</h3><p>请确认箱体编号和当前账号的授权范围。</p><RouterLink to="/boxes" class="button-primary">返回箱体总览</RouterLink></div>

  <template v-else>
    <section class="detail-hero">
      <div class="detail-hero-main">
        <RouterLink to="/boxes" class="breadcrumb-link">← 返回箱体总览</RouterLink>
        <div class="detail-title-row"><span class="detail-asset-icon">▣</span><div><div class="page-eyebrow"><span class="eyebrow-line"></span> CONTAINER MONITORING</div><h1>{{ session.box.box_id }}</h1><p>双节点环境与姿态监测 · 当前状态快照</p></div></div>
      </div>
      <div class="detail-hero-actions">
        <span class="state-tag" :class="connectivityClass"><i></i>{{ label(session.box.connectivity) }}</span>
        <span class="detail-sync-label">{{ session.synchronized ? '数据已同步' : '数据同步中断' }}</span>
        <button class="button-secondary" type="button" :disabled="session.loading" @click="session.refresh"><span class="button-icon">↻</span> 刷新快照</button>
      </div>
    </section>

    <section class="detail-state-grid">
      <article class="detail-state-card">
        <div class="state-card-symbol symbol-amber">◉</div><div class="state-card-content"><span>环境判定</span><strong>尚未评估</strong><small>物品画像规则引擎未接入</small></div><span class="state-tag status-neutral"><i></i>UNKNOWN</span>
      </article>
      <article class="detail-state-card">
        <div class="state-card-symbol symbol-red">!</div><div class="state-card-content"><span>告警状态</span><strong>{{ alarmText }}</strong><small>未知状态不代表环境正常</small></div><span class="state-tag status-neutral"><i></i>{{ session.box.alarm.assessment }}</span>
      </article>
      <article class="detail-state-card">
        <div class="state-card-symbol symbol-blue">⌑</div><div class="state-card-content"><span>门状态</span><strong>{{ label(session.box.door.state) }}</strong><small>当前结论可信度：{{ label(session.box.door.confidence) }}</small></div><span class="state-tag status-neutral"><i></i>{{ session.box.door.confidence }}</span>
      </article>
      <article v-if="session.box.sentry && canDiagnose(session.user?.role)" class="detail-state-card">
        <div class="state-card-symbol symbol-green">⌁</div><div class="state-card-content"><span>哨兵状态</span><strong>{{ label(session.box.sentry.state) }}</strong><small>修订版本 {{ String(session.box.sentry.revision) }}</small></div><span class="state-tag" :class="session.box.sentry.state === 'ARMED' ? 'status-good' : 'status-neutral'"><i></i>{{ session.box.sentry.state }}</span>
      </article>
    </section>

    <section class="section-block detail-section">
      <div class="section-heading-row"><div><span class="section-eyebrow">LIVE TELEMETRY</span><h2>实时监测数据</h2><p>只显示当前接口提供的最新观测，不虚构历史趋势。</p></div><span class="updated-label">最新快照 · {{ time(session.box.env?.received_at) }}</span></div>
      <div class="telemetry-grid">
        <SampleCard title="环境监测" node-label="ENV NODE · E53-IA1" :sample="session.box.env" :fields="envFields" :synchronized="session.synchronized" />
        <SampleCard v-if="canDiagnose(session.user?.role)" title="姿态监测" node-label="POSE NODE · E53-SC2" :sample="session.box.pose" :fields="poseFields" :synchronized="session.synchronized" />
        <SampleCard title="位置观测" node-label="POSE NODE · LOCATION" :sample="session.box.loc" :fields="locFields" :synchronized="session.synchronized" mock />
      </div>
    </section>

    <section v-if="canDiagnose(session.user?.role)" class="section-block">
      <div class="section-heading-row"><div><span class="section-eyebrow">DEVICE TOPOLOGY</span><h2>节点运行状态</h2><p>通信连接、启动代次和节点能力。</p></div><span class="count-label">{{ session.nodes.length }} 个节点</span></div>
      <div class="node-grid">
        <article v-for="node in session.nodes" :key="node.node_id" class="node-card">
          <div class="node-card-head"><div class="node-role-icon" :class="node.role === 'ENV' ? 'role-env' : 'role-pose'">{{ node.role === 'ENV' ? '◉' : '⌁' }}</div><div class="node-title"><span>{{ node.role }} SENSOR NODE</span><h3>{{ node.node_id }}</h3></div><span class="state-tag" :class="nodeStatusClass(node.online)"><i></i>{{ node.online ? '在线' : '离线' }}</span></div>
          <div class="node-facts"><div><span>设备角色</span><strong>{{ node.role === 'ENV' ? '环境监测节点' : '姿态与定位节点' }}</strong></div><div><span>启动代次</span><strong>{{ node.boot_id ?? '未知' }}</strong></div><div><span>最后接收</span><strong>{{ time(node.last_received_at) }}</strong></div></div>
          <div class="node-capabilities"><span>已声明能力</span><p>{{ capText(node.caps) }}</p></div>
        </article>
        <div v-if="!session.nodes.length" class="empty-inline">暂无节点诊断数据</div>
      </div>
    </section>

    <section v-if="canCommand(session.user?.role, 'sentry')" class="section-block command-section">
      <div class="section-heading-row"><div><span class="section-eyebrow">DEVICE OPERATIONS</span><h2>设备控制中心</h2><p>命令受理与设备实际执行结果分开呈现。</p></div><span class="source-tag">REST · MQTT · ACK</span></div>
      <Controls :key="`${session.user?.username}-${route.params.boxId}`" />
    </section>

    <section class="disclaimer-strip detail-disclaimer">
      <span class="disclaimer-icon">i</span><div><strong>当前能力边界</strong><p>真实双板已验证基础采集与三项输出；温湿度阈值判定、3帧去抖、姿态/冲击完整算法、门融合与告警判断仍待 TASK-008。GPS 当前为 MOCK。控制成功以节点实际回执为准。</p></div>
    </section>
  </template>
</template>
