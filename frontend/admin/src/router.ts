import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

/**
 * Hash routes (/#/network …): the server serves the console at "/" only.
 * Every page works on the experiment the server has selected (see the experiment switcher).
 * `meta.title` is the page heading, `meta.group` its sidebar section; `meta.needsExperiment`
 * pages show a prompt to pick one when none is selected.
 */
export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/session' },
  { path: '/session', name: 'session', component: () => import('./pages/session/SessionPage.vue'), meta: { title: 'Live session', needsExperiment: true } },
  { path: '/network', name: 'network', component: () => import('./pages/network/NetworkPage.vue'), meta: { title: 'Network', needsExperiment: true, fullBleed: true } },
  { path: '/players', name: 'players', component: () => import('./pages/players/PlayersPage.vue'), meta: { title: 'Players', needsExperiment: true } },
  { path: '/console', name: 'console', component: () => import('./pages/console/ConsolePage.vue'), meta: { title: 'Script console', needsExperiment: true, fullBleed: true } },
  { path: '/steps', name: 'steps', component: () => import('./pages/steps/StepsPage.vue'), meta: { title: 'Steps', needsExperiment: true, fullBleed: true } },
  { path: '/content', name: 'content', component: () => import('./pages/content/ContentPage.vue'), meta: { title: 'Content', needsExperiment: true, fullBleed: true } },
  { path: '/parameters', name: 'parameters', component: () => import('./pages/parameters/ParametersPage.vue'), meta: { title: 'Parameters', needsExperiment: true } },
  { path: '/page', name: 'page', component: () => import('./pages/participant-page/ParticipantPagePage.vue'), meta: { title: 'Participant page', needsExperiment: true, fullBleed: true } },
  { path: '/images', name: 'images', component: () => import('./pages/images/ImagesPage.vue'), meta: { title: 'Images', needsExperiment: true } },
  { path: '/preview', name: 'preview', component: () => import('./pages/preview/PreviewPage.vue'), meta: { title: 'Participant preview', needsExperiment: true } },
  { path: '/sessions', name: 'sessions', component: () => import('./pages/sessions/SessionsPage.vue'), meta: { title: 'Sessions & data', needsExperiment: true } },
  { path: '/autopilot', name: 'autopilot', component: () => import('./pages/autopilot/AutopilotPage.vue'), meta: { title: 'Autopilot' } },
  { path: '/recruit', name: 'recruit', component: () => import('./pages/recruit/RecruitPage.vue'), meta: { title: 'Recruitment' } },
  { path: '/experiments', name: 'experiments', component: () => import('./pages/experiments/ExperimentsPage.vue'), meta: { title: 'Experiments' } },
  { path: '/settings', name: 'settings', component: () => import('./pages/settings/SettingsPage.vue'), meta: { title: 'Settings' } },
  { path: '/:pathMatch(.*)*', redirect: '/session' },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const t = to.meta.title as string | undefined
  document.title = t ? `${t} · Breadboard` : 'Breadboard'
})
