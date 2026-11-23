<script setup lang="ts">
import { useRoute } from 'vue-router';
import EnterpriseLayout from './layouts/EnterpriseLayout.vue';
import RiderLayout from './layouts/RiderLayout.vue';
const route = useRoute();
</script>
<template>
  <EnterpriseLayout v-if="route.path.startsWith('/enterprise')">
    <slot />
  </EnterpriseLayout>
  <RiderLayout v-else>
    <slot />
  </RiderLayout>
</template>