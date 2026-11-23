<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useSession } from '../../stores/session';
import {
  commandKindLabel, commandStatusLabel, dateTime,
  type Box, type CommandOperation, type CommandRow,
} from './model';

const props = defineProps<{ box: Box; commands: CommandRow[] }>();
const emit = defineEmits<{ changed: [] }>();
const session = useSession();
type CommandKind = 'output' | 'sentry' | 'profile' | 'gps_mode' | 'calibrate';
const kind = ref<CommandKind>('output');
const outputKind = ref<'light' | 'fan' | 'indicator'>('light');
const targetNodeId = ref('');
const form = reactive({ on: true, duration: 2, mode: 'FIXED', seed: 1, profile: 'NORMAL', tempMin: 0, tempMax: 40, humiMin: 20, humiMax: 80, tempHyst: 2, humiHyst: 3 });
const submitting = ref(false), operation = ref<CommandOperation | null>(null), operationError = ref(''), pollNote = ref('');
let pollTimer: ReturnType<typeof setTimeout> | undefined, polls = 0;

const capability = computed(() => kind.value === 'output'
  ? outputKind.value === 'indicator' ? 16 : outputKind.value === 'fan' ? 4 : 2
  : kind.value === 'sentry' ? 64 : kind.value === 'profile' ? 1 : kind.value === 'gps_mode' ? 32 : 8);
const candidates = computed(() => props.box.nodes.filter(node => (node.caps & capability.value) === capability.value));
const availableKinds = computed(() => ([
  { id: 'output', label: '输出控制', caps: 2 | 4 | 16 },
  { id: 'sentry', label: '哨兵', caps: 64 },
  { id: 'profile', label: '环境画像', caps: 1 },
  { id: 'gps_mode', label: '定位模式', caps: 32 },
  { id: 'calibrate', label: '姿态校准', caps: 8 },
] as const).filter(item => props.box.nodes.some(node => (node.caps & item.caps) !== 0)));
const canSubmit = computed(() => !submitting.value && props.box.demo_enabled !== false && candidates.value.length > 0);
const selectedNode = computed(() => candidates.value.find(node => node.node_id === targetNodeId.value) ?? candidates.value[0] ?? null);

watch([kind, outputKind], () => {
  targetNodeId.value = candidates.value[0]?.node_id ?? '';
  operation.value = null; operationError.value = ''; pollNote.value = '';
});
watch(candidates, value => { if (!value.some(node => node.node_id === targetNodeId.value)) targetNodeId.value = value[0]?.node_id ?? ''; });

