<template>
  <svg :width="size" :height="size" viewBox="0 0 40 40" aria-hidden="true" class="brand-mark">
    <defs>
      <linearGradient :id="gid" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#818cf8" />
        <stop offset="1" stop-color="#4f46e5" />
      </linearGradient>
    </defs>
    <rect width="40" height="40" rx="11" :fill="`url(#${gid})`" />
    <g stroke="#fff" stroke-opacity="0.75" stroke-width="1.6" stroke-linecap="round">
      <line x1="13" y1="14" x2="27" y2="13" />
      <line x1="13" y1="14" x2="20" y2="27" />
      <line x1="27" y1="13" x2="20" y2="27" />
      <line x1="27" y1="13" x2="31" y2="24" />
    </g>
    <g fill="#fff">
      <circle cx="13" cy="14" r="3.2" />
      <circle cx="27" cy="13" r="3.2" />
      <circle cx="20" cy="27" r="3.6" />
      <circle cx="31" cy="24" r="2.4" fill-opacity="0.85" />
    </g>
  </svg>
</template>

<script setup lang="ts">
import { uid } from '@/ui/uid'
withDefaults(defineProps<{ size?: number }>(), { size: 28 })
const gid = uid('brand')
</script>

<style>
.brand-mark { flex: none; display: block; }
</style>
