<template>
  <BPage
    class="ip"
    intro="Pictures for your content, steps and participant page. Upload them here, then copy an image's link into your text."
  >
    <FileModeNotice v-if="readOnly" what="Images" />

    <input
      ref="picker"
      type="file"
      class="sr-only"
      :accept="ACCEPT"
      multiple
      tabindex="-1"
      aria-hidden="true"
      @change="onPicked"
    >

    <!-- what is being uploaded right now, and what failed (with the reason) -->
    <section v-if="uploads.length" class="ip-uploads" aria-labelledby="ip-uploads-title">
      <header class="ip-uploads__head">
        <h2 id="ip-uploads-title" class="ip-uploads__title">Uploads</h2>
        <span class="ip-uploads__sum" role="status">{{ uploadSummary }}</span>
        <BButton v-if="uploads.some(u => u.state === 'done' || u.state === 'failed')" size="sm" variant="ghost" @click="clearFinished">Clear</BButton>
      </header>
      <ul class="ip-uploads__list">
        <li v-for="u in uploads" :key="u.key" class="ip-up" :class="`is-${u.state}`">
          <span class="ip-up__icon" aria-hidden="true">
            <BSpinner v-if="u.state === 'uploading'" :size="15" label="" />
            <CheckCircle2 v-else-if="u.state === 'done'" :size="16" />
            <AlertCircle v-else-if="u.state === 'failed'" :size="16" />
            <Clock v-else :size="16" />
          </span>
          <div class="ip-up__text">
            <p class="ip-up__name">{{ u.name }} <span class="ip-up__size">{{ formatBytes(u.size) }}</span></p>
            <p class="ip-up__status">
              <template v-if="u.state === 'queued'">Waiting…</template>
              <template v-else-if="u.state === 'uploading'">Uploading…</template>
              <template v-else-if="u.state === 'done'">
                {{ u.savedAs && u.savedAs !== u.name ? `Saved as ${u.savedAs}, because ${u.name} already exists` : 'Uploaded' }}{{ u.experimentId !== admin.experiment?.id ? ` to ${u.experimentName}` : '' }}
              </template>
              <template v-else>{{ u.error }}</template>
            </p>
            <p v-if="u.state !== 'failed' && u.size > LARGE_FILE" class="ip-up__warn">Large file: the participant page will load it slowly. A smaller version (under 500 KB) works better.</p>
          </div>
          <div v-if="u.state === 'failed'" class="ip-up__actions">
            <BButton v-if="u.retryable" size="sm" :icon="RotateCw" @click="retry(u)">Try again</BButton>
            <BButton size="sm" variant="ghost" :icon="X" :aria-label="`Dismiss ${u.name}`" @click="dismiss(u)" />
          </div>
        </li>
      </ul>
    </section>

    <!-- the drop target; large and teaching when there is nothing yet -->
    <div
      v-if="!readOnly"
      class="ip-drop"
      :class="{ 'is-empty': !list.length }"
    >
      <span class="ip-drop__icon" aria-hidden="true"><ImagePlus :size="list.length ? 18 : 26" /></span>
      <div class="ip-drop__text">
        <p class="ip-drop__title">{{ list.length ? 'Add more images' : 'No images yet' }}</p>
        <p class="ip-drop__hint">
          Drag images here, paste one from the clipboard (<kbd>{{ mod }}</kbd><kbd>V</kbd>), or choose files.
          <template v-if="!list.length"> JPG, PNG, GIF, SVG and WebP all work; a file with a name that already exists is saved with a number added.</template>
        </p>
      </div>
      <BButton :variant="list.length ? 'secondary' : 'primary'" :icon="Upload" @click="choose">Choose images</BButton>
    </div>

    <BPanel v-if="readOnly && !list.length">
      <BEmpty :icon="ImageIcon" title="No images" text="This experiment's Images folder is empty. Copy pictures into it on the server and they appear here." />
    </BPanel>

    <section v-if="list.length" class="ip-gallery" aria-labelledby="ip-gallery-title">
      <header class="ip-gallery__head">
        <h2 id="ip-gallery-title" class="ip-gallery__title">
          {{ list.length }} {{ list.length === 1 ? 'image' : 'images' }}
          <span v-if="query && visible.length !== list.length" class="ip-gallery__match">· {{ visible.length }} shown</span>
        </h2>
        <BInput
          v-if="list.length > 6"
          v-model="query"
          class="ip-gallery__filter"
          size="sm"
          :icon="Search"
          type="search"
          placeholder="Filter by name"
          aria-label="Filter images by name"
        />
      </header>

      <ul v-if="visible.length" class="ip-grid">
        <ImageCard
          v-for="img in visible"
          :key="keyOf(img)"
          :image="img"
          :experiment-id="experimentId"
          :read-only="readOnly"
          :fresh="img.id != null && freshIds.has(img.id)"
          :busy="img.id != null && deletingId === img.id"
          @preview="previewKey = keyOf(img)"
          @remove="remove(img)"
        />
      </ul>
      <BEmpty v-else compact :icon="Search" title="No image matches" :text="`Nothing is called “${query}”.`">
        <template #actions><BButton size="sm" @click="query = ''">Clear the filter</BButton></template>
      </BEmpty>
    </section>

    <div v-if="dragOver" class="ip-overlay" aria-hidden="true">
      <div class="ip-overlay__box">
        <UploadCloud :size="30" />
        <p>{{ readOnly ? 'This experiment is in file mode: add images to its Images folder instead' : `Drop to upload to ${admin.experiment?.name}` }}</p>
      </div>
    </div>

    <ImageLightbox
      :image="previewImage"
      :experiment-id="experimentId"
      :index="previewIndex"
      :count="visible.length"
      :read-only="readOnly"
      :busy="previewImage != null && previewImage.id != null && deletingId === previewImage.id"
      @close="previewKey = null"
      @step="stepPreview"
      @remove="previewImage && remove(previewImage)"
    />
  </BPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  AlertCircle, CheckCircle2, Clock, Image as ImageIcon, ImagePlus, RotateCw, Search, Upload, UploadCloud, X,
} from 'lucide-vue-next'
import { BButton, BEmpty, BInput, BPage, BPanel, BSpinner, confirm, notify } from '@/ui'
import { http, HttpError } from '@/lib/http'
import { admin, actions } from '@/store/admin'
import type { Image } from '@/store/types'
import FileModeNotice from '../parameters/FileModeNotice.vue'
import ImageCard from './ImageCard.vue'
import ImageLightbox from './ImageLightbox.vue'
import { ACCEPT, LARGE_FILE, checkUploadResponse, fileProblem, findImageUsage, formatBytes, pastedName } from './images'

