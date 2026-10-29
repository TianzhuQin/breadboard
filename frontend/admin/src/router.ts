import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { toast, toasts } from './ui/toast'

/**
 * Hash routes (/#/network …): the server serves the console at "/" only.
 * Every page works on the experiment the server has selected (see the experiment switcher).
 * `meta.title` is the page heading, `meta.group` its sidebar section; `meta.needsExperiment`
 * pages show a prompt to pick one when none is selected. `meta.usesEngine` pages act on what the
 * shared engine runs: they say so when that is another experiment (shell/EngineNotice).
 */
export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/session' },
  { path: '/session', name: 'session', component: () => import('./pages/session/SessionPage.vue'), meta: { title: 'Live session', needsExperiment: true, usesEngine: true } },
  { path: '/network', name: 'network', component: () => import('./pages/network/NetworkPage.vue'), meta: { title: 'Network', needsExperiment: true, fullBleed: true, usesEngine: true } },
  { path: '/players', name: 'players', component: () => import('./pages/players/PlayersPage.vue'), meta: { title: 'Players', needsExperiment: true, usesEngine: true } },
  { path: '/console', name: 'console', component: () => import('./pages/console/ConsolePage.vue'), meta: { title: 'Script console', needsExperiment: true, fullBleed: true, usesEngine: true } },
  { path: '/steps', name: 'steps', component: () => import('./pages/steps/StepsPage.vue'), meta: { title: 'Steps', needsExperiment: true, fullBleed: true } },
  { path: '/content', name: 'content', component: () => import('./pages/content/ContentPage.vue'), meta: { title: 'Content', needsExperiment: true, fullBleed: true } },
  { path: '/parameters', name: 'parameters', component: () => import('./pages/parameters/ParametersPage.vue'), meta: { title: 'Parameters', needsExperiment: true } },
  { path: '/page', name: 'page', component: () => import('./pages/participant-page/ParticipantPagePage.vue'), meta: { title: 'Participant page', needsExperiment: true, fullBleed: true } },
  { path: '/images', name: 'images', component: () => import('./pages/images/ImagesPage.vue'), meta: { title: 'Images', needsExperiment: true } },
  { path: '/preview', name: 'preview', component: () => import('./pages/preview/PreviewPage.vue'), meta: { title: 'Participant preview', needsExperiment: true, usesEngine: true } },
  { path: '/sessions', name: 'sessions', component: () => import('./pages/sessions/SessionsPage.vue'), meta: { title: 'Sessions & data', needsExperiment: true } },
  { path: '/autopilot', name: 'autopilot', component: () => import('./pages/autopilot/AutopilotPage.vue'), meta: { title: 'Autopilot' } },
  { path: '/recruit', name: 'recruit', component: () => import('./pages/recruit/RecruitPage.vue'), meta: { title: 'Recruitment' } },
  { path: '/experiments', name: 'experiments', component: () => import('./pages/experiments/ExperimentsPage.vue'), meta: { title: 'Experiments' } },
  { path: '/settings', name: 'settings', component: () => import('./pages/settings/SettingsPage.vue'), meta: { title: 'Settings' } },
  { path: '/:pathMatch(.*)*', redirect: '/session' },
]

// (No scrollBehavior: the window never scrolls, pages scroll inside <main>; AppShell resets it.)
export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/** The page being opened, while its code loads. */
let opening: string | null = null
router.beforeEach((to) => { opening = to.fullPath })
router.afterEach((to, _from, failure) => {
  opening = null
  if (failure) return
  const t = to.meta.title as string | undefined
  document.title = t ? `${t} · Breadboard` : 'Breadboard'
})

// ---- after a server update ----------------------------------------------------------------------
// Pages load on first visit, from files whose names change with every build. A console left open
// across an update asks for files that are gone, and the page would just not open.

/** Whether an error is a page's code failing to load (wording differs between browsers). */
export function isStaleBuildError (err: unknown): boolean {
  const text = String((err as any)?.message ?? err ?? '')
  return /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Unable to preload CSS/i.test(text)
}

let staleToast = 0
/**
 * Say so, with a Reload button that opens the page that failed (`path`, when it was a navigation)
 * in the new version. Never reloads by itself: the server may be down for the update right now.
 */
export function onStaleBuild (path: string | null, reload: () => void = () => window.location.reload()) {
  // the same failure is reported twice (by Vite, then by the router): one message is enough
  if (toasts.some(t => t.id === staleToast)) return
  staleToast = toast('warning', 'This page could not open', 'Breadboard may have been updated since this console was opened. Reload to get the new version.', {
    timeout: 0,
    action: {
      label: 'Reload',
      run: () => {
        if (path) window.history.replaceState(window.history.state, '', '#' + path)
        reload()
      },
    },
  })
}

router.onError((err, to) => {
  opening = null
  if (isStaleBuildError(err)) onStaleBuild(to.fullPath)
})
if (typeof window !== 'undefined') {
  // (Vite reports every failed import here, a page's own runtime error too: only missing files count)
  window.addEventListener('vite:preloadError', (ev: any) => { if (isStaleBuildError(ev.payload)) onStaleBuild(opening) })
}
