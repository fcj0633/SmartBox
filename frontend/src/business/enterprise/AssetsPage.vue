<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import AssetDrawer from './AssetDrawer.vue';
import ScenarioDrawer from './ScenarioDrawer.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import { usePolling, errorText } from '../model';
import { assetSourceLabel, connectivityLabel, dateTime, demoEnabledLabel, environmentTitle, environmentValue, positionLabel, scenarioLabel, scenarioTone, taskStatusLabel, taskStatusTone, type Box } from './model';

const session = useSession(), router = useRouter();
const boxes = ref<Box[]>([]), query = ref(''), source = ref(''), connectivity = ref(''), transport = ref(''), scenarioFilter = ref('');
const assetDrawer = ref<{ mode: 'create' | 'edit'; box: Box | null } | null>(null);
const scenarioBox = ref<Box | null>(null);
const confirmBox = ref<Box | null>(null);
const actionError = ref(''), busy = ref(false);
const { error, loading, refresh } = usePolling(async signal => {
  const value = await session.request<Box[]>('/enterprise/console/boxes', { signal });
  if (!signal.aborted) boxes.value = value;
}, () => { boxes.value = []; }, 12000);
const visible = computed(() => boxes.value.filter(box => (!source.value || box.asset_source === source.value) && (!connectivity.value || box.connectivity === connectivity.value) && (!transport.value || box.current_transport?.status === transport.value) && (!scenarioFilter.value || box.demo_scenario === scenarioFilter.value) && `${box.box_id} ${box.display_name} ${box.purpose ?? ''}`.toLowerCase().includes(query.value.trim().toLowerCase())));
const simulated = computed(() => boxes.value.filter(box => box.asset_source === 'SIMULATED').length);
const needAttention = computed(() => boxes.value.filter(box => box.alarm.active || box.connectivity === 'OFFLINE').length);
const confirmTitle = computed(() => confirmBox.value?.demo_enabled === false ? '确认启用模拟输入？' : '确认停用模拟输入？');
function openCreate() { actionError.value = ''; assetDrawer.value = { mode: 'create', box: null }; }
function openEdit(box: Box) { actionError.value = ''; assetDrawer.value = { mode: 'edit', box }; }
function openScenario(box: Box) { actionError.value = ''; scenarioBox.value = box; }
async function toggle(box: Box) {
  if (busy.value) return;
  busy.value = true; actionError.value = '';
  try {
    await session.request(`/enterprise/console/boxes/${encodeURIComponent(box.box_id)}/${box.demo_enabled === false ? 'enable' : 'disable'}`, { method: 'POST' });
    confirmBox.value = null; refresh();
  } catch (problem) { actionError.value = errorText(problem); confirmBox.value = null; }
  finally { busy.value = false; }
}
function saved() { refresh(); }
</script>

