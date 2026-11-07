<script setup lang="ts">
import type { Sample } from '../api/types';
import { quality, time, label } from '../domain';
defineProps<{ title: string; sample?: Sample; fields: { key: string; label: string; unit: string }[]; synchronized: boolean; mock?: boolean }>();
function value(input: unknown) { if (input == null) return '—'; if (Array.isArray(input)) return input.map(v => String(v)).join(' / '); return typeof input === 'boolean' ? input ? '开启' : '关闭' : String(input); }
</script>
<template>
  <section class="panel sample-card">
    <header class="panel-title"><h2>{{ title }}</h2><span v-if="mock" class="badge mock">MOCK</span><span class="badge" :class="sample?.freshness === 'FRESH' && synchronized && sample.valid ? 'good' : 'muted'">{{ quality(sample, synchronized) }}</span></header>
    <dl class="metrics"><div v-for="field in fields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ value(sample?.value?.[field.key]) }}<small>{{ field.unit }}</small></dd></div></dl>
    <footer class="sample-meta"><div>观测时间 <span>{{ time(sample?.observed_at) }}</span></div><div>接收时间 <span>{{ time(sample?.received_at) }}</span></div><div>时间质量 <span>{{ label(sample?.time_quality) }}</span></div></footer>
  </section>
</template>
