<template>
  <button
    type="button"
    class="sitem"
    :class="{ 'is-active': active, 'is-dirty': dirty }"
    :aria-current="active ? 'true' : undefined"
    @click="emit('select')"
  >
    <span class="sitem__text">
      <span class="sitem__name">{{ d.name || 'Untitled step' }}</span>
      <span v-if="codeMatch" class="sitem__hint">matches in the code</span>
      <span v-else-if="hint" class="sitem__hint">{{ hint }}</span>
    </span>
    <span class="sitem__state">
      <BSpinner v-if="d.status === 'saving' || d.status === 'deleting'" :size="12" :label="d.status === 'saving' ? 'Saving' : 'Deleting'" />
      <CircleAlert v-else-if="d.status === 'error'" :size="14" class="sitem__err" aria-label="Last save failed" />
      <Check v-else-if="d.status === 'saved'" :size="14" class="sitem__ok" aria-label="Saved" />
      <span v-else-if="isNew(d)" class="sitem__new">new</span>
      <span v-else-if="dirty" class="sitem__dot" title="Unsaved changes"><span class="sr-only">Unsaved changes</span></span>
    </span>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Check, CircleAlert } from 'lucide-vue-next'
import { BSpinner } from '@/ui'
import { isDirty, isNew, type StepDraft } from './stepsModel'

const props = defineProps<{ d: StepDraft, active: boolean, hint?: string, codeMatch?: boolean }>()
const emit = defineEmits<{ select: [] }>()
const dirty = computed(() => isDirty(props.d))
</script>

<style>
.sitem {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 34px;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-soft);
  text-align: left;
  cursor: pointer;
  transition: background var(--duration) var(--ease), color var(--duration) var(--ease);
}
.sitem:hover { background: var(--surface-hover); color: var(--text); }
.sitem.is-active { background: var(--accent-soft); color: var(--accent-soft-text); }
.sitem:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.sitem__text { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.sitem__name { font-size: var(--text-sm); font-weight: 560; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sitem.is-active .sitem__name { font-weight: 650; }
.sitem__hint { font-size: var(--text-xs); color: var(--faint); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sitem.is-active .sitem__hint { color: color-mix(in srgb, var(--accent-soft-text) 70%, transparent); }
.sitem__state { flex: none; display: inline-grid; place-items: center; min-width: 16px; }
.sitem__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--warning); }
.sitem__new { padding: 0 6px; border-radius: 999px; background: var(--warning-soft); color: var(--warning-soft-text); font-size: 10.5px; font-weight: 650; line-height: 17px; }
.sitem__ok { color: var(--success); }
.sitem__err { color: var(--danger); }
</style>
