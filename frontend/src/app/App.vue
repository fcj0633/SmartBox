<script setup lang="ts">
import { watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../stores/session';

const session = useSession();
const router = useRouter();
const route = useRoute();
watch(() => session.identityValid, valid => {
  if (!valid && route.path !== '/login') void router.replace('/login');
});
</script>

<template>
  <RouterView />
</template>
