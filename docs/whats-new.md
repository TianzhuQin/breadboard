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
(`recruitment.completeProlific(player, [completionCode: "..."])`).

| Setting | Effect |
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
the network changed (`applied`), which is false when `onEdit` only collects it. Every edit,
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
  that reloads the engine (selecting another experiment, launching or resuming another session,
  reloading). Press **Resume** on the Autopilot page to let it open sessions again; after a run
  with **continuous** off, the button reads **Open another session**. Both work when
  `application.conf` is in charge too, and a pause survives a server restart.
- While a researcher has another experiment loaded and a console open, the autopilot leaves the
  engine to them and opens its session once they close the console.
- While it is on, it opens a session whenever the script engine runs none. The engine is shared
  by everyone on the server, so on a server that runs the autopilot, test other experiments on
  another server, or turn the autopilot off first.

Settings saved there take precedence over the ones in the experiment's steps. Alternatively, in
`application.conf` (or as `-D` options), in which case the Autopilot page only shows them:

```
breadboard.autopilot.experiment = "My experiment"
breadboard.autopilot.user = "researcher@lab.org"   # admin account it runs as (default: first user)
breadboard.autopilot.continuous = true             # open the next session when one finishes
breadboard.autopilot.nextSessionDelaySeconds = 10
```

In the experiment's steps:

```groovy
autopilot.waitingRoom([
  minPlayers: 15, maxPlayers: 25,
  waitSeconds: 120,        // once minPlayers are here, wait this long for more
  maxWaitSeconds: 900,     // give up if minPlayers never arrive
  countdownSeconds: 10,
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
`/play/{e}/prolific?...` and wait in the waiting room. The game starts by itself, and people who
left before the start get no seat. Someone who drops out mid-game is played by the computer after
the grace period. A new session opens when the game finishes.

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

- **Timestamps:** CSV exports are now in true UTC. The format is unchanged.
- **Exact values:** every CSV, the server's and the console's downloads, keeps values exactly as
  recorded, with no byte-order mark. A spreadsheet may read a value that starts with `=`, `+`,
  `-` or `@` as a formula, so import the file as text when participants could type such values.
- **No IP addresses:** login events no longer store IP address, user agent or referer.
- **Connection events:** `clientConnected` and `clientDisconnected`.
- **Autopilot events:** `autopilotStart`, `autopilotCancelled`, `aiTakeover`, `aiReturned`.
- **Tie edits:** `edgeEdit`, which records the actor.
- **Prolific entry:** `prolificEntry`.
