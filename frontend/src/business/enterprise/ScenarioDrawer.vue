<script setup lang="ts">
import { ref } from 'vue';
import { useSession } from '../../stores/session';
import { errorText } from '../model';
import { scenarioLabel, type Box } from './model';

const props = defineProps<{ box: Box }>();
const emit = defineEmits<{ (event: 'close'): void; (event: 'saved'): void }>();
const session = useSession();
const value = ref(props.box.demo_scenario ?? 'normal'), error = ref(''), saving = ref(false);
const options = [
  { id: 'normal', label: '正常', hint: '在线、环境正常；会恢复由本任务产生的环境告警。' },
  { id: 'env_high', label: '高温', hint: '温度约 9.2°C，经现有环境规则产生告警。' },
  { id: 'offline', label: '离线', hint: '节点离线，保留最后位置和最后观测时间。' },
  { id: 'stale', label: '数据过期', hint: '节点保持在线但停止更新样本，约 5 秒后显示过期。' },
  { id: 'impact', label: '姿态冲击', hint: '产生姿态设备事件；当前规则不会自动生成 IMPACT 告警。' },
];
async function submit() {
  if (saving.value) return;
  saving.value = true; error.value = '';
  try {
    await session.request(`/enterprise/console/boxes/${props.box.box_id}/scenario`, { method: 'POST', body: { scenario: value.value } });
    emit('saved'); emit('close');
  } catch (problem) { error.value = errorText(problem); }
  finally { saving.value = false; }
}
</script>

<template>
  <div class="drawer-backdrop" @click.self="emit('close')">
    <aside class="enterprise-drawer" role="dialog" aria-modal="true" aria-label="切换模拟场景">
      <header class="drawer-head"><div><span class="drawer-eyebrow">SIMULATED SCENARIO</span><h2>切换模拟场景</h2></div><button class="icon-close" aria-label="关闭" @click="emit('close')">×</button></header>
      <p class="drawer-note">{{ box.box_id }} · {{ box.display_name }}。场景只影响模拟快照和服务端可观察事实，不会改变真实设备身份或历史任务。</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div class="scenario-options">
        <button v-for="option in options" :key="option.id" type="button" :class="['scenario-option', { selected: value === option.id }]" @click="value = option.id">
          <strong>{{ option.label }}</strong><small>{{ option.hint }}</small>
        </button>
      </div>
      <p class="drawer-inline-note">当前：{{ scenarioLabel(box.demo_scenario) }}；切换后最多 5 秒由演示快照生效，告警可能需要一次刷新。</p>
      <footer class="drawer-footer"><button class="button secondary" :disabled="saving" @click="emit('close')">取消</button><button class="button primary" :disabled="saving" @click="submit">{{ saving ? '正在切换…' : `切换到${scenarioLabel(value)}` }}</button></footer>
    </aside>
  </div>
</template>
