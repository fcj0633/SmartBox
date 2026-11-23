<script setup lang="ts">
import { ref, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../../stores/session';
import { ApiError } from '../../api/http';
import BusinessShell from '../BusinessShell.vue';
import { usePolling, sourceName, errorText, type Asset, type Task } from '../model';
import Icon from '../components/Icon.vue';
const route = useRoute(), router = useRouter(), session = useSession(), boxes = ref<Asset[]>([]), tasks = ref<Task[]>([]), selected = ref(typeof route.query.box === 'string' ? route.query.box : ''), busy = ref(false), uncertain = ref(false), actionError = ref('');
const { error, loading } = usePolling(async (signal) => { const result = await Promise.all([session.request<Asset[]>('/rider/boxes', { signal }), session.request<Task[]>('/rider/transports', { signal })]); if (signal.aborted)
    return; boxes.value = result[0]; tasks.value = result[1]; }, () => { boxes.value = []; tasks.value = []; selected.value = ''; });
const occupied = (id: string) => tasks.value.some(t => t.box_id === id && t.status === 'ACTIVE');
const controller = new AbortController();
onUnmounted(() => controller.abort());
async function create() { if (busy.value || uncertain.value || !boxes.value.some(b => b.box_id === selected.value) || occupied(selected.value))
    return; busy.value = true; actionError.value = ''; try {
    const task = await session.request<Task>('/rider/transports', { method: 'POST', body: { box_id: selected.value }, signal: controller.signal });
    if (!controller.signal.aborted)
        await router.replace(`/rider/transports/${task.task_id}?step=2`);
}
catch (e) {
    if (controller.signal.aborted)
        return;
    actionError.value = errorText(e);
    if (e instanceof ApiError && [403, 404].includes(e.status)) {
        boxes.value = [];
        tasks.value = [];
        selected.value = '';
    }
    if (!(e instanceof ApiError) || [0, 503].includes(e.status)) {
        uncertain.value = true;
        actionError.value += '；创建结果尚未确认，请先查看运输记录，不要重复提交。';
    }
}
finally {
    busy.value = false;
} }
</script>
<template>
  <BusinessShell>
    <RouterLink to="/rider" class="back-link">‹ 返回工作台</RouterLink>
    <div class="page-title">
      <h1>开始新运输</h1>
    </div>
    <section class="flow-sheet">
      <ol class="stepper">
        <li class="active"><b>1</b>选择箱体</li>
        <li><b>2</b>绑定物品</li>
        <li><b>3</b>核对开始</li>
      </ol>
      <h2>选择运输箱体</h2>
      <p>同一箱体同时只能执行一个进行中运输。</p>
      <p v-if="loading">正在读取可用箱体…</p>
      <p v-if="error||actionError" class="form-error" role="alert">{{error||actionError}}</p><button v-for="b in boxes"
        :key="b.box_id" :class="['select-box-row',{chosen:selected===b.box_id}]"
        :disabled="busy||uncertain||occupied(b.box_id)" @click="selected=b.box_id">
        <Icon name="box" /><span><strong>{{b.display_name}}</strong><small>{{b.box_id}} ·
            {{sourceName(b.source)}}</small><small>{{occupied(b.box_id)?'正在运输，不可选择':'可申请使用；在线与风险由开始接口检查'}}</small></span><span
          class="selection-mark">{{selected===b.box_id?'已选':'选择'}}</span>
      </button>
      <p v-if="!boxes.length&&!loading" class="empty-record">暂无可使用箱体。</p><button class="button primary wide"
        :disabled="busy||uncertain||!boxes.some(b=>b.box_id===selected)||occupied(selected)"
        @click="create">{{busy?'正在创建草稿…':'下一步：绑定物品'}}</button>
      <RouterLink v-if="uncertain" class="text-button" to="/rider/history">核对运输记录</RouterLink>
      <p class="data-note">下一步会创建真实草稿。已有草稿可从运输记录继续，避免重复创建。</p>
    </section>
  </BusinessShell>
</template>