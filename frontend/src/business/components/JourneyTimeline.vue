<script setup lang="ts">
import { date, type Task } from '../model';
defineProps<{
    task: Task;
}>();
</script>
<template>
  <section class="journey">
    <h2>运输进度</h2>
    <ol>
      <li :class="{done:!!task.item_name}">
        <span><strong>{{task.item_name?'已绑定物品':'等待绑定物品'}}</strong><small>{{task.item_name||'尚未绑定'}}</small></span><time>{{task.item_name?'已记录':'待完成'}}</time>
      </li>
      <li :class="{done:task.status!=='READY'}">
        <strong>{{task.started_at?'已开始运输':'等待开始运输'}}</strong><time>{{task.started_at?date(task.started_at):'待开始'}}</time>
      </li>
      <li :class="{done:task.status==='COMPLETED',current:task.status==='ACTIVE'}">
        <strong>{{task.status==='COMPLETED'?'运输已结束':task.status==='ACTIVE'?'运输进行中':'等待运输'}}</strong><time>{{task.ended_at?date(task.ended_at):task.status==='ACTIVE'?'当前':'—'}}</time>
      </li>
    </ol>
  </section>
</template>