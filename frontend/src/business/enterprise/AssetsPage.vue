<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import { usePolling } from '../model';
import { assetSourceLabel, connectivityLabel, dateTime, environmentTitle, environmentValue, positionLabel, taskStatusLabel, taskStatusTone, type Box } from './model';

const session = useSession(), router = useRouter();
const boxes = ref<Box[]>([]), query = ref(''), source = ref(''), connectivity = ref(''), transport = ref('');
const { error, loading, refresh } = usePolling(async signal => {
  const value = await session.request<Box[]>('/enterprise/console/boxes', { signal });
  if (!signal.aborted) boxes.value = value;
}, () => { boxes.value = []; }, 12000);
const visible = computed(() => boxes.value.filter(box => (!source.value || box.asset_source === source.value) && (!connectivity.value || box.connectivity === connectivity.value) && (!transport.value || box.current_transport?.status === transport.value) && `${box.box_id} ${box.display_name}`.toLowerCase().includes(query.value.trim().toLowerCase())));
const simulated = computed(() => boxes.value.filter(box => box.asset_source === 'SIMULATED').length);
const needAttention = computed(() => boxes.value.filter(box => box.alarm.active || box.connectivity === 'OFFLINE').length);
</script>

<template>
  <BusinessShell>
    <div class="page-title enterprise-page-head"><div><p class="data-note">企业运输中心 · 资产</p><h1>物流箱资产</h1><p class="data-note">真实与模拟箱体统一列表；资产创建、编辑和场景切换留给后续切片。</p></div><div class="page-actions"><button class="button secondary" @click="refresh">刷新</button></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <section class="asset-summary-row"><div><small>企业箱体</small><strong>{{ boxes.length }}<i> 个</i></strong></div><div><small>模拟箱体</small><strong>{{ simulated }}<i> 个</i></strong></div><div><small>需关注</small><strong class="summary-attention">{{ needAttention }}<i> 个</i></strong></div><p class="data-note">来源、在线、运输、环境、告警各自独立，不用一个“正常”覆盖全部维度。</p></section>
    <section class="panel asset-table-panel">
      <div class="list-toolbar"><label class="list-search">搜索<input v-model="query" placeholder="箱体编号 / 名称" /></label><select v-model="source" aria-label="来源筛选"><option value="">全部来源</option><option value="REAL">实物箱</option><option value="SIMULATED">模拟箱</option></select><select v-model="connectivity" aria-label="在线筛选"><option value="">全部在线状态</option><option value="ONLINE">在线</option><option value="PARTIAL">部分离线</option><option value="OFFLINE">离线</option><option value="UNKNOWN">未知</option></select><select v-model="transport" aria-label="运输筛选"><option value="">全部运输状态</option><option value="ACTIVE">运输中</option><option value="READY">待开始</option><option value="COMPLETED">已结束</option></select><span class="table-count">{{ visible.length }} 个结果</span></div>
      <p v-if="loading && !boxes.length" role="status">正在读取企业箱体…</p>
      <div class="table-wrap"><table class="enterprise-table"><thead><tr><th>物流箱</th><th>来源</th><th>在线</th><th>运输</th><th>环境</th><th>告警</th><th>最后位置</th><th>更新时间</th></tr></thead><tbody>
        <tr v-for="box in visible" :key="box.box_id" @click="router.push(`/enterprise/assets/${box.box_id}`)">
          <td><strong>{{ box.box_id }}</strong><small class="table-subline">{{ box.display_name }}</small></td>
          <td><span :class="['source-badge', box.asset_source === 'SIMULATED' ? 'simulated' : 'real']">{{ assetSourceLabel(box.asset_source) }}</span></td>
          <td><span :class="['status-pill', box.connectivity === 'ONLINE' ? 'good' : box.connectivity === 'OFFLINE' ? 'critical' : 'warn']">{{ connectivityLabel(box.connectivity) }}</span></td>
          <td><span v-if="box.current_transport" :class="['status-pill', taskStatusTone(box.current_transport.status)]">{{ taskStatusLabel(box.current_transport.status) }} · {{ box.current_transport.item_name || '未填写物品' }}</span><small v-else class="table-subline">当前无运输任务</small></td>
          <td>{{ environmentValue(box.environment) }}<small class="table-subline">{{ environmentTitle(box.environment) }}</small></td>
          <td><span :class="['status-pill', box.alarm.active ? 'critical' : 'neutral']">{{ box.alarm.active ? `${box.alarm.active_count} 条活跃` : box.alarm.assessment === 'EVALUATED' ? '无活动告警' : '判定未知' }}</span></td>
          <td>{{ positionLabel(box.position) }}</td>
          <td>{{ dateTime(box.environment.received_at || box.last_seen_at) }}</td>
        </tr>
      </tbody></table><p v-if="!visible.length && !loading" class="empty-record">当前筛选下没有箱体。</p></div>
      <p class="capability-note">只读视图：资产创建/编辑、场景切换、停用和 CSV 导出不在首切片；箱体详情当前只提供总览摘要。</p>
    </section>
  </BusinessShell>
</template>
