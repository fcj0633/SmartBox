<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../stores/session';
import BusinessShell from './BusinessShell.vue';
import EnvironmentSummary from './components/EnvironmentSummary.vue';
import Icon from './components/Icon.vue';
import { usePolling, errorText, sourceName, stateName, date, type Asset, type Task, type Monitoring } from './model';
const route = useRoute(), session = useSession(), boxes = ref<Asset[]>([]), tasks = ref<Task[]>([]), monitors = ref<Record<string, Monitoring>>({}), monitorErrors = ref<Record<string, string>>({}), selectedBox = ref(typeof route.query.box==='string'?route.query.box:''), filter = ref('全部');
const mode = computed(() => route.path.split('/')[2] || 'home'), active = computed(() => tasks.value.filter(t => t.status === 'ACTIVE'));
const { error, loading, refresh } = usePolling(async (signal) => {
    const [assets, list] = await Promise.all([session.request<Asset[]>('/rider/boxes', { signal }), session.request<Task[]>('/rider/transports', { signal })]);
    if (signal.aborted)
        return;
    boxes.value = assets;
    tasks.value = list;
    const current = list.filter(t => t.status === 'ACTIVE');
    const values = await Promise.allSettled(current.map(t => session.request<Monitoring>(`/rider/transports/${encodeURIComponent(String(t.task_id))}/monitoring`, { signal })));
    if (signal.aborted)
        return;
    const next: Record<string, Monitoring> = {}, failures: Record<string, string> = {};
    values.forEach((v, i) => { const key = String(current[i].task_id); if (v.status === 'fulfilled' && String(v.value.task.task_id) === key)
        next[key] = v.value;
    else
        failures[key] = v.status === 'rejected' ? errorText(v.reason) : '任务监测响应不匹配'; });
    monitors.value = next;
    monitorErrors.value = failures;
}, () => { boxes.value = []; tasks.value = []; monitors.value = {}; monitorErrors.value = {}; selectedBox.value = ''; });
const occupied = (id: string) => tasks.value.some(t => t.box_id === id && t.status === 'ACTIVE');
const filtered = computed(() => tasks.value.filter(t => filter.value === '全部' || stateName(t.status) === filter.value));
</script>
<template>
  <BusinessShell>
    <div class="page-title">
      <div>
        <h1>{{mode==='home'?'运输工作台':mode==='boxes'?'我的箱体':mode==='history'?'运输记录':'我的'}}</h1>
        <p class="data-note">{{session.user?.username}} · {{mode==='history'?'本人最近 50 项任务':'个人运输工作空间'}}</p>
      </div><button class="text-button" @click="refresh">刷新</button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{error}}</p>
    <p v-if="loading" role="status">正在读取运输与箱体…</p>
    <div v-if="mode==='home'" class="workspace-grid">
      <section class="current-work"><template v-if="active.length">
          <div class="section-heading">
            <h2>当前运输<span v-if="active.length>1">（{{active.length}}）</span></h2><span class="status-live">运输中</span>
          </div>
          <article v-for="t in active" :key="String(t.task_id)" class="current-transport">
            <div class="transport-identity">
              <h3>{{t.item_name}}</h3>
              <p>{{t.box_id}} · {{date(t.started_at)}} 开始</p>
            </div>
            <EnvironmentSummary v-if="monitors[String(t.task_id)]"
              :environment="monitors[String(t.task_id)].environment" />
            <p v-else class="data-note">{{monitorErrors[String(t.task_id)]||'正在读取本次任务环境…'}}</p>
            <RouterLink class="button primary wide" :to="`/rider/transports/${t.task_id}`">查看当前运输
              <Icon name="chevron" :size="18" />
            </RouterLink>
          </article>
        </template>
        <div v-else-if="!loading" class="no-transport">
          <Icon name="task" :size="32" />
          <h2>{{boxes.length?'当前没有运输':'暂无可使用的箱体'}}</h2>
          <p>{{boxes.length?'选择可用箱体，绑定物品后开始运输。':'请确认箱体归属，或联系企业分配。'}}</p>
          <RouterLink v-if="boxes.length" to="/rider/prepare" class="button primary">开始新运输</RouterLink>
          <RouterLink v-else to="/rider/account" class="button secondary">查看使用权限</RouterLink>
        </div>
      </section>
      <aside class="workspace-side">
        <section class="box-list-section">
          <div class="section-heading">
            <h2>我的箱体</h2>
            <RouterLink class="text-button" to="/rider/boxes">全部</RouterLink>
          </div>
          <RouterLink v-for="b in boxes" :key="b.box_id" class="list-row box-row"
            :to="{path:'/rider/boxes',query:{box:b.box_id}}">
            <Icon name="box" /><span><strong>{{b.display_name}}</strong><small>{{b.box_id}} ·
                {{sourceName(b.source)}}</small><small>{{occupied(b.box_id)?'正在运输':'暂无进行中任务'}} · 在线情况未提供</small></span>
            <Icon name="chevron" :size="18" />
          </RouterLink>
          <RouterLink v-if="active.length&&boxes.length" class="button secondary wide" to="/rider/prepare">开始新运输
          </RouterLink>
          <p v-if="!boxes.length&&!loading" class="data-note">暂无本人可用箱体。</p>
        </section>
        <section class="auxiliary">
          <RouterLink class="list-row" to="/rider/history">
            <Icon name="archive" /><strong>运输记录</strong>
            <Icon name="chevron" :size="18" />
          </RouterLink>
          <RouterLink v-if="active.length" class="list-row" :to="`/rider/transports/${active[0].task_id}?view=events`">
            <Icon name="bell" /><strong>设备事件</strong>
            <Icon name="chevron" :size="18" />
          </RouterLink>
        </section>
      </aside>
    </div>
    <section v-else-if="mode==='boxes'" class="sheet">
      <p class="data-note">个人拥有和企业分配分别展示，设备控制资格独立核验。</p>
      <article v-for="b in boxes" :key="b.box_id"><button class="list-row box-row"
          :aria-expanded="selectedBox===b.box_id"
          @click="selectedBox=selectedBox===b.box_id?'':b.box_id">
          <Icon name="box" /><span><strong>{{b.display_name}}</strong><small>{{b.box_id}} ·
              {{sourceName(b.source)}}</small><small>{{occupied(b.box_id)?'正在运输':'暂无进行中任务'}}</small></span>
          <Icon name="chevron" :size="18" />
        </button>
        <div v-if="selectedBox===b.box_id" class="asset-inline-detail">
          <p>在线状态、节点诊断未由资产接口提供。拥有或使用箱体不自动授予设备配置权限。</p>
          <RouterLink v-if="occupied(b.box_id)" class="button secondary"
            :to="`/rider/transports/${tasks.find(t=>t.box_id===b.box_id&&t.status==='ACTIVE')!.task_id}`">查看当前运输
          </RouterLink>
          <RouterLink v-else class="button primary" :to="{path:'/rider/prepare',query:{box:b.box_id}}">开始新运输
          </RouterLink>
        </div>
      </article>
      <p v-if="!boxes.length&&!loading" class="empty-record">暂无可使用箱体。</p>
    </section>
    <section v-else-if="mode==='history'" class="sheet">
      <div class="segmented"><button v-for="f in ['全部','运输中','已结束','待开始']" :key="f" :class="{selected:filter===f}"
          @click="filter=f">{{f}}</button></div>
      <RouterLink v-for="t in filtered" :key="String(t.task_id)" class="list-row record-row"
        :to="`/rider/transports/${t.task_id}`">
        <Icon :name="t.status==='COMPLETED'?'archive':'task'" />
        <span><strong>{{t.item_name||'待绑定物品'}}</strong><small>{{t.transport_no}} ·
            {{stateName(t.status)}}</small><small>{{t.box_id}} · {{date(t.started_at)}}</small></span>
        <Icon name="chevron" :size="18" />
      </RouterLink>
      <p v-if="!filtered.length&&!loading" class="empty-record">暂无对应运输记录。</p>
    </section>
    <section v-else class="sheet account-page">
      <h2>{{session.user?.username}}</h2>
      <p>骑手 / 个人箱体拥有者</p>
      <h3>使用关系与权限</h3>
      <p class="data-note">{{boxes.filter(b=>b.source==='OWNED').length}}
        个个人箱体，{{boxes.filter(b=>b.source==='ASSIGNED').length}} 个企业分配箱体。分配不转移所有权。</p>
      <ul>
        <li>仅查看获准资产与本人运输。</li>
        <li>运输操作由服务端再次校验资格与状态。</li>
        <li>设备控制与资产使用资格独立，不因个人所有自动扩权。</li>
      </ul>
      <RouterLink to="/boxes" class="button secondary">进入原设备监测</RouterLink>
    </section>
  </BusinessShell>
</template>