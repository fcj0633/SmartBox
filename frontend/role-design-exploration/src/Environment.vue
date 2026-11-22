<script setup lang="ts">
import { computed } from 'vue';
import Icon from './Icon.vue';
const props = withDefaults(defineProps<{
    temperature: number;
    humidity: number;
    scenario: string;
    historical?: boolean;
    sampleTime?: string;
}>(), {
    historical: false,
    sampleTime: '14:32'
});
const uncertain = computed(() => ['offline', 'stale', 'invalid', 'unknown'].includes(props.scenario));
const title = computed(() => props.scenario === 'offline' ? '设备离线，环境暂无法判断' : props.scenario === 'stale' ?
    '数据已过期，环境暂无法判断' : props.scenario === 'invalid' ? '采样无效，环境暂无法判断' : props.scenario === 'unknown' ? '环境状态暂无法判断' : props
    .scenario === 'alarm' ? (props.historical ? '末次记录环境异常' : '当前运输环境异常') : (props.historical ? '末次记录环境正常' : '当前运输环境正常'));
const sample = computed(() => props.historical ? props.sampleTime : ['offline', 'stale'].includes(props.scenario) ?
    '14:20' : '14:32');
</script>
<template>
  <section
    :class="['environment',scenario==='alarm'?'environment-alarm':uncertain?'environment-unknown':'environment-good']"
    aria-label="环境摘要">
    <div class="environment-conclusion">
      <Icon :name="scenario==='alarm'||uncertain?'alert':'shield'" :size="23" />
      <div>
        <h2>{{ title }}</h2>
        <p>{{historical?'历史样本':scenario==='invalid'?'本次采样':uncertain?'最近有效采样':'最近更新'}} {{sample}}<span
            v-if="scenario==='invalid'"> · 当前采样无效</span><span v-else-if="uncertain"> · 当前结论不可用</span></p>
      </div>
    </div>
    <div class="sensor-readings">
      <div><span>温度</span><strong
          class="reading-number">{{scenario==='invalid'?'—':scenario==='alarm'?'8.6':temperature.toFixed(1)}}<small>°C</small></strong>
      </div>
      <div><span>相对湿度</span><strong
          class="reading-number">{{scenario==='invalid'?'—':humidity}}<small>%</small></strong></div>
    </div>
    <p v-if="['offline','stale'].includes(scenario)" class="data-note">以上为最后有效读数，不能代表当前环境。</p>
    <p v-if="historical" class="data-note">仅为本次运输末次样本，不代表全程无异常。</p>
    <details class="quality-details">
      <summary>数据状态说明</summary>
      <p>
        设备{{scenario==='offline'?'离线':'在线'}}；采样{{scenario==='invalid'?'无效':'有效'}}；{{['offline','stale'].includes(scenario)?'数据过期':historical?'历史数据':'演示新鲜样本'}}；环境{{uncertain?'未获得有效判断':scenario==='alarm'?'异常':'已评估正常'}}。结论来自统一
        Mock 样例，不由页面阈值计算。</p>
    </details>
  </section>
</template>