const readOnly = computed(() => !!admin.experiment?.fileMode)
const experimentId = computed(() => admin.experiment?.id ?? 0)
const mod = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent) ? '⌘' : 'Ctrl'

// ---- the list: what the server last pushed, plus our changes until a push confirms them ----

const added = ref<Image[]>([])
const removedIds = ref(new Set<number>())
const serverList = computed<Image[]>(() => admin.experiment?.images || [])
const list = computed<Image[]>(() => {
  const base = serverList.value.filter(i => i.id == null || !removedIds.value.has(i.id))
  const extra = added.value.filter(a => !serverList.value.some(i => i.id === a.id))
  return [...base, ...extra]
})
watch(serverList, (now) => {
  added.value = added.value.filter(a => !now.some(i => i.id === a.id))
  removedIds.value = new Set([...removedIds.value].filter(id => now.some(i => i.id === id)))
})
watch(experimentId, () => {
  added.value = []
  removedIds.value = new Set()
  previewKey.value = null
  query.value = ''
})

const keyOf = (img: Image) => img.id != null ? `id-${img.id}` : `name-${img.fileName}`
const query = ref('')
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
const visible = computed(() => {
  const q = query.value.trim().toLowerCase()
  return [...list.value]
    .filter(i => !q || i.fileName.toLowerCase().includes(q))
    .sort((a, b) => collator.compare(a.fileName, b.fileName))
})

// ---- preview ----

const previewKey = ref<string | null>(null)
const previewIndex = computed(() => visible.value.findIndex(i => keyOf(i) === previewKey.value))
const previewImage = computed(() => previewIndex.value >= 0 ? visible.value[previewIndex.value] : null)
function stepPreview (delta: number) {
  const n = visible.value.length
  if (!n || previewIndex.value < 0) return
  previewKey.value = keyOf(visible.value[(previewIndex.value + delta + n) % n])
}

