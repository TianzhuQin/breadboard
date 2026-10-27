<template>
  <BPage narrow intro="Your account, how the console looks, and where to find the previous console.">
    <!-- Account -->
    <section class="set" aria-labelledby="set-account">
      <div class="set__about">
        <h2 id="set-account" class="set__title">Account</h2>
        <p class="set__desc">The account you are signed in with.</p>
      </div>
      <div class="set__body">
        <div class="set-card set-acct">
          <span class="set-acct__avatar" aria-hidden="true">{{ initial }}</span>
          <div class="set-acct__text">
            <span class="set-acct__label">Signed in as</span>
            <span class="set-acct__email">{{ admin.email || 'Unknown' }}</span>
          </div>
          <BButton :icon="LogOut" :loading="signingOut" @click="signOut">Sign out</BButton>
        </div>
        <p class="set__note">Signing out doesn't stop a running session; participants keep playing.</p>
      </div>
    </section>

    <!-- Password -->
    <section class="set" aria-labelledby="set-password">
      <div class="set__about">
        <h2 id="set-password" class="set__title">Password</h2>
        <p class="set__desc">Use at least {{ PASSWORD_MIN }} characters. You stay signed in after changing it.</p>
      </div>
      <div class="set__body">
        <form class="set-card set-pw" novalidate @submit.prevent="savePassword">
          <!-- lets password managers tie the new password to this account -->
          <input type="text" name="username" autocomplete="username" :value="admin.email" class="sr-only" tabindex="-1" aria-hidden="true" readonly>
          <p v-if="pwError" class="set-pw__error" role="alert">{{ pwError }}</p>
          <BField label="Current password" :error="shownErrors.current">
            <template #default="{ id, describedby }">
              <BInput
                :id="id"
                v-model="pw.current"
                :type="reveal ? 'text' : 'password'"
                autocomplete="current-password"
                :aria-describedby="describedby"
                :aria-invalid="shownErrors.current ? 'true' : undefined"
              />
            </template>
          </BField>
          <div class="set-pw__row">
            <BField label="New password" :error="shownErrors.next" :hint="`At least ${PASSWORD_MIN} characters.`">
              <template #default="{ id, describedby }">
                <BInput
                  :id="id"
                  v-model="pw.next"
                  :type="reveal ? 'text' : 'password'"
                  autocomplete="new-password"
                  :aria-describedby="describedby"
                  :aria-invalid="shownErrors.next ? 'true' : undefined"
                />
              </template>
            </BField>
            <BField label="Repeat the new password" :error="shownErrors.confirm">
              <template #default="{ id, describedby }">
                <BInput
                  :id="id"
                  v-model="pw.confirm"
                  :type="reveal ? 'text' : 'password'"
                  autocomplete="new-password"
                  :aria-describedby="describedby"
                  :aria-invalid="shownErrors.confirm ? 'true' : undefined"
                />
              </template>
            </BField>
          </div>
          <div class="set-pw__foot">
            <BCheckbox v-model="reveal">Show passwords</BCheckbox>
            <span class="set-pw__spacer" />
            <BButton v-if="pwDirty" variant="ghost" :disabled="pwBusy" @click="resetPassword">Clear</BButton>
            <BButton type="submit" variant="primary" :icon="KeyRound" :loading="pwBusy">Change password</BButton>
          </div>
        </form>
      </div>
    </section>

    <!-- Appearance -->
    <section class="set" aria-labelledby="set-theme">
      <div class="set__about">
        <h2 id="set-theme" class="set__title">Appearance</h2>
        <p class="set__desc">Saved in this browser only.</p>
      </div>
      <div class="set__body">
        <div class="set-theme" role="radiogroup" aria-labelledby="set-theme">
          <label v-for="t in themes" :key="t.value" class="set-theme__opt" :class="{ 'is-on': theme === t.value }">
            <input
              type="radio"
              name="set-theme"
              class="set-theme__radio"
              :value="t.value"
              :checked="theme === t.value"
              @change="setTheme(t.value)"
            >
            <span class="set-theme__preview" :class="`is-${t.value}`" aria-hidden="true">
              <span class="set-theme__win is-light"><i /><b /><b /><em /></span>
              <span class="set-theme__win is-dark"><i /><b /><b /><em /></span>
            </span>
            <span class="set-theme__label">
              <component :is="t.icon" :size="15" aria-hidden="true" />
              {{ t.label }}
            </span>
            <span class="set-theme__hint">{{ t.hint }}</span>
          </label>
        </div>
      </div>
    </section>

    <!-- Previous console -->
    <section class="set" aria-labelledby="set-legacy">
      <div class="set__about">
        <h2 id="set-legacy" class="set__title">Previous console</h2>
        <p class="set__desc">Still here while you move over.</p>
      </div>
      <div class="set__body">
        <div class="set-card set-legacy">
          <span class="set-legacy__icon" aria-hidden="true"><History :size="18" /></span>
          <div class="set-legacy__text">
            <p>The previous console works with the same experiments and sessions.</p>
            <p class="set__note">You can keep both open at the same time.</p>
          </div>
          <BButton href="/legacy-admin" :icon-right="ArrowRight">Open it</BButton>
        </div>
      </div>
    </section>

    <!-- About -->
    <section class="set" aria-labelledby="set-about">
      <div class="set__about">
        <h2 id="set-about" class="set__title">About</h2>
        <p class="set__desc">Handy when asking for help.</p>
      </div>
      <div class="set__body">
        <dl class="set-card set-facts">
          <div class="set-facts__row">
            <dt>Console</dt>
            <dd>Breadboard console {{ version }}</dd>
          </div>
          <div class="set-facts__row">
            <dt>Server</dt>
            <dd class="mono">{{ host }}</dd>
          </div>
          <div class="set-facts__row">
            <dt>Connection</dt>
            <dd>
              <BBadge :tone="connectionState === 'open' ? 'success' : 'warning'" dot>{{ connectionText }}</BBadge>
            </dd>
          </div>
          <div class="set-facts__row">
            <dt>Experiments</dt>
            <dd>{{ experiments.length }}</dd>
          </div>
          <div class="set-facts__row">
            <dt>Help</dt>
            <dd>
              <a href="https://github.com/human-nature-lab/breadboard/discussions" target="_blank" rel="noopener noreferrer">
                Breadboard discussions on GitHub <ArrowUpRight :size="13" aria-hidden="true" />
              </a>
            </dd>
          </div>
        </dl>
      </div>
    </section>
  </BPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ArrowRight, ArrowUpRight, History, KeyRound, LogOut, Monitor, Moon, Sun } from 'lucide-vue-next'
