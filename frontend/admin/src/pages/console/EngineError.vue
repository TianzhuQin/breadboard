<template>
  <div class="eng-err">
    <p class="eng-err__title">
      <CircleAlert :size="15" aria-hidden="true" class="eng-err__icon" />
      <span>{{ parsed.title }}</span>
    </p>
    <pre v-if="showMessage" class="eng-err__msg">{{ parsed.message }}</pre>
    <p v-if="parsed.hint" class="eng-err__hint">{{ parsed.hint }}</p>
    <ul v-if="places.length" class="eng-err__places" aria-label="Where it happened">
      <li v-for="(p, i) in places" :key="i">
        <button
          v-if="p.kind === 'own' && canGo"
          type="button"
          class="eng-err__place is-own is-link"
          :title="`Show line ${editorLine(p.line)} in the editor`"
          @click="emit('goto', editorLine(p.line), p.column)"
        >
          <CornerDownRight :size="13" aria-hidden="true" />
          {{ ownLabel(p) }}
        </button>
        <span v-else class="eng-err__place" :class="`is-${p.kind}`">
          {{ p.kind === 'own' ? ownLabel(p) : placeLabel(p) }}
          <span class="eng-err__where">{{ whereText(p.kind) }}</span>
        </span>
      </li>
    </ul>
    <details v-if="parsed.raw && parsed.raw !== parsed.message" class="eng-err__raw">
      <summary>Full error</summary>
      <pre>{{ parsed.raw }}</pre>
    </details>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleAlert, CornerDownRight } from 'lucide-vue-next'
import { parseGroovyError, placeLabel, type ErrorPlace, type PlaceKind } from './groovyError'

/** An engine error, explained: what happened, what to try, and which line (with a jump to it). */
const props = withDefaults(defineProps<{
  error: string
  /** The editor line the run script started on; null when it didn't come from the editor. */
  fromLine?: number | null
  /** What "own" refers to in words, e.g. "your script" or "this step". */
  ownName?: string
}>(), { fromLine: null, ownName: 'the script' })
const emit = defineEmits<{ goto: [line: number, column?: number] }>()

const parsed = computed(() => parseGroovyError(props.error))
// Breadboard's own files (step.groovy, actions.groovy …) are no place a researcher can change: they
// are under Full error only
const places = computed(() => parsed.value.places.filter(p => p.kind !== 'library').slice(0, 6))
const canGo = computed(() => props.fromLine != null)
const showMessage = computed(() => !!parsed.value.message && parsed.value.message !== parsed.value.title)

function editorLine (line: number) {
  return (props.fromLine ?? 1) + line - 1
}
function ownLabel (p: ErrorPlace) {
  const line = canGo.value ? editorLine(p.line) : p.line
  return `Line ${line}${p.column ? `, column ${p.column}` : ''} of ${props.ownName}`
}
function whereText (kind: PlaceKind) {
  return kind === 'other' ? 'an earlier script or a step' : ''
}
</script>

<style>
.eng-err { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.eng-err__title { display: flex; align-items: flex-start; gap: 7px; font-weight: 600; color: var(--danger-soft-text); font-size: var(--text-sm); line-height: 1.4; }
.eng-err__icon { flex: none; margin-top: 2px; color: var(--danger); }
.eng-err__msg {
  margin: 0;
  padding: 7px 10px;
  border-radius: var(--radius-xs);
  background: var(--danger-soft);
  color: var(--danger-soft-text);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow: auto;
}
.eng-err__hint { color: var(--muted); font-size: var(--text-sm); line-height: 1.45; }
.eng-err__places { display: flex; flex-wrap: wrap; gap: 6px; margin: 2px 0 0; padding: 0; list-style: none; }
.eng-err__place {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-soft);
  font-size: var(--text-xs);
  font-family: var(--font);
  white-space: nowrap;
}
.eng-err__place.is-own { border-color: color-mix(in srgb, var(--danger) 40%, var(--border)); color: var(--danger-soft-text); }
.eng-err__place.is-link { cursor: pointer; font-weight: 600; }
.eng-err__place.is-link:hover { background: var(--danger-soft); }
.eng-err__where { color: var(--faint); }
.eng-err__raw { font-size: var(--text-xs); color: var(--muted); }
.eng-err__raw summary { cursor: pointer; width: max-content; }
.eng-err__raw summary:hover { color: var(--text); }
.eng-err__raw pre { margin: 6px 0 0; padding: 8px 10px; border-radius: var(--radius-xs); background: var(--surface-sunken); border: 1px solid var(--border); white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow: auto; }
</style>
