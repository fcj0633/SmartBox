<script setup lang="ts">
import { computed } from 'vue';
import type { Sample } from '../api/types';
import { quality, time, label } from '../domain';

const props = defineProps<{
  title: string;
  sample?: Sample;
  fields: { key: string; label: string; unit: string }[];
  synchronized: boolean;
  mock?: boolean;
  nodeLabel?: string;
}>();

function value(input: unknown) {
  if (input == null) return '—';
  if (Array.isArray(input)) return input.map(v => typeof v === 'number' ? v.toFixed(3) : String(v)).join(' / ');
  if (typeof input === 'boolean') return input ? '开启' : '关闭';
  if (typeof input === 'number') return Number.isInteger(input) ? String(input) : input.toFixed(2);
  return String(input);
}
const statusText = computed(() => quality(props.sample, props.synchronized));
const statusClass = computed(() => props.sample?.freshness === 'FRESH' && props.synchronized && props.sample.valid ? 'status-good' : props.sample?.freshness === 'STALE' ? 'status-warning' : 'status-neutral');
const sampleState = computed(() => !props.sample ? '尚无样本' : !props.sample.valid ? '样本无效' : !props.synchronized ? '同步中断' : label(props.sample.freshness));
</script>

<template>
  <section class="data-panel sample-card">
    <header class="data-panel-header">
      <div class="panel-heading-group">
        <span class="panel-eyebrow">LIVE TELEMETRY</span>
        <h2>{{ title }}</h2>
        <p v-if="nodeLabel" class="panel-subtitle">{{ nodeLabel }}</p>
      </div>
      <div class="panel-status-stack">
        <span v-if="mock" class="source-tag">MOCK</span>
        <span class="state-tag" :class="statusClass"><i></i>{{ statusText }}</span>
      </div>
    </header>

    <div class="metric-grid" :class="`metric-count-${fields.length}`">
      <div v-for="field in fields" :key="field.key" class="metric-cell">
        <span class="metric-label">{{ field.label }}</span>
        <div class="metric-value-line">
          <strong>{{ value(sample?.value?.[field.key]) }}</strong>
          <small>{{ field.unit }}</small>
        </div>
      </div>
    </div>

    <div v-if="sample?.value && ('light_on' in sample.value || 'fan_on' in sample.value)" class="output-state-strip">
      <span>执行器状态</span>
      <span class="output-state-item"><i :class="sample.value.light_on ? 'output-on' : ''"></i>补光灯 {{ sample.value.light_on ? '开启' : '关闭' }}</span>
      <span class="output-state-item"><i :class="sample.value.fan_on ? 'output-on' : ''"></i>风扇 {{ sample.value.fan_on ? '开启' : '关闭' }}</span>
    </div>

    <footer class="sample-card-footer">
      <div><span>观测时间</span><strong>{{ time(sample?.observed_at) }}</strong></div>
      <div><span>数据质量</span><strong>{{ sampleState }}</strong></div>
      <div><span>时间源</span><strong>{{ label(sample?.time_quality) }}</strong></div>
    </footer>
  </section>
</template>