import { BBadge, BButton, BCheckbox, BField, BInput, BPage, notify } from '@/ui'
import { admin, adoptUid, connectionState, experiments, logout } from '@/store/admin'
import { confirmSignOut } from '@/shell/unsaved'
import { theme, setTheme, type ThemeChoice } from '@/lib/theme'
import { version } from '../../../package.json'
import { useUnsavedGuard } from '../autopilot/useUnsavedGuard'
import { describeError } from '../experiments/experiments'
import {
  PASSWORD_MIN, changePassword, friendlyPasswordError, validatePasswordChange, type PasswordChange, type PasswordErrors,
} from './settings'

const initial = computed(() => (admin.email || '?').trim().charAt(0).toUpperCase())
const host = window.location.host
const connectionText = computed(() => ({
  open: 'Connected',
  connecting: 'Connecting…',
  retrying: 'Reconnecting…',
  idle: 'Not connected',
  closed: 'Not connected',
}[connectionState.value] || connectionState.value))

// ---- account ----------------------------------------------------------------------------------

const signingOut = ref(false)
async function signOut () {
  if (signingOut.value) return
  // unsaved work on other pages (step drafts, content…) would be lost
  if (!await confirmSignOut()) return
  signingOut.value = true
  try {
    await logout()
  } finally {
    signingOut.value = false
  }
}

// ---- password ---------------------------------------------------------------------------------

const pw = reactive<PasswordChange>({ current: '', next: '', confirm: '' })
const reveal = ref(false)
const pwBusy = ref(false)
const pwError = ref('')
const tried = ref(false)
const serverErrors = ref<PasswordErrors>({})
const pwDirty = computed(() => !!(pw.current || pw.next || pw.confirm))
const localErrors = computed(() => validatePasswordChange(pw))
// show the checks after the first try, and the server's answer until the field is edited
const shownErrors = computed<PasswordErrors>(() => ({ ...(tried.value ? localErrors.value : {}), ...serverErrors.value }))
watch(() => pw.current, () => { delete serverErrors.value.current })
watch(() => [pw.next, pw.confirm], () => {
  delete serverErrors.value.next
  delete serverErrors.value.confirm
})

// Only a typed new password counts as unsaved: browsers may fill in the current one by themselves.
const pwTyped = computed(() => !!(pw.next || pw.confirm))
useUnsavedGuard(pwTyped, { what: 'your password', save: () => { if (pwDirty.value) savePassword() } })

function resetPassword () {
  pw.current = ''
  pw.next = ''
  pw.confirm = ''
  tried.value = false
  pwError.value = ''
  serverErrors.value = {}
}

async function savePassword () {
  if (pwBusy.value) return
  tried.value = true
  pwError.value = ''
  serverErrors.value = {}
  if (Object.keys(localErrors.value).length) return
  pwBusy.value = true
  try {
    const res = await changePassword(admin.email, { ...pw })
    if (res.success) {
      // the account has a new uid now; without it every later request would be refused
      if (res.uid) adoptUid(res.uid)
      resetPassword()
      notify.success('Password changed', 'Use the new password the next time you sign in.')
    } else {
      const f = friendlyPasswordError(res.error)
      if (f.field) serverErrors.value = { [f.field]: f.text }
      else pwError.value = f.text
    }
  } catch (e) {
    pwError.value = `The password was not changed. ${describeError(e)}`
  } finally {
    pwBusy.value = false
  }
}

