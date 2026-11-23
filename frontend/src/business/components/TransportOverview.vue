<script setup lang="ts">
import { date, stateName, type Monitoring } from '../model';
import EnvironmentSummary from './EnvironmentSummary.vue';
import JourneyTimeline from './JourneyTimeline.vue';
import Icon from './Icon.vue';
defineProps<{
    data: Monitoring;
    canFinish?: boolean;
    busy?: boolean;
}>();
defineEmits<{
    finish: [
    ];
    view: [
        kind: 'environment' | 'events'
    ];
}>();
</script>
<template>
  <div class="transport-page">
    <section class="transport-header">
      <div>
        <p class="task-number">{{data.task.transport_no}}</p>
        <h1>{{data.task.item_name||'尚未绑定物品'}}</h1>
        <p>
          {{data.task.started_at?date(data.task.started_at):'尚未开始'}}{{data.task.ended_at?' — '+date(data.task.ended_at):''}}<span
            v-if="data.task.box_id"> · {{data.task.box_id}}</span></p>
      </div><span
        :class="['transport-state',{finished:data.task.status==='COMPLETED'}]">{{stateName(data.task.status)}}</span>
    </section>
    <div v-if="data.task.status==='COMPLETED'" class="historical-note">
      <Icon name="archive" :size="18" />本次运输已结束，以下仅为本次历史记录。
    </div>
    <div class="overview-grid">
      <section class="monitor-summary">
        <EnvironmentSummary :environment="data.environment" :historical="data.task.status==='COMPLETED'" /><button
          class="list-row detail-link" @click="$emit('view','environment')">
          <Icon name="chart" /><strong>查看环境记录</strong>
          <Icon name="chevron" :size="18" />
        </button><button class="list-row detail-link" aria-label="设备事件" @click="$emit('view','events')">
          <Icon name="bell" />
          <span><strong>设备事件</strong><small>{{data.events.length?'查看本次可确认设备事件':'暂无可确认事件，不代表没有异常'}}</small></span>
          <Icon name="chevron" :size="18" />
        </button>
      </section>
      <aside class="transport-progress">
        <JourneyTimeline :task="data.task" /><button v-if="canFinish&&data.task.status==='ACTIVE'"
          class="button danger wide" :disabled="busy" @click="$emit('finish')">结束运输</button>
        <p class="data-note">{{canFinish?'结束不代表签收、告警恢复或补传完成。':'仅展示本次获准运输的信息。'}}</p>
      </aside>
    </div>
  </div>
</template>