<template>
  <BusinessShell>
    <div class="page-title enterprise-page-head"><div><p class="data-note">企业运输中心 · 资产</p><h1>物流箱资产</h1><p class="data-note">真实与模拟箱体统一列表；新建/编辑仅改元信息，场景和启停只对模拟箱体生效。</p></div><div class="page-actions"><button class="button secondary" @click="refresh">刷新</button><button class="button primary" @click="openCreate">＋ 新建模拟箱体</button></div></div>
    <p v-if="error || actionError" class="form-error" role="alert">{{ error || actionError }}</p>
    <section class="asset-summary-row"><div><small>企业箱体</small><strong>{{ boxes.length }}<i> 个</i></strong></div><div><small>模拟箱体</small><strong>{{ simulated }}<i> 个</i></strong></div><div><small>需关注</small><strong class="summary-attention">{{ needAttention }}<i> 个</i></strong></div><p class="data-note">用途、备注、场景仅改变演示元信息/快照，不改变真实设备身份。</p></section>
    <section class="panel asset-table-panel">
      <div class="list-toolbar"><label class="list-search">搜索<input v-model="query" placeholder="箱体编号 / 名称 / 用途" /></label><select v-model="source" aria-label="来源筛选"><option value="">全部来源</option><option value="REAL">实物箱</option><option value="SIMULATED">模拟箱</option></select><select v-model="connectivity" aria-label="在线筛选"><option value="">全部在线状态</option><option value="ONLINE">在线</option><option value="PARTIAL">部分离线</option><option value="OFFLINE">离线</option><option value="UNKNOWN">未知</option></select><select v-model="transport" aria-label="运输筛选"><option value="">全部运输状态</option><option value="ACTIVE">运输中</option><option value="READY">待开始</option><option value="COMPLETED">已结束</option></select><select v-model="scenarioFilter" aria-label="场景筛选"><option value="">全部场景</option><option value="normal">正常演示</option><option value="env_high">高温</option><option value="offline">离线</option><option value="stale">数据过期</option><option value="impact">姿态冲击</option></select><span class="table-count">{{ visible.length }} 个结果</span></div>
      <p v-if="loading && !boxes.length" role="status">正在读取企业箱体…</p>
      <div class="table-wrap"><table class="enterprise-table"><thead><tr><th>物流箱</th><th>来源</th><th>场景/启停</th><th>在线</th><th>运输</th><th>环境</th><th>告警</th><th>最后位置</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="box in visible" :key="box.box_id" @click="router.push(`/enterprise/assets/${box.box_id}`)">
          <td><strong>{{ box.box_id }}</strong><small class="table-subline">{{ box.display_name }}</small><small v-if="box.purpose" class="table-subline">{{ box.purpose }}</small></td>
          <td><span :class="['source-badge', box.asset_source === 'SIMULATED' ? 'simulated' : 'real']">{{ assetSourceLabel(box.asset_source) }}</span></td>
          <td><template v-if="box.asset_source === 'SIMULATED'"><span :class="['status-pill', scenarioTone(box.demo_scenario)]">{{ scenarioLabel(box.demo_scenario) }}</span><small class="table-subline">{{ demoEnabledLabel(box.demo_enabled) }}</small></template><span v-else class="table-subline">—</span></td>
          <td><span :class="['status-pill', box.connectivity === 'ONLINE' ? 'good' : box.connectivity === 'OFFLINE' ? 'critical' : 'warn']">{{ connectivityLabel(box.connectivity) }}</span></td>
          <td><span v-if="box.current_transport" :class="['status-pill', taskStatusTone(box.current_transport.status)]">{{ taskStatusLabel(box.current_transport.status) }} · {{ box.current_transport.item_name || '未填写物品' }}</span><small v-else class="table-subline">当前无运输任务</small></td>
          <td>{{ environmentValue(box.environment) }}<small class="table-subline">{{ environmentTitle(box.environment) }}</small></td>
          <td><span :class="['status-pill', box.alarm.active ? 'critical' : 'neutral']">{{ box.alarm.active ? `${box.alarm.active_count} 条活跃` : box.alarm.assessment === 'EVALUATED' ? '无活动告警' : '判定未知' }}</span></td>
          <td>{{ positionLabel(box.position) }}</td>
          <td>{{ dateTime(box.environment.received_at || box.last_seen_at) }}</td>
          <td class="row-actions"><button class="text-button" @click.stop="openEdit(box)">编辑</button><template v-if="box.asset_source === 'SIMULATED'"><button class="text-button" @click.stop="openScenario(box)">场景</button><button class="text-button" @click.stop="confirmBox = box">{{ box.demo_enabled === false ? '启用' : '停用' }}</button></template></td>
        </tr>
      </tbody></table><p v-if="!visible.length && !loading" class="empty-record">当前筛选下没有箱体。</p></div>
      <p class="capability-note">只读边界：八分区详情、CSV 导出、告警人工处置不在本切片；模拟场景由服务端演示快照产生，不冒充真实 MQTT 设备。</p>
    </section>
    <AssetDrawer v-if="assetDrawer" :mode="assetDrawer.mode" :box="assetDrawer.box" @close="assetDrawer = null" @saved="saved" />
    <ScenarioDrawer v-if="scenarioBox" :box="scenarioBox" @close="scenarioBox = null" @saved="saved" />
    <ConfirmDialog v-if="confirmBox" :title="confirmTitle" :busy="busy" :danger="confirmBox.demo_enabled !== false" :confirm-label="confirmBox.demo_enabled === false ? '启用模拟输入' : '停用模拟输入'" @close="confirmBox = null" @confirm="toggle(confirmBox)">
      <p>{{ confirmBox.demo_enabled === false ? '启用后将按当前场景恢复模拟快照；不创建真实 MQTT 身份。' : '停用后停止模拟快照并显示离线；任务、历史和最后位置保留。ACTIVE 运输中会拒绝停用。' }}</p>
      <p class="data-note">{{ confirmBox.box_id }} · {{ confirmBox.display_name }}</p>
    </ConfirmDialog>
  </BusinessShell>
</template>
