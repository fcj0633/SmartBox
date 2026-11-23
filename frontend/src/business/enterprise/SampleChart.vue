<script setup lang="ts">
import { computed } from 'vue';
import { dateTime, type SamplePoint } from './model';

const props = defineProps<{ points: SamplePoint[]; kind: 'env' | 'pose' }>();
const sorted = computed(() => [...props.points].sort((a, b) => a.observed_at - b.observed_at || a.id - b.id));
const value = (point: SamplePoint) => props.kind === 'env' ? point.temperature : point.tilt_deg;
const values = computed(() => sorted.value.filter(point => point.valid && typeof value(point) === 'number').map(point => value(point) as number));
const low = computed(() => values.value.length ? Math.min(...values.value) : 0);
const high = computed(() => values.value.length ? Math.max(...values.value) : 1);
const span = computed(() => Math.max(0.5, high.value - low.value));
const x = (time: number) => {
  const first = sorted.value[0]?.observed_at ?? 0, last = sorted.value.at(-1)?.observed_at ?? first;
  return last === first ? 250 : ((time - first) / (last - first)) * 500;
};
const y = (number: number) => 150 - ((number - low.value) / span.value) * 130 + 10;
const segments = computed(() => {
  const paths: string[] = []; let line = '';
  for (const point of sorted.value) {
    const number = value(point);
    if (!point.valid || typeof number !== 'number') { if (line) paths.push(line); line = ''; }
    else line += `${line ? 'L' : 'M'}${x(point.observed_at)} ${y(number)} `;
  }
  if (line) paths.push(line);
  return paths;
});
const unit = computed(() => props.kind === 'env' ? '°C' : '°');
</script>

<template>
  <div class="detail-trend">
    <div v-if="!points.length" class="empty-record">当前时间窗口没有可用于该指标的样本；没有数据不假定正常。</div>
    <template v-else>
      <div class="chart-labels"><span>{{ high.toFixed(1) }}{{ unit }}</span><span>{{ low.toFixed(1) }}{{ unit }}</span></div>
      <svg viewBox="0 0 500 180" role="img" :aria-label="kind === 'env' ? '环境趋势' : '姿态趋势'">
        <path d="M0 10H500 M0 85H500 M0 160H500" class="grid-line" />
        <path v-for="(path, index) in segments" :key="index" :d="path" class="data-line" />
        <circle v-for="point in sorted.filter(item => item.valid && typeof value(item) === 'number')" :key="point.id"
          :cx="x(point.observed_at)" :cy="y(value(point) as number)" r="2.5" fill="#147b67" />
      </svg>
      <div class="chart-x"><span>{{ dateTime(sorted[0]?.observed_at) }}</span><span>{{ dateTime(sorted.at(-1)?.observed_at) }}</span></div>
      <p v-if="sorted.some(item => !item.valid || typeof value(item) !== 'number')" class="data-note">包含无效或缺失采样，曲线在缺口处断开。</p>
    </template>
  </div>
</template>
