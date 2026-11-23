<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useSession } from '../../stores/session';
import { errorText } from '../model';
import type { Box, Route, Task } from './model';
import { assetSourceLabel, connectivityLabel } from './model';

const props = withDefaults(defineProps<{ boxes: Box[]; routes: Route[]; task?: Task | null; busy?: boolean }>(), { task: null, busy: false });
const emit = defineEmits<{ (event: 'close'): void; (event: 'saved'): void }>();
const session = useSession();
const boxId = ref(''), itemName = ref(''), description = ref(''), routeCode = ref(''), error = ref(''), saving = ref(false);
const editing = computed(() => props.task != null);
let initialized = false;
watch(() => [props.task?.task_id ?? 'new', props.routes.length, props.boxes.length], () => {
  if (initialized) return;
  if (props.task) {
    boxId.value = props.task.box_id;
    itemName.value = props.task.item_name ?? '';
    description.value = props.task.item_description ?? '';
    routeCode.value = props.task.route?.code ?? props.routes[0]?.code ?? '';
  } else {
    boxId.value = props.boxes[0]?.box_id ?? '';
    routeCode.value = props.routes[0]?.code ?? '';
    itemName.value = ''; description.value = '';
  }
  initialized = props.routes.length > 0 || props.task != null;
}, { immediate: true });
const selectedBox = computed(() => props.boxes.find(box => box.box_id === boxId.value) ?? null);
async function submit() {
  if (saving.value) return;
  if (!boxId.value || !itemName.value.trim() || !routeCode.value) { error.value = '请选择箱体、填写物品并选择模拟路线。'; return; }
  saving.value = true; error.value = '';
  try {
    const path = editing.value ? `/enterprise/console/transports/${props.task!.task_id}/edit` : '/enterprise/console/transports';
    await session.request(path, { method: 'POST', body: { box_id: boxId.value, item_name: itemName.value.trim(), item_description: description.value.trim() || null, route_code: routeCode.value } });
    emit('saved');
    emit('close');
  } catch (problem) { error.value = errorText(problem); }
  finally { saving.value = false; }
}
</script>

<template>
  <div class="drawer-backdrop" @click.self="emit('close')">
    <aside class="enterprise-drawer" role="dialog" aria-modal="true" :aria-label="editing ? '编辑运输任务' : '新建运输任务'">
      <header class="drawer-head"><div><span class="drawer-eyebrow">{{ editing ? 'EDIT READY TRANSPORT' : 'NEW TRANSPORT · READY' }}</span><h2>{{ editing ? '编辑待开始运输' : '新建运输任务' }}</h2></div><button class="icon-close" aria-label="关闭" @click="emit('close')">×</button></header>
      <p class="drawer-note">创建后为 READY；在线、告警、同箱占用由服务端在“开始”时重新校验。地图路线为 MOCK。</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <label class="drawer-field">箱体<select v-model="boxId" :disabled="editing">
        <option v-for="box in boxes" :key="box.box_id" :value="box.box_id">{{ box.box_id }} · {{ box.display_name }}</option>
      </select></label>
      <p v-if="selectedBox" class="drawer-inline-note">{{ assetSourceLabel(selectedBox.asset_source) }} · {{ connectivityLabel(selectedBox.connectivity) }} · 当前 {{ selectedBox.current_transport?.status === 'ACTIVE' ? '运输中' : '无进行中运输' }}</p>
      <label class="drawer-field">物品名称<input v-model="itemName" maxlength="50" placeholder="例如：冷链样本 / 精密仪器" /></label>
      <label class="drawer-field">物品说明<textarea v-model="description" maxlength="200" placeholder="可选，最多 200 字" /></label>
      <fieldset class="route-fieldset"><legend>预设模拟路线</legend>
        <button v-for="route in routes" :key="route.code" type="button" :class="['route-option', { selected: routeCode === route.code }]" @click="routeCode = route.code">
          <strong>{{ route.from }} → {{ route.to }}</strong><small>{{ route.name }} · {{ Math.round(route.duration_ms / 60000) }} 分钟演示</small>
        </button>
      </fieldset>
      <footer class="drawer-footer"><button class="button secondary" :disabled="saving" @click="emit('close')">取消</button><button class="button primary" :disabled="saving || busy" @click="submit">{{ saving ? '正在保存…' : editing ? '保存修改' : '创建 READY' }}</button></footer>
    </aside>
  </div>
</template>
