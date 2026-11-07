<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElDialog } from 'element-plus';
import { useSession } from '../stores/session';
import { canCommand, commandNames, label, operationLabel } from '../domain';
import type { Command, CommandKind } from '../api/types';
import { actualFields, buildCommand, defaultForm } from '../api/commands';
const session = useSession(), kind = ref<CommandKind>('sentry'), values = reactive(defaultForm());
const draft = ref<Command | null>(null), validation = ref('');
const kinds = computed(() => (Object.keys(commandNames) as CommandKind[]).filter(k => canCommand(session.user?.role, k)));
watch(kind, () => { validation.value = ''; draft.value = null; values.duration = kind.value === 'output' ? 2 : 10; });
const targets = computed(() => {
  const capability = kind.value === 'sentry' ? 64 : kind.value === 'profile' ? 1 : kind.value === 'gps_mode' ? 32 : kind.value === 'calibrate' ? 8 : values.output === 'indicator' ? 16 : 2;
  return session.nodes.filter(node => kind.value === 'sentry' ? node.required : (node.caps & capability) === capability);
});
const unsynced = computed(() => targets.value.some(node => (node.role === 'ENV' ? session.box?.env : session.box?.pose)?.time_quality === 'UNSYNCED'));
function prepare() {
  validation.value = '';
  try { draft.value = buildCommand(kind.value, values); }
  catch (e) { validation.value = (e as Error).message; }
}
async function confirm() { const command = draft.value; draft.value = null; if (command) await session.submit(command); }
</script>
<template>
  <section class="panel controls"><header class="panel-title"><div><h2>远程控制</h2><p class="subtle">操作将发送至当前接入节点；真实设备会执行实物动作。服务端决定目标及最终权限。</p></div><span class="badge mock">设备指令</span></header>
    <form @submit.prevent="prepare"><div class="form-grid"><div><label for="command-kind">操作类型</label><select id="command-kind" v-model="kind" :disabled="session.submitting || !!session.pending"><option v-for="item in kinds" :key="item" :value="item">{{ commandNames[item] }}</option></select></div>
    <div v-if="kind === 'sentry' || kind === 'output'"><label for="command-on">执行动作</label><select id="command-on" v-model="values.on"><option :value="true">{{ kind === 'sentry' ? '布防' : '开启' }}</option><option :value="false">{{ kind === 'sentry' ? '撤防' : '关闭' }}</option></select></div>
    <div v-if="kind === 'sentry' || kind === 'output'"><label for="duration">{{ kind === 'sentry' ? '有效防护时长（秒，0为不限）' : '输出时长（秒）' }}</label><input id="duration" v-model.number="values.duration" type="number" :min="kind === 'sentry' ? 0 : 1" :max="kind === 'sentry' ? 86400 : 30" step="1" required></div>
    <div v-if="kind === 'output'"><label for="output-kind">输出设备</label><select id="output-kind" v-model="values.output"><option value="light">ENV补光</option><option value="fan">ENV风扇</option><option value="indicator">POSE指示灯</option></select></div>
    <template v-if="kind === 'gps_mode'"><div><label for="gps-mode">定位模式</label><select id="gps-mode" v-model="values.mode"><option value="FIXED">FIXED · 固定位置</option><option value="ROUTE">ROUTE · 预设路线</option><option value="WALK">WALK · 随机游走</option></select></div><div><label for="seed">随机种子</label><input id="seed" v-model.number="values.seed" type="number" min="0" max="4294967295" step="1" required></div></template>
    <template v-if="kind === 'profile'"><div><label for="profile-id">画像标识</label><input id="profile-id" v-model="values.profile" required></div><div v-for="field in ([['tempMin','温度下限 °C'],['tempMax','温度上限 °C'],['humiMin','湿度下限 %'],['humiMax','湿度上限 %'],['tempHyst','温度回差 °C'],['humiHyst','湿度回差 %']] as const)" :key="field[0]"><label :for="field[0]">{{ field[1] }}</label><input :id="field[0]" v-model.number="values[field[0]]" type="number" step="any" required></div></template></div>
    <p class="subtle">目标：{{ targets.map(n => n.node_id + (n.online ? '' : '（离线）')).join('、') || '无合适节点' }}</p>
    <p v-if="kind === 'sentry'" class="section-note">布防有3秒缓冲，有效时长从实际进入ARMED起算；必需节点全部确认后才显示整体已布防。</p><p v-if="kind === 'profile'" class="section-note">表单是待提交配置；仅节点实际确认采用后，结果区才显示已采用画像。完整告警算法尚未接入。</p><p v-if="kind === 'calibrate'" class="section-note">本操作只执行模拟校准流程，不代表实际传感器校准。</p>
    <p v-if="unsynced" class="section-note">目标节点未校时，暂不能发送控制；请等待实际校时状态恢复。</p><p v-if="validation" class="error-box" role="alert">{{ validation }}</p><button class="primary" :disabled="session.submitting || !!session.pending || !targets.length || unsynced">{{ session.submitting ? '正在提交…' : '检查并确认操作' }}</button></form>
    <div v-if="session.pending" class="notice"><span>提交结果待确认，请使用原请求重试，不会自动创建新动作。</span><button @click="session.submit()" :disabled="session.submitting">使用原请求重试</button></div>
    <p v-if="session.operationError" class="error-box" role="alert">{{ session.operationError }}</p>
    <section v-if="session.operation" class="operation" data-testid="operation"><header class="panel-title"><div><h3>操作结果</h3><p class="mono operation-id">{{ session.operation.operation_id }}</p></div><div class="heading-actions"><span class="badge" :class="session.operation.status === 'SUCCEEDED' ? 'good' : 'muted'">{{ operationLabel(session.operation.status) }}</span><button @click="session.refreshOperation">刷新结果</button></div></header><p class="section-note">整体终态固定；超时不等于节点未执行。迟到执行事实可能与整体结果不同，以下实际状态以节点回执为准。</p>
    <table><thead><tr><th>目标节点</th><th>执行结果</th><th>节点执行事实</th><th>原因</th></tr></thead><tbody><tr v-for="node in session.operation.nodes" :key="node.node_id"><td class="mono">{{ node.node_id }}</td><td>{{ label(node.status) }}</td><td><span v-for="[key,value] in actualFields(node.actual_state)" :key="key" class="actual-fact">{{ key }}：{{ typeof value === 'boolean' ? value ? '是' : '否' : String(value) }}</span><span v-if="!actualFields(node.actual_state).length">暂无执行事实</span></td><td>{{ node.code ?? '—' }}</td></tr></tbody></table><p v-if="!session.operation.nodes.length" class="subtle">已受理，正在查询节点结果…</p></section>
    <ElDialog :model-value="!!draft" title="确认发送控制" width="480px" @close="draft = null"><p>将对 {{ session.box?.box_id }} 执行「{{ commandNames[kind] }}」。命令将发送至当前接入节点，真实设备会执行相应实物动作。</p><p>提交后等待实际执行回执；请求受理不表示执行完成。</p><template #footer><button @click="draft = null">返回检查</button><button class="primary" @click="confirm">确认发送</button></template></ElDialog>
  </section>
</template>
