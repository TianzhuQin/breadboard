# What's new in this fork

These changes add to the platform; existing experiments keep working unless noted.

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

The client sees its rules in `player.edgeEdits` and sends `Breadboard.sendEdgeEdit(op, a, b)`.
Every edit, accepted or not, is logged as an `edgeEdit` event naming the actor.

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

In `application.conf` (or as `-D` options):

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

The server opens a session at startup. Participants enter through `/play/{e}/login` or
`/play/{e}/prolific?...` and wait in the waiting room. The game starts by itself, and people who
left before the start get no seat. Someone who drops out mid-game is played by the computer after
the grace period. A new session opens when the game finishes.

## Data

- **Timestamps:** CSV exports are now in true UTC. The format is unchanged.
- **No IP addresses:** login events no longer store IP address, user agent or referer.
- **Connection events:** `clientConnected` and `clientDisconnected`.
- **Autopilot events:** `autopilotStart`, `autopilotCancelled`, `aiTakeover`, `aiReturned`.
- **Tie edits:** `edgeEdit`, which records the actor.
- **Prolific entry:** `prolificEntry`.
