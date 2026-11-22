<script setup lang="ts">
import Icon from './Icon.vue';
import type { Task } from './model';
defineProps<{ task: Task; compact?: boolean }>();
</script>
<template>
  <section :class="['journey', { 'journey-compact': compact }]">
    <div class="section-heading"><h2>运输时间线</h2><span class="quiet-label">已记录的运输阶段</span></div>
    <ol class="journey-list">
      <li class="done"><span class="journey-dot"><Icon name="check" :size="13" /></span><div><strong>已关联待运输物品</strong><p>{{ task.item || '待绑定物品' }}<span>演示关联</span></p></div><time>{{ task.state === 'ready' ? '待完成' : task.start }}</time></li>
      <li :class="task.state !== 'ready' ? 'done' : 'future'"><span class="journey-dot"><Icon :name="task.state !== 'ready' ? 'check' : 'task'" :size="13" /></span><div><strong>{{ task.state === 'ready' ? '等待开始运输' : '已开始运输' }}</strong><p>{{ task.state === 'ready' ? '核对箱体与物品后开始' : '运输环境开始按任务记录' }}</p></div><time>{{ task.start || '—' }}</time></li>
      <li :class="task.state === 'active' ? 'current' : task.state === 'completed' ? 'done' : 'future'"><span class="journey-dot"><Icon name="box" :size="14" /></span><div><strong>{{ task.state === 'completed' ? '本次运输已结束' : '运输途中，持续监测' }}</strong><p>{{ task.state === 'completed' ? '环境与异常已归入本次记录' : '温度、湿度与相关异常' }}</p></div><time>{{ task.end || (task.state === 'active' ? '当前' : '—') }}</time></li>
      <li v-if="task.state !== 'completed'" class="future"><span class="journey-dot"><Icon name="archive" :size="14" /></span><div><strong>结束并保存运输记录</strong><p>以实际结束操作为准，不预估到达时间</p></div><time>待完成</time></li>
    </ol>
  </section>
</template>
