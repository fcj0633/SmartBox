<script setup lang="ts">
import type { Task } from './model';
defineProps<{
    task: Task;
    compact?: boolean;
}>();
</script>
<template>
  <section class="journey">
    <h2>运输进度</h2>
    <ol>
      <li :class="{done:!!task.item}">
        <span><strong>{{task.item?'已绑定物品':'等待绑定物品'}}</strong><small>{{task.item||'尚未绑定'}}</small></span><time>{{task.item?'已记录':'待完成'}}</time>
      </li>
      <li :class="{done:task.state!=='ready'}">
        <span><strong>{{task.state==='ready'?'等待开始运输':'已开始运输'}}</strong></span><time>{{task.start||'待开始'}}</time></li>
      <li :class="{done:task.state==='completed',current:task.state==='active'}">
        <span><strong>{{task.state==='completed'?'运输已结束':task.state==='active'?'运输进行中':'等待运输'}}</strong></span><time>{{task.end||(task.state==='active'?'当前':'—')}}</time>
      </li>
    </ol>
  </section>
</template>
