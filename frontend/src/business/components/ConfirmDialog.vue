<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
defineProps<{
    title: string;
    busy?: boolean;
    confirmLabel?: string;
    danger?: boolean;
}>();
const emit = defineEmits<{
    confirm: [
    ];
    close: [
    ];
}>();
const element = ref<HTMLElement>();
let previous: HTMLElement | null = null;
function keys(e: KeyboardEvent) { if (e.key === 'Escape')
    emit('close'); if (e.key === 'Tab') {
    const all = element.value?.querySelectorAll<HTMLElement>('button:not(:disabled)');
    if (!all?.length)
        return;
    if (e.shiftKey && document.activeElement === all[0]) {
        e.preventDefault();
        all[all.length - 1].focus();
    }
    else if (!e.shiftKey && document.activeElement === all[all.length - 1]) {
        e.preventDefault();
        all[0].focus();
    }
} }
onMounted(() => { previous = document.activeElement as HTMLElement; element.value?.querySelector('button')?.focus(); window.addEventListener('keydown', keys); });
onUnmounted(() => { window.removeEventListener('keydown', keys); previous?.focus(); });
</script>
<template>
  <div class="modal-shade" @click.self="!busy&&emit('close')">
    <section ref="element" class="modal" role="dialog" aria-modal="true" :aria-label="title">
      <h2>{{title}}</h2>
      <slot />
      <div class="form-actions"><button class="button secondary" :disabled="busy"
          @click="emit('close')">返回核对</button><button :class="['button',danger?'danger':'primary']" :disabled="busy"
          @click="emit('confirm')">{{busy?'正在提交…':confirmLabel||'确认'}}</button></div>
    </section>
  </div>
</template>