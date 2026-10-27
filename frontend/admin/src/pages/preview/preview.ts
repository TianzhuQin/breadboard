import { reactive } from 'vue'

/**
 * Helpers for the Preview page: who a player is (test, AI, connected), which players a quick pick
 * chooses, the participant page's address, and how big a scaled preview is. Fetching the preview
 * token that the address carries is in previewLink.ts.
 * Kept free of components so they can be tested on their own (test/preview.test.ts).
 */

export interface PlayerLike { id: string, data: Record<string, any> }

/** A flag set by a script: true, "true", 1, "yes" … but not false, "false", 0 or "". */
export function truthyFlag (v: unknown): boolean {
  if (v === null || v === undefined || v === false) return false
  if (typeof v === 'number') return v !== 0 && !isNaN(v)
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase()
    return s !== '' && s !== 'false' && s !== '0' && s !== 'no' && s !== 'off' && s !== 'null'
  }
  return true
}

/** Marked as a test player by the experiment (player.testmode, as in the old Testing panel). */
export const isTestPlayer = (p: PlayerLike) => truthyFlag(p.data?.testmode)
/** A computer seat: created as AI (id starting with "_"), or a person's seat the computer took over (ai = 1). */
export const isAIPlayer = (p: PlayerLike) => p.id.startsWith('_') || truthyFlag(p.data?.ai)
/** The player's own browser is connected right now (_system.connected). */
export const isConnected = (p: PlayerLike) => p.data?._system?.connected === true

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
/** Players in natural id order: p2 before p10. */
export function sortPlayers<T extends PlayerLike> (list: readonly T[]): T[] {
  return [...list].sort((a, b) => collator.compare(a.id, b.id))
}

export function matchesPlayer (p: PlayerLike, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  if (p.id.toLowerCase().includes(q)) return true
  if (q === 'test') return isTestPlayer(p)
  if (q === 'ai') return isAIPlayer(p)
  return false
}

export type QuickPick = 'test' | 'ai' | 'first'
/** The players a quick pick chooses, in natural order. */
export function quickPick<T extends PlayerLike> (players: readonly T[], pick: QuickPick, n = 4): T[] {
  const sorted = sortPlayers(players)
  if (pick === 'test') return sorted.filter(isTestPlayer)
  if (pick === 'ai') return sorted.filter(isAIPlayer)
  return sorted.slice(0, Math.max(0, Math.floor(n)))
}

/**
 * Of the players about to be previewed, those whose own browser is connected: a preview takes
 * over their connection (ScriptBoard.addClient closes the older socket, and their page keeps
 * reconnecting and taking it back), so the researcher is asked first.
 */
export function wouldDisplace<T extends PlayerLike> (adding: readonly T[], alreadyShown: ReadonlySet<string>): T[] {
  return adding.filter(p => !alreadyShown.has(p.id) && isConnected(p))
}

// ---- addresses --------------------------------------------------------------------------------

/** One player of one session, whose page a researcher previews. */
export interface PreviewTarget { experimentId: number, instanceId: number, playerId: string }

/**
 * The live participant page of one player, for a researcher: `preview=1` has the server send it
 * sandboxed (an opaque origin, even in a tab of its own), and the preview token in the fragment
 * (see previewLink.ts) lets it act as that player. The fragment never reaches the server or a
 * Referer; the page reads it and keeps it in memory.
 */
export function previewPageUrl (t: PreviewTarget, token: string, origin = window.location.origin): string {
  return `${origin}/game/${t.experimentId}/${t.instanceId}/${encodeURIComponent(t.playerId)}/connected?preview=1#bbt=${encodeURIComponent(token)}`
}
/** The sign-up page a new participant sees. */
export function joinUrl (experimentId: number, instanceId: number, origin = window.location.origin): string {
  return `${origin}/game/${experimentId}/${instanceId}/login`
}

// ---- geometry ---------------------------------------------------------------------------------

export type DeviceKind = 'desktop' | 'phone'
export const DEVICES: Record<DeviceKind, { width: number, height: number, label: string }> = {
  // the old Testing panel rendered 1280×786; a laptop browser window is about this size
  desktop: { width: 1280, height: 800, label: 'Desktop' },
  phone: { width: 390, height: 844, label: 'Phone' },
}
export type TileSize = 'S' | 'M' | 'L'
/** Tile widths in CSS pixels for each device and size. */
export const TILE_WIDTHS: Record<DeviceKind, Record<TileSize, number>> = {
  desktop: { S: 260, M: 360, L: 540 },
  phone: { S: 170, M: 230, L: 320 },
}
export const MIN_TILE_WIDTH = 140

export interface FrameGeometry {
  /** Outer size of the framed preview. */
  width: number
  height: number
  /** Phone bezel thickness (0 for desktop). */
  bezel: number
  /** Scale of the device-sized page inside. */
  scale: number
  deviceWidth: number
  deviceHeight: number
}

/** A phone's bezel, in the page's own pixels (it scales with the preview). */
const BEZEL: Record<DeviceKind, number> = { desktop: 0, phone: 12 }

/** The framed preview at a given outer width: the page keeps its device size and is scaled down. */
export function frameGeometry (device: DeviceKind, width: number): FrameGeometry {
  const d = DEVICES[device]
  const b = BEZEL[device]
  const w = Math.max(1, Math.round(width))
  const scale = w / (d.width + 2 * b)
  return { width: w, height: Math.round((d.height + 2 * b) * scale), bezel: b * scale, scale, deviceWidth: d.width, deviceHeight: d.height }
}

/** A tile's width: the chosen size, but never wider than the space there is. */
export function tileWidth (device: DeviceKind, size: TileSize, available?: number): number {
  const chosen = TILE_WIDTHS[device][size]
  if (!available || available <= 0) return chosen
  return Math.max(MIN_TILE_WIDTH, Math.min(chosen, Math.floor(available)))
}

/** The outer width at which the page shows at 100 % (its real size). */
export function naturalWidth (device: DeviceKind): number {
  return DEVICES[device].width + 2 * BEZEL[device]
}

/** The widest framed preview that fits a box, never above 100 %. */
export function widthToFit (device: DeviceKind, boxWidth: number, boxHeight: number): number {
  const d = DEVICES[device]
  const b = BEZEL[device]
  const full = naturalWidth(device)
  const byHeight = boxHeight > 0 ? boxHeight * (d.width + 2 * b) / (d.height + 2 * b) : full
  const byWidth = boxWidth > 0 ? boxWidth : full
  return Math.max(MIN_TILE_WIDTH, Math.floor(Math.min(full, byWidth, byHeight)))
}

// ---- what the page remembers between visits -------------------------------------------------

export const MAX_PREVIEWS = 24

/** Kept while the console is open, so leaving the page and coming back shows the same previews. */
export const previewMemory = reactive({
  instanceId: null as number | null,
  ids: [] as string[],
  /** The test players were picked automatically once for this session (as the old Testing panel did). */
  autoPicked: false,
  device: 'desktop' as DeviceKind,
  size: 'M' as TileSize,
  interactive: false,
})
