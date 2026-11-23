<script setup lang="ts">
// Geometry extracted from the selected route prototype; no asset markers or actual geographic coordinates.
</script>
<template>
  <div class="enterprise-map" role="img" aria-label="示意地图，无真实GPS数据"><svg viewBox="0 0 800 480"
      preserveAspectRatio="none" aria-hidden="true">
      <rect width="800" height="480" fill="#eef3f1" />
      <path d="M0 78 C120 120 100 170 220 178 S340 120 430 164 S530 240 650 205 S740 110 800 140" class="map-river" />
      <path d="M-15 315 C105 288 160 335 272 305 S415 240 500 284 S655 346 810 300" class="map-river second" />
      <path
        d="M70 0 L140 480 M188 0 L232 480 M365 0 L324 480 M512 0 L552 480 M678 0 L644 480 M0 124 L800 190 M0 240 L800 248 M0 394 L800 355"
        class="map-road" />
    </svg>
    <div class="map-empty"><span>示意地图 · MOCK</span>
      <h3>尚无真实 GPS 位置</h3>
      <p>请从右侧选择运输任务查看数据。<br />真实资产未映射到示意坐标。</p>
    </div><span class="map-watermark">示意路网 · 非地图服务</span>
  </div>
</template>