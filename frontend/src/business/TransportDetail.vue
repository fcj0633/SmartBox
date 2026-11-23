<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../stores/session';
import { ApiError } from '../api/http';
import BusinessShell from './BusinessShell.vue';
import TransportOverview from './components/TransportOverview.vue';
import SampleHistory from './components/SampleHistory.vue';
import EventList from './components/EventList.vue';
import ConfirmDialog from './components/ConfirmDialog.vue';
import { usePolling, errorText, type Monitoring, type Task } from './model';
const route = useRoute(), router = useRouter(), session = useSession(), enterprise = computed(() => route.path.startsWith('/enterprise')), id = String(route.params.id), task = ref<Task | null>(null), monitor = ref<Monitoring | null>(null), name = ref(''), description = ref(''), busy = ref(false), actionError = ref(''), code = ref(''), shareNote = ref('');
const step = ref(2), view = ref(route.query.view === 'events' ? 'events' : 'overview'), dialog = ref<'start' | 'risk' | 'finish' | 'change' | 'share' | null>(null), controller = new AbortController();
let initialized = false;
const base = () => `/${enterprise.value ? 'enterprise' : 'rider'}/transports/${encodeURIComponent(id)}`;
function accept(t: Task, m: Monitoring) { if (String(t.task_id) !== id || String(m.task.task_id) !== id)
    throw new Error('task mismatch'); task.value = t; monitor.value = m; if (!initialized) {
    name.value = t.item_name || '';
    description.value = t.item_description || '';
    step.value = route.query.step === '1' ? 1 : route.query.step === '2' ? 2 : t.item_name ? 3 : 2;
    initialized = true;
} }
const { error, loading, refresh } = usePolling(async (signal) => { const [t, m] = await Promise.all([session.request<Task>(base(), { signal }), session.request<Monitoring>(base() + '/monitoring', { signal })]); if (!signal.aborted)
    accept(t, m); }, () => { task.value = null; monitor.value = null; code.value = ''; dialog.value = null; name.value = ''; description.value = ''; });
async function action(kind: 'item' | 'start' | 'finish' | 'access-code', confirm = false) {
    if (busy.value || enterprise.value)
        return;
    busy.value = true;
    actionError.value = '';
    try {
        if (kind === 'item') {
            await session.request(base() + '/item', { method: 'POST', body: { name: name.value, description: description.value }, signal: controller.signal });
            const t = await session.request<Task>(base(), { signal: controller.signal });
            task.value = t;
            name.value = t.item_name || '';
            description.value = t.item_description || '';
            step.value = 3;
            void router.replace({ query: { step: '3' } });
        }
        else if (kind === 'access-code') {
            const result = await session.request<{
                code: string;
            }>(base() + '/access-code', { method: 'POST', signal: controller.signal });
            code.value = result.code;
            shareNote.value = '仅当次显示。重新生成会使旧验证码与旧查看会话失效。';
            dialog.value = null;
        }
        else {
            const result = await session.request<Task>(base() + '/' + kind, { method: 'POST', body: kind === 'start' ? { confirm_risk: confirm } : undefined, signal: controller.signal });
            task.value = result;
            code.value = '';
            dialog.value = null;
            view.value = 'overview';
        }
        refresh();
    }
    catch (e) {
        if (controller.signal.aborted)
            return;
        if (e instanceof ApiError && e.code === 'RISK_CONFIRMATION_REQUIRED')
            dialog.value = 'risk';
        else {
            dialog.value = null;
            actionError.value = errorText(e);
            if (e instanceof ApiError && [403, 404].includes(e.status)) {
                task.value = null;
                monitor.value = null;
                code.value = '';
            }
        }
    }
    finally {
        busy.value = false;
    }
}
function confirm() { if (dialog.value === 'change') {
    dialog.value = null;
    void router.push('/rider/prepare');
}
else if (dialog.value === 'share')
    void action('access-code');
else
    void action(dialog.value === 'finish' ? 'finish' : 'start', dialog.value === 'risk'); }
