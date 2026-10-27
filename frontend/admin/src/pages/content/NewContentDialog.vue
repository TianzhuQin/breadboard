<template>
  <BDialog
    :open="open"
    title="New text"
    subtitle="A text your scripts show to participants: instructions, a consent form, a message."
    size="sm"
    @update:open="emit('update:open', $event)"
  >
    <form id="ct-new-form" class="ct-new" @submit.prevent="submit">
      <BField label="Name" :error="shownError" required>
        <template #default="{ id, describedby }">
          <BInput
            :id="id"
            v-model="name"
            :aria-describedby="describedby"
            :aria-invalid="shownError ? 'true' : undefined"
            autocomplete="off"
            spellcheck="false"
            placeholder="e.g. Instructions"
            data-autofocus
            @blur="touched = true"
          />
        </template>
        <template #hint>
          Scripts show it with <code>c.get("{{ name.trim() || 'Name' }}")</code>. It is saved when you save.
        </template>
      </BField>
      <BField v-if="sources.length" label="Start from" optional>
        <template #default="{ id }">
          <BSelect :id="id" v-model="copyOf" :options="sources" />
        </template>
      </BField>
    </form>
    <template #footer>
      <BButton variant="ghost" @click="emit('update:open', false)">Cancel</BButton>
      <BButton variant="primary" type="submit" form="ct-new-form" :disabled="!!error">Create text</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BButton, BDialog, BField, BInput, BSelect, type SelectOption } from '@/ui'
import { validateName } from './contentModel'
import { useContentState } from './useContent'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean], created: [key: string] }>()
const s = useContentState()

const name = ref('')
const touched = ref(false)
const copyOf = ref<string>('')

watch(() => props.open, (o) => {
  if (o) { name.value = ''; touched.value = false; copyOf.value = '' }
})

const error = computed(() => validateName(name.value, s.items.value))
// "Give it a name" only after they tried; a clash or a bad character at once
const shownError = computed(() => (name.value.trim() || touched.value ? error.value : null))

const sources = computed<SelectOption[]>(() => {
  const list = s.items.value.filter(i => Object.values(i.translations).some(t => t.text.trim()))
  if (!list.length) return []
  return [{ value: '', label: 'An empty text' }, ...list.map(i => ({ value: i.key, label: `A copy of “${i.name}”` }))]
})

function submit () {
  touched.value = true
  if (error.value) return
  const source = copyOf.value ? s.items.value.find(i => i.key === copyOf.value) : null
  const key = s.create(name.value)
  if (!key) return
  if (source) {
    const created = s.items.value.find(i => i.key === key)
    if (created) {
      for (const t of Object.values(source.translations)) {
        if (t.text.trim()) s.updateText(created, t.language, t.text)
      }
    }
  }
  emit('update:open', false)
  emit('created', key)
}
</script>

<style>
.ct-new { display: flex; flex-direction: column; gap: 16px; }
.ct-new code { padding: 1px 5px; border-radius: 5px; background: var(--bg-subtle); color: var(--text-soft); word-break: break-all; }
</style>
