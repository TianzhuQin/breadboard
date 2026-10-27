<template>
  <span class="b-spinner" :style="{ width: `${size}px`, height: `${size}px` }" role="status" :aria-label="label" />
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number, label?: string }>(), { size: 18, label: 'Loading' })
</script>

<style>
.b-spinner { display: inline-block; border-radius: 50%; border: 2px solid var(--accent-soft); border-top-color: var(--accent); animation: b-spin 0.75s linear infinite; flex: none; }
</style>
