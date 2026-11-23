<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../stores/session';
import BusinessShell from './BusinessShell.vue';
import { usePolling,errorText,stateName,type Asset,type Task } from './model';
const session=useSession(),router=useRouter(),boxes=ref<Asset[]>([]),tasks=ref<Task[]>([]),box=ref(''),busy=ref(false),actionError=ref('');
const {error,loading,refresh}=usePolling(async signal=>{const result=await Promise.all([session.request<Asset[]>('/rider/boxes',{signal}),session.request<Task[]>('/rider/transports',{signal})]);if(!signal.aborted){boxes.value=result[0];tasks.value=result[1]; }},()=>{boxes.value=[];tasks.value=[];});
async function create(){if(busy.value||!box.value)return;busy.value=true;actionError.value='';try{const task=await session.request<Task>('/rider/transports',{method:'POST',body:{box_id:box.value}});await router.push(`/rider/transports/${task.task_id}`);}catch(e){actionError.value=errorText(e);}finally{busy.value=false;}}
</script>
<template><BusinessShell><div class="page-heading"><div><p class="biz-muted">{{ session.user?.username }} 的工作台</p><h1>每一程，都有据可查。</h1><p>查看当前运输，或选择箱体准备下一程。</p></div><button class="biz-secondary" @click="refresh">刷新</button></div><p v-if="error||actionError" class="biz-error" role="alert">{{ error||actionError }}</p><p v-if="loading">正在读取运输…</p>
 <section class="current-run" v-if="tasks.some(t=>t.status==='ACTIVE')"><h2>正在运输</h2><RouterLink v-for="task in tasks.filter(t=>t.status==='ACTIVE')" :key="String(task.task_id)" :to="`/rider/transports/${task.task_id}`"><strong>{{ task.item_name }}</strong><span>{{ task.transport_no }}</span><b>查看运输 ›</b></RouterLink></section>
 <section class="prepare-section"><h2>准备新运输</h2><p class="biz-muted">选箱后填写物品，核对状态，再开始运输。</p><form class="prepare-form" @submit.prevent="create"><label for="transport-box">我的箱体</label><select id="transport-box" v-model="box" required><option value="" disabled>选择可使用的箱体</option><option v-for="asset in boxes" :key="asset.box_id" :value="asset.box_id">{{ asset.display_name }} · {{ asset.source==='OWNED'?'个人拥有':'企业分配' }}</option></select><button class="biz-primary" :disabled="busy||!box">{{ busy?'正在创建…':'创建运输草稿' }}</button></form><p v-if="!boxes.length&&!loading">暂时没有可使用的箱体，请联系企业分配。</p><p class="biz-note">个人演示箱没有实物节点，不能在线开始。开始前仍由服务端检查设备状态。</p></section>
 <section class="task-section"><h2>我的运输记录</h2><p v-if="!tasks.length" class="biz-muted">还没有运输记录。</p><RouterLink v-for="task in tasks" :key="String(task.task_id)" class="task-row" :to="`/rider/transports/${task.task_id}`"><div><strong>{{ task.item_name||'待填写物品' }}</strong><small>{{ task.transport_no }}</small></div><span :class="['task-state',task.status]">{{ stateName(task.status) }}</span></RouterLink></section>
 </BusinessShell></template>
