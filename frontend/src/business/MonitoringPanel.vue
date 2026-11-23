<script setup lang="ts">
import { type Monitoring } from './model';
import EnvironmentSummary from './components/EnvironmentSummary.vue';
import SampleHistory from './components/SampleHistory.vue';
import EventList from './components/EventList.vue';
defineProps<{
    data: Monitoring;
}>();
</script>
<template>
  <section class="monitoring-panel" aria-label="本次运输监测">
    <EnvironmentSummary :environment="data.environment" :historical="data.task.status==='COMPLETED'" />
    <SampleHistory :samples="data.samples" />
    <EventList :events="data.events" />
  </section>
</template>