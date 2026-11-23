<script setup lang="ts">
import { computed, ref } from 'vue';
import type { Box, Task } from './model';
import { positionLabel, taskStatusLabel } from './model';

const props = withDefaults(defineProps<{
  boxes: Box[];
  tasks?: Task[];
  selectedBoxId?: string;
  selectedTaskId?: number | null;
  compact?: boolean;
}>(), { tasks: () => [], selectedBoxId: '', selectedTaskId: null, compact: false });
const emit = defineEmits<{ (event: 'select-box', boxId: string): void }>();
const scale = ref(1);
const located = computed(() => props.boxes.filter(box => box.position != null));
const selectedTask = computed(() => {
  if (props.selectedTaskId != null) {
    const task = props.tasks.find(item => item.task_id === props.selectedTaskId);
    if (task) return task;
  }
  const box = props.boxes.find(item => item.box_id === props.selectedBoxId);
  if (!box) return null;
  const current = box.current_transport;
  if (current) {
    const task = props.tasks.find(item => item.task_id === current.task_id);
    if (task) return task;
  }
  return props.tasks.find(item => item.box_id === box.box_id && (item.status === 'ACTIVE' || item.status === 'READY')) ?? null;
});
const selectedRoute = computed(() => selectedTask.value?.route ?? null);
const activeRoutes = computed(() => props.tasks.filter(task => task.status === 'ACTIVE' && task.route != null && task.task_id !== selectedTask.value?.task_id));
function points(route: { points: { x: number; y: number }[] } | null) {
  return route == null ? '' : route.points.map(point => `${point.x * 8},${point.y * 4.8}`).join(' ');
}
function markerClass(box: Box) {
  const status = box.current_transport?.status;
  return ['map-pin', status === 'ACTIVE' ? 'is-active' : status === 'READY' ? 'is-ready' : 'is-idle', box.connectivity === 'OFFLINE' ? 'is-offline' : '', box.alarm.active ? 'has-alarm' : ''];
}
function zoom(delta: number) { scale.value = Math.max(1, Math.min(1.6, Math.round((scale.value + delta) * 10) / 10)); }
function reset() { scale.value = 1; }
</script>

<template>
  <div :class="['map-scene', { compact }]" role="img" aria-label="物流地图示意，所有位置为 MOCK">
    <div class="map-layer" :style="{ transform: `scale(${scale})` }">
      <svg viewBox="0 0 800 480" preserveAspectRatio="none" aria-hidden="true">
        <rect width="800" height="480" fill="#eef3f1" />
        <path d="M0 78 C120 120 100 170 220 178 S340 120 430 164 S530 240 650 205 S740 110 800 140" class="map-river" />
        <path d="M-15 315 C105 288 160 335 272 305 S415 240 500 284 S655 346 810 300" class="map-river second" />
        <path d="M70 0 L140 480 M188 0 L232 480 M365 0 L324 480 M512 0 L552 480 M678 0 L644 480 M0 124 L800 190 M0 240 L800 248 M0 394 L800 355" class="map-road" />
        <polyline v-for="task in activeRoutes" :key="`faint-${task.task_id}`" :points="points(task.route)" class="route-faint" />
        <polyline v-if="selectedRoute" :points="points(selectedRoute)" class="route-underlay" />
        <polyline v-if="selectedRoute" :points="points(selectedRoute)" class="route-path" />
        <circle v-if="selectedRoute && selectedRoute.points.length" :cx="selectedRoute.points[0].x * 8" :cy="selectedRoute.points[0].y * 4.8" r="6" class="route-origin" />
      </svg>
      <button v-for="(box, index) in located" :key="box.box_id" type="button" :class="[...markerClass(box), { selected: box.box_id === selectedBoxId }]"
        :style="{ left: `${box.position!.x}%`, top: `${box.position!.y}%` }" :aria-label="`选择 ${box.box_id} ${positionLabel(box.position)}`"
        @click="emit('select-box', box.box_id)">
        <span>{{ String(index + 1).padStart(2, '0') }}</span><i />
      </button>
    </div>
    <div class="map-toolbar">
      <button type="button" aria-label="放大地图" @click="zoom(0.2)">＋</button>
      <button type="button" aria-label="缩小地图" @click="zoom(-0.2)">−</button>
      <button type="button" aria-label="重置地图视图" @click="reset">⌖</button>
    </div>
    <div class="map-legend">
      <span><i class="legend-dot is-active" />运输中</span>
      <span><i class="legend-dot is-ready" />待开始</span>
      <span><i class="legend-dot is-idle" />无运输</span>
      <span><i class="legend-dot is-alarm" />有告警</span>
    </div>
    <div v-if="!located.length" class="map-empty">
      <span>示意地图 · MOCK</span>
      <h3>暂无可定位箱体</h3>
      <p>模拟位置来自任务路线；没有任务时显示“未定位”。</p>
    </div>
    <span class="map-watermark">示意路网 · MOCK · 非真实 GPS</span>
    <div v-if="selectedTask" class="map-selected-route">
      <span>{{ selectedTask.box_id }}</span>
      <strong>{{ selectedTask.route?.name ?? '无模拟路线' }}</strong>
      <small>{{ taskStatusLabel(selectedTask.status) }} · {{ selectedTask.route ? `${Math.round(selectedTask.route.duration_ms / 60000)} 分钟演示路线` : '未选择路线' }}</small>
    </div>
  </div>
