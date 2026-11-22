<script setup lang="ts">
withDefaults(defineProps<{ historical?: boolean; gap?: boolean; alarm?: boolean; temperature?: number }>(), { historical: false, gap: false, alarm: false, temperature: 4.8 });
</script>
<template>
  <div class="trend-plot" role="img" :aria-label="gap ? '演示温度曲线，包含数据缺口' : '任务范围内的演示温度曲线'">
    <div class="y-labels"><span>8°</span><span>5°</span><span>2°</span></div>
    <svg viewBox="0 0 540 150" preserveAspectRatio="none" aria-hidden="true">
      <path d="M0 15H540 M0 75H540 M0 135H540" class="grid-line" />
      <path d="M0 135V90L35 86 70 94 105 81 140 86 175 75 210 81 245 79 280 85 315 72 350 78 385 70 420 79 455 73 490 83 540 79V135Z" class="chart-area" />
      <path v-if="!gap" :transform="alarm ? '' : `translate(0,${-20 * (temperature - (historical ? 5.1 : 4.8))})`" :d="alarm ? 'M0 90L35 86 70 94 105 81 140 86 175 75 210 81 245 66 280 71 315 55 350 62 385 49 420 33 455 22 490 12 540 5' : historical ? 'M0 77L35 82 70 76 105 89 140 84 175 77 210 79 245 70 280 76 315 65 350 71 385 62 420 71 455 66 490 74 540 73' : 'M0 90L35 86 70 94 105 81 140 86 175 75 210 81 245 79 280 85 315 72 350 78 385 70 420 79 455 73 490 83 540 79'" :class="['data-line', {danger:alarm}]" />
      <template v-else><path d="M0 90L35 86 70 94 105 81 140 86 175 75 M350 78L385 70 420 79 455 73 490 83 540 79" class="data-line" /><rect x="190" y="0" width="144" height="150" class="gap-area" /><text x="262" y="80" text-anchor="middle" class="gap-label">采样缺口</text></template>
    </svg>
    <div class="x-labels"><span>{{ historical ? '09:12' : '13:10' }}</span><span>{{ historical ? '09:40' : '13:40' }}</span><span>{{ historical ? '10:10' : '14:10' }}</span><span>{{ historical ? '10:40' : '14:32' }}</span></div>
  </div>
</template>
