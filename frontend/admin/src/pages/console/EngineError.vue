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
          v-if="p.kind === 'own' && (canGo || elsewhere(p))"
          type="button"
          class="eng-err__place is-own is-link"
          :title="`Show line ${ownLine(p)} ${elsewhere(p) ? `of ${p.step}` : 'in the editor'}`"
          @click="emit('goto', ownLine(p), p.column, elsewhere(p) ? p.step : undefined)"
        >
          <CornerDownRight :size="13" aria-hidden="true" />
          {{ ownLabel(p) }}
        </button>
        <!-- a line of a step the server names (where it went wrong in another step's code, a step that started
             this one): opens that step there -->
        <button
          v-else-if="p.step && p.kind !== 'own'"
          type="button"
          class="eng-err__place is-link"
          :class="`is-${p.kind}`"
          :title="`Show line ${p.line} of ${p.step}`"
          @click="emit('goto', p.line, p.column, p.step)"
        >
          {{ placeLabel(p) }}
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
import { parseGroovyError, placeLabel, type ErrorFrame, type ErrorPlace, type PlaceKind } from './groovyError'

/**
 * An engine error, explained: what happened, what to try, and which line (with a jump to it). With the
 * server's names for its lines (`frames`, newer servers), each line of a step's code is named by its
 * step and opens it there: "Line 14 of RoundStep", "in isHuman (InitStep line 30)" where it went wrong
 * in another step's code, "RoundStep line 80".
 */
const props = withDefaults(defineProps<{
  error: string
  /** The server's names for the error's lines, when it sent them. */
  frames?: ErrorFrame[] | null
  /** The editor line the run script started on; null when it didn't come from the editor. */
  fromLine?: number | null
  /** What "own" refers to in words, e.g. "your script" or "this step". */
  ownName?: string
  /** The step `ownName` is (the Steps page's): a line of another step's code is named by that step. */
  ownStep?: string | null
  /** The error happened in a step's own code, which the server named: its line is in that step's code (parseGroovyError). */
  inStep?: boolean
}>(), { frames: null, fromLine: null, ownName: 'the script', ownStep: null, inStep: false })
/** `step`: the line is in that step's code, not in what `ownName` is (the parent opens that step there). */
const emit = defineEmits<{ goto: [line: number, column?: number, step?: string] }>()

const parsed = computed(() => parseGroovyError(props.error, { inStep: props.inStep, frames: props.frames }))
// Breadboard's own files (step.groovy, actions.groovy …) are no place a researcher can change: they
// are under Full error only
const places = computed(() => parsed.value.places.filter(p => p.kind !== 'library').slice(0, 6))
const canGo = computed(() => props.fromLine != null)
const showMessage = computed(() => !!parsed.value.message && parsed.value.message !== parsed.value.title)

function editorLine (line: number) {
  return (props.fromLine ?? 1) + line - 1
}
/** A line of the failing code that the server names after another step than the one `ownName` is. */
function elsewhere (p: ErrorPlace) {
  return !!p.step && p.step !== props.ownStep
}
/** The line of a place in the failing code: in the editor's lines for the script run from it, else in its step's code. */
function ownLine (p: ErrorPlace) {
  return canGo.value && !elsewhere(p) ? editorLine(p.line) : p.line
}
function ownLabel (p: ErrorPlace) {
  return `Line ${ownLine(p)}${p.column ? `, column ${p.column}` : ''} of ${elsewhere(p) ? p.step : props.ownName}`
}
function whereText (kind: PlaceKind) {
  if (kind !== 'other') return ''
  // (the server names every step's code: one it doesn't name is no step's)
  return props.frames?.length ? 'another script' : 'an earlier script or a step'
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
.eng-err__place.is-own,
.eng-err__place.is-in { border-color: color-mix(in srgb, var(--danger) 40%, var(--border)); color: var(--danger-soft-text); }
.eng-err__place.is-link { cursor: pointer; font-weight: 600; }
.eng-err__place.is-link:hover { background: var(--danger-soft); }
.eng-err__where { color: var(--faint); }
.eng-err__raw { font-size: var(--text-xs); color: var(--muted); }
.eng-err__raw summary { cursor: pointer; width: max-content; }
.eng-err__raw summary:hover { color: var(--text); }
.eng-err__raw pre { margin: 6px 0 0; padding: 8px 10px; border-radius: var(--radius-xs); background: var(--surface-sunken); border: 1px solid var(--border); white-space: pre-wrap; word-break: break-word; max-height: 220px; overflow: auto; }
</style>
