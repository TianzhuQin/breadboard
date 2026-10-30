# The researcher console

The console is where researchers build experiments, run their sessions, recruit participants and
download the data. Open the server's address in a browser, for example <http://localhost:9000>,
and sign in. The previous console is still at `/legacy-admin` (see
[The previous console](#the-previous-console)). For installing the server, see
[install.md](install.md); for what experiment scripts can do, [whats-new.md](whats-new.md).
Shortcuts are written with Ctrl; on a Mac, use Cmd.

## Signing in

On a new install the console asks for the first researcher account (**Welcome to Breadboard**):
an **Email**, a **Password** of at least 8 characters (typed twice), and the **Default language**
new content is written in. Opened from another computer than the server's own, it also asks for the
**Setup code** the server prints in its window and in `logs/application.log`. **Create account** creates it and signs you in. The form only appears
while the server has no account. From then on the console opens with **Sign in to Breadboard**.

The email is not case-sensitive. The account can be signed in on several browsers at once (say a
laptop and the lab's computer). To sign out, use the menu under your email at the bottom of the
sidebar, or Settings: this signs the account out on every browser, as changing its password does.
Signing out doesn't stop a running session; participants keep playing. With unsaved changes, it
asks first.

Each researcher can have an account of their own: add them in Settings → Researchers. Every
account works on every experiment on the server: the experiment list, sessions and data are the
same for all of them. (An account can run any code on the server from the script console, so
separate lists would not keep studies apart; run separate servers for that.)

## Finding your way around

The console opens on Live session. Every page works on one experiment: the one in the experiment
switcher at the top of the sidebar. Click it to find an experiment and switch to it, or for
**New experiment**, **Import** and **Manage**. A running session keeps running when you switch
(see below).

| Sidebar | Page | What it is for |
|---|---|---|
| Run | **Live session** | Open, start, stop and resume sessions; who is waiting and connected; the events as they happen |
| | **Network** | The session's network, live; add and cut ties |
| | **Players** | Everyone in the session: their screen, choices, ties and properties |
| | **Script console** | Run Groovy in the live engine |
| Build | **Steps** | The experiment's Groovy code |
| | **Content** | The texts participants read, in each language |
| | **Parameters** | Settings chosen each time a session is created |
| | **Participant page** | The HTML, JavaScript and CSS of the participants' page |
| | **Images** | Pictures for content, steps and the participant page |
| | **Preview** | The participant page exactly as chosen players see it, live |
| Recruit & data | **Autopilot** | Run sessions with nobody at the console |
| | **Recruitment** | Prolific and Mechanical Turk |
| | **Sessions & data** | Every session, its events and CSV downloads |
| (bottom) | **Experiments** | Create, import, export, duplicate and delete experiments; file mode |
| | **Settings** | Password, researchers' accounts, backups, appearance |

On a narrow screen the sidebar opens from the menu button at the top left. The top bar shows:

| Item | |
|---|---|
| The session | Its status and name ("Running · Session 1"), or "No session". Click it to go to Live session. |
| **Engine** | The script engine that runs the experiment. It reads **Reloading…** while it restarts, and **Reload needed** when steps were saved since it last loaded them (every researcher's console says so, also after a refresh) or a reload didn't finish. With no session running, or the autopilot's own session open, it reads **Steps saved** instead: the next session loads them. Its menu has **Reload engine**. |
| **Reconnecting…** | The console has lost its connection to the server. |
| Output (Ctrl+J) | What the experiment's scripts print and return, and their errors. A red badge counts new errors. |

There is one script engine per server, shared by every researcher's console and the autopilot.
It runs one experiment at a time, which need not be the one you work on: while a session is running
in it (yours, another researcher's or the autopilot's), switching experiments only changes what you
work on, and that session carries on. Your experiment's Build pages and Sessions & data work as
usual. On the Run pages (Live session, Network, Players, Script console, Preview) a notice says
what the engine runs and whose session it is, and your experiment's live controls (Start, Run,
scripts, previews) do nothing until that session has ended; **New session** still ends it, after
asking. Every console hears when that session ends, whoever ends it, and the notice and controls
follow. The notice's **Work on …** switches to the experiment the engine runs; working on that
experiment, your Live session page shows its running session, and each one that opens after it (the
autopilot's next session, say) as it opens, in every tab. When the engine only has another
experiment loaded, with no session running, the notice also offers **Load …**, which loads yours.
Meanwhile the sidebar doesn't count the engine's players under **Players**: they are in the other
experiment's session.

## Building an experiment

### Experiments

The top of the page shows the experiment you're working on, with **Export .zip**, **Rename** and
**Duplicate**. **All experiments** lists every experiment as a card: **Work on this** switches to
it, and its menu has **Export as .zip**, **Rename…**, **Duplicate…**, **Use file mode…** (or
**Leave file mode…**) and **Delete…**. Renaming keeps the experiment's sessions, data and links;
it isn't possible in file mode, whose folder is named after the experiment, and an autopilot set
up by name in the server's settings file (`conf/local.conf`) needs the new name there too.

| Action | |
|---|---|
| **New experiment** | Starts from **Breadboard's template** (the empty steps OnJoinStep, OnLeaveStep and InitStep, and the standard participant page), from **Example: network game**, or from **A copy of an experiment** (its steps, content, translations, parameters, images and participant page, without sessions or data). Names must be unique and can't contain `/` or `\`. |
| **Import** | Takes a .zip exported from Breadboard, chosen in the dialog or dropped anywhere on the page. It becomes a new experiment, named by the same rules; nothing you have is changed. |
| **Export .zip** | Downloads the experiment, to move it to another server or keep a copy. |
| **Delete…** | Deletes the experiment with all its sessions and their data, for good. Type its name to confirm (capital letters, spaces and dashes don't matter). Not possible while a session is running. If the autopilot runs it, the autopilot stops opening sessions. |

**File mode** lets you edit an experiment with your own code editor, or keep it in version control.
Breadboard then reads it from `dev/<name>_<id>/` in the server's folder (spaces in the name become
dashes) instead of the database. **Use file mode…** writes the steps, content, parameters, images,
participant page and style there. **If the folder already exists, it is deleted first.** In file
mode, Steps, Content, Parameters, Images and Participant page are read-only in the console and
follow the files, and a step file saved in `Steps/` runs at once while the engine runs the
experiment, during its sessions too. While the engine runs another experiment, a saved step file
isn't loaded (that experiment's session would get it): the output panel says so, and the
experiment's next session, or a reload of the engine while you work on it, loads what is saved.
**Edit in the console again…** replaces the experiment's steps, content, parameters, images,
participant page and style in the database with what is in the folder, which stays on the server.
Neither works while a session of the experiment is running, and **Use file mode…** doesn't while
another experiment's session is.

**Example: network game** is a complete experiment that runs from start to finish by itself, to try
Breadboard out or to build on. People wait in a waiting room until at least 2 are there; computer
players then fill the network up to 8. Each round everyone picks Blue or Orange and scores a point
when their pick matches most of their neighbors'; the network shows what each neighbor picked last
round and the ties among their neighbors, and during a round everyone may cut one of their own ties
by clicking it. The size of the network, the waiting room's minimum and wait, and the number and
length of rounds are launch parameters, so they change on the Parameters page, and everything
participants read is on the Content page, to reword or translate. Picks and scores are
recorded as `ColourPick` and `RoundResult` events. Open a session (or turn on the autopilot) and
open its sign-in link in two tabs to watch it run: in the same browser, the second tab asks whether
to continue as the first person or **Join as someone else**.

### Steps

Steps are the Groovy code that runs the experiment. The list has the **Built in** steps (InitStep,
OnJoinStep, OnLeaveStep: they can't be renamed or deleted, and a missing one has **Add**) and
**Your steps**. **New** adds a **Synchronous** step (waits for choices), an **Asynchronous** one
(no waiting) or an **Empty** one. The **⋯** menu has **Rename**, **Duplicate**, **Revert to
saved**, **Copy the code** and **Delete step** (with **Undo** afterwards).

**Save** (Ctrl+S) saves the step and **Save all** (Ctrl+Shift+S) every changed step. Saving doesn't
change what the engine runs: while a session runs, the page says **The engine isn't running your
latest steps** and offers **Reload engine**, in every researcher's console. **Run**, or **Save &
run** (Ctrl+Enter), loads just this step's code into the live engine instead. It doesn't start the
step. While the autopilot's own session is open, the page says instead that the autopilot's next
session uses what is saved, and **Reload engine** is the lesser button there: a reload would start
that session's game again. While the engine runs another
experiment, **Run** is off and the page says why: saving still works, and the next session of this
experiment uses what is saved. **Reload engine** then loads this experiment, unless the other one
has a session running, which a reload would end.

### Content

Content holds the texts participants read: instructions, consent forms, messages. Scripts show a
text with `c.get("Name")`, shown above the editor with a copy button. Each text is written once
per language: **Languages** sets the experiment's languages, and the language bar switches between
them. **New text** starts from an empty text or a copy of another. Edit in **Visual** or **HTML**;
**Preview** shows the text as participants see it. **Save** saves this text, and the button at the
top right saves every changed text (Ctrl+S). The **⋯** menu has **Rename** (F2), **Discard
changes** and **Delete text…**. After a rename, scripts that use the old name need the new one.

### Parameters

Parameters are settings chosen each time a session is created, such as the number of rounds or a
treatment condition. **New parameter** asks for a **Name**, a **Type** (**Whole number**,
**Decimal number**, **Yes or no** or **Text**), for numbers the **Smallest allowed** and **Largest
allowed** value, a **Default**, and a **Short description** shown when a session is created.
Scripts read each one as a variable with its name, already converted to its type.

### Participant page

Three tabs hold the page: **Client HTML** (the layout, a Vue template), **Client graph** (the
JavaScript that starts the page) and **Style** (CSS, for participants only). **Save …** saves the
tab shown (Ctrl+Enter) and **Save all** every changed tab (Ctrl+S). **Insert the new participant
page** and **Insert the standard script** put Breadboard's defaults in the editor, to save if you
like them. An experiment that uses the previous layout shows a note with **Try the new page**.
Changes reach a participant the next time their page loads.

### Images

Add images by dragging them onto the page, pasting one (Ctrl+V) or **Choose images**. A file with
a name that already exists is saved with a number added. **Copy link** copies an image's address;
its menu has **Copy HTML tag**, **Copy link by ID**, **Preview** and **Delete…**. Prefer the link by
file name: it also works in file mode, where images have no ID. It contains the experiment's ID,
though, so a copy of the experiment imported elsewhere needs its links updated.

### Participant preview

The Preview page shows the participant page of a running session as chosen players see it, live.
Tick players in the list, or pick **Test players** (those your steps marked with `testmode`) or
**AI players**, then choose **Desktop** or **Phone**. **Join as a new participant** opens the
session's sign-in page in a new tab.

**Previews are live.** A click in a preview is a real choice, recorded with the session's data. A
player whose own page is connected is shown read-only: you see their screen as it changes, but
can't answer for them, and their page is left alone. So is a computer player: you see what the
computer does, and it goes on playing. A test player, whose own page hasn't been in the session,
is played through the preview: it signs in as that player, and they count as connected (the
connections it causes are recorded with `preview` = `true`). Anyone else (someone whose own page
has been in the session and isn't now: they left, or the computer took their seat) can be answered
for in a preview, but counts as away all the same, like anyone who leaves, however often you open,
reload or enlarge the preview, also after a **Resume** or a reload of the engine: the computer takes
their seat after the usual wait (when it plays for people who leave), or goes on playing it, and a
waiting room doesn't count them. So **AI players** shows the computer players and the seats the
computer took over, and the computer goes on playing all of them. A preview never adds anyone to the
game: the tile of a player who isn't in the network (someone who left before a **Resume**, say, and
hasn't joined again) says they aren't in the game, and shows their page once they are back. When a player's own page comes back, it takes over, and the preview goes on
read-only. In the list, a green dot means the seat is connected; its tooltip says whether by their
own page or by a preview (maybe another console's), or that a preview stands in for someone away.
The first look at a session shows the test players whose seat is neither connected nor held by a
preview, theirs or another console's (which a preview here would take it from).

## Running a session

### Live session

A session is one run of the experiment. **New session** asks for a **Session name** and the
**Launch parameters**, filled in with their defaults. **Create session** opens it at once, and
people can join with its links. If a session is running, the button reads **Stop it and create**.

| Button | Shown when | What it does |
|---|---|---|
| **Start the game** | The session runs and its game hasn't started | Runs `initStep.start()`: everyone waiting is let in and the experiment begins. Later arrivals are turned away, and a waiting room is cut short. Once started, it does nothing more until the engine is reloaded. |
| **Stop session** | The session runs | Nobody new can join and its game stops: its participants see a page saying the session was stopped (someone who was given a completion code keeps it on screen), and nothing more is recorded in it. Stopping the autopilot's session pauses the autopilot. |
| **Resume session** | The session is stopped | Restarts the experiment's scripts: the network is emptied and everyone is disconnected, then people can join again with its links, and its game starts again from the beginning. The data so far is kept. |
| **Download data** | The session has finished | Its events as CSV. A finished session can't be resumed; start a new one. |

Start, stop and resume ask before they act. Below them, **In the network**, **Connected now**,
**AI seats** and **Ties** count who is there; while an autopilot waiting room fills, a bar shows
how many people are here and the countdown. **Events** lists what the session records, newest
first, as it happens, with what the well-known ones mean (a researcher's preview signing in says
so); click one to see its data. When the experiment's scripts report an error, a card at the top
says what went wrong in plain words and on which line of which step (click it to open the step at
that line); **Full error** has the rest, Breadboard's own code included.

Under **Links for participants**, **This session** works while this session runs. **Whichever
session is open** leads to this experiment's open session whose game hasn't started, so it can be
shared once and reused. **Prolific study URL** goes into Prolific. The links use the address you
opened the console at, so open it at the server's public address before copying them.

### Network

The Network page draws everyone in the session and their ties. What a click does depends on the
tool:

| Tool | Key | |
|---|---|---|
| **Select** | V | Click someone to see their details; drag people to move them. |
| **Tie** | C | Click one person, then another, or drag from one to the other. |
| **Cut** | X | Click a tie. |

Ties can be changed while the session runs and the engine runs this experiment. Every edit goes
through `edgeEdits.adminEdit` and is recorded as an `edgeEdit` event with the actor `admin`. An
edit shows as a dashed line until the server answers; then a message says what happened, with
**Undo** when it worked. Ctrl+Z takes back your edits one at a time, as long as the network hasn't
been rebuilt since. Only ties can be added or cut; other kinds of link are drawn but not editable.
**Find a player** (`/`) takes an id or a filter such as `score>5`. The **⋯** menu downloads the
network as PNG, SVG, JSON or GraphML, and lists the **Keyboard shortcuts** (?).

### Players

The Players page lists everyone in the session. Type an id, or a filter: `score=5`, `score!=5`,
`score>5` (also `<`, `>=`, `<=`), `name~ann` (contains). Join conditions with `&`; dots reach inside
a property (`_system.connected=false`). The buttons above the table show **Everyone**, **People**,
**AI players**, **Connected**, **Disconnected** or who **Has a choice**. **Columns** adds properties
to the table, and **Download CSV** saves the players shown with every property. Computer players
(`g.addAI`) are called AI 1 (`_1`), AI 2 (`_2`) and so on, here, on the Network and Preview pages
and in Live session's events; scripts, filters and the data use their ids. A participant's page calls the people around
them Person A, Person B, … in the order it first shows them, so a player's letter can differ from
one participant's page to another.

Click a player (here or on the Network page) to see what is **On their screen** and their
choices, their **Ties** and **Properties**. Clicking a choice submits it for them, after you
confirm; it can't be undone. Each tie has **Cut**, and **Tie … to…** adds one. **Preview their
screen** opens them on the Preview page. **Preview connected** means a researcher's preview plays
the seat of a test player, whose own page hasn't been in the session. **Away: preview standing in**
means their own page isn't connected and a preview has their seat: the preview can answer for
them, but they count as away.

### Script console

The Script console runs Groovy in the live engine. **Run script** (Ctrl+Enter) runs the editor's
text; with text selected, it reads **Run selection** and runs only that. The line next to it says
where it runs: in the live session, where changes apply at once, or in the experiment's engine when
no session runs. Results appear under the editor, and the script is kept in this browser.
**Library** has ready-made **Commands** (count people, tie two players, add computer players, write
a note into the data, and more) and the **History** of your scripts; commands marked "changes"
change the live session.

### Reloading the engine

**Reload engine** (in the top bar's Engine menu, or on the Steps page) restarts the experiment's
scripts from its saved steps. The engine is shared by every researcher on the server, so everyone
in the network is disconnected and the network is emptied, whoever's session they are in. Your own
running session carries on: it takes people again, newcomers and those who held a seat, and its
game counts as not started, so **Start the game** runs InitStep again. Creating a session and
resuming one restart the engine too, and so does switching experiments while no session is
running. While anyone is using it, the console says who before doing any of these, and whether it
pauses the autopilot. Before a reload that carries a session on (yours, or the autopilot's when it
opened it as your account), it asks while that session's game is in progress or anyone is connected
to it, and says that the game starts again from the beginning and that the people in it rejoin by
themselves. While another experiment's session is running, a reload is refused: it would end that
session.

## Running sessions with nobody at the console

The Autopilot page lets an experiment run by itself: Breadboard opens a session, lets people in
through a fixed link, starts the game once enough have arrived, and opens the next session when one
ends. Saved settings are kept in the database and take effect at once. While the autopilot runs an
experiment, the waiting room and the takeover of seats, each when switched on here, replace the
experiment's own `autopilot.waitingRoom(…)` and `autopilot.replaceDisconnected(…)` calls; callbacks
such as `onCancel` are kept from the experiment's call. Switched off, the experiment's own calls
apply; the page shows them, and when the steps set no waiting room it says that sessions will then
wait for someone to press Start the game. Set up for the first time, the page suggests the
experiment you're working on and leaves to its steps whatever they already set.

| Setting | Suggested | |
|---|---|---|
| **Run this experiment unattended** | off | Keeps a session of the chosen **Experiment** open: right after saving, when the server starts, and after each session ends. Turned off, it opens no new session; an open one keeps running. |
| **Open the next session when one finishes** | on | Also after a session is called off. |
| **Pause before the next session** | 10 seconds | 0 opens it at once. |
| **Longest session** | 120 minutes | Counted from the start of the game. A game that runs longer is ended (see below). Empty: no limit. New settings start at 120 minutes; settings saved with an older release keep no limit until you set one. |
| **Use the waiting room set here** | on (off when the steps set one) | The waiting room: people who arrive early see a waiting page, and the game starts by itself. |
| **Minimum players** | 2 | The game can start once this many people are here. |
| **Maximum players** | none | When this many are here, the countdown starts at once. |
| **Wait for more people** | 60 seconds | Once the minimum is here, how long to wait for latecomers. |
| **Give up after** | never | Counted from the first arrival. If the minimum isn't reached by then, the session is called off and everyone waiting is told. |
| **Countdown** | 5 seconds | Participants see "Starting in … seconds" before the game begins. |
| **Let the computer play for people who leave, as set here** | on (off when the steps set it) | The computer plays the seat of someone who closes their page during the game. |
| **Take over after** | 30 seconds | A grace period, so a page reload or a short network drop doesn't hand the seat over. |
| **Give the seat back when they return** | on | They take over from the computer and play on. |

**Save** (Ctrl+S) saves, after asking when it turns the autopilot on. If another researcher's
session is running, the autopilot waits for it to end; **Save and end it now…** ends it there and
then. While a researcher has another experiment loaded and a console open, the autopilot leaves the
engine to them and opens its session once they close the console. While the autopilot runs, you
can still work on other experiments: switching to one leaves its session running, and their Build
pages and data work as usual. To run or test another experiment, stop the autopilot's session
first (that pauses it) or open a session of the other experiment (which ends the autopilot's,
after asking), then press **Resume** here when you are done.

The top of the page shows the autopilot's state, its **Open session** (with **Watch it live**),
recent activity, any problem that keeps it from opening sessions, and the **Participant links**:
the **Sign-in link** (`/play/<experiment id>/login`) and the **Prolific link**. They always lead to
the session open at the moment, so the same links keep working from one session to the next.

**What pauses it.** Ending the autopilot's session from the console pauses the autopilot: stopping
it, or anything that reloads the engine (creating or resuming another session, reloading); selecting
another experiment doesn't. The console says so before you do it. A paused autopilot opens no
session, so its links lead nowhere until you press **Resume** on this page. After its one session
with **Open the next session when one finishes** off, the button reads **Open another session**.
Both work when the server's settings file (`conf/local.conf`) is in charge too, and a pause
survives a server restart.

**A mistake in a step pauses it too.** When the steps of a session it opens don't load (a typo, a
name that isn't defined), the autopilot stops that session before anyone can join, records
`stepsFailedToLoad` in it, and pauses. This page then says which step and line failed and what
went wrong, in the words the Steps page uses, with a link that opens the step at that line. Fix the
step, then press **Resume**. While the autopilot runs an experiment, its Steps page says so: the
next session it opens uses what you save there.

**A step that fails during the game.** When a step's code throws while the game runs (on a timer,
after a choice, or in OnJoinStep or OnLeaveStep), every open console shows the error in its Output
panel, and the session records a `stepFailed` event naming the step and the line. The failure
*stalls* the game when it kept the next step from getting going; a step gets going when its start
finishes without an error. In the autopilot's own session (also one of its experiment that was open
when you pressed **Resume** or saved its settings), a failure that stalls ends the session, telling
its participants that it had to stop because of a problem with the study (someone who was given a
completion code keeps it on screen), and pauses the autopilot, and this page names the step and
line; so does an OnJoinStep that fails before the game starts when no waiting room is set. A failure
that doesn't stall, such as a typo after the next round has started, is only recorded and shown, and
the game goes on. In a session you run by hand nothing is ended. When **Start the game**, the
waiting room or the OnJoinStep started a game that didn't get going, the start is taken back, so
newcomers can join and **Start the game** works again once you fix the step; a start the experiment
makes on a timer or after a choice is taken back only if InitStep itself fails, and otherwise the
game counts as started. In the autopilot's own session, a **Start the game** that didn't get going
ends the session and pauses the autopilot, as its waiting room's start does: once Start is pressed,
its waiting room no longer starts the game.

**Games that never end.** The autopilot's own session is also ended when its game runs longer than
**Longest session**, whatever keeps it going: its participants are told the session ran out of time
(someone who was given a completion code keeps it on screen), the session is finished with a
`sessionTooLong` event (`minutes`, `limit`), and the next one opens after the pause. This page says
which session it was until you save or press **Resume**. After three such sessions in a row the
autopilot pauses instead; a game that comes to its end starts the count again. And once a step has
failed in its game, a game that has had nothing to wait for during a minute (no choice on anyone's
screen, no step or timer waiting for its time, no form someone has yet to finish, no other input it
listens for; a chat isn't one, as nothing written there moves a game on) is ended as a failure that
stalls it, with a `sessionStalled` event, and the autopilot pauses, naming the step. A session you
run by hand is never ended this way.

Settings saved on this page take precedence over the server's settings file, `conf/local.conf`.
Until some are saved, a `breadboard.autopilot.experiment` line in `conf/local.conf` turns the
autopilot on, and the page only shows the settings
([whats-new.md](whats-new.md#running-without-anyone-at-the-admin-console-autopilot) lists them).
Remove the `breadboard.autopilot.*` lines and restart the server to manage them here.

## Recruiting participants

Live session and Autopilot show the sign-in links. The Recruitment page has a tab for Prolific and
one for Mechanical Turk.

**Prolific.** **Study URL** is the address to paste into your study on Prolific (**Copy URL**). In
Prolific, choose to record Prolific IDs with URL parameters, and leave the parts like
`{{%PROLIFIC_PID%}}` as they are. The link leads to this experiment's open session whose game
hasn't started, and the page says which one that is now, or warns that participants would see
"No session is open right now". **Try it as a test participant** opens it with a made-up Prolific
ID, taking a seat like a real participant.

A participant's Prolific ID is swapped for a player id, so other participants never see it, and
recorded in a `prolificEntry` event. **When a participant finishes** builds the line for your last
step from your completion code, `recruitment.completeProlific(player, [completionCode: "…"])`:
participants then see the code and a **Finish** button that takes them back to Prolific. **From
Prolific in this session** lists who joined and who finished, with **Copy bonus list** for
Prolific's bulk bonus. Each completion is recorded as a `prolificComplete` event.

**People don't take part twice.** Someone who opens the Prolific link again after they took part in
this experiment (they were given a completion code, or were still there when a session's game
finished) sees **You have already taken part in this study** with their code and a button back to
Prolific, also after a server restart, and gets no seat. Someone whose session was stopped or cut
short by a restart may join the open one. The check covers the whole experiment: for a new wave on
Prolific with the same people, copy the experiment. Testing by hand, use **Try it as a test
participant**, which makes up a new Prolific ID each time.

**Mechanical Turk.** The server needs your AWS keys first ([install.md](install.md#settings)); until
then the tab explains how to add them. At the top, choose **Sandbox** (practice) or **Production**
(real workers, real money). Posting to production, approving submissions and creating payment HITs
ask you to confirm first.

| Tab | |
|---|---|
| **HITs** | Your HITs and their submissions: **Approve**, **Reject…**, **Bonus…**, mark workers completed, grant or remove the qualification, download as CSV. |
| **Create HIT** | Posts a HIT that sends workers into the running session. **Start the game by itself** turns newcomers away when the HIT closes and starts the game after the tutorial time. Workers reach the server at `breadboard.rootUrl`, which must be a public https address. |
| **Payment HITs** | Pays workers for work done outside a normal HIT, with a private HIT for each, to approve on the HITs tab. |
| **Workers** | Everyone who submitted one of your HITs, with **Add past participants…**. |
| **Qualifications** | Each experiment's participant qualification, which new HITs can require or use to keep previous participants out. |

## Data

**Sessions & data** lists every session of the experiment, to filter by status, search by name, ID
or parameter, and sort. Each row has buttons to stop or resume the session, view its events and
download its data. Click a row for its launch parameters, its participant link while it runs, and
**Delete…**: only stopped or finished sessions can be deleted, with their data, for good.

**View events** lists a session's events as a **Timeline** or **By event**, to search and filter.
**Export all** (or **Export filtered**) saves the events listed; **Export as table** saves one kind
of event with a column per data name.

| Download | Where | Contents |
|---|---|---|
| **Download data** | Live session, Sessions & data | `<session>_<id>.csv`: one row per data item of each event (an event without data, such as `Finished`, gets one row with empty data columns), with the columns `id`, `event`, `event_date`, `data_name`, `data_value`, in the order the events happened. A researcher stopping and resuming the session is recorded as `sessionStopped` and `sessionResumed`; after a resume the game starts again from the beginning. A researcher's preview signing in as a player is recorded as `clientLogIn` with `preview` = `true`, and so are the `clientConnected` / `clientDisconnected` of a preview that plays a test player's seat. |
| **Download all data** | Sessions & data | `<experiment>_data.csv`: the events of every session listed (all of them, or those the search and filter leave), in one file. Each row starts with `session_id`, `session_name`, `session_status` and a column per launch parameter, then the columns of a session's own file. Sessions oldest first. A session the server was running when it stopped has a `sessionInterrupted` event; one whose steps didn't load when the autopilot opened it, `stepsFailedToLoad`. |
| **Download list** | Sessions & data | `<experiment>.csv`: one row per session, with `id`, `instance` (its name), `status`, `created_at` and a column per launch parameter. |
| **Export all**, **Export filtered** | A session's events | The same columns as the session's file, with times in standard ISO 8601 (`2026-10-28T13:24:23.757Z`). Events without data keep a row. |
| **Export as table** | A session's events, By event | One row per event of that kind, one column per data name. |
| **Download CSV** | Players | The players shown, with every property. |

Timestamps in the CSV files are in UTC, written as `2026-10-28T13:24:23:757Z` (with a colon
before the milliseconds, as earlier versions of Breadboard wrote them, so existing analysis scripts
keep working). In R, `as.POSIXct(sub(":(\\d{3})Z$", ".\\1", x), format = "%Y-%m-%dT%H:%M:%OS", tz = "UTC")`
reads them; in Python, `pandas.to_datetime(s, format="%Y-%m-%dT%H:%M:%S:%fZ", utc=True)`.
Sort rows by `event_date`, not by `id`: ids are given out in blocks and don't follow the order
events happened in. After a session finishes or is stopped, nothing more is recorded in it.
Values are written exactly as recorded. A spreadsheet may
read a value that starts with `=`, `+`, `-` or `@` as a formula, so import the file as text when
participants could type such values.

## Settings

| Section | |
|---|---|
| **Account** | The email you are signed in with, **Sign out**, and the **Default language** new content is written in. |
| **Password** | **Current password**, **New password** (at least 8 characters) and **Repeat the new password**, then **Change password**. You stay signed in; other browsers signed in to this account are signed out, and previews must be opened again. |
| **Researchers** | The server's accounts. **Add a researcher** takes an email address and shows the new account's password once (**Copy sign-in details** copies the address, email and password to pass on); they change it in Settings after signing in. **New password** gives another account a new password (shown once) and signs it out everywhere; **Remove** deletes another account and signs it out (its experiments and data stay). You can't remove your own account. |
| **Backups** | The copies of the database the server makes in `backups/` (every day, and before an upgrade; the newest 14 are kept). **Back up now** makes one; **Download** saves one. To go back to a backup, see [install.md](install.md#backups). |
| **Appearance** | **System** (follows your computer), **Light** or **Dark**, saved in this browser only. The menu under your email has the same choice. |
| **Previous console** | **Open it** goes to `/legacy-admin`. |
| **About** | The console's version, the server, the connection and the number of experiments, for when you ask for help. |

## The previous console

The previous console is still at `/legacy-admin`, while researchers move over. Open it from the
sign-in page, from **Open the previous console** in the menu under your email, or from Settings. It
works with the same experiments and sessions and the same script engine, and both consoles can be
open at the same time. Both show when the session you follow ends, and the next session of your
experiment as it opens.
