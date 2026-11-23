<script setup lang="ts">
import Icon from '../components/Icon.vue';
import '../styles/transport.css';
defineProps<{
    entered: boolean;
}>();
defineEmits<{
    exit: [
    ];
}>();
</script>
<template>
  <div class="sb-transport sb-guest">
    <header class="product-header"><span class="brand"><span class="brand-symbol">
          <Icon name="box" :size="24" />
        </span><strong>SmartBox</strong><span class="brand-context">运输查看</span></span><button v-if="entered"
        class="text-button" @click="$emit('exit')">退出查看</button>
      <Icon v-else name="lock" :size="19" />
    </header>
    <main class="main-content">
      <slot />
    </main>
    <footer class="product-footer">只展示本次运输可确认的数据，不代表全程安全结论。</footer>
  </div>
</template>