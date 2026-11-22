<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 });
const paths: Record<string, string> = {
  box: 'M3 7l9-4 9 4v10l-9 4-9-4V7z M3 7l9 4 9-4 M12 11v10 M7 5l10 4',
  task: 'M9 4H5v17h14V4h-4 M9 3h6v4H9V3z M8 12h8 M8 16h5',
  bell: 'M5 16V10a7 7 0 0114 0v6l2 2H3l2-2z M10 21h4',
  user: 'M16 7a4 4 0 11-8 0 4 4 0 018 0z M4 21v-3a8 8 0 0116 0v3',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  chevron: 'M9 5l7 7-7 7',
  back: 'M15 5l-7 7 7 7',
  check: 'M5 12l4 4L19 6',
  temp: 'M9 14V5a3 3 0 016 0v9a5 5 0 11-6 0z M12 8v10',
  drop: 'M12 2s7 8 7 13a7 7 0 01-14 0c0-5 7-13 7-13z M9 16a3 3 0 003 3',
  shield: 'M12 2l8 3v7c0 5-8 10-8 10S4 17 4 12V5l8-3z M8 11l3 3 5-6',
  clock: 'M21 12a9 9 0 11-18 0 9 9 0 0118 0z M12 7v5l3 2',
  plus: 'M12 5v14 M5 12h14',
  signal: 'M4 20v-3 M9 20v-7 M14 20V9 M19 20V4',
  leaf: 'M20 3c-10-1-17 3-17 9 0 5 7 8 11 4 4-4 5-9 6-13z M4 21l10-11',
  lock: 'M5 10h14v11H5V10z M8 10V6a4 4 0 018 0v4 M12 15v3',
  refresh: 'M20 8A9 9 0 104 18 M20 3v5h-5',
  logout: 'M9 4H4v16h5 M13 8l5 4-5 4 M8 12h10',
  close: 'M6 6l12 12 M18 6L6 18',
  alert: 'M12 3L2 21h20L12 3z M12 9v5 M12 17v1',
  chart: 'M4 4v16h17 M7 14l4-5 4 3 5-7',
  archive: 'M3 3h18v5H3V3z M5 8v13h14V8 M9 12h6',
};
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.box" /></svg></template>