function terminal(status: string) { return ['SUCCEEDED', 'PARTIAL', 'FAILED', 'EXPIRED'].includes(status); }
function stopPolling() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined; }
async function poll(operationId: string) {
  stopPolling();
  try {
    const value = await session.request<CommandOperation>(`/enterprise/console/boxes/${encodeURIComponent(props.box.box_id)}/commands/${encodeURIComponent(operationId)}`);
    operation.value = value;
    if (terminal(value.status)) { polls = 0; emit('changed'); return; }
    if (++polls >= 20) { pollNote.value = '服务端仍未进入终态，请稍后刷新或查看命令历史；不以 HTTP 受理代替设备确认。'; return; }
    pollTimer = setTimeout(() => void poll(operationId), 1000);
  } catch (problem) {
    pollNote.value = problem instanceof Error ? problem.message : '执行结果读取失败';
  }
}
function body() {
  const params: Record<string, unknown> = {};
  if (kind.value === 'output') { params.kind = outputKind.value; params.on = form.on; params.duration_s = form.duration; }
  else if (kind.value === 'sentry') { params.on = form.on; params.duration_s = form.duration; }
  else if (kind.value === 'gps_mode') { params.mode = form.mode; params.seed = form.seed; }
  else if (kind.value === 'profile') { params.profile_id = form.profile.trim(); params.temp_min_c = form.tempMin; params.temp_max_c = form.tempMax; params.humi_min_pct = form.humiMin; params.humi_max_pct = form.humiMax; params.temp_hysteresis_c = form.tempHyst; params.humi_hysteresis_pct = form.humiHyst; }
  const result: Record<string, unknown> = { cmd: kind.value, params };
  if (kind.value !== 'sentry' && targetNodeId.value) result.target_node_id = targetNodeId.value;
  return result;
}
function validate() {
  if (kind.value === 'output' && (!Number.isInteger(form.duration) || form.duration < 1 || form.duration > 30)) return '输出时长必须是 1～30 秒';
  if (kind.value === 'sentry' && (!Number.isInteger(form.duration) || form.duration < 0 || form.duration > 86400)) return '防护时长必须是 0～86400 秒';
  if (kind.value === 'profile' && (!form.profile.trim() || form.tempMin >= form.tempMax || form.humiMin >= form.humiMax || form.tempHyst <= 0 || form.humiHyst <= 0)) return '请检查画像标识、上下限和正回差';
  return '';
}
async function submit() {
  operationError.value = ''; pollNote.value = ''; polls = 0;
  const invalid = validate();
  if (invalid) { operationError.value = invalid; return; }
  submitting.value = true;
  try {
    const result = await session.request<{ operation_id: string }>(`/enterprise/console/boxes/${encodeURIComponent(props.box.box_id)}/commands`, {
      method: 'POST', body: body(), key: crypto.randomUUID(),
    });
    operation.value = { operation_id: result.operation_id, box_id: props.box.box_id, status: 'PENDING', nodes: [] };
    emit('changed');
    void poll(result.operation_id);
  } catch (problem) {
    operationError.value = problem instanceof Error ? problem.message : '命令提交失败';
  } finally { submitting.value = false; }
}
onBeforeUnmount(stopPolling);
</script>

