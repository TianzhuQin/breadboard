import { reactive } from 'vue'
import { playerName } from '@/network/players'

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
/**
 * A computer player, created as one (g.addAI's _1, _2, …): a preview of it only watches what the
 * computer does, read-only. (A person's seat the computer took over is still theirs to preview.)
 */
export const isComputerPlayer = (p: PlayerLike) => p.id.startsWith('_')
/**
 * The player's seat is connected right now (_system.connected): their own page, or a researcher's
 * preview that took it while it was free (another console's, or this one's).
 */
export const isConnected = (p: PlayerLike) => p.data?._system?.connected === true
/**
 * A researcher's preview holds their seat (_system.preview): their own page isn't connected. It plays
 * the seat of a test player, whose own page hasn't been in the session (connected), or stands in for
 * anyone else (not connected: they count as away, and the computer takes over as usual).
 */
export const heldByPreview = (p: PlayerLike) => p.data?._system?.preview === true
/** Their own page is connected: a preview of them only watches it, read-only. */
export const ownPageConnected = (p: PlayerLike) => isConnected(p) && !heldByPreview(p)

/**
 * What a preview of this player does, as far as the console can tell:
 * - 'computer': a computer player; the preview watches what the computer does.
 * - 'watching': their own page is connected; the preview watches it.
 * - 'plays': a preview plays the seat (a test player's, whose own page hasn't been in the session).
 * - 'away': their own page has been in the session and isn't now: a preview stands in for them (or
 *   will), answering for them, but they count as away. Also a person whose seat the computer took over.
 * - null: nobody is on the seat, and the console can't tell whether their own page has been in the
 *   session (the server says so once a preview takes the seat).
 */
export type PreviewRole = 'computer' | 'watching' | 'plays' | 'away'
export function previewRole (p: PlayerLike): PreviewRole | null {
  if (isComputerPlayer(p)) return 'computer'
  if (ownPageConnected(p)) return 'watching'
  if (heldByPreview(p)) return isConnected(p) ? 'plays' : 'away'
  // (the computer takes over only the seat of someone who left)
  if (truthyFlag(p.data?.ai)) return 'away'
  return null
}

/**
 * Where the player's seat is, and so what a preview of them does, in words (the player list's
 * tooltip and screen-reader text). `shownHere`: their page is shown on this page.
 */
export function seatText (p: PlayerLike, shownHere = false): string {
  const text = (() => {
    switch (previewRole(p)) {
      case 'computer': return 'played by the computer, so a preview is read-only'
      case 'watching': return 'their own page is connected, so a preview is read-only'
      case 'plays': return 'a preview plays this seat: their own page hasn’t been in this session'
      // (another console's preview, or this one's)
      case 'away': return heldByPreview(p) ? 'away: a preview stands in for them' : 'away: the computer plays their seat'
      default: return 'not connected'
    }
  })()
  return shownHere ? `${text}, shown here` : text
}

/** The same in a word, for the list (only where it changes what a preview does). */
export function seatWord (p: PlayerLike): string {
  const role = previewRole(p)
  return role === 'computer' || role === 'watching' ? 'Watching' : role === 'away' ? 'Away' : ''
}

/**
 * What the tile of someone away says ('away' above): the preview stands in for them, answering for
 * them, but they count as away, and what that means now. What the computer does with their seat is
 * said when the console knows it, in the autopilot's own session with its takeover switched on: how
 * long it waits before it takes the seat (`takeoverSeconds`), and whether it gives the seat back when
 * their own page is back (`returnToHuman`, the Autopilot page's "Give the seat back when they
 * return"). Otherwise the steps decide, if they let the computer play at all.
 */
export function awayText (p: PlayerLike, opts: { gameStarted: boolean, takeoverSeconds?: number | null, returnToHuman?: boolean | null }): string {
  if (truthyFlag(p.data?.ai)) {
    if (opts.returnToHuman === true) return 'Away: you are standing in, and the computer plays their seat until their own page is back.'
    if (opts.returnToHuman === false) return 'Away: you are standing in, and the computer plays their seat; it doesn’t give it back when they return.'
    return 'Away: you are standing in, and the computer plays their seat; it gives it back when they return if the steps say so.'
  }
  if (!opts.gameStarted) return 'Away: you are standing in, but they count as away, so a waiting room doesn’t count them.'
  const s = opts.takeoverSeconds
  if (s != null && s >= 0) return `Away: you are standing in; the computer takes over their seat ${s > 0 ? `after ${s} s away` : 'at once'}.`
  return 'Away: you are standing in, but they count as away: the computer takes their seat after the usual wait, if it plays for people who leave.'
}

/** Why a preview only watches, for the roles where it does (as the participant page says it). */
export const WATCHING_WHY: Record<'computer' | 'watching', string> = {
  computer: 'the computer plays this seat, so you see what it does but can’t answer for it',
  watching: 'their own page is connected, so you see their screen but can’t answer for them',
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
/** Players in natural id order: p2 before p10. */
export function sortPlayers<T extends PlayerLike> (list: readonly T[]): T[] {
  return [...list].sort((a, b) => collator.compare(a.id, b.id))
}

export function matchesPlayer (p: PlayerLike, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  // (by the name the list shows too: "AI 6" finds _6)
  if (playerName(p.id).toLowerCase().includes(q)) return true
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
 * Of the players about to be previewed, those whose previews are read-only, which the researcher is
 * told: those whose own page is connected (the server lets a preview only watch them: it shows what
 * their page gets, and leaves their page alone), and computer players. A seat another preview holds
 * is not theirs.
 */
export function shownReadOnly<T extends PlayerLike> (adding: readonly T[], alreadyShown: ReadonlySet<string>): T[] {
  return adding.filter(p => !alreadyShown.has(p.id) && (ownPageConnected(p) || isComputerPlayer(p)))
}

/**
 * The test players shown on the first look at a session, as the old Testing panel did: not those
 * whose seat is connected, by their own page or by another console's preview, nor those another
 * preview stands in for: a preview from here would take the seat from it (two consoles would take
 * it from each other).
 */
export function autoPicked<T extends PlayerLike> (players: readonly T[]): T[] {
  return quickPick(players, 'test').filter(p => !isConnected(p) && !heldByPreview(p)).slice(0, MAX_PREVIEWS)
}

/**
 * When the first look at a session is taken: once its network has been quiet this long. The server
 * sends each player before their properties (testmode among them), so a look taken while the
 * network comes in, as on a direct load of the page or a reload, found no test players, or only
 * some of them. A page that opens on a network quiet this long already takes it at once.
 */
export const FIRST_LOOK_QUIET_MS = 300
/**
 * …or this long after the first players were seen, at the latest, in a network that keeps changing.
 * A network sent this long before the page opened has come in: the look is taken at once.
 */
export const FIRST_LOOK_MAX_MS = 2000

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
  // (two Medium ones fit in a row beside the player list in a 1280 px window)
  desktop: { S: 260, M: 320, L: 540 },
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
