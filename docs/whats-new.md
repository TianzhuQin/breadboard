# What's new in Breadboard 3

Everything here adds to Breadboard 2.4; existing experiments keep working unless noted. The
experiment language itself (steps, `g`, `a`, content) is unchanged and still described in the
[original wiki](https://github.com/human-nature-lab/breadboard/wiki). For running a release, see
[install.md](install.md); for the console, [console.md](console.md).

## Participant login and recruitment

A participant's page used to be reachable by anyone who knew its URL
(`/game/{experiment}/{instance}/{clientId}/connected`), because the client id was the only
credential. Now every way in hands out a personal token, and a page without it is sent to the login
form.

| Way in | Link |
|---|---|
| Login form | `/game/{e}/{i}/login`. An id that is already in use can't be taken again. |
| Signed invitation, one per participant | `/game/{e}/{i}/{clientId}/connected?invite={signature}`. The signature comes from `security.ParticipantAuth.inviteSignature(instanceId, clientId)` and works until the seat is taken. |
| Prolific | `/game/{e}/{i}/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}` |
| Whichever session is open (for autopilot) | `/play/{e}/login` and `/play/{e}/prolific?...` |

Prolific participants get a pseudonymous client id (`pp-…`). The PID is never in a URL and never
sent to other participants. It is logged as a `prolificEntry` event for payment, and stored in the
player's own `_system.recruitment`, which drives the built-in Prolific completion screen
(`recruitment.completeProlific(player, [completionCode: "..."])`). For a player who didn't come
from Prolific, `completeProlific` does nothing and returns false (the server's log says why); it
used to throw, which stopped the step that called it.

| Setting (in `conf/local.conf`) | Effect |
|---|---|
| `breadboard.participantLogin.allowReclaim = true` | Lets a login take over a seat that is already in use. |
| `breadboard.allowLateJoin = true` | Keeps the game open to newcomers after InitStep starts. |
| `breadboard.secureCookies = true` | Marks the login cookie Secure, for https deployments. |

The start scripts now generate `conf/application.secret` instead of using a published default.

A session closes to newcomers when its game starts, however it starts: the console's Start, the
autopilot, the AMT timer, or the experiment's own code calling `initStep.start()` (for example an
OnJoinStep that starts the game once enough people are in). Code that only mentions
`initStep.start()`, such as that OnJoinStep while the steps load, starts nothing. Reloading the
engine, resuming a session or selecting its experiment again forgets the game, so the session then
takes people again (newcomers and those who held a seat) until its game is started again.

## What participants receive about others

Set from experiment scripts:

| Script call | Effect |
|---|---|
| `g.setVisibility("egoTies")` | Also send the ties **among a participant's neighbors**. The default `"ego"` sends only their own ties. |
| `g.setNeighborIds("real")` | Neighbors normally appear under a random alias instead of their client id. This restores the real ids. |
| `g.fromClientId(id)` / `g.clientIdOf(vertex)` | Translate between the ids browsers see and vertices. |
| `g.hideFromNeighbors("score")` / `g.showToNeighbors("ai")` | Engine bookkeeping (`_system`, `timers`, `ai`, `lastupdated`) is hidden from neighbors by default. `private`, `text` and `choices` are never sent to anyone else. |

## Adding and cutting ties from the network

```groovy
edgeEdits.allow(player, [
  add: true, remove: true,
  scope: "amongNeighbors",    // or "own", "visible"
  maxAdds: 1, maxRemoves: 1,  // per allow(); null = unlimited
  confirm: true,              // the client asks before sending
  candidates: { p -> [[op: "remove", a: v1, b: v2, available: false, reason: "..."], ...] },
  validate: { p, op, v1, v2 -> true },   // false or a reason string refuses
  onEdit: { p, op, v1, v2 -> },          // replace applying at once, e.g. to collect proposals
])
edgeEdits.disallow(player)
edgeEdits.adminEdit("add" | "remove", id1, id2)   // researcher edits
```

The client sees its rules in `player.edgeEdits` and sends `Breadboard.sendEdgeEdit(op, a, b, turn)`.
Every `allow()` starts a new turn (`player.edgeEdits.turn`); a change sent with an older turn, such
as one confirmed just as a round ended and the next one called `allow()` again, is refused rather
than made in (and counted against) the new turn.
The answer (`bb:edge-edit-result`) says whether the edit was accepted (`ok`). It also says whether
the network changed (`applied`), which is false when `onEdit` only collects it, and true when
`onEdit` makes the change itself (the network game example does, to record a cut as a `TieCut`
event of its own). Every edit,
accepted or not, is logged as an `edgeEdit` event naming the actor.

Scopes `amongNeighbors` and `visible` concern ties among a participant's neighbors. Turn those ties
on with `g.setVisibility("egoTies")`. Under the default visibility the page cannot tell which
neighbors are tied, so it doesn't offer to connect two of them, and the server logs a warning.

## Steps with a time limit

```groovy
step.duration = 30            // seconds
step.waitForDuration = false  // true: wait the full time even if everyone has chosen
step.onTimeout = { player -> }
step.clearChoicesOnTimeout = true
```

A timed step ends when every choice is made or time runs out, whichever comes first. With no
choices it waits for the whole duration. Steps without `duration` behave as before.

## Running without anyone at the admin console (autopilot)

The simplest way is the console's **Autopilot** page. Pick the experiment, the waiting room
(minimum and maximum players, how long to wait) and what happens when someone drops out, then
save. The settings are kept in the database and take effect at once. The page shows the
session that is open and the two links to give participants: the sign-in link and the Prolific
study URL.

- If another experiment's session is running when you turn it on, the autopilot waits for that
  session to end; the page also offers to end it there and then.
- Ending the autopilot's session from the console pauses the autopilot: stopping it, or anything
  that reloads the engine (launching or resuming another session, reloading). Selecting another
  experiment, to edit it, doesn't: the autopilot's session keeps running. Press **Resume** on the
  Autopilot page to let it open sessions again; after a run with **continuous** off, the button
  reads **Open another session**. Both work when the server's settings file (`conf/local.conf`)
  is in charge too, and a pause survives a server restart.
- While a researcher has another experiment loaded and a console open, the autopilot leaves the
  engine to them and opens its session once they close the console.
- While it is on, it opens a session whenever the script engine runs none. The engine is shared
  by everyone on the server: while the autopilot's session runs, you can still edit other
  experiments, but to run or test one, stop that session first (which pauses the autopilot) or
  open a session of the other experiment (which ends the autopilot's, after asking), then press
  **Resume**.

Settings saved there take precedence over the ones in the experiment's steps. Alternatively, in
the server's settings file, `conf/local.conf` (or as `-D` options), in which case the Autopilot
page only shows them:

```
breadboard.autopilot.experiment = "My experiment"
breadboard.autopilot.user = "researcher@lab.org"   # admin account it runs as (default: first user)
breadboard.autopilot.continuous = true             # open the next session when one finishes
breadboard.autopilot.nextSessionDelaySeconds = 10
breadboard.autopilot.maxSessionMinutes = 120       # longest a session's game may run; 0 or left out: no limit
```

In the experiment's steps:

```groovy
autopilot.waitingRoom([
  minPlayers: 15, maxPlayers: 25,
  waitSeconds: 120,        // once minPlayers are here, wait this long for more
  maxWaitSeconds: 900,     // give up if minPlayers never arrive
  countdownSeconds: 10,
  departureGraceSeconds: 15, // someone leaving counts only once they have been gone this long
  onCancel: { players -> players.each { recruitment.completeProlific(it, [completionCode: "WAITFEE"]) } },
])
autopilot.replaceDisconnected([afterSeconds: 30, returnToHuman: true])
```

While people wait, each one's `player._system.autopilot` holds the waiting state, which the new
participant page shows as a waiting screen. Older pages show the waiting text instead. The waiting
state is cleared however the game starts, whether by the autopilot, the admin's Run button, the
AMT timer or the experiment's own code, because the server sends a `bb:game-start` event that
scripts can also listen for.
People who come back after a session was given up get its "could not start" notice, and are passed
to `onCancel` too.

The server opens a session at startup. Participants enter through `/play/{e}/login` or
`/play/{e}/prolific?...` and wait in the waiting room. Someone whose connection drops there counts
as gone only after `departureGraceSeconds`, so a moment's drop neither starts the wait again nor
gets the session given up. The game starts by itself. People who left before the start get no
seat, and are told so if they come back (see below). With `replaceDisconnected`, someone away for
less than a minute (or `afterSeconds`, if that is longer) keeps their seat, which the computer plays
until they are back; without it nobody would play that seat, so they get none either.
Someone who drops out mid-game is played by the computer after the grace period. A new session
opens when the game finishes. Waiting-room settings saved in the console replace the steps' own
even for people who are already waiting: the limits count from the first arrival.

## The participant page

New experiments start with a redesigned page (`conf/defaults/default-client-html.html`):

```html
<v-app class="bb-app">
  <ConnectionStatus />
  <BBMain :player="player">
    <BBGame :player="player" :graph="graph" />
  </BBMain>
</v-app>
```

- **`BBGame`** lays out the network next to the experiment's text, timers, choices and forms. On
  phones they are stacked, with the experiment's part first.
  - While an autopilot session fills up, it shows a waiting screen with the number of people there
    and a countdown. Whatever the experiment shows meanwhile (timers, choices, forms) appears under
    it and stays as it is when the game starts.
  - Slots: `network`, `text`, `panel-top`, `panel-bottom`, `waiting`.
- **`BBNetwork`** draws the participant in the middle and their neighbors on a circle.
  - With `g.setVisibility("egoTies")`, ties among neighbors are drawn darker. Neighbors who know
    each other sit next to each other, and longer ties curve around the outside.
  - When the experiment allows edits (`edgeEdits.allow`), participants click (or tap) a tie to cut
    it, or click two people to connect them. They confirm before anything is sent and then see the
    server's answer.
  - Moves the experiment marks unavailable are greyed out, and tapping one says why.
  - Every possible change is also listed under the picture, for keyboards, screen readers and
    crowded networks. The list is folded away until opened, and opens by itself when people are
    drawn too small to tap.
  - Neighbors without a name are called "Person A", "Person B", ... so every change can be put in
    words.
  - Options, passed through `BBGame`'s `network-props`:

    | Option | Meaning |
    |---|---|
    | `layout` | `"ring"` or `"force"` |
    | `nodeLabel` | Text under each person |
    | `nodeBadge` | A small tag under the label |
    | `nodeColor` | Fill colour |
    | `nodeRadius` | Size of each person |
    | `egoLabel` | Label on the participant (default "You") |
    | `showMoveList` | Offer the changes as a list too (default on) |
    | `editable` | Keep room for editing from the start, so the picture doesn't shrink when a later round allows it |

  - Like `SVGGraph`, it sets `node.isEgo` and emits `nodeClick` and `edgeClick` for clicks on any
    person or tie. `SVGGraph`'s layout props and edge-label slots don't apply.
- Until the experiment shows anything (and between two screens), the page says the participant is
  in and should keep it open, rather than staying blank.
- When the experiment sets `player.private.finished = true` (or `player.finished`) on the last
  screen, the page shows that screen alone, centred, without the network, and no longer warns
  about the connection when the next session restarts the engine. Stopping a session from the
  console shows its participants such a screen too.
- A choice can have a colour: `a.add(player, [name: "Blue", color: "#2563eb", result: {...}])`.
- Restyle the page from the Participant page's Style tab through the theme's variables, e.g.
  `.bb-app { --bb-accent: #c2410c; }` (also `--bb-text`, `--bb-bg`, `--bb-surface`, `--bb-muted`,
  `--bb-radius`, `--bb-font`), or with selectors that start with `.bb-app .bb-prose`, e.g.
  `.bb-app .bb-prose h2 { color: #c2410c; }`; plain selectors such as `h2` lose to the theme.
- Sign-in, message and loading pages use the same look.
- The new look only applies inside `class="bb-app"`. The page is always light, so what
  participants see doesn't depend on their system's dark mode.

**Existing experiments keep their own client HTML and look unchanged.**
- Vuetify's colours change only for pages marked `class="bb-app"`.
- `SVGGraph` and every other component still work.
- Imported and upgraded experiments keep the previous default page,
  `conf/defaults/legacy-client-html.html`; only new experiments get `BBGame`.
To move an old page over, replace `<SVGGraph ...>` with `<BBNetwork :player="player" :graph="graph" />`,
or the whole layout with `BBGame`.

After an update, browsers fetch the new client right away: the entry scripts carry a version
parameter, and code loaded on demand has content-hashed file names.

A page that reconnects after its session has ended (for example after the autopilot gave up on it)
is not let into the session the server runs now. It gets back the last screen it had, such as its
completion code, or else a "This session has ended" notice.

## The participant page's connection

The page reconnects by itself, at once when the network or the participant comes back to the tab,
and notices a connection that died without closing. `<ConnectionStatus />` says what is going on,
with **Try now** while it reconnects. When the study is opened in another tab or window, the newer
page takes over and the older one stops, with **Use it here** to take it back.

`Breadboard.connection` says how the connection is, and Breadboard emits `bb:connection` whenever
it may have changed (`Breadboard.on('bb:connection', () => ...)`):

| Value | |
|---|---|
| `connecting` | The first try is under way. |
| `open` | Connected. |
| `lost` | It dropped, and is reconnecting. |
| `blocked` | It has never connected after several tries: the network may not let it. It goes on trying. |
| `elsewhere` | Another page has the seat, e.g. the study opened in another tab. `Breadboard.useHere()` takes it back. |
| `ended` | The session is over for this page; it no longer reconnects. |
| `watching` | A researcher's preview that only watches (below). |

**No answers while disconnected.** While the connection is down, `PlayerChoices`, `Form` and
`BBNetwork` disable their choices, buttons and tie changes, and say why; a page's own choices in
`PlayerChoices`'s `choice` slot get a `disabled` slot prop. A choice or tie change sent anyway
(`Breadboard.sendChoice`, `sendGroupChoice`, `sendEdgeEdit`) waits for the connection for at most
5 seconds and is then dropped, since the screen may have moved on. Other messages
(`Breadboard.send`) wait as long as it takes, as before.

**`ConnectionStatus`'s slots.** The default slot replaces the message while the page reconnects
(`lost` and `blocked`), with the slot props `time` (ms until the next try, counting down, and 0
while one is under way), `state` and `tryNow()`. A message written for 3.0 stays there, and now
shows only in those states. The other states have slots of their own, with Breadboard's words
otherwise: `elsewhere` (slot prop `useHere()`), `ended` and `watching`.

```html
<ConnectionStatus>
  <template #default="{ time, tryNow }">
    Reconnecting in {{ Math.ceil(time / 1000) }} s… <button @click="tryNow">Retry</button>
  </template>
  <template #elsewhere="{ useHere }">
    This study is open elsewhere. <button @click="useHere">Continue here</button>
  </template>
</ConnectionStatus>
```

**Researchers' previews watch connected players.** The console's preview of a player whose own
page is connected only watches: it shows what their page shows, live, and leaves their page alone.
Its choices, forms and tie changes are disabled, it sends nothing for them, and
`Breadboard.connection` is `watching` (`Breadboard.preview` tells a preview from a participant's
page). So does a preview of a computer player (`g.addAI`'s `_1`, `_2`, …): it shows what the
computer does, which goes on playing (`Breadboard.computerPlayer` is `true`, and the page's notes
say the computer plays the seat). A preview of a test player, whose own page hasn't been in the
session, acts for them as before, and they count as connected, until their own page comes back;
from then on it watches. A preview of anyone whose own page has been in the session and is away
(they left, or the computer took their seat) can act for them too, but they count as gone, like
anyone who leaves, whichever preview it is: the computer takes over after `afterSeconds`, or goes on
playing, and a waiting room doesn't count them.

**On the server.** What the server does about connections, for experiment authors:

- **One page holds the seat.** A participant's newer page (another tab, a reload) takes the seat.
  The older one is sent `{"bbClosed": "replaced"}` and closed, so it doesn't reconnect and take the
  seat back. `clientConnected` and `clientDisconnected` are recorded only when the participant comes
  or goes, not each time a page takes over; `bb:connection` is emitted whenever a page takes the
  seat, so scripts may hear `connected: true` for someone who is connected already.
- **A heartbeat.** The page sends `{"action": "Ping"}` as soon as it connects and every 5 seconds
  after, and gets `{"pong": <the server's time in ms>}`. A page that has pinged and then sends
  nothing for 30 seconds is closed and counts as gone, as when its socket closes: a connection that
  dies without closing (a phone out of range, a laptop put to sleep) used to look connected for
  good, so the computer never took that seat. Pages that never ping (from before 3.1) are left
  alone. A page given its last screen as it connects (its session is over, isn't the one running,
  or started without it: e.g. a thank-you page left open when the next session opens) has its pings
  answered too, and is closed once it falls silent; nobody used to answer them.
- **`player._system.disconnectedAt`**: since when (ms) the player has been away, next to
  `_system.connected`; it goes when they are back.
- **Previews.** A researcher's preview of a player whose own page is connected, or of a computer
  player (an id starting with `_`), is sent `{"bbWatching": true}` and then every frame that page
  gets; what it sends is ignored, apart from its sign-in (recorded as a preview's). Nothing about a
  computer player's connections is recorded or announced, and the autopilot leaves its seat to the
  computer, with the experiment's own behaviour. A preview that takes the seat of a test player
  (one no page of the participant's has held in the session) holds it as their page would: they
  count as connected, so the researcher plays them, and the `clientConnected` /
  `clientDisconnected` it causes have `preview` = `true`, as does the `clientDisconnected` when
  their own page takes the seat from it. While a preview holds the seat, `player._system.preview`
  is `true`; neighbours never see it, as with the rest of `_system`.
- **A preview that stands in.** Once a participant's own page has held their seat in a session, a
  preview never counts as them being there. One that watches as their page goes takes the seat and
  is sent `{"bbWatching": false}`, and any preview opened while their page is away takes it too (the
  console opens new ones all the time: a tile opened full size and closed again, a reload, the
  Preview page visited again, its **AI players** pick): its choices count, but nobody counts as
  being there. `_system.connected` stays `false` (with `_system.preview` `true`), no
  `clientConnected` is recorded and no `bb:connection` with `connected: true` is emitted, so their
  departure goes on as anyone's: the computer takes over after `afterSeconds` (or goes on playing a
  seat it took over), and the waiting room stops counting them once `departureGraceSeconds` have
  passed. It used to count as them coming back, so nobody played the seat for as long as the
  preview was open, and a start went ahead with a seat nobody played. This holds for the whole
  session, also once the engine has been rebuilt for it (**Resume**, or a reload that carries it on),
  which used to forget whose own page had been there.
- **A preview adds nobody.** In a real session, a preview of someone who isn't in the network (the
  console keeps its tiles across a Stop and Resume, say, of people who haven't joined again) is sent
  a screen saying they aren't in the game (`player.ended` = `"notInGame"`), and nothing else
  happens: it used to add them before the game started, OnJoinStep and all, counted as connected,
  so a waiting room could start a game with nobody but previews in it. Only an experiment's test
  session takes the test players a preview names. The console's Preview page loads such a tile
  again once the player is back in the network.
- **Started without them.** Someone the autopilot's start left out (see the autopilot above) is
  recorded as a `missedStart` event. Whenever their page comes back, even after the next session
  has opened, it gets a screen saying the session went ahead without them (Prolific participants
  are asked to return the study), with `player.ended = "missedStart"`, and stops reconnecting.
  Someone taken out of the network any other way (by a researcher, or by the experiment) gets their
  last screen back, as before.
- **The seat cookie lasts a day.** A participant whose browser was closed finds their seat again
  through the study link or the game's address.
- **No identical views.** A page isn't sent the same view twice in a row. Participants got one every
  few hundred milliseconds, mostly unchanged, and a page on a weak connection fell further and
  further behind. A page that connects, and a preview that starts watching, get the whole view.

## The researcher console

The console is new: open the server's address and sign in. The previous console still works at
`/legacy-admin`, and both can be open at the same time.

- **Run:** Live session (open, start, stop and resume sessions; who is waiting, who is
  connected, the events as they happen), Network, Players and the Script console.
- **Build:** Steps, Content, Parameters, Participant page, Images and Preview (the participant
  page exactly as chosen players see it).
- **Recruit and data:** Autopilot, Recruitment (Prolific and Mechanical Turk) and Sessions &
  data (every session, its events and CSV downloads).
- **Experiments and Settings:** create, duplicate, import, export and delete experiments, file
  mode, password and theme.

**Editing the network.** On the Network page, choose **Tie** (key `C`) and click two people, or
drag from one to the other. Choose **Cut** (key `X`) and click a tie to remove it. Every edit
goes through `edgeEdits.adminEdit`: the rules set for participants don't limit researchers, and
the edit is logged as an `edgeEdit` event with the actor `admin`. A toast offers Undo when the
edit worked, and Cmd/Ctrl+Z works too. The player panel lists each person's
ties with a Cut button and a "Tie to…" picker.

**Previews.** The Preview page and "Open in a new tab" show the participant page in a sandbox,
with a preview token that works for that player only and lasts 12 hours. The page's own scripts
(the experiment's, or text participants typed) can't act with the researcher's sign-in. Signing
out or changing the password ends the tokens; open the preview again to get a new one. The
console's sign-in cookie is now `SameSite=Lax`, and approving a Mechanical Turk assignment (which
pays the worker) takes a POST, so a link on another site can't do it.

**Several consoles.** Each open console gets the answers to its own actions; the server used to
send them to whichever console had connected last. OnJoinStep and OnLeaveStep run once per
person, whether no console, one or several are open, and a second account signing in no longer
restarts the running session.

## Data

- **Timestamps:** CSV exports are now in true UTC. The format is unchanged
  (`2026-10-28T13:24:23:757Z`), and the files the console makes from a session's events use it too.
- **Exact values:** every CSV, the server's and the console's downloads, keeps values exactly as
  recorded, with no byte-order mark unless you ask for one: **For Excel** on Sessions & data, or
  `excel=true` on the server's CSV addresses, adds the mark Excel and WPS need to read Chinese. A
  spreadsheet may read a value that starts with `=`, `+`, `-` or `@` as a formula, so import the
  file as text when participants could type such values.
- **No IP addresses:** login events no longer store IP address, user agent or referer.
- **Connection events:** `clientConnected` and `clientDisconnected`, each time a participant comes
  or goes (not for every page that takes over their seat).
- **Order:** events recorded in the same millisecond come out in the order they were recorded. Their
  ids follow that order now; they were given out twenty at a time, in an order of the database's
  own, so such events could come out the wrong way round.
- **Autopilot events:** `autopilotStart` (with `players`, and `away` and `leftOut` when anyone was),
  `missedStart` (someone its start left out), `autopilotCancelled`, `aiTakeover`, `aiReturned`.
- **Tie edits:** `edgeEdit`, which records the actor.
- **Prolific entry:** `prolificEntry`.
