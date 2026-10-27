/**
 * Handy commands for the script console. Each one exists in the engine (groovy/*.groovy, the
 * Gremlin 2 graph `g`, the PlayerActions `a`). `{p1}` and `{p2}` become real player ids from the
 * live network when the researcher inserts them, each as a whole single-quoted string literal.
 *
 * `changes` marks commands that change the running session: those are only inserted into the
 * editor, so the researcher reads (and fixes the ids) before running them.
 */
export interface Snippet {
  id: string
  group: string
  title: string
  /** One line: what it does. */
  text: string
  code: string
  changes: boolean
}

export const SNIPPET_GROUPS = ['Look around', 'Network', 'Players', 'Session & data', 'Autopilot']

export const SNIPPETS: Snippet[] = [
  // ---- read-only
  { id: 'count-people', group: 'Look around', title: 'Count people', text: 'Everyone in the network, computer players included.', code: 'g.V.count()', changes: false },
  { id: 'count-ties', group: 'Look around', title: 'Count ties', text: 'How many ties connect them.', code: 'g.E.count()', changes: false },
  { id: 'list-ids', group: 'Look around', title: 'List player ids', text: 'One id per line. Ids that start with _ are computer players.', code: 'g.V.id', changes: false },
  { id: 'list-props', group: 'Look around', title: 'Everyone and their properties', text: 'Each player\'s id and every property they have, one player per line.', code: 'g.V.transform { [id: it.id] + it.map() }', changes: false },
  { id: 'one-player', group: 'Look around', title: 'One player\'s properties', text: 'Everything stored on one player. Change the id to look at someone else.', code: 'g.getVertex({p1}).map()', changes: false },
  { id: 'neighbors', group: 'Look around', title: 'Who is tied to whom', text: 'Each player with the ids of their neighbors.', code: 'g.V.transform { [it.id, it.neighbors.id.toList()] }', changes: false },
  { id: 'connected', group: 'Look around', title: 'Who is online', text: 'Players whose page is open right now.', code: 'g.V.filter { it._system?.connected }.id', changes: false },
  { id: 'pending', group: 'Look around', title: 'Choices waiting for an answer', text: 'How many choice buttons are on screen and not answered yet.', code: 'a.size()', changes: false },

  // ---- network
  { id: 'tie-add', group: 'Network', title: 'Tie two players together', text: 'Adds a tie, as the researcher. It is recorded in the data as an edgeEdit event.', code: 'edgeEdits.adminEdit("add", {p1}, {p2})', changes: true },
  { id: 'tie-remove', group: 'Network', title: 'Cut a tie', text: 'Removes the tie between two players, recorded as an edgeEdit event.', code: 'edgeEdits.adminEdit("remove", {p1}, {p2})', changes: true },
  { id: 'edit-allow', group: 'Network', title: 'Let a participant change ties', text: 'They may add one and cut one of their own ties. scope can also be "amongNeighbors" or "visible".', code: 'edgeEdits.allow(g.getVertex({p1}), [add: true, remove: true, scope: "own", maxAdds: 1, maxRemoves: 1])', changes: true },
  { id: 'edit-disallow', group: 'Network', title: 'Stop a participant changing ties', text: 'Takes the tie-editing tools away from one player.', code: 'edgeEdits.disallow(g.getVertex({p1}))', changes: true },
  { id: 'edit-disallow-all', group: 'Network', title: 'Stop everyone changing ties', text: 'Takes the tie-editing tools away from every player.', code: 'edgeEdits.disallowAll()', changes: true },
  { id: 'ego-ties', group: 'Network', title: 'Show ties among neighbors', text: 'Participants also see the ties between their neighbors. g.setVisibility("ego") switches back.', code: 'g.setVisibility("egoTies")', changes: true },

  // ---- players
  { id: 'add-ai', group: 'Players', title: 'Add computer players', text: 'Adds two computer-controlled players (ids _1, _2 …) that answer at random.', code: 'g.addAI(a, 2)', changes: true },
  { id: 'ai-seat', group: 'Players', title: 'Let the computer play a seat', text: 'The computer makes this player\'s choices from now on.', code: 'a.ai.add(g.getVertex({p1}))', changes: true },
  { id: 'ai-return', group: 'Players', title: 'Give a seat back', text: 'Stops the computer playing for this player.', code: 'a.ai.remove(g.getVertex({p1}))', changes: true },
  { id: 'set-text', group: 'Players', title: 'Change what a player sees', text: 'Replaces the text on this player\'s page.', code: 'g.getVertex({p1}).text = "<h3>Please wait a moment</h3>"', changes: true },
  { id: 'set-prop', group: 'Players', title: 'Set a property', text: 'Stores a value on a player (here: score = 10).', code: 'g.getVertex({p1}).score = 10', changes: true },
  { id: 'remove-player', group: 'Players', title: 'Take a player out of the network', text: 'Removes them and their ties.', code: 'g.removePlayer({p1})', changes: true },
  { id: 'kick', group: 'Players', title: 'Send a player away', text: 'Tells this player\'s page that they have been removed from the study.', code: 'kickPlayers({p1})', changes: true },

  // ---- session
  { id: 'start', group: 'Session & data', title: 'Start the game', text: 'The same as Start game: runs InitStep once and closes the session to newcomers.', code: 'initStep.start()', changes: true },
  { id: 'note', group: 'Session & data', title: 'Write a note into the data', text: 'Saved with the session\'s events, so it appears in the data download.', code: 'a.addEvent("researcherNote", [text: "Paused for a fire alarm"])', changes: true },
  { id: 'timers', group: 'Session & data', title: 'Stop every timer', text: 'Cancels all running timers, such as step countdowns.', code: 'timers.cancel()', changes: true },

  // ---- autopilot
  { id: 'ap-room', group: 'Autopilot', title: 'Waiting room', text: 'Starts the game by itself once enough people are here. The Autopilot page sets this for every session.', code: 'autopilot.waitingRoom([minPlayers: 4, maxPlayers: 8, waitSeconds: 60, maxWaitSeconds: 600, countdownSeconds: 5])', changes: true },
  { id: 'ap-replace', group: 'Autopilot', title: 'Replace people who drop out', text: 'After 30 seconds away, the computer plays their seat until they return.', code: 'autopilot.replaceDisconnected([afterSeconds: 30, returnToHuman: true])', changes: true },
  { id: 'ap-stop', group: 'Autopilot', title: 'Turn the autopilot off here', text: 'Clears the waiting room and replacement rules for this session.', code: 'autopilot.stop()', changes: true },
  { id: 'ap-state', group: 'Autopilot', title: 'Waiting-room status', text: 'Each player\'s waiting-room state (waiting, starting or cancelled).', code: 'g.V.transform { [it.id, it._system?.autopilot] }', changes: false },
]