// ---- upload ----

interface UploadItem {
  key: number
  name: string
  size: number
  state: 'queued' | 'uploading' | 'done' | 'failed'
  error: string
  savedAs: string
  retryable: boolean
  experimentId: number
  experimentName: string
}
const uploads = reactive<UploadItem[]>([])
const files = new Map<number, File>()
let seq = 0
const freshIds = ref(new Set<number>())
const picker = ref<HTMLInputElement>()

const uploadSummary = computed(() => {
  const active = uploads.filter(u => u.state === 'queued' || u.state === 'uploading').length
  const failed = uploads.filter(u => u.state === 'failed').length
  const done = uploads.filter(u => u.state === 'done').length
  if (active) return `Uploading ${done + 1} of ${done + active}…`
  if (failed) return `${failed} couldn’t be uploaded`
  return done === 1 ? '1 uploaded' : `${done} uploaded`
})

function choose () {
  picker.value?.click()
}
function onPicked (e: Event) {
  const input = e.target as HTMLInputElement
  addFiles(Array.from(input.files || []))
  input.value = '' // so the same file can be picked again
}

function addFiles (list: File[]) {
  const exp = admin.experiment
  if (!exp || !list.length) return
  if (exp.fileMode) {
    notify.info('This experiment is in file mode', 'Copy images into its Images folder on the server; they show up here by themselves.')
    return
  }
  for (const f of list) {
    const problem = fileProblem(f)
    const key = ++seq
    files.set(key, f)
    uploads.push({
      key,
      name: f.name,
      size: f.size,
      state: problem ? 'failed' : 'queued',
      error: problem || '',
      savedAs: '',
      retryable: false,
      experimentId: exp.id,
      experimentName: exp.name,
    })
  }
  pump()
}

function describe (e: unknown): string {
  if (e instanceof HttpError) {
    if (e.status === 413) return 'The file is too large for the server to accept. Make it smaller and try again.'
    if (e.status >= 500) return `The server had a problem saving it (${e.status}: ${e.message}).`
    return e.message ? (/[.!?]$/.test(e.message.trim()) ? e.message.trim() : `${e.message.trim()}.`) : `The server refused it (${e.status}).`
  }
  return e instanceof Error ? `Couldn’t reach the server: ${e.message}` : 'Something went wrong.'
}

// One at a time: the server picks "-2", "-3" names by looking at what is already saved.
let pumping = false
async function pump () {
  if (pumping) return
  pumping = true
  const batch: UploadItem[] = []
  try {
    for (;;) {
      const item = uploads.find(u => u.state === 'queued')
      if (!item) break
      item.state = 'uploading'
      const file = files.get(item.key)
      if (!file) { item.state = 'failed'; item.error = 'The file is no longer available. Choose it again.'; continue }
      const form = new FormData()
      form.append('picture[file]', file, file.name)
      form.append('experimentId', String(item.experimentId))
      try {
        const res = await http.post<unknown>('/images/upload', form)
        const check = checkUploadResponse(res)
        if (check.ok) {
          item.state = 'done'
          item.savedAs = check.image.fileName
          files.delete(item.key)
          if (item.experimentId === admin.experiment?.id) {
            added.value = [...added.value, { id: check.image.id, fileName: check.image.fileName }]
            markFresh(check.image.id)
          }
        } else {
          item.state = 'failed'
          item.error = check.reason
          item.retryable = true
        }
      } catch (e) {
        item.state = 'failed'
        item.error = describe(e)
        item.retryable = !(e instanceof HttpError && e.status === 413)
      }
      batch.push(item)
    }
  } finally {
    pumping = false
  }
  report(batch)
}

