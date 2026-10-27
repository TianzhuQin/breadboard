<template>
  <div class="mt-reqs">
    <ul v-if="modelValue.length" class="mt-reqs__list">
      <li v-for="(r, i) in modelValue" :key="i" class="mt-reqs__item">
        <ListFilter :size="16" class="rc-faint" aria-hidden="true" />
        <div class="mt-reqs__text">
          <div class="rc-strong">{{ describeRequirement(r) }}</div>
          <div class="rc-sub">Others: {{ guardLabel(r) }}<template v-if="r.kind === 'masters'"> · MTurk charges 5% more for Masters</template></div>
        </div>
        <BButton size="sm" variant="ghost" :icon="Pencil" :aria-label="`Edit: ${describeRequirement(r)}`" :disabled="disabled" @click="edit(i)" />
        <BButton size="sm" variant="danger-ghost" :icon="Trash2" :aria-label="`Remove: ${describeRequirement(r)}`" :disabled="disabled" @click="remove(i)" />
      </li>
    </ul>
    <p v-else class="rc-sub">No requirements: any worker on MTurk can take the HIT.</p>
    <div>
      <BButton size="sm" :icon="Plus" :disabled="disabled" @click="add">Add a requirement</BButton>
    </div>
    <RequirementDialog v-model:open="dialogOpen" :initial="editIndex == null ? null : modelValue[editIndex]" @save="save" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ListFilter, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import { BButton, toast } from '@/ui'
import RequirementDialog from './RequirementDialog.vue'
import { ACTIONS_GUARDED, describeRequirement, type RequirementDraft } from './lib'

const props = defineProps<{ modelValue: RequirementDraft[], disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: RequirementDraft[]] }>()

const dialogOpen = ref(false)
const editIndex = ref<number | null>(null)

function guardLabel (r: RequirementDraft) {
  return (ACTIONS_GUARDED.find(g => g.value === r.actionsGuarded)?.label || r.actionsGuarded).toLowerCase()
}

function add () {
  editIndex.value = null
  dialogOpen.value = true
}
function edit (i: number) {
  editIndex.value = i
  dialogOpen.value = true
}
function save (d: RequirementDraft) {
  const list = props.modelValue.slice()
  if (editIndex.value == null) list.push(d)
  else list[editIndex.value] = d
  emit('update:modelValue', list)
  dialogOpen.value = false
}
function remove (i: number) {
  const before = props.modelValue.slice()
  const removed = before[i]
  emit('update:modelValue', before.filter((_, j) => j !== i))
  toast('info', 'Requirement removed', describeRequirement(removed), { action: { label: 'Undo', run: () => emit('update:modelValue', before) } })
}
</script>

<style>
.mt-reqs { display: flex; flex-direction: column; gap: 10px; }
.mt-reqs__list { list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: var(--radius-sm); }
.mt-reqs__item { display: flex; align-items: center; gap: 10px; padding: 9px 8px 9px 12px; }
.mt-reqs__item + .mt-reqs__item { border-top: 1px solid var(--border); }
.mt-reqs__text { flex: 1; min-width: 0; font-size: var(--text-sm); }
</style>
