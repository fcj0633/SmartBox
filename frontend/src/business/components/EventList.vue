<script setup lang="ts">
import { date, type Monitoring } from '../model';
defineProps<{
    events: Monitoring['events'];
}>();
</script>
<template>
  <section>
    <h2>设备事件</h2>
    <p v-if="!events.length" class="empty-record">暂无可确认设备事件，这不代表运输期间没有异常。</p>
    <ol class="real-event-list">
      <li v-for="event in events" :key="String(event.id)">
        <span><strong>{{event.description}}</strong><small>{{date(event.observed_at)}} ·
            {{event.type}}</small></span><span>{{event.level}}</span></li>
    </ol>
    <p class="data-note">仅限本次任务可见事件，最多 20 条；只读，不代表人工确认或告警结案。</p>
  </section>
</template>