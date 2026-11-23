<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useSession } from '../../stores/session';
import { errorText } from '../model';
import { assetSourceLabel, scenarioLabel, type Box } from './model';

const props = withDefaults(defineProps<{ box?: Box | null; mode: 'create' | 'edit'; busy?: boolean }>(), { box: null, busy: false });
const emit = defineEmits<{ (event: 'close'): void; (event: 'saved'): void }>();
const session = useSession();
const displayName = ref(''), purpose = ref(''), notes = ref(''), scenario = ref('normal'), error = ref(''), saving = ref(false);
const editing = computed(() => props.mode === 'edit' && props.box != null);
let initialized = false;
watch(() => props.box?.box_id ?? 'new', () => {
  if (initialized) return;
  displayName.value = props.box?.display_name ?? '';
  purpose.value = props.box?.purpose ?? '';
  notes.value = props.box?.notes ?? '';
  scenario.value = props.box?.demo_scenario ?? 'normal';
  initialized = true;
}, { immediate: true });
async function submit() {
  if (saving.value) return;
  if (!displayName.value.trim()) { error.value = '请填写箱体展示名。'; return; }
  saving.value = true; error.value = '';
  try {
    const path = editing.value ? `/enterprise/console/boxes/${props.box!.box_id}/edit` : '/enterprise/console/boxes';
    await session.request(path, { method: 'POST', body: { display_name: displayName.value.trim(), purpose: purpose.value.trim() || null, notes: notes.value.trim() || null, ...(editing.value ? {} : { scenario: scenario.value }) } });
    emit('saved'); emit('close');
  } catch (problem) { error.value = errorText(problem); }
  finally { saving.value = false; }
}
</script>

<template>
  <div class="drawer-backdrop" @click.self="emit('close')">
    <aside class="enterprise-drawer" role="dialog" aria-modal="true" :aria-label="editing ? '编辑箱体元信息' : '新建模拟箱体'">
      <header class="drawer-head"><div><span class="drawer-eyebrow">{{ editing ? 'EDIT ASSET METADATA' : 'NEW SIMULATED ASSET' }}</span><h2>{{ editing ? '编辑箱体元信息' : '新建模拟箱体' }}</h2></div><button class="icon-close" aria-label="关闭" @click="emit('close')">×</button></header>
      <p class="drawer-note">模拟箱体使用独立 ENV/POSE 节点身份和演示快照服务，不是真实 MQTT 设备。来源、节点和所有权不可在本页修改。</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div v-if="box" class="asset-identity-strip"><span>编号 <strong>{{ box.box_id }}</strong></span><span>来源 <strong>{{ assetSourceLabel(box.asset_source) }}</strong></span><span>节点 <strong>{{ box.nodes.length }}</strong></span></div>
      <label class="drawer-field">展示名<input v-model="displayName" maxlength="128" placeholder="例如：华东冷链模拟箱" /></label>
      <label class="drawer-field">用途<input v-model="purpose" maxlength="120" placeholder="例如：冷链样本周转" /></label>
      <label class="drawer-field">备注<textarea v-model="notes" maxlength="500" placeholder="可选，最多 500 字" /></label>
      <label v-if="!editing" class="drawer-field">初始场景<select v-model="scenario"><option v-for="value in ['normal','env_high','offline','stale','impact']" :key="value" :value="value">{{ scenarioLabel(value) }}</option></select></label>
      <p v-if="editing" class="drawer-inline-note">场景切换请使用资产行的“场景”操作；停用/启用请使用行操作。</p>
      <footer class="drawer-footer"><button class="button secondary" :disabled="saving" @click="emit('close')">取消</button><button class="button primary" :disabled="saving || busy" @click="submit">{{ saving ? '正在保存…' : editing ? '保存元信息' : '创建模拟箱体' }}</button></footer>
    </aside>
  </div>
</template>
