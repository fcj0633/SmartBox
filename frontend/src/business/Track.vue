<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { Http, ApiError } from '../api/http';
import { SerialRead } from '../realtime/serial';
import { decode } from '../api/json';
import { errorText, type Monitoring } from './model';
import GuestLayout from './layouts/GuestLayout.vue';
import TransportOverview from './components/TransportOverview.vue';
import SampleHistory from './components/SampleHistory.vue';
import EventList from './components/EventList.vue';
import Icon from './components/Icon.vue';
const http = new Http(), no = ref(''), code = ref(''), error = ref(''), data = ref<Monitoring | null>(null), busy = ref(false);
const viewMode = ref<'overview' | 'environment' | 'events'>('overview'), authorized = ref(false);
let token = '', reader: SerialRead | undefined, timer: ReturnType<typeof setInterval> | undefined, expiry: ReturnType<typeof setTimeout> | undefined, generation = 0;
function clear(message = '') { generation++; token = ''; authorized.value = false; viewMode.value = 'overview'; reader?.stop(); clearInterval(timer); clearTimeout(expiry); data.value = null; code.value = ''; busy.value = false; error.value = message; }
async function view(signal: AbortSignal, epoch: number) { try {
    const response = await fetch('/api/v1/public/transport-view', { signal, headers: { 'X-Transport-View-Token': token }, credentials: 'omit' });
    const body = decode<{
        data: Monitoring;
        request_id: string;
        error?: {
            code: string;
        };
    }>(await response.text());
    if (!response.ok)
        throw new ApiError(response.status, body.request_id, body.error?.code);
    if (epoch === generation && !signal.aborted) {
        data.value = body.data;
        error.value = '';
    }
}
catch (e) {
    if (signal.aborted || epoch !== generation)
        return;
    if (e instanceof ApiError && [401, 403].includes(e.status))
        clear(errorText(e));
    else
        error.value = errorText(e);
} }
async function verify() { if (busy.value || token)
    return; const epoch = ++generation; busy.value = true; error.value = ''; try {
    const result = await http.request<{
        token: string;
        expires_at: number;
    }>('/public/transport-access/verify', { method: 'POST', body: { transport_no: no.value.trim(), code: code.value }, login: true });
    if (epoch !== generation)
        return;
    code.value = '';
    token = result.token;
    authorized.value = true;
    reader = new SerialRead(signal => view(signal, epoch), 200);
    reader.trigger();
    timer = setInterval(() => reader?.trigger(), 10000);
    expiry = setTimeout(() => clear('查看会话已到期，请重新验证'), Math.max(0, result.expires_at - Date.now()));
}
catch (e) {
    if (epoch === generation)
        error.value = errorText(e);
}
finally {
    if (epoch === generation)
        busy.value = false;
} }
onUnmounted(() => clear());
</script>
<template>
  <GuestLayout :entered="!!data||authorized" @exit="clear()">
    <p v-if="error" class="form-error" role="alert">{{error}}<span v-if="data"> · 刷新未成功，下方为上次返回的数据。</span></p>
    <section v-if="!data&&!authorized" class="entry-layout">
      <div class="entry-intro">
        <Icon name="box" :size="40" />
        <h1>查看运输进度与环境</h1>
        <p>使用骑手提供的运输编号和验证码，查看本次物品的运输状态、环境记录与设备事件。</p>
        <div class="entry-points"><span>
            <Icon name="task" />运输状态
          </span><span>
            <Icon name="leaf" />环境记录
          </span><span>
            <Icon name="shield" />仅限获准任务
          </span></div>
      </div>
      <section class="entry-form">
        <h2>验证运输信息</h2>
        <form @submit.prevent="verify"><label class="field" for="transport-number">运输编号<input id="transport-number"
              v-model="no" required maxlength="64" autocomplete="off" placeholder="输入运输编号"
              :disabled="busy" /></label><label class="field" for="view-code">查看验证码<input id="view-code" v-model="code"
              required maxlength="64" autocomplete="off" :disabled="busy" /></label><button class="button primary wide"
            :disabled="busy">{{busy?'正在验证…':'验证并查看'}}</button></form>
        <p class="data-note">无需注册，只能查看本次获准运输。刷新页面后需重新验证。</p>
      </section>
    </section>
    <section v-else-if="!data" class="access-state">
      <h1>正在读取本次运输</h1>
      <p>验证已完成，正在获取任务范围内的数据。</p><button class="button secondary" @click="reader?.trigger()">重试读取</button><button
        class="text-button" @click="clear()">退出查看</button>
    </section><template v-else>
      <div class="task-tools"><button v-if="viewMode!=='overview'" class="back-link" aria-label="返回运输概览"
          @click="viewMode='overview'">‹ 返回运输概览</button><button class="text-button"
          @click="reader?.trigger()">刷新</button></div>
      <TransportOverview v-if="viewMode==='overview'" :data="data" @view="viewMode=$event" />
      <section v-else class="sheet">
        <p class="task-number">{{data.task.transport_no}} · {{data.task.item_name}}</p>
        <SampleHistory v-if="viewMode==='environment'" :samples="data.samples" />
        <EventList v-else :events="data.events" />
      </section>
    </template>
  </GuestLayout>
</template>