function report (batch: UploadItem[]) {
  const done = batch.filter(u => u.state === 'done')
  const failed = batch.filter(u => u.state === 'failed')
  if (done.length) {
    const renamed = done.filter(u => u.savedAs && u.savedAs !== u.name)
    const detail = renamed.length === 1
      ? `${renamed[0].name} already existed, so it was saved as ${renamed[0].savedAs}.`
      : renamed.length > 1 ? `${renamed.length} had names that already existed and were saved with a number added.` : undefined
    notify.success(done.length === 1 ? `Uploaded ${done[0].savedAs || done[0].name}` : `Uploaded ${done.length} images`, detail)
    actions.refresh().then((m) => {
      if (!m) notify.warning('The list may be out of date', 'The server didn’t send the updated experiment. Reload the page if an image is missing.')
    })
    // finished rows go away by themselves; failures stay until dismissed
    setTimeout(() => {
      for (const u of done) {
        const i = uploads.indexOf(u)
        if (i >= 0 && u.state === 'done') uploads.splice(i, 1)
      }
    }, 6000)
  }
  if (failed.length) {
    notify.error(failed.length === 1 ? `Couldn’t upload ${failed[0].name}` : `${failed.length} images couldn’t be uploaded`, failed.length === 1 ? failed[0].error : 'The list above says why for each one.')
  }
}

function markFresh (id: number) {
  freshIds.value = new Set([...freshIds.value, id])
  setTimeout(() => {
    const next = new Set(freshIds.value)
    next.delete(id)
    freshIds.value = next
  }, 3000)
}

function retry (u: UploadItem) {
  u.state = 'queued'
  u.error = ''
  pump()
}
function dismiss (u: UploadItem) {
  const i = uploads.indexOf(u)
  if (i >= 0) uploads.splice(i, 1)
  files.delete(u.key)
}
function clearFinished () {
  for (const u of uploads.filter(x => x.state === 'done' || x.state === 'failed')) dismiss(u)
}

// ---- drag and drop, paste ----

// The whole window is the drop target while this page is open (a file dropped anywhere else
// would make the browser open it and leave the console).
const dragOver = ref(false)
let dragDepth = 0
const hasFiles = (e: DragEvent) => !!e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files')
function onDragEnter (e: DragEvent) {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth++
  dragOver.value = true
}
function onDragOver (e: DragEvent) {
  if (!hasFiles(e)) return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}
function onDragLeave (e: DragEvent) {
  if (!hasFiles(e)) return
  dragDepth = Math.max(0, dragDepth - 1)
  if (!dragDepth) dragOver.value = false
}
function onDrop (e: DragEvent) {
  if (!hasFiles(e)) return
  e.preventDefault()
  dragDepth = 0
  dragOver.value = false
  addFiles(Array.from(e.dataTransfer?.files || []))
}

function onPaste (e: ClipboardEvent) {
  const items = Array.from(e.clipboardData?.items || []).filter(i => i.kind === 'file' && i.type.startsWith('image/'))
  if (!items.length) return
  e.preventDefault()
  const pasted = items.map(i => i.getAsFile()).filter((f): f is File => !!f).map((f) => {
    // screenshots arrive as "image.png"; give them a name that says when
    if (!f.name || /^image\.\w+$/i.test(f.name)) return new File([f], pastedName(f.type), { type: f.type })
    return f
  })
  addFiles(pasted)
}

const windowListeners: [string, (e: any) => void][] = [
  ['dragenter', onDragEnter], ['dragover', onDragOver], ['dragleave', onDragLeave], ['drop', onDrop], ['paste', onPaste],
]
onMounted(() => { for (const [ev, fn] of windowListeners) window.addEventListener(ev, fn) })
onBeforeUnmount(() => { for (const [ev, fn] of windowListeners) window.removeEventListener(ev, fn) })

// ---- delete ----

const deletingId = ref<number | null>(null)
async function remove (img: Image) {
  const exp = admin.experiment
  if (!exp || img.id == null || exp.fileMode) return
  const usedIn = findImageUsage(exp, img)
  const lines = ['The file is removed from the server; this can’t be undone.']
  if (usedIn.length) lines.push(`It is used in ${usedIn.join(', ')}. Participants will see a broken image there until you change it.`)
  else lines.push('Pages that still link to it will show a broken image. (The participant page’s HTML isn’t checked.)')
  // The confirm would open behind the preview (both dialogs sit at the same level), so step out
  // of the preview while asking and come back to it afterwards.
  const fromPreview = previewImage.value?.id === img.id
  const n = visible.value.length
  const nextKey = fromPreview && n > 1 ? keyOf(visible.value[(previewIndex.value + 1) % n]) : null
  if (fromPreview) previewKey.value = null
  const ok = await confirm({ title: `Delete “${img.fileName}”?`, message: lines.join('\n\n'), confirmLabel: 'Delete image', danger: true })
  if (!ok) {
    if (fromPreview) previewKey.value = keyOf(img)
    return
  }
  const id = img.id
  deletingId.value = id
  try {
    await http.del(`/images/${id}`)
    removedIds.value = new Set([...removedIds.value, id])
    if (nextKey) previewKey.value = nextKey
    notify.success(`Deleted ${img.fileName}`)
    actions.refresh()
  } catch (e) {
    notify.error(`Couldn’t delete ${img.fileName}`, describe(e))
    if (fromPreview) previewKey.value = keyOf(img)
  } finally {
    deletingId.value = null
  }
}
</script>

