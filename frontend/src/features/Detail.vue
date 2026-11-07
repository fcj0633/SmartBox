<script setup lang="ts">
import { watch, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../stores/session';
import { canDiagnose, label, time, canCommand } from '../domain';
import SampleCard from '../components/SampleCard.vue';
import Controls from './Controls.vue';
const route = useRoute(), session = useSession();
watch(() => route.params.boxId, id => session.openScope(String(id)), { immediate: true });
onBeforeUnmount(() => session.stopScope());
const envFields = [{ key: 'temp_c', label: '温度', unit: '°C' }, { key: 'humi_pct', label: '湿度', unit: '%' }, { key: 'lux', label: '光照', unit: 'lx' }];
const poseFields = [{ key: 'tilt_deg', label: '倾角', unit: '°' }, { key: 'acc_g', label: '三轴加速度', unit: 'g' }];
const locFields = [{ key: 'lat', label: '纬度', unit: '' }, { key: 'lon', label: '经度', unit: '' }];
</script>
<template>
  <section class="page-heading"><div><RouterLink to="/boxes" class="back-link">← 返回物流箱列表</RouterLink><h1>{{ route.params.boxId }}</h1><p class="subtle">环境、姿态与位置 · 当前观测</p></div><div class="heading-actions"><span v-if="session.box" class="badge" :class="session.box.connectivity === 'ONLINE' ? 'good' : 'muted'">{{ label(session.box.connectivity) }}</span><button @click="session.refresh">刷新数据</button></div></section>
  <p v-if="session.error" role="alert" class="error-box">{{ session.error }}</p><div v-if="session.loading" role="status" class="empty">正在读取箱体状态…</div>
  <template v-if="session.box">
    <div class="notice"><span class="badge mock">接入说明</span> 数据由当前接入的ENV / POSE节点提供，定位仍为MOCK；连接真实设备时，输出控制会触发灯或风扇等实物动作。</div>
    <div class="status-grid"><section class="status-card"><span>门状态</span><strong>{{ label(session.box.door.state) }}</strong><small>{{ label(session.box.door.confidence) }} · 实验性结论</small></section><section class="status-card"><span>告警判断</span><strong>{{ session.box.alarm.assessment === 'UNKNOWN' ? '尚未完成判断' : session.box.alarm.active ? '存在告警' : '无活动告警' }}</strong><small>未知不等于正常或安全</small></section><section v-if="session.box.sentry && canDiagnose(session.user?.role)" class="status-card"><span>哨兵状态</span><strong>{{ label(session.box.sentry.state) }}</strong><small>修订 {{ String(session.box.sentry.revision) }} · 以节点实际确认为准</small></section></div>
    <div class="sample-grid"><SampleCard title="环境监测" :sample="session.box.env" :fields="envFields" :synchronized="session.synchronized" /><SampleCard v-if="canDiagnose(session.user?.role)" title="姿态监测" :sample="session.box.pose" :fields="poseFields" :synchronized="session.synchronized" /><SampleCard title="位置观测" :sample="session.box.loc" :fields="locFields" :synchronized="session.synchronized" mock /></div>
    <section v-if="canDiagnose(session.user?.role)" class="panel"><header class="panel-title"><h2>节点状态</h2><span class="subtle">通信在线与数据新鲜度独立判断</span></header><table><thead><tr><th>节点</th><th>角色 / 能力</th><th>通信</th><th>启动代次</th><th>最后接收</th></tr></thead><tbody><tr v-for="node in session.nodes" :key="node.node_id"><td class="mono">{{ node.node_id }} <span v-if="node.required" class="small-label">必需</span></td><td>{{ node.role }} / {{ node.caps }}</td><td><span class="badge" :class="node.online ? 'good' : 'muted'">{{ node.online ? '在线' : '离线' }}</span></td><td>{{ node.boot_id ?? '未知' }}</td><td>{{ time(node.last_received_at) }}</td></tr></tbody></table><p v-if="!session.nodes.length" class="subtle">尚无节点数据</p></section>
    <Controls v-if="canCommand(session.user?.role, 'sentry')" :key="`${session.user?.username}-${route.params.boxId}`" />
  </template>
</template>
