<script setup lang="ts">withDefaults(defineProps<{ small?: boolean }>(), { small: false });</script>
<template>
  <svg viewBox="0 0 300 235" :class="['box-art', {small}]" fill="none" aria-label="SmartBox 物流箱插画" role="img">
    <ellipse cx="151" cy="211" rx="111" ry="13" fill="currentColor" opacity=".08" />
    <path d="M51 85L165 41L257 84L146 131L51 85Z" fill="#e2ebe8" stroke="#bacdc7" stroke-width="2" />
    <path d="M51 85V170L146 215V131L51 85Z" fill="#eff4ef" stroke="#bacdc7" stroke-width="2" />
    <path d="M146 131L257 84V169L146 215V131Z" fill="#c0d4ce" stroke="#9ab9af" stroke-width="2" />
    <path d="M74 92V158L121 180V114 M167 130V192 M238 101V160" stroke="#92aea3" stroke-width="3" />
    <path d="M51 90L146 136L257 89" stroke="#183e46" stroke-width="8" stroke-linejoin="round" />
    <path d="M85 73L181 117 M108 65L203 107 M135 54L226 98" stroke="#b9ccc3" stroke-width="2" />
    <path d="M92 107L112 117V130L92 120V107Z" fill="#233f49" />
    <path d="M182 138L219 123V150L182 166V138Z" fill="#193e48" />
    <path d="M189 143L211 134" stroke="#65c5ac" stroke-width="3" /><path d="M189 151L202 146" stroke="#b4d9cd" stroke-width="2" />
    <circle cx="213" cy="142" r="2.5" fill="#edaa5c" />
    <path d="M66 128L101 144V161L66 145V128Z" fill="#d5e3da" />
    <path d="M72 135L96 146 M72 140L88 147" stroke="#8daa9c" stroke-width="2" />
    <path d="M50 78L160 35L268 82" stroke="#f5faf7" stroke-width="5" stroke-linecap="round" />
    <circle cx="246" cy="40" r="19" fill="#edf7f2" stroke="#c3e0d5" /><path d="M238 40l5 5 10-11" stroke="#2c9277" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
</template>