/** Two player ids to put in snippets: people first, then computer players, else "1" and "2". */
export function pickIds (ids: string[]): [string, string] {
  const people = ids.filter(id => !id.startsWith('_'))
  const pool = [...people, ...ids.filter(id => id.startsWith('_'))]
  return [pool[0] ?? '1', pool[1] ?? (pool[0] === '2' ? '1' : '2')]
}

/**
 * A single-quoted Groovy string holding `s` exactly. Player ids are typed by participants, so they
 * must never go into a double-quoted string: there `${…}` would run as code on the server.
 */
export function groovyLiteral (s: string): string {
  return `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n').replace(/\r/g, '\\r')}'`
}

/**
 * Puts the ids in place of `{p1}` and `{p2}`; each placeholder becomes a whole string literal.
 * One pass, so an id that itself contains "{p2}" is never filled in a second time.
 */
export function fillSnippet (code: string, [p1, p2]: [string, string]) {
  return code.replace(/\{(p[12])\}/g, (_, k: string) => groovyLiteral(k === 'p1' ? p1 : p2))
}

export function searchSnippets (list: Snippet[], query: string) {
  const q = query.trim().toLowerCase()
  if (!q) return list
  return list.filter(s => (s.title + ' ' + s.text + ' ' + s.code + ' ' + s.group).toLowerCase().includes(q))
}
