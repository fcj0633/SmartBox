<script setup lang="ts">
import type { PreviewState } from './usePreview';
import Environment from './Environment.vue';
import Journey from './Journey.vue';
import Icon from './Icon.vue';
import { demoDate, stateNames } from './model';
defineProps<{
    p: PreviewState;
}>();
defineEmits<{
    finish: [
    ];
}>();
</script>
<template>
  <div class="transport-page">
    <section class="transport-header">
      <div>
        <p class="task-number">{{p.task.id}}</p>
        <h1>{{p.task.item||'尚未绑定物品'}}</h1>
        <p>{{demoDate}} · {{p.task.start||'尚未开始'}}{{p.task.end?' — '+p.task.end:''}}<span v-if="p.role==='rider'"> ·
            {{p.task.boxId}}</span></p>
      </div><span :class="['transport-state',{finished:p.task.state==='completed'}]">{{stateNames[p.task.state]}}</span>
    </section>
    <div v-if="p.task.state==='completed'" class="historical-note">
      <Icon name="archive" :size="18" />本次运输已结束，以下仅展示本次历史记录。
    </div>
    <div class="overview-grid">
      <section class="monitor-summary">
        <div v-if="p.taskScenario==='alarm'" class="attention">
          <Icon name="alert" />
          <div><strong>温度超出演示范围</strong><span>{{p.alarmTime}} · 8.6 °C · 有效样本</span>
          </div><button class="text-button" @click="p.go('alerts')">详情</button>
        </div>
        <Environment :temperature="p.task.temperature" :humidity="p.task.humidity" :scenario="p.taskScenario"
          :historical="p.task.state==='completed'" :sample-time="p.task.sampleTime||p.task.end||'14:32'" />
        <button class="list-row detail-link" @click="p.go('environment')">
          <Icon name="chart" /><strong>查看环境记录</strong>
          <Icon name="chevron" :size="18" />
        </button>
        <button class="list-row detail-link" aria-label="异常记录" @click="p.go('alerts')">
          <Icon name="bell" />
          <span><strong>异常记录</strong><small>{{p.taskScenario==='alarm'?'1 条温度异常': ['offline','invalid','stale','unknown'].includes(p.taskScenario)?'当前判断不可用，请查看数据状态':'本次暂无演示异常记录'}}</small></span>
          <Icon name="chevron" :size="18" />
        </button>
      </section>
      <aside class="transport-progress">
        <Journey :task="p.task" /><template v-if="p.role==='rider'"><button v-if="p.task.state==='active'"
            class="button danger wide" :disabled="p.denied(p.task.boxId)" @click="$emit('finish')">结束运输</button>
          <p v-if="p.denied(p.task.boxId)" class="form-error">使用授权已失效，结束资格待确认。</p><button v-if="p.task.state==='ready'"
            class="button primary wide" @click="p.prepare(p.task.boxId)">继续准备运输</button><button
            v-if="p.task.state==='completed'" class="button secondary wide" @click="p.go('history')">返回运输记录</button>
        </template>
        <p class="data-note">{{p.role==='customer'?'仅展示本次获准运输的信息。':'结束运输不代表签收、告警恢复或补传完成。'}}</p>
      </aside>
    </div>
  </div>
</template>