async function copy() { try {
    await navigator.clipboard.writeText(`运输编号：${task.value?.transport_no}\n验证码：${code.value}\n打开 ${location.origin}/track 查看本次运输。`);
    shareNote.value = '分享信息已复制，请仅提供给本次运输查看者。';
}
catch {
    shareNote.value = '浏览器不允许复制，请手工复制。';
} }
onUnmounted(() => controller.abort());
</script>
<template>
  <BusinessShell>
    <RouterLink class="back-link" :to="enterprise?'/enterprise':'/rider'">‹ 返回{{enterprise?'企业工作区':'工作台'}}</RouterLink>
    <p v-if="error||actionError" class="form-error" role="alert">{{error||actionError}}</p>
    <p v-if="loading" role="status">正在读取本次运输…</p>
    <template v-if="task">
      <section v-if="!enterprise&&task.status==='READY'" class="flow-sheet">
        <ol class="stepper">
          <li v-for="(label,i) in ['选择箱体','绑定物品','核对开始']" :key="label" :class="{active:step===i+1,done:step>i+1}">
            <b>{{i+1}}</b>{{label}}</li>
        </ol><template v-if="step===1">
          <h1>已选箱体</h1>
          <dl class="definition-list">
            <div>
              <dt>运输编号</dt>
              <dd>{{task.transport_no}}</dd>
            </div>
            <div>
              <dt>箱体</dt>
              <dd>{{task.box_id}}</dd>
            </div>
          </dl>
          <p class="data-note">当前草稿已创建。更换箱体会另建草稿，原草稿保留在运输记录中。</p>
          <div class="form-actions"><button class="button secondary" :disabled="busy"
              @click="dialog='change'">更换箱体并另建</button><button class="button primary" :disabled="busy"
              @click="step=2">继续绑定物品</button></div>
        </template>
        <form v-else-if="step===2" @submit.prevent="action('item')">
          <h1>绑定运输物品</h1>
          <p class="data-note">{{task.transport_no}} · {{task.box_id}}</p><label class="field"
            for="item-name">物品名称<input id="item-name" v-model="name" maxlength="50" required :disabled="busy"
              placeholder="填写本次运输的物品名称" /></label><label class="field" for="item-description">物品说明（可选）<textarea
              id="item-description" v-model="description" maxlength="200" :disabled="busy" /></label>
          <div class="form-actions"><button class="button secondary" type="button" :disabled="busy"
              @click="step=1">上一步</button><button class="button primary" type="submit"
              :disabled="busy">{{busy?'正在保存…':'保存物品并核对'}}</button></div>
        </form>
        <template v-else>
          <h1>核对并开始运输</h1>
          <p class="binding-success">物品已保存，尚未开始运输。</p>
          <dl class="definition-list">
            <div>
              <dt>运输编号</dt>
              <dd>{{task.transport_no}}</dd>
            </div>
            <div>
              <dt>箱体</dt>
              <dd>{{task.box_id}}</dd>
            </div>
            <div>
              <dt>物品</dt>
              <dd>{{task.item_name}}</dd>
            </div>
            <div>
              <dt>说明</dt>
              <dd>{{task.item_description||'—'}}</dd>
            </div>
          </dl>
          <p class="data-note">在线、占用、当前使用资格与风险由服务端在开始时核验。</p>
          <div class="form-actions"><button class="button secondary" :disabled="busy"
              @click="step=2">修改物品</button><button class="button primary" :disabled="busy||!task.item_name"
              @click="dialog='start'">确认并开始运输</button></div>
        </template>
      </section>
      <template v-else-if="monitor">
        <div class="task-tools"><button v-if="view!=='overview'" class="back-link" aria-label="返回运输概览"
            @click="view='overview'">‹ 返回运输概览</button><button class="text-button" @click="refresh">刷新状态</button></div>
        <TransportOverview v-if="view==='overview'" :data="{...monitor,task}" :can-finish="!enterprise" :busy="busy"
          @view="view=$event" @finish="dialog='finish'" />
        <section v-else class="sheet">
          <p class="task-number">{{task.transport_no}} · {{task.item_name}}</p>
          <SampleHistory v-if="view==='environment'" :samples="monitor.samples" />
          <EventList v-else :events="monitor.events" />
        </section>
        <section v-if="!enterprise&&task.status!=='READY'&&view==='overview'" class="share-section">
          <h2>分享给查看者</h2>
          <p class="data-note">查看者无需账号，仅可访问本次运输。结束后有效窗口由服务端校验。</p><button class="button secondary" :disabled="busy"
            @click="code?dialog='share':action('access-code')">{{code?'重新生成验证码':'生成查看验证码'}}</button>
          <div v-if="code" class="share-code"><strong aria-label="运输验证码">{{code}}</strong><button class="text-button"
              @click="copy">复制分享信息</button>
            <p class="data-note">{{shareNote}}</p><button class="text-button" @click="code=''">隐藏验证码</button>
          </div>
        </section>
      </template>
    </template>
    <ConfirmDialog v-if="dialog"
      :title="dialog==='finish'?'确认结束运输？':dialog==='risk'?'确认风险后开始？':dialog==='change'?'更换箱体并另建草稿？':dialog==='share'?'重新生成查看验证码？':'确认开始运输？'"
      :busy="busy" :danger="dialog==='finish'"
      :confirm-label="dialog==='risk'?'我已知晓风险，继续开始':dialog==='finish'?'结束运输':dialog==='change'?'保留原草稿并另建':dialog==='share'?'重新生成':'开始运输'"
      @close="dialog=null" @confirm="confirm">
      <p>
        {{dialog==='finish'?'结束后保留本次任务历史，不代表签收、告警恢复或数据已补齐。':dialog==='risk'?'设备环境有风险或尚未完成判断。请核实情况后，明确决定是否继续。':dialog==='change'?'原草稿不会删除。接下来重新选箱，点击下一步后才创建新草稿。':dialog==='share'?'此操作使旧验证码与旧会话失效，请仅分享给本次查看者。':'请核对箱体与物品，提交后由服务端检查在线、占用与使用资格。'}}
      </p>
      <p class="data-note">{{task?.transport_no}} · {{task?.item_name}}</p>
    </ConfirmDialog>
  </BusinessShell>
</template>