<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../stores/session';
import BusinessShell from './BusinessShell.vue';
import MapIllustration from './enterprise/MapIllustration.vue';
import EnvironmentSummary from './components/EnvironmentSummary.vue';
import { ApiError } from '../api/http';
import { usePolling, errorText, stateName, type Task, type Rider, type Asset, type Monitoring } from './model';
const route = useRoute(), session = useSession(), enterprise = ref<{
    enterprise_name: string;
    boxes: Asset[];
} | null>(null), tasks = ref<Task[]>([]), riders = ref<Rider[]>([]), selected = ref<Record<string, string>>({}), busy = ref(false), actionError = ref(''), search = ref(''), selectedTaskId = ref(''), monitor = ref<Monitoring | null>(null), monitorError = ref('');
const tab = computed(() => route.query.view === 'assets' ? 'assets' : route.query.view === 'transports' ? 'transports' : 'overview'), selectedTask = computed(() => tasks.value.find(t => String(t.task_id) === selectedTaskId.value));
const visibleTasks = computed(() => tasks.value.filter(t => `${t.transport_no} ${t.item_name || ''} ${t.box_id || ''}`.toLowerCase().includes(search.value.toLowerCase()))), visibleAssets = computed(() => enterprise.value?.boxes.filter(b => `${b.box_id} ${b.display_name}`.toLowerCase().includes(search.value.toLowerCase())) || []);
const { error, loading, refresh } = usePolling(async (signal) => {
    const result = await Promise.all([session.request<{
            enterprise_name: string;
            boxes: Asset[];
        }>('/enterprise/boxes', { signal }), session.request<Task[]>('/enterprise/transports', { signal }), session.request<Rider[]>('/enterprise/riders', { signal })]);
    if (signal.aborted)
        return;
    enterprise.value = result[0];
    tasks.value = result[1];
    riders.value = result[2];
    if (!selectedTask.value) {
        selectedTaskId.value = tasks.value[0] ? String(tasks.value[0].task_id) : '';
        monitor.value = null;
    }
    const target = selectedTaskId.value;
    if (target) {
        try {
            const value = await session.request<Monitoring>(`/enterprise/transports/${encodeURIComponent(target)}/monitoring`, { signal });
            if (!signal.aborted && selectedTaskId.value === target && String(value.task.task_id) === target) {
                monitor.value = value;
                monitorError.value = '';
            }
        }
        catch (e) {
            if (!signal.aborted && selectedTaskId.value === target) {
                monitor.value = null;
                monitorError.value = errorText(e);
            }
        }
    }
}, () => { enterprise.value = null; tasks.value = []; riders.value = []; monitor.value = null; selectedTaskId.value = ''; selected.value = {}; });
async function change(box: string, unassign = false) { if (busy.value)
    return; actionError.value = ''; busy.value = true; try {
    await session.request(`/enterprise/boxes/${encodeURIComponent(box)}/${unassign ? 'unassign' : 'assign'}`, { method: 'POST', body: unassign ? undefined : { rider_user_id: BigInt(selected.value[box]) } });
    refresh();
}
catch (e) {
    actionError.value = errorText(e);
    if (e instanceof ApiError && [403, 404].includes(e.status)) {
        enterprise.value = null;
        tasks.value = [];
        riders.value = [];
        monitor.value = null;
        selectedTaskId.value = '';
        selected.value = {};
    }
}
finally {
    busy.value = false;
} }
function selectTask(id: number) { selectedTaskId.value = String(id); monitor.value = null; monitorError.value = ''; refresh(); }
</script>
<template>
  <BusinessShell>
    <div class="page-title">
      <div>
        <p class="data-note">{{enterprise?.enterprise_name||'企业工作空间'}}</p>
        <h1>{{tab==='assets'?'物流箱资产':tab==='transports'?'运输任务':'运营总览'}}</h1>
        <p class="data-note">从资产和运输任务进入本企业授权信息。</p>
      </div><button class="button secondary" @click="refresh">刷新</button>
    </div>
    <p v-if="error||actionError" role="alert" class="form-error">{{error||actionError}}</p>
    <p v-if="loading">正在读取企业资产与任务…</p>
    <div class="enterprise-kpis">
      <div><span>本企业资产</span><strong>{{enterprise?.boxes.length??'—'}}<small> 个</small></strong></div>
      <div><span>近期运输中</span><strong>{{tasks.filter(t=>t.status==='ACTIVE').length}}<small> 项</small></strong></div>
      <div><span>近期已结束</span><strong>{{tasks.filter(t=>t.status==='COMPLETED').length}}<small> 项</small></strong></div>
      <p>运输统计仅限接口返回的<br />最近 {{tasks.length}} 项（最多 50 项）</p>
    </div>
    <div class="enterprise-toolbar">
      <nav aria-label="企业页面分区">
        <RouterLink to="/enterprise" :class="{active:tab==='overview'}">概览</RouterLink>
        <RouterLink to="/enterprise?view=assets" :class="{active:tab==='assets'}">资产</RouterLink>
        <RouterLink to="/enterprise?view=transports" :class="{active:tab==='transports'}">运输</RouterLink>
      </nav><label>搜索<input v-model="search" aria-label="搜索资产或任务" placeholder="箱体编号、物品或运输编号" /></label>
    </div>
    <section v-if="tab!=='assets'" :class="['route-dashboard-layout',{'tasks-only':tab==='transports'}]">
      <section v-if="tab==='overview'" class="route-map-panel">
        <div class="enterprise-panel-heading">
          <div>
            <h2>资产与运输工作区</h2>
            <p>无真实位置数据，地图仅承担布局示意</p>
          </div><span class="map-mock-label">地图 Mock</span>
        </div>
        <MapIllustration />
      </section>
      <section class="route-task-panel">
        <div class="enterprise-panel-heading">
          <div>
            <h2>运输任务</h2>
            <p>最近任务 · {{visibleTasks.length}} 项结果</p>
          </div>
        </div>
        <div class="enterprise-task-scroll"><button v-for="t in visibleTasks" :key="String(t.task_id)"
            :class="['route-task-row',{'active-route-task':String(t.task_id)===selectedTaskId}]"
            @click="selectTask(t.task_id)"><span class="task-marker" :class="t.status" /><span
              class="task-main"><strong>{{t.item_name||'待绑定物品'}}</strong><small>{{t.transport_no}}</small><small>{{t.box_id}}</small></span><span>{{stateName(t.status)}}</span></button>
          <p v-if="!visibleTasks.length&&!loading" class="empty-record">暂无对应运输任务。</p>
        </div>
        <RouterLink v-if="selectedTask" class="enterprise-task-detail"
          :to="`/enterprise/transports/${selectedTask.task_id}`">查看选中任务详情 ›</RouterLink>
      </section>
    </section>
    <section v-if="selectedTask&&tab!=='assets'" class="enterprise-selected sheet">
      <div class="section-heading">
        <div>
          <h2>{{selectedTask.item_name||'待绑定物品'}}</h2>
          <p class="data-note">{{selectedTask.transport_no}} · {{stateName(selectedTask.status)}}</p>
        </div>
        <RouterLink class="text-button" :to="`/enterprise/transports/${selectedTask.task_id}`">任务详情</RouterLink>
      </div>
      <EnvironmentSummary v-if="monitor" :environment="monitor.environment"
        :historical="selectedTask.status==='COMPLETED'" />
      <p v-else class="data-note">{{monitorError||'正在读取选中任务监测…'}}</p>
    </section>
    <section v-if="tab!=='transports'" class="sheet enterprise-assets">
      <div class="section-heading">
        <h2>企业箱体</h2><span>{{visibleAssets.length}} 个结果</span>
      </div>
      <p v-if="!visibleAssets.length&&!loading" class="empty-record">当前没有对应箱体。</p>
      <article v-for="b in visibleAssets" :key="b.box_id" class="enterprise-asset-row">
        <div><strong>{{b.display_name}}</strong><small>{{b.box_id}}</small></div>
        <span>{{b.rider_username?'使用骑手：'+b.rider_username:'尚未分配'}}</span><button v-if="b.rider_user_id"
          class="button secondary" :disabled="busy" @click="change(b.box_id,true)">收回分配</button>
        <form v-else @submit.prevent="change(b.box_id)"><label :for="'rider-'+b.box_id" class="sb-sr-only">选择骑手
            {{b.box_id}}</label><select :id="'rider-'+b.box_id" v-model="selected[b.box_id]" required>
            <option disabled value="">选择演示骑手</option>
            <option v-for="r in riders" :key="String(r.user_id)" :value="String(r.user_id)">{{r.username}}</option>
          </select><button class="button primary" :disabled="busy||!selected[b.box_id]">分配箱体</button></form>
      </article>
    </section>
  </BusinessShell>
</template>