</template>

<style scoped>
.map-scene { position: relative; overflow: hidden; min-height: 400px; background: #eef3f1; }
.map-scene.compact { min-height: 280px; }
.map-layer { position: absolute; inset: 0; transform-origin: center; transition: transform .2s ease; }
.map-layer > svg { position: absolute; inset: 0; width: 100%; height: 100%; }
.map-river { fill: none; stroke: #d6e8e5; stroke-width: 25; opacity: .8; }
.map-river.second { stroke-width: 15; }
.map-road { fill: none; stroke: #ffffffc9; stroke-width: 4; }
.route-faint { fill: none; stroke: #c7a16a; stroke-width: 3; opacity: .25; stroke-dasharray: 6 8; }
.route-underlay { fill: none; stroke: #f4e4c9; stroke-width: 12; stroke-linecap: round; }
.route-path { fill: none; stroke: #c7832e; stroke-width: 4; stroke-linecap: round; stroke-dasharray: 10 6; }
.route-origin { fill: #176e5e; stroke: white; stroke-width: 3; }
.map-pin { position: absolute; transform: translate(-50%, -100%); width: 34px; height: 34px; border-radius: 50% 50% 50% 4px; rotate: -45deg; border: 2px solid white; background: #8fa6ad; color: white; box-shadow: 0 4px 12px #1d34304d; display: grid; place-items: center; cursor: pointer; z-index: 3; }
.map-pin > span { rotate: 45deg; font-size: 11px; font-weight: 700; }
.map-pin > i { display: none; }
.map-pin.is-active { background: #147b67; }
.map-pin.is-ready { background: #c7832e; }
.map-pin.has-alarm { box-shadow: 0 0 0 5px #c94b3b33, 0 4px 12px #1d34304d; }
.map-pin.is-offline { opacity: .6; }
.map-pin.selected { outline: 3px solid #fff; box-shadow: 0 0 0 6px #147b6755; z-index: 4; }
.map-toolbar { position: absolute; right: 14px; top: 14px; display: grid; gap: 6px; z-index: 5; }
.map-toolbar button { width: 32px; height: 32px; border: 1px solid #d6e1dd; background: #ffffffeb; border-radius: 6px; font-size: 16px; cursor: pointer; color: #334e48; }
.map-legend { position: absolute; left: 14px; bottom: 14px; display: flex; flex-wrap: wrap; gap: 10px 14px; background: #ffffffd9; border: 1px solid #e0eae6; border-radius: 6px; padding: 8px 10px; font-size: 11px; color: #57716c; z-index: 4; }
.map-legend span { display: inline-flex; align-items: center; gap: 5px; }
.legend-dot { width: 7px; height: 7px; border-radius: 50%; background: #8fa6ad; }
.legend-dot.is-active { background: #147b67; } .legend-dot.is-ready { background: #c7832e; } .legend-dot.is-alarm { background: #c94b3b; }
.map-empty { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 24px; background: #eef3f12e; z-index: 2; }
.map-empty > span { font-size: 12px; color: #8d6329; background: #fff9ef; padding: 7px 12px; border-radius: 5px; }
.map-empty h3 { margin-top: 16px; font-size: 20px; color: #57716c; }
.map-empty p { font-size: 13px; color: #6b807b; margin-top: 8px; }
.map-watermark { position: absolute; right: 12px; bottom: 12px; color: #738a85; font-size: 11px; padding: 5px 8px; background: #ffffffb0; border-radius: 4px; z-index: 4; }
.map-selected-route { position: absolute; left: 14px; top: 14px; background: #ffffffef; border: 1px solid #e0eae6; border-radius: 7px; padding: 9px 12px; display: grid; gap: 2px; font-size: 12px; color: #57716c; z-index: 4; max-width: 260px; }
.map-selected-route strong { font-size: 13px; color: #213c36; }
.map-selected-route small { color: #7b908b; }
</style>