// ---- appearance -------------------------------------------------------------------------------

const themes: { value: ThemeChoice, label: string, hint: string, icon: any }[] = [
  { value: 'system', label: 'System', hint: 'Follows your computer', icon: Monitor },
  { value: 'light', label: 'Light', hint: 'Always light', icon: Sun },
  { value: 'dark', label: 'Dark', hint: 'Always dark', icon: Moon },
]
</script>

<style>
.set { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 12px 32px; padding-bottom: 24px; border-bottom: 1px solid var(--border); }
.set:last-child { border-bottom: 0; }
.set__title { font-size: var(--text-md); font-weight: 650; }
.set__desc { margin-top: 3px; font-size: var(--text-sm); color: var(--muted); }
.set__body { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.set__note { font-size: var(--text-xs); color: var(--muted); line-height: 1.5; }
@media (max-width: 760px) {
  .set { grid-template-columns: minmax(0, 1fr); }
}

.set-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 16px 18px;
  min-width: 0;
}

.set-acct { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.set-acct__avatar {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--accent);
  color: var(--on-accent);
  font-weight: 700;
  font-size: var(--text-lg);
  flex: none;
}
.set-acct__text { flex: 1; min-width: 160px; display: flex; flex-direction: column; }
.set-acct__label { font-size: var(--text-xs); color: var(--muted); }
.set-acct__email { font-weight: 600; overflow-wrap: anywhere; }

.set-pw { display: flex; flex-direction: column; gap: 16px; }
.set-pw__row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
@media (max-width: 560px) { .set-pw__row { grid-template-columns: minmax(0, 1fr); } }
.set-pw__error { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
.set-pw__foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding-top: 4px; }
.set-pw__spacer { flex: 1; }

.set-theme { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 480px) { .set-theme { grid-template-columns: minmax(0, 1fr); } }
.set-theme__opt {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 10px 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--surface);
  cursor: pointer;
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.set-theme__opt:hover { border-color: var(--faint); }
.set-theme__opt.is-on { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
.set-theme__opt:focus-within { box-shadow: 0 0 0 3px var(--accent-ring); }
.set-theme__radio { position: absolute; opacity: 0; width: 1px; height: 1px; }
.set-theme__label { display: flex; align-items: center; gap: 6px; margin-top: 6px; font-weight: 600; font-size: var(--text-sm); }
.set-theme__hint { font-size: var(--text-xs); color: var(--muted); }

/* Small pictures of each theme. They show that theme's colours whatever the console uses now. */
.set-theme__preview {
  position: relative;
  display: block;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border);
}
.set-theme__win { position: absolute; inset: 0; padding: 8px; display: flex; flex-direction: column; gap: 5px; }
.set-theme__win.is-light { background: #f6f7fb; }
.set-theme__win.is-dark { background: #0b0d14; }
.set-theme__win i { display: block; width: 40%; height: 7px; border-radius: 3px; }
.set-theme__win b { display: block; height: 9px; border-radius: 3px; }
.set-theme__win em { display: block; width: 28%; height: 9px; border-radius: 3px; margin-top: auto; }
.set-theme__win.is-light i { background: #d3d8e4; }
.set-theme__win.is-light b { background: #ffffff; box-shadow: 0 0 0 1px #e5e8f0; }
.set-theme__win.is-light em { background: #4f46e5; }
.set-theme__win.is-dark i { background: #2f3850; }
.set-theme__win.is-dark b { background: #181d2a; box-shadow: 0 0 0 1px #232a3a; }
.set-theme__win.is-dark em { background: #818cf8; }
.set-theme__preview.is-light .set-theme__win.is-dark { display: none; }
.set-theme__preview.is-dark .set-theme__win.is-light { display: none; }
.set-theme__preview.is-system .set-theme__win.is-dark { clip-path: inset(0 0 0 50%); }

.set-legacy { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.set-legacy__icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 10px; background: var(--bg-subtle); color: var(--muted); flex: none; }
.set-legacy__text { flex: 1; min-width: 200px; display: flex; flex-direction: column; gap: 4px; }

.set-facts { margin: 0; padding: 4px 18px; }
.set-facts__row { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.set-facts__row:last-child { border-bottom: 0; }
.set-facts dt { font-size: var(--text-sm); color: var(--muted); }
.set-facts dd { margin: 0; font-size: var(--text-sm); overflow-wrap: anywhere; }
.set-facts dd a { display: inline-flex; align-items: center; gap: 4px; }
</style>