<style>
.ip { position: relative; }

.ip-drop {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1.5px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--surface);
}
.ip-drop.is-empty { flex-direction: column; text-align: center; gap: 10px; padding: 48px 24px; }
.ip-drop__icon { display: grid; place-items: center; flex: none; width: 36px; height: 36px; border-radius: 10px; background: var(--accent-soft); color: var(--accent); }
.ip-drop.is-empty .ip-drop__icon { width: 52px; height: 52px; border-radius: 14px; margin-bottom: 4px; }
.ip-drop__text { flex: 1; min-width: 0; }
.ip-drop__title { font-weight: 600; color: var(--text); }
.ip-drop.is-empty .ip-drop__title { font-size: var(--text-lg); font-weight: 650; }
.ip-drop__hint { font-size: var(--text-sm); color: var(--muted); }
.ip-drop.is-empty .ip-drop__hint { max-width: 460px; margin: 4px auto 6px; font-size: var(--text-md); }
.ip-drop kbd + kbd { margin-left: 2px; }

.ip-uploads { border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--surface); box-shadow: var(--shadow-sm); }
.ip-uploads__head { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--border); }
.ip-uploads__title { font-size: var(--text-md); font-weight: 650; }
.ip-uploads__sum { flex: 1; font-size: var(--text-sm); color: var(--muted); }
.ip-uploads__list { list-style: none; margin: 0; padding: 4px 0; max-height: 280px; overflow: auto; }
.ip-up { display: flex; align-items: flex-start; gap: 10px; padding: 8px 14px; }
.ip-up__icon { display: grid; place-items: center; flex: none; width: 18px; height: 20px; color: var(--faint); }
.ip-up.is-done .ip-up__icon { color: var(--success); }
.ip-up.is-failed .ip-up__icon { color: var(--danger); }
.ip-up__text { flex: 1; min-width: 0; }
.ip-up__name { font-size: var(--text-sm); font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
.ip-up__size { margin-left: 4px; font-weight: 400; color: var(--faint); font-size: var(--text-xs); }
.ip-up__status { font-size: var(--text-xs); color: var(--muted); }
.ip-up.is-failed .ip-up__status { color: var(--danger); }
.ip-up__warn { font-size: var(--text-xs); color: var(--warning-soft-text); }
.ip-up__actions { display: flex; gap: 4px; flex: none; }

.ip-gallery { display: flex; flex-direction: column; gap: 12px; }
.ip-gallery__head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.ip-gallery__title { flex: 1; font-size: var(--text-md); font-weight: 650; }
.ip-gallery__match { font-weight: 400; color: var(--muted); }
.ip-gallery__filter { width: 240px; max-width: 100%; }
.ip-grid { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(176px, 1fr)); gap: 14px; }

.ip-overlay {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--overlay);
  pointer-events: none;
}
.ip-overlay__box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  max-width: 420px;
  padding: 40px 56px;
  border: 2px dashed var(--accent);
  border-radius: var(--radius-lg);
  background: var(--surface-raised);
  color: var(--accent);
  font-weight: 600;
  text-align: center;
  box-shadow: var(--shadow-lg);
}
.ip-overlay__box p { color: var(--text); }
@media (max-width: 640px) {
  .ip-drop:not(.is-empty) { flex-wrap: wrap; }
  .ip-drop:not(.is-empty) .b-btn { width: 100%; }
  .ip-grid { grid-template-columns: repeat(auto-fill, minmax(148px, 1fr)); gap: 10px; }
  .ip-gallery__filter { width: 100%; }
}
</style>
