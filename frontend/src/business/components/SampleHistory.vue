<script setup lang="ts">
import { computed } from 'vue';
import { date, type Monitoring } from '../model';
const props = defineProps<{
    samples: Monitoring['samples'];
}>();
const sorted = computed(() => [...props.samples].sort((a, b) => a.observed_at - b.observed_at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)));
const values = computed(() => sorted.value.filter(s => s.valid && typeof s.temperature === 'number').map(s => s.temperature!));
const low = computed(() => values.value.length ? Math.min(...values.value) - 1 : 0), high = computed(() => values.value.length ? Math.max(...values.value) + 1 : 1);
const x = (time: number) => { const first = sorted.value[0]?.observed_at || 0, last = sorted.value.at(-1)?.observed_at || first; return last === first ? 250 : ((time - first) / (last - first)) * 500; };
const y = (v: number) => 160 - (v - low.value) / (high.value - low.value) * 150;
const segments = computed(() => { const paths: string[] = []; let line = ''; for (const s of sorted.value) {
    if (!s.valid || s.temperature === null) {
        if (line)
            paths.push(line);
        line = '';
    }
    else
        line += `${line ? 'L' : 'M'}${x(s.observed_at)} ${y(s.temperature)} `;
} if (line)
    paths.push(line); return paths; });
</script>
<template>
  <section class="sample-history">
    <h2>最近可信采样</h2>
    <p class="data-note">最多 30 条；仅限本次任务，不代表完整全程历史。</p>
    <p v-if="!samples.length" class="empty-record">暂无可确认样本，未校时或跨界样本不推测归属。</p><template v-else>
      <div v-if="values.length" class="real-chart" role="img" aria-label="本次任务温度曲线，无效采样断开">
        <div class="chart-labels"><span>{{high.toFixed(1)}} °C</span><span>{{low.toFixed(1)}} °C</span></div><svg
          viewBox="0 0 500 180" aria-hidden="true">
          <path d="M0 10H500 M0 85H500 M0 160H500" class="grid-line" />
          <path v-for="(d,i) in segments" :key="i" :d="d" class="data-line" />
          <circle v-for="s in sorted.filter(s=>s.valid&&s.temperature!==null)" :key="String(s.id)"
            :cx="x(s.observed_at)" :cy="y(s.temperature!)" r="3" fill="#147b67" />
        </svg>
      </div>
      <div class="chart-x"><span>{{date(sorted[0].observed_at)}}</span><span>{{date(sorted.at(-1)!.observed_at)}}</span>
      </div>
      <p v-if="samples.some(s=>!s.valid||s.temperature===null)" class="data-note">包含无效或缺失采样，曲线在缺口处断开。</p>
      <details>
        <summary>查看采样记录（{{samples.length}}）</summary>
        <div class="sample-scroll">
          <table>
            <thead>
              <tr>
                <th>采样时间</th>
                <th>温度</th>
                <th>湿度</th>
                <th>质量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in sorted" :key="String(s.id)">
                <td>{{date(s.observed_at)}}</td>
                <td>{{s.valid?s.temperature??'—':'—'}} °C</td>
                <td>{{s.valid?s.humidity??'—':'—'}} %</td>
                <td>{{s.valid?'有效':'无效'}}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </template>
  </section>
</template>