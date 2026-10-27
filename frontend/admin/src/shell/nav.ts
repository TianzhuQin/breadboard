import type { Component } from 'vue'
import {
  Activity, Share2, Users, SquareTerminal, ListOrdered, FileText, SlidersHorizontal, LayoutTemplate,
  Image, MonitorSmartphone, Bot, Megaphone, Database, FlaskConical, Settings,
} from 'lucide-vue-next'

export interface NavItem { to: string, label: string, icon: Component }
export interface NavGroup { label: string, items: NavItem[] }

export const NAV: NavGroup[] = [
  {
    label: 'Run',
    items: [
      { to: '/session', label: 'Live session', icon: Activity },
      { to: '/network', label: 'Network', icon: Share2 },
      { to: '/players', label: 'Players', icon: Users },
      { to: '/console', label: 'Script console', icon: SquareTerminal },
    ],
  },
  {
    label: 'Build',
    items: [
      { to: '/steps', label: 'Steps', icon: ListOrdered },
      { to: '/content', label: 'Content', icon: FileText },
      { to: '/parameters', label: 'Parameters', icon: SlidersHorizontal },
      { to: '/page', label: 'Participant page', icon: LayoutTemplate },
      { to: '/images', label: 'Images', icon: Image },
      { to: '/preview', label: 'Preview', icon: MonitorSmartphone },
    ],
  },
  {
    label: 'Recruit & data',
    items: [
      { to: '/autopilot', label: 'Autopilot', icon: Bot },
      { to: '/recruit', label: 'Recruitment', icon: Megaphone },
      { to: '/sessions', label: 'Sessions & data', icon: Database },
    ],
  },
]

export const FOOTER_NAV: NavItem[] = [
  { to: '/experiments', label: 'Experiments', icon: FlaskConical },
  { to: '/settings', label: 'Settings', icon: Settings },
]
