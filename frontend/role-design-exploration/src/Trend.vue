<script setup lang="ts">
import { computed } from 'vue';
const props = withDefaults(defineProps<{
    temperature?: number;
    start?: string;
    end?: string;
    gap?: boolean;
    alarm?: boolean;
}>(), {
    temperature: 4.8,
    start: '13:10',
    end: '14:32',
    gap: false,
    alarm: false
});
const samples = computed(() => [props.temperature - .2, props.temperature - .1, props.gap ? null : props.temperature,
    props.gap ? null : props.temperature - .1, props.alarm ? 7.9 : props.temperature - .2, props.alarm ? 8.6 : props
        .temperature
]);
const segments = computed(() => {
    const lines: string[] = [];
    let line = '';
    samples.value.forEach((v, i) => {
        if (v === null) {
            if (line)
                lines.push(line);
            line = '';
        }
        else
            line += `${line ? 'L' : 'M'}${i * 100} ${160 - (v - 2) * 20} `;
    });
    if (line)
        lines.push(line);
    return lines;
});
</script>
<template>
  <p v-if="start===end" class="data-note">本次演示运输尚无足够时序样本，暂不绘制曲线。</p>
  <div v-else class="trend-plot" role="img" :aria-label="gap?'温度示例曲线，存在数据缺口':'任务内温度示例曲线'">
    <div class="chart-y"><span>10°C</span><span>6°C</span><span>2°C</span></div><svg viewBox="0 0 500 160"
      preserveAspectRatio="none" aria-hidden="true">
      <path d="M0 0H500 M0 80H500 M0 160H500" class="grid-line" />
      <path v-for="(d,i) in segments" :key="i" :d="d" :class="['data-line',{danger:alarm}]" /><template v-if="gap">
        <rect x="150" y="0" width="200" height="180" class="gap-area" /><text x="250" y="100"
          text-anchor="middle">无有效样本</text>
      </template>
    </svg>
    <div class="chart-x"><span>{{start}}</span><span>{{end}}</span></div>
  </div>
  <details v-if="start!==end" class="sample-table">
    <summary>查看曲线使用的模拟样本</summary>
    <p>六个等间隔示例采样点；曲线只连接相邻有效点。</p>
    <table>
      <thead>
        <tr>
          <th>点位</th>
          <th>温度</th>
          <th>质量</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(v,i) in samples" :key="i">
          <td>{{i+1}}</td>
          <td>{{v===null?'—':v.toFixed(1)+' °C'}}</td>
          <td>{{v===null?'缺失':'有效'}}</td>
        </tr>
      </tbody>
    </table>
  </details>
</template>