<template>
  <section class="panel">
    <div class="enterprise-panel-heading"><div><h2>控制中心</h2><p>仅显示节点能力支持的动作；成功以设备 ACK 为准</p></div><span class="map-mock-label">CONTROL</span></div>
    <p v-if="box.demo_enabled === false" class="data-note">模拟输入已停用，不能提交控制命令。请先在资产页启用。</p>
    <div class="command-layout">
      <div class="command-form">
        <div class="command-form-grid">
          <label class="drawer-field"><span>命令</span><select v-model="kind"><option v-for="item in availableKinds" :key="item.id" :value="item.id">{{ item.label }}</option></select></label>
          <label v-if="kind === 'output'" class="drawer-field"><span>输出设备</span><select v-model="outputKind"><option value="light">ENV 补光</option><option value="fan">ENV 风扇</option><option value="indicator">POSE 指示灯</option></select></label>
          <label v-if="kind !== 'sentry' && candidates.length > 1" class="drawer-field"><span>目标节点</span><select v-model="targetNodeId"><option v-for="node in candidates" :key="node.node_id" :value="node.node_id">{{ node.node_id }}</option></select></label>
          <label v-if="kind === 'output' || kind === 'sentry'" class="drawer-field"><span>执行动作</span><select v-model="form.on"><option :value="true">{{ kind === 'sentry' ? '布防' : '开启' }}</option><option :value="false">{{ kind === 'sentry' ? '撤防' : '关闭' }}</option></select></label>
          <label v-if="kind === 'output' || kind === 'sentry'" class="drawer-field"><span>{{ kind === 'sentry' ? '有效防护时长（秒，0 不限）' : '输出时长（秒）' }}</span><input v-model.number="form.duration" type="number" :min="kind === 'sentry' ? 0 : 1" :max="kind === 'sentry' ? 86400 : 30" step="1" /></label>
          <template v-if="kind === 'gps_mode'"><label class="drawer-field"><span>定位模式</span><select v-model="form.mode"><option value="FIXED">FIXED · 固定位置</option><option value="ROUTE">ROUTE · 预设路线</option><option value="WALK">WALK · 随机游走</option></select></label><label class="drawer-field"><span>随机种子</span><input v-model.number="form.seed" type="number" min="0" max="4294967295" step="1" /></label></template>
          <template v-if="kind === 'profile'"><label class="drawer-field"><span>画像标识</span><input v-model="form.profile" /></label><label class="drawer-field"><span>温度下限 °C</span><input v-model.number="form.tempMin" type="number" step="any" /></label><label class="drawer-field"><span>温度上限 °C</span><input v-model.number="form.tempMax" type="number" step="any" /></label><label class="drawer-field"><span>湿度下限 %</span><input v-model.number="form.humiMin" type="number" step="any" /></label><label class="drawer-field"><span>湿度上限 %</span><input v-model.number="form.humiMax" type="number" step="any" /></label><label class="drawer-field"><span>温度回差 °C</span><input v-model.number="form.tempHyst" type="number" step="any" /></label><label class="drawer-field"><span>湿度回差 %</span><input v-model.number="form.humiHyst" type="number" step="any" /></label></template>
        </div>
        <p class="data-note">目标：{{ selectedNode?.node_id || '无匹配节点' }} · caps {{ selectedNode?.caps ?? '—' }}。{{ kind === 'sentry' ? '布防有 3 秒缓冲，有效时长从实际 ARMED 起算。' : kind === 'calibrate' ? '请将 POSE 放稳，等待约 3 秒静置采样。' : '提交后以设备回执/超时状态为准。' }}</p>
        <p v-if="operationError" class="form-error" role="alert">{{ operationError }}</p>
        <button class="button primary" :disabled="!canSubmit" @click="submit">{{ submitting ? '提交中…' : `提交${commandKindLabel(kind)}` }}</button>
      </div>
      <div class="command-result">
        <div v-if="!operation" class="empty-record">尚未提交命令。HTTP 受理不等于设备执行成功。</div>
        <template v-else>
          <div class="command-result-head"><strong>{{ commandKindLabel(kind) }} · {{ operation.operation_id }}</strong><span :class="['status-pill', terminal(operation.status) ? operation.status === 'SUCCEEDED' ? 'good' : 'critical' : 'warn']">{{ commandStatusLabel(operation.status) }}</span></div>
          <div v-for="node in operation.nodes" :key="node.node_id" class="command-node-result"><strong>{{ node.node_id }}</strong><small>{{ node.status }} · {{ node.code || '无错误码' }} · {{ node.actual_state ? JSON.stringify(node.actual_state) : '尚未回执' }}</small></div>
          <p v-if="!operation.nodes.length" class="data-note">操作已受理，节点回执尚未到达。</p>
          <p v-if="pollNote" class="data-note">{{ pollNote }}</p>
        </template>
      </div>
    </div>
  </section>
  <section class="panel">
    <div class="enterprise-panel-heading"><div><h2>节点能力</h2><p>由 caps 推导；仅用于说明可提交动作</p></div></div>
    <div class="capability-list"><div v-for="node in box.nodes" :key="node.node_id" class="capability-row"><strong>{{ node.node_id }} · {{ node.role }}</strong><span v-if="(node.caps & 1)">画像</span><span v-if="(node.caps & 2)">灯</span><span v-if="(node.caps & 4)">风扇</span><span v-if="(node.caps & 8)">校准</span><span v-if="(node.caps & 16)">指示</span><span v-if="(node.caps & 32)">GPS</span><span v-if="(node.caps & 64)">哨兵</span><small v-if="!node.caps">无能力位</small></div></div>
  </section>
  <section class="panel">
    <div class="enterprise-panel-heading"><div><h2>命令历史</h2><p>只读最近 20 次操作及逐节点结果</p></div></div>
    <div class="table-wrap"><table class="enterprise-table"><thead><tr><th>操作</th><th>命令</th><th>状态</th><th>提交</th><th>节点结果</th></tr></thead><tbody><tr v-for="command in commands" :key="command.operation_id"><td>{{ command.operation_id }}</td><td>{{ command.cmd }}</td><td>{{ commandStatusLabel(command.status) }}</td><td>{{ dateTime(command.created_at) }}</td><td><small v-for="node in command.nodes" :key="node.node_id" class="table-subline">{{ node.node_id }} · {{ node.status }} · {{ node.result || '—' }}</small></td></tr></tbody></table><p v-if="!commands.length" class="empty-record">暂无命令记录。</p></div>
  </section>
</template>
