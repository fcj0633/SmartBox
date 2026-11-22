<script setup lang="ts">
import Icon from './Icon.vue';
import Trend from './Trend.vue';
withDefaults(defineProps<{ temperature: number; humidity: number; scenario: string; historical?: boolean; prominent?: boolean; compact?: boolean; context?: 'box' | 'task' }>(), { historical: false, prominent: false, compact: false, context: 'task' });
</script>
<template>
  <section :class="['environment', { prominent, compact }]">
    <div class="section-heading"><div><span class="section-kicker">{{ historical ? '本次运输历史' : '运输环境' }}</span><h2>{{ ['invalid','unknown'].includes(scenario) ? '环境暂无法判断' : scenario === 'alarm' ? '温度超出演示范围' : scenario === 'stale' || scenario === 'offline' ? '等待新的环境数据' : '环境平稳，一路鲜行' }}</h2></div><span :class="['pill', scenario === 'alarm' ? 'bad' : ['invalid','unknown','stale','offline'].includes(scenario) ? 'warn' : 'good']"><Icon :name="scenario === 'normal' ? 'shield' : 'alert'" :size="14" />{{ ['invalid','unknown'].includes(scenario) ? '判断未知' : ['offline','stale'].includes(scenario) ? '数据过期' : scenario === 'alarm' ? '环境异常' : '已评估正常' }}</span></div>
    <div class="sensor-readings"><div class="reading"><span class="reading-label"><Icon name="temp" :size="18" />温度</span><div class="reading-number">{{ scenario === 'invalid' ? '—' : scenario === 'alarm' ? '8.6' : temperature.toFixed(1) }}<small>°C</small></div><span class="range-label">画像参考 2–8 °C <i>Demo</i></span></div><div class="reading"><span class="reading-label"><Icon name="drop" :size="18" />相对湿度</span><div class="reading-number">{{ scenario === 'invalid' ? '—' : humidity }}<small>%</small></div><span class="range-label">参考区间 45–70% <i>Demo</i></span></div></div>
    <div class="quality-row"><span class="quality-item"><i :class="{'warning-dot':scenario !== 'normal'}"></i>{{ scenario === 'invalid' ? '采样无效' : '样本有效' }}</span><span>{{ scenario === 'offline' || scenario === 'stale' ? '已过期 · 14:20 观测' : historical ? '历史样本 · 10:40' : '14:32 更新' }}</span><span class="mock-text">Mock Data</span></div>
    <template v-if="!compact"><div class="chart-heading"><strong>温度变化</strong><span>{{ context === 'box' ? '最近采样' : '本次运输' }} <i class="chart-key"></i> 温度</span></div><Trend :temperature="temperature" :historical="historical" :gap="['invalid','offline'].includes(scenario)" :alarm="scenario === 'alarm'" /><p class="chart-caption">{{ context === 'box' ? '箱体最近采样示例，不代表任意任务的全部历史。' : historical ? '仅显示本次已结束运输的数据，不随箱体现时读数变化。' : '仅显示本次运输的样本；曲线与参考范围为演示数据。' }}</p></template>
  </section>
</template>
