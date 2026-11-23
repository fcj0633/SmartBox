<script setup lang="ts">
import { date, environmentTitle, type Monitoring } from '../model';
import Icon from './Icon.vue';
defineProps<{
    environment: Monitoring['environment'];
    historical?: boolean;
}>();
</script>
<template>
  <section class="environment environment-unknown" aria-label="环境摘要">
    <div class="environment-conclusion">
      <Icon name="alert" :size="23" />
      <div>
        <h2>{{environmentTitle(environment)}}</h2>
        <p>{{historical?'历史末次可信采样':'最近可信采样'}} {{date(environment.observed_at)}}</p>
      </div>
    </div>
    <div class="sensor-readings">
      <div><span>温度</span><strong
          class="reading-number">{{environment.data_state==='INVALID'?'—':environment.latest_temperature??'—'}}<small>°C</small></strong>
      </div>
      <div><span>相对湿度</span><strong
          class="reading-number">{{environment.data_state==='INVALID'?'—':environment.latest_humidity??'—'}}<small>%</small></strong>
      </div>
    </div>
    <p v-if="historical" class="data-note">仅限本次运输历史，不代表全程没有异常。</p>
    <details class="quality-details">
      <summary>数据状态说明</summary>
      <p>
        {{environment.data_state==='READY'?'任务尚未开始，没有过程样本。':environment.data_state==='INVALID'?'最近采样无效，不能解释为正常。':environment.data_state==='INSUFFICIENT'?'未校时或跨越任务边界的数据不会计入。':'已有任务范围内可信采样；尚未完成任务级判断，不能据读数作出安全结论。'}}
        时间质量：{{environment.time_quality}}。设备在线与数据新鲜度未由本接口提供。</p>
    </details>
  </section>
</template>