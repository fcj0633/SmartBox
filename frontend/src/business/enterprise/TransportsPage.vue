<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../../stores/session';
import BusinessShell from '../BusinessShell.vue';
import TransportDrawer from './TransportDrawer.vue';
import { usePolling } from '../model';
import { dateTime as date, taskStatusLabel, taskStatusTone, type Box, type Route, type Task } from './model';

const session = useSession(), router = useRouter();
const tasks = ref<Task[]>([]), boxes = ref<Box[]>([]), routes = ref<Route[]>([]), filter = ref('ALL'), query = ref(''), boxFilter = ref(''), drawer = ref(false), editing = ref<Task | null>(null);
const { error, loading, refresh } = usePolling(async signal => {
  const [taskList, boxList, routeList] = await Promise.all([
    session.request<Task[]>('/enterprise/console/transports', { signal }),
    session.request<Box[]>('/enterprise/console/boxes', { signal }),
    session.request<Route[]>('/enterprise/console/routes', { signal }),
  ]);
  if (signal.aborted) return;
  tasks.value = taskList; boxes.value = boxList; routes.value = routeList;
}, () => { tasks.value = []; boxes.value = []; }, 12000);
const visible = computed(() => tasks.value.filter(task => (filter.value === 'ALL' || task.status === filter.value) && (!boxFilter.value || task.box_id === boxFilter.value) && `${task.transport_no} ${task.item_name ?? ''} ${task.box_id} ${task.box_display_name}`.toLowerCase().includes(query.value.trim().toLowerCase())));
const counts = computed<Record<string, number>>(() => ({ ALL: tasks.value.length, READY: tasks.value.filter(t => t.status === 'READY').length, ACTIVE: tasks.value.filter(t => t.status === 'ACTIVE').length, COMPLETED: tasks.value.filter(t => t.status === 'COMPLETED').length }));
function create() { editing.value = null; drawer.value = true; }
function edit(task: Task) { editing.value = task; drawer.value = true; }
function saved() { refresh(); }
</script>

<template>
  <BusinessShell>
    <div class="page-title enterprise-page-head"><div><p class="data-note">企业运输中心 · 任务</p><h1>运输任务</h1><p class="data-note">企业直接创建、开始和结束；同箱最多一个 ACTIVE 由服务端保证。</p></div><div class="page-actions"><button class="button secondary" @click="refresh">刷新</button><button class="button primary" @click="create">＋ 新建运输</button></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <section class="panel transport-list-panel">
      <div class="list-toolbar">
        <div class="filter-tabs"><button v-for="tab in [{ key: 'ALL', label: '全部' }, { key: 'READY', label: '待开始' }, { key: 'ACTIVE', label: '运输中' }, { key: 'COMPLETED', label: '已结束' }]" :key="tab.key" :class="{ active: filter === tab.key }" @click="filter = tab.key">{{ tab.label }} <small>{{ counts[tab.key] }}</small></button></div>
        <label class="list-search">搜索<input v-model="query" placeholder="运输编号 / 物品 / 箱体" /></label>
        <select v-model="boxFilter" aria-label="按箱体筛选"><option value="">全部箱体</option><option v-for="box in boxes" :key="box.box_id" :value="box.box_id">{{ box.box_id }}</option></select>
      </div>
      <p v-if="loading && !tasks.length" role="status">正在读取运输任务…</p>
      <div class="table-wrap">
        <table class="enterprise-table">
          <thead><tr><th>运输编号</th><th>物品</th><th>箱体</th><th>状态</th><th>模拟路线</th><th>创建 / 起止</th><th>操作者</th><th></th></tr></thead>
          <tbody>
            <tr v-for="task in visible" :key="task.task_id" @click="router.push(`/enterprise/transports/${task.task_id}`)">
              <td><strong>{{ task.transport_no }}</strong></td>
              <td>{{ task.item_name || '未填写' }}<small class="table-subline">{{ task.item_description || '无说明' }}</small></td>
              <td>{{ task.box_id }}<small class="table-subline">{{ task.box_display_name }}</small></td>
              <td><span :class="['status-pill', taskStatusTone(task.status)]">{{ taskStatusLabel(task.status) }}</span></td>
              <td>{{ task.route ? `${task.route.from} → ${task.route.to}` : '无模拟路线' }}<small class="table-subline">{{ task.position ? task.position.place : '未定位' }}</small></td>
              <td>{{ date(task.created_at) }}<small class="table-subline">{{ task.started_at ? date(task.started_at) : '未开始' }} → {{ task.ended_at ? date(task.ended_at) : '进行中/未结束' }}</small></td>
              <td>{{ task.operator_username || '—' }}</td>
              <td class="row-actions"><button v-if="task.status === 'READY'" class="text-button" @click.stop="edit(task)">编辑</button><button class="text-button" @click.stop="router.push(`/enterprise/transports/${task.task_id}`)">详情</button></td>
            </tr>
          </tbody>
        </table>
        <p v-if="!visible.length && !loading" class="empty-record">当前筛选下没有运输任务。</p>
      </div>
    </section>
    <TransportDrawer v-if="drawer" :boxes="boxes" :routes="routes" :task="editing" @close="drawer = false" @saved="saved" />
  </BusinessShell>
</template>
