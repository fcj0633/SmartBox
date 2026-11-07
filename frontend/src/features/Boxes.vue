<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue';
import { useSession } from '../stores/session';
import { label, quality } from '../domain';
const session = useSession();
onMounted(() => session.openScope()); onBeforeUnmount(() => session.stopScope());
</script>
<template>
  <section class="page-heading"><div><p class="eyebrow">FLEET OVERVIEW</p><h1>我的物流箱</h1><p class="subtle">查看已授权物流箱，掌握最新监测状态。</p></div><button @click="session.refresh">刷新列表</button></section>
  <p v-if="session.error" class="error-box" role="alert">{{ session.error }}</p>
  <div v-if="session.loading" role="status" class="empty">正在读取授权箱体…</div>
  <div v-else-if="!session.boxes.length && !session.error" class="empty"><h2>暂无授权箱体</h2><p>当前账号尚无可查看的物流箱。</p></div>
  <div class="box-grid"><RouterLink v-for="box in session.boxes" :key="box.box_id" :to="`/boxes/${box.box_id}`" class="box-card"><div class="box-card-top"><span class="box-icon" aria-hidden="true">▣</span><span class="badge" :class="box.connectivity === 'ONLINE' ? 'good' : 'muted'">{{ label(box.connectivity) }}</span></div><h2>{{ box.box_id }}</h2><p class="subtle">ENV + POSE · 双节点监测</p><div class="card-values"><div><span>温度</span><strong>{{ box.env?.value?.temp_c ?? '—' }}<small>°C</small></strong></div><div><span>湿度</span><strong>{{ box.env?.value?.humi_pct ?? '—' }}<small>%</small></strong></div></div><footer><span class="badge mock">MOCK</span><span>{{ quality(box.env, session.synchronized) }}</span><span>查看箱体详情 →</span></footer></RouterLink></div>
  <p class="section-note">未接入完整告警与门融合算法；未知状态不表示运输安全或无异常。</p>
</template>
