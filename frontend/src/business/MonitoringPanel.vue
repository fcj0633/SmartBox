<script setup lang="ts">
import { date, type Monitoring } from './model';
defineProps<{data:Monitoring}>();
</script>
<template>
 <section class="monitoring-panel" aria-label="本次运输监测">
  <div class="section-head"><h2>本次运输环境</h2><span class="quality-tag">{{ data.environment.time_quality==='SNTP'?'可信设备时间':'时间质量未知' }}</span></div>
  <div class="reading-strip"><div><span>温度</span><strong>{{ data.environment.latest_temperature ?? '—' }} <small>°C</small></strong></div><div><span>湿度</span><strong>{{ data.environment.latest_humidity ?? '—' }} <small>%</small></strong></div><div><span>最近可信采样</span><b>{{ date(data.environment.observed_at) }}</b></div></div>
  <p class="biz-note" role="status">{{ data.environment.data_state==='READY'?'任务尚未开始，暂无运输过程数据。':data.environment.data_state==='INSUFFICIENT'?'暂无可确认数据；未校时或跨越任务边界的数据不会计入。':data.environment.data_state==='INVALID'?'最近采样无效，不能解释为正常。':'以下为任务范围内的可信采样；环境是否正常尚未完成任务级判断。' }}</p>
  <details><summary>最近可信采样（{{ data.samples.length }}）</summary><div class="sample-list"><p v-for="sample in data.samples" :key="String(sample.id)"><time>{{ date(sample.observed_at) }}</time><span>{{ sample.valid?`${sample.temperature} °C / ${sample.humidity} %`:'采样无效' }}</span></p></div></details>
  <h3>设备事件</h3><p v-if="!data.events.length" class="biz-muted">暂无可确认的设备事件，这不代表运输期间没有异常。</p><ol class="event-list"><li v-for="event in data.events" :key="String(event.id)"><time>{{ date(event.observed_at) }}</time><span>{{ event.description }}</span><small>{{ event.level }}</small></li></ol>
 </section>
</template>
