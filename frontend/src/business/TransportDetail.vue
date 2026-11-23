<script setup lang="ts">
import { computed,ref } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../stores/session';
import { ApiError } from '../api/http';
import BusinessShell from './BusinessShell.vue';
import MonitoringPanel from './MonitoringPanel.vue';
import { usePolling,errorText,stateName,date,type Monitoring,type Task } from './model';
const route=useRoute(),session=useSession(),enterprise=computed(()=>route.path.startsWith('/enterprise')),id=computed(()=>String(route.params.id)),task=ref<Task|null>(null),monitor=ref<Monitoring|null>(null),name=ref(''),description=ref(''),busy=ref(false),actionError=ref(''),risk=ref(false),code=ref(''),shareNote=ref('');
const {error,loading,refresh}=usePolling(async signal=>{const path=`/${enterprise.value?'enterprise':'rider'}/transports/${encodeURIComponent(id.value)}`;const [t,m]=await Promise.all([session.request<Task>(path,{signal}),session.request<Monitoring>(path+'/monitoring',{signal})]);if(!signal.aborted){task.value=t;monitor.value=m;}},()=>{task.value=null;monitor.value=null;code.value='';});
const base=()=>`/rider/transports/${encodeURIComponent(id.value)}`;
async function action(kind:string,confirm=false){if(busy.value)return;busy.value=true;actionError.value='';try{
 if(kind==='item')await session.request(base()+'/item',{method:'POST',body:{name:name.value,description:description.value}});
 else if(kind==='access-code'){const response=await session.request<{code:string}>(base()+'/access-code',{method:'POST'});code.value=response.code;shareNote.value='仅本次显示，请及时复制。重新生成会使旧验证码和会话失效。';}
 else await session.request(base()+'/'+kind,{method:'POST',body:kind==='start'?{confirm_risk:confirm}:undefined});
 risk.value=false;refresh();
 }catch(e){if(e instanceof ApiError&&e.code==='RISK_CONFIRMATION_REQUIRED')risk.value=true;else actionError.value=errorText(e);}finally{busy.value=false;}}
async function copy(){try{await navigator.clipboard.writeText(`运输编号：${task.value?.transport_no}
验证码：${code.value}
打开 ${location.origin}/track 查看本次运输。`);shareNote.value='分享信息已复制，请仅提供给本次运输的查看者。';}catch{shareNote.value='浏览器不允许复制，请手工复制编号与验证码。';}}
</script>
<template><BusinessShell><RouterLink class="back-link" :to="enterprise?'/enterprise':'/rider'">‹ 返回{{ enterprise?'企业资产':'我的运输' }}</RouterLink><p v-if="error||actionError" class="biz-error" role="alert">{{ error||actionError }}</p><p v-if="loading">正在读取任务…</p><template v-if="task"><div class="page-heading"><div><p class="biz-muted">{{ task.transport_no }}</p><h1>{{ task.item_name || '准备本次运输' }}</h1><p>{{ task.item_description }}</p></div><span :class="['task-state',task.status]">{{ stateName(task.status) }}</span></div><div class="transport-times"><span>开始 {{ date(task.started_at) }}</span><span>结束 {{ date(task.ended_at) }}</span><button class="biz-text" @click="refresh">刷新状态</button></div>
 <section v-if="!enterprise&&task.status==='READY'" class="prepare-section"><h2>填写物品并核对</h2><form @submit.prevent="action('item')" class="item-form"><label for="item-name">物品名称</label><input id="item-name" v-model="name" :placeholder="task.item_name||'例如：书籍'" maxlength="50" required><label for="item-description">物品说明（可选）</label><textarea id="item-description" v-model="description" maxlength="200" /><button class="biz-secondary" :disabled="busy">保存物品</button></form><button class="biz-primary start-button" :disabled="busy||!task.item_name" @click="action('start')">开始运输</button><div v-if="risk" class="risk-confirm" role="alert"><p>设备环境有风险或尚未完成判断。请核实情况，再决定是否继续。</p><button class="biz-primary" :disabled="busy" @click="action('start',true)">我已知晓风险，继续开始</button><button class="biz-text" @click="risk=false">暂不开始</button></div></section>
 <section v-if="!enterprise&&task.status==='ACTIVE'" class="finish-section"><h2>完成这一程</h2><p>结束后，历史只保留本次运输时间范围内可确认的数据。</p><button class="biz-primary" :disabled="busy" @click="action('finish')">结束运输</button></section>
 <MonitoringPanel v-if="monitor" :data="monitor" />
 <section v-if="!enterprise&&task.status!=='READY'" class="share-section"><h2>分享给查看者</h2><p>查看者无需账号，只能访问本次运输。</p><button class="biz-secondary" :disabled="busy" @click="action('access-code')">{{ code?'重新生成验证码':'生成查看验证码' }}</button><div v-if="code" class="share-code"><strong aria-label="运输验证码">{{ code }}</strong><button class="biz-text" @click="copy">复制分享信息</button><p>{{ shareNote }}</p></div></section>
 </template></BusinessShell></template>
