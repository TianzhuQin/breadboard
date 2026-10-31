# Changelog

### [v3.1.0] - 2026-10-29
Easier to install, safe to leave running by itself, steadier for participants on weak connections,
and ready for a lab of several researchers. Existing experiments keep working; to upgrade from 3.0,
see [docs/install.md](docs/install.md#upgrading).

#### Installing and running
- Downloads for macOS (Apple silicon and Intel), Windows and Linux that carry their own Java 8:
  nothing else to install. The Java is a pinned Azul Zulu 8 build (`release/jre.lock`), with its
  licence files in `jre/`. On a Mac, `Start Breadboard.command` starts it with a double-click; the
  launchers open the console in the browser once the server is up, and again when Breadboard is
  started a second time while it runs. They print the addresses it is reached at: on this computer,
  and on each network it is on (the one it reaches the internet through first).
- Settings go in `conf/local.conf`, which the launchers create and which you copy into each new
  release when you upgrade; a release replaces `conf/application-prod.conf`. The port (`http.port`)
  and the server's memory (`breadboard.memory`) go there too, for every start, a double-click
  included: when the port is taken or the server runs out of memory, the launchers say which line to
  change, or give a command that works from where you are. Curly quotes in it (TextEdit's Smart
  Quotes, a Chinese input method) are named with their line before the server starts, and a
  `conf/local.conf` the server can't read is refused at start, with how to fix it.
- On macOS and Linux, the database, its backups, the logs and `conf/local.conf` can be read only by
  the account the server runs as, also when they were copied from an earlier release.
- While the server runs, the computer doesn't go to sleep by itself (macOS and Windows; on Linux the
  launcher asks systemd, and GNOME, to keep it awake).
- Keep-running mode: the launcher starts the server again after a crash or when it runs out of
  memory (`logs/out-of-memory.hprof` tells you it did, and `logs/jvm-pid<N>.log` what Java said),
  and says why in its window and in `logs/application.log`, also when it leaves it stopped (in the
  log first, so a paused window can't hold that up). On Windows, exit code 1 is said to mean the
  server's own stop after an error, a crash of Java (its report in `hs_err_pid*.log`) or an end from
  outside, not the first alone. It checks the port before each restart, and doesn't start again a
  server that never started (a mistake in `conf/local.conf`, its port taken):
  `logs/start-errors.log` keeps what Java printed.
  Ready-made recipes for starting it with the computer: launchd (macOS), a Task Scheduler task
  (`breadboard-task.xml`, Windows) and systemd (Linux); as a Windows service, Java's own errors go
  to `logs/service-errors.log`. `/health` (GET or HEAD) for uptime monitors.
- A stop that hangs ends after 20 seconds, and the database is always closed properly at the end of
  a stop, so nothing committed in its last moments is lost.
- Stopping the server ends the running session's game first (nothing plays on behind its
  participants), and their pages say what to do: open the study's link again in a few minutes, or,
  for Prolific participants, open the study again from Prolific or return it.
- The launchers work behind a proxy set in `http_proxy` (common in China) and accept `JAVA8_HOME` as
  the folder Finder shows for a JDK. When the port is taken, by Breadboard itself or by another
  program (also one listening on `[::1]` only), they say so, with a command for another port that
  works from wherever you are. A server window that stops taking output (a click in a Windows
  console, Ctrl+S in a terminal, a pipe nobody reads) no longer holds the server up, nor its restart
  after a crash. The Windows launcher refuses to run from inside the zip.
- On a new server, the first account can be created from another computer (or through a proxy) only
  with the setup code the server prints in its window and log. It can be created as soon as the
  server answers; the setup page waits by itself while the server is still starting.
- Automatic backups of the database while the server runs: every day and before an upgrade. The
  newest 14 of each kind (daily, made by hand, before an upgrade) are kept in `backups/`, so many
  clicks on **Back up now** don't push out the daily ones. Settings → Backups lists them with the
  time in their names (also those copied from an earlier release), backs up on request and downloads
  them; [docs/install.md](docs/install.md#backups) says how to go back to one.
- Session ids follow on after a crash: they used to skip up to 32 numbers after a kill or a power
  cut.
- The docs cover Gatekeeper on macOS 15, Windows' Unblock and firewall prompt (**Allow access**, for
  the kind of network the computer is on), running from a read-only folder, and what to do when
  something goes wrong.

#### Running studies by itself (the autopilot)
- A session the autopilot opens never hangs on a mistake in the steps: when they don't load, it is
  stopped before anyone joins; when a step fails during the game in a way that keeps the next step
  from starting, the session ends, participants are told, and the data records `sessionFailed` with
  the step, line and error. Either way the autopilot pauses and its page names the step and line. A
  mistake that leaves the game going is recorded (`stepFailed`) and shown, and the game goes on.
  Sessions a researcher runs by hand are never ended this way: a start that didn't get going is
  taken back, so Start works again.
- **Longest session** (Autopilot page): the autopilot's own session is ended when its game reaches
  that length, participants are told it ran out of time (a completion code stays on screen), and the
  next one opens; three in a row and the autopilot pauses. New settings start at 2 hours; settings
  saved with 3.0, and a settings-file autopilot without `maxSessionMinutes`, keep no limit.
- A backstop for games that stall after a mistake: once a step has failed, a game with nothing left
  to wait for during a minute is ended and the autopilot pauses, naming the step.
- Participants see an honest end screen: that the session ran out of time, had to stop because of a
  problem with the study, or ended because the server restarted, with what to do on Prolific for
  those who came from there; a researcher's Stop keeps its own.
- **Maximum players** is a real maximum: the first to arrive are seated, and anyone else there as
  the game starts is told the session is full, with a link to the next session (from Prolific: open
  the study again there, or return it), and a `sessionFull` event. While a full room counts down,
  the study's links send nobody more to it.
- Between sessions and while a game runs, the study's links (sign-in and Prolific) show a page that
  says a session is in progress and the next opens when it ends; it checks again every few seconds
  and takes people in once a session opens. Prolific participants read that they can keep it open or
  return the study. With the autopilot off, paused or done, with **Open the next session when one
  finishes** off, or while something keeps it from opening the next session (another experiment's
  session is running, a researcher has another experiment loaded, the disk is almost full: what the
  Autopilot page reports), the links still say no session is open.
- **Reload engine** carries your running session on, and the autopilot's too when you saved its
  settings, and asks first when a game is in progress or anyone is connected: its game starts again
  from the beginning, the pages of those who held a seat get back in by themselves, newcomers are
  taken as before the game, the autopilot goes on running the session, and the data records
  `engineReloaded` (`restarted`, `by`). From an account the autopilot doesn't run as, the console
  says that a reload would end that session and pause the autopilot.
- A session a researcher opens by hand is theirs: it runs with the steps' own waiting room and
  takeover, isn't marked "Opened by Autopilot", and ending it doesn't pause the autopilot, which
  then opens its next session as usual (nor does the server tell a researcher on another experiment
  that it would). The Autopilot page shows it as the open session, with who opened it, whether it
  takes participants and **Watch it live**, and says that the autopilot waits for it to end; the
  autopilot opens its own once it has ended (paused or done, Resume hands it over). The Autopilot
  page's settings apply to the sessions the autopilot opens, and to one that Resume or a save hands
  it; ending one of those still pauses it.
- A stopped session stays stopped: computer players no longer play it on to its end, so it can be
  resumed. Nothing happens in a session once it has ended: a page closed after its game finished no
  longer hands that seat to the computer (an `aiTakeover` recorded in the finished session).
- Sessions that were running when the server stopped are marked Stopped when it starts again, with a
  `sessionInterrupted` event, and the autopilot opens the next one; participants' pages stop
  reconnecting and say the session ended because the server stopped or restarted, also after a crash
  (kill -9, a power cut). Resume starts a stopped session's game again.
- A resumed session's steps load with its launch parameters, as do those of a session that a reload
  of the engine carries on: a waiting room sized by them no longer falls back to the experiment's
  defaults.
- Prolific participants who already took part are shown their completion code again instead of being
  given a second seat, also after a restart.
- Calling `recruitment.completeProlific` for someone who didn't come from Prolific (a test
  participant, someone who typed their ID) does nothing for them and says so in the server's log; it
  used to stop the step, so the game stalled.
- The autopilot opens no new session while the disk that holds the database is almost full.
- Memory stays level over long unattended runs: replaced script engines are freed, their classes too
  (they used to pile up until Java's class space was full), computer players share one timer, and
  seats of finished sessions are released.
- Rebuilding the script engine, as every new session and **Reload engine** do, takes about half a
  second instead of two or more: with a 2-second pause, the autopilot's next session opens about 2.5
  seconds after the last one ended, instead of 4.4. Live session and the Autopilot page say so.
- The Autopilot page leaves the waiting room and takeover to the steps of an experiment that sets
  them, and suggests giving a lone participant ten minutes before calling the session off.
- While the autopilot's own session is open, Steps and the top bar say that its next session uses
  what you save, instead of asking for a reload that would start its game again.
- The Autopilot page explains a pause for a mistake in a step as the Steps page does, with a link
  that opens the step at that line in the autopilot's experiment (asking first to switch to it). Its
  status line says why it paused, and its recent activity lists step failures, also those the game
  went on after ("RoundStep failed at line 72; the game went on"), and what stopped or ended its
  session.
- An Autopilot setting that needs fixing is scrolled into view, clear of the save bar, and the
  page's messages no longer cover Save.
- The autopilot names its sessions "Autopilot · 2026-10-30 13:20:47 UTC", which wrap the same in
  every row of Sessions & data, its search finds as typed or pasted, and whose data files download as
  `Autopilot - 2026-10-30 13-20-47 UTC_14.csv`.

#### Participants' connections
- A page reconnects within a second after a short drop, backs off up to 30 seconds while the network
  is down, and tries again at once when the network or the page comes back.
- Connections that die without closing are noticed: the page says so within about 20 seconds, and
  the server counts the participant as gone after 30, so the computer takes over after the usual
  delay.
- Two tabs of one participant no longer take the seat from each other: the older one says the study
  is open elsewhere, with **Use it here**. An older tab whose connection comes back by itself leaves
  the seat to the newer page, and takes it back by itself once that page has gone. The tab the seat
  was taken back on, by **Use it here** or by itself, keeps it when the other one reconnects in the
  background (it counts as opened last, in the server's time). A network that
  blocks the live connection is told so, also after the page had connected (while the server answers
  over plain HTTP; while the server is down, the page says the connection was lost).
- While the page is reconnecting, a note at the top of the page says so and moves the page down
  rather than covering the timer and the round's heading, its buttons are disabled, and answers that
  waited too long are dropped instead of being sent late. So is a choice or tie change that reaches
  the server more than 10 seconds after it was given (clicked just before the connection died
  without a word): the page then shows it as still to be made.
- An outage the page notices is recorded once, as a `connectionGap` with how long the page was out
  of touch, not as a disconnection and a connection a moment apart, or not at all.
- Someone whose browser was closed gets back to their seat for 24 hours, from the study link or the
  page's address.
- People away for a moment as the game starts keep their seat (the computer plays it until they are
  back); someone away longer sees that the session went ahead without them. A drop of a few seconds
  in the waiting room no longer restarts the wait.
- However a session ends (Stop, a new session or a reload that replaces it, the autopilot, the
  server's stop), every participant's page shows how it ended and stops reconnecting, also a page
  that comes back later after missing a reload of the engine: never a game that no longer answers. A
  completion code on screen stays. Ending a session never holds up the engine, even as the game
  changes the network.
- A researcher's preview of a participant only watches: their own page is never disturbed, and if
  they leave, the computer takes over as usual. Previews of the test players a researcher creates
  still play them. In a real session, a preview of someone who isn't in the game says
  "Not in the game" and adds nobody, and a preview never counts as a participant being there, also
  after a Resume or a reload of the engine. A preview standing in for someone who left the waiting
  room shows the room as it is now.
- The study's shared link asks a browser that already has a seat whether to continue as that person
  or join as someone else, so a study can be tried in several tabs of one browser.
- Identical updates are no longer sent again, which helps weak connections keep up.
- The participant's network keeps everyone in their place when ties change; a newcomer sits next to
  someone they are tied to.

#### The console and the lab
- Researcher accounts: Settings → Researchers adds, removes and gives new passwords to the lab's
  accounts (passwords are shown once, to pass on; **Copy sign-in details** gives the server's
  address on the lab's network when the console is open at localhost, or says that the address works
  on this computer only). Every account works on every experiment on the server.
- Work on any experiment while the autopilot or a colleague runs another: its Build pages and data
  are there, and the Run pages say what the engine runs and how to run yours. Every console hears
  when a session opens or ends, and follows the sessions of the experiment it works on (the previous
  console too). Before a reload or restart that would end a running session, the console says whose
  it is: the autopilot's, a colleague's, or yours.
- Sessions & data: **Download all data**, every listed session's events in one file, each row with
  its session and launch parameters.
- Sessions & data: **For Excel**, remembered in this browser, downloads the CSV files with the
  byte-order mark Excel and WPS need to show Chinese; it applies to Live session's **Download data**
  and the Players page's **Download CSV** too, and the server's CSV addresses take `excel=true`.
  Without it the files are as before. The files saved from **View events** (**Export all**,
  **Export filtered**, **Export as table**) write times as the server's CSV files do
  (`2026-10-28T13:24:23:757Z`), so one way of reading times reads every file.
- Events recorded in the same millisecond keep their order in the data; they could swap places.
- The console says where computers on the same network reach the server, wherever it shows
  participant links while it was opened at localhost (links that work on this computer only).
- Help in the menu under your email: the console's guide, the Chinese quick start, troubleshooting.
- The first-account form says what **Default language** does: the language participants' texts are
  written in on the Content page, which new experiments are set up for; the console stays in
  English. A new server names the languages in English, also on a computer set to another language
  (on a Chinese system they were named in Chinese for good), and the Chinese quick start names them
  by their codes (`eng`, `zho`).
- Parameters says which sessions get a parameter's default: the autopilot's, and the first New
  session, which after that fills in the last session's values (**Use default** brings a new default
  in). It said that every new session was filled in with the defaults.
- The network game example: its network size and waiting room are launch parameters, and everything
  participants read is on the Content page; it is offered first, named Network game, when there is
  no experiment yet, and an empty Experiments page points to it. Its first round no longer shows
  "Your score: 0 of 0", a cut tie is recorded as `TieCut` (who cut it, whom, in which round) instead
  of `Disconnected`, and for an account whose Default language isn't English its English texts stay
  English, with that language added next to them to translate.
- Experiment names are unique on the server, can't contain `/` or `\`, and every open console
  updates its experiment list when anyone makes, imports, renames or deletes an experiment. Import
  fills in the experiment's own name from the zip, with "(copy)" when it is taken; the previous
  console's Import keeps the chosen file and name when a name is refused.
- "Reload needed" comes from the server: every researcher's console says the same, also after a
  refresh and as soon as the autopilot is switched off while its session goes on, and never just
  while the engine loads the steps. It doesn't show after saving steps while no session runs: the
  next session loads them.
- Computer players are named alike everywhere in the console ("AI 6 (_6)").
- The events lists say in plain words what the well-known events mean (failures, sessions ended for
  stalling, failing or running too long, server stops, reloads, full sessions, participants'
  comments, dropped answers, connection gaps, the computer's choices, missed starts, Prolific
  completions), name the step and line of a failure, say when a researcher's preview signed in, and
  show failures in red and warnings in amber.
- Engine errors on the Live session and Autopilot pages are explained as on the Steps page. They
  name the step that failed (`step` in the error the console gets): Live session's error card says
  "Line 71 of RoundStep" and opens that step at that line, in the experiment the engine runs (asking
  to switch to it first); it shows the whole message and **Full error**, which alone lists
  Breadboard's own code.
- A finished or stopped session's Network card says the session has ended, not that no one has
  joined yet.
- Participants type an ID to join, any name or number, one seat per ID: Live session's links, the
  message after creating the example and **Join as a new participant** say so. **New session** says
  that the session opens right away and that its game starts when you, or its waiting room, start
  it; the console calls the button Start the game everywhere.
- Recruitment → Prolific: the Study URL's note, and the one under the Autopilot page's Prolific
  link, say that Prolific needs the server's public address. While the autopilot runs the
  experiment, Recruitment says when the study link holds newcomers until the autopilot's next
  session opens, and warns when something keeps the autopilot from opening it (another experiment's
  session on the engine, a colleague with another experiment loaded, a problem on the Autopilot
  page): the link then says no session is open and asks Prolific participants to return the study,
  so recruiting is best paused. The Autopilot card beside it follows the same answers (On, Paused,
  Done, held up), asked again every 10 seconds, and no longer promises that people from Prolific
  always find a session.
- Recruitment → Prolific: **When a participant finishes** says when an experiment gives the
  completion code by itself (a `prolificCode` parameter, as the example has, set on Parameters);
  otherwise **Copy code** gives code that completes only Prolific participants.
  **From Prolific in every session** lists who joined and who finished in every session, with their
  codes and bonuses and **Copy bonus list** for all of them, so a session the autopilot has moved on
  from can still be paid. Both lists count someone the game ended for without a completion code as
  finished (**No code**), and the list of the session shown no longer says Playing once it has
  ended, nor lists the players of another session the engine runs.
- What a Prolific participant writes under "Comments for the researcher" on the finish screen is
  saved in their session's data (`participantComment`), also once their game has ended and the next
  session has opened: in View events and every download. It used to be lost. A finish screen left
  open can still send it a week after the session ended, also after many more sessions or a restart
  of the server, and when it can't be saved the screen says so, with **Try again**, instead of going
  on to Prolific without it.
- Preview shows the session's test players as soon as it opens, also after a reload and beside a
  player opened with Preview their screen; on a phone the player list folds away above them. Its
  tiles and player list say Watching where a preview only watches (a connected participant, a
  computer player) and Away where it stands in for someone who left, with what that means now: a
  waiting room doesn't count them, when the computer takes over, whether they get their seat back.
  The full-size view says what a click does for that player; two Medium tiles fit side by side on a
  laptop screen; the player list shows whole Prolific IDs; Resume an earlier one shows only when
  there is a session to resume.
- The sidebar fits laptop screens; every page says when the console isn't connected, and that a
  stopped Breadboard needs starting again (Offline on a phone); the sign-in page says how to get a
  new password; the previous console opens in a new tab and links back.
- On a phone, the sidebar closes after you switch experiments, Parameters shows each parameter as a
  card, and stat cards wrap their second line instead of cutting it.
- Content's list previews read as the page shows them, with no space before punctuation after bold
  text.
- The Chinese quick start uses the console's own names and short steps: the example's Prolific
  completion code (`prolificCode` on Parameters, **Use default** in New session, then a test run
  with **Try it as a test participant**), the Autopilot settings, the messages a taken port really
  gives, curly quotes and Windows Firewall, and opening the CSV files in Excel or WPS and reading
  their times in pandas and R.
- Participant pages no longer print Vue's notices for developers in the browser console, and their
  scripts and styles are sent compressed.

#### For experiment authors
Details are in [docs/whats-new.md](docs/whats-new.md).
- Forms and chat stop listening once they are done: a finished form, and a chat switched off with
  `disableTextChat` (which now really switches it off), leave no listeners behind. Turning chat on
  again, or starting a form again, no longer handles each message twice, and ending a form no longer
  runs `onDone` again for someone who had finished it.
- The page's connection: `Breadboard.connection` and the `bb:connection` event; `ConnectionStatus`
  passes `state`, `tryNow` and `useHere` to its slot. Choices and tie changes say when they were
  given (`bbSentAt`, in the server's time as the page reckons it from the heartbeat's `pong`).
- New events in the data: `stepFailed`, `sessionTooLong`, `sessionStalled`, `sessionFailed` (`step`,
  `line`, `error`), `missedStart`, `sessionFull`, `engineReloaded` (`restarted`, `by`),
  `participantComment` (`player`, `text`), `aiChoice` (the computer's picks), `connectionGap`
  (`player`, `awayMs`) and `answerDropped` (`player`, `ageMs`); `clientConnected` and
  `clientDisconnected` only on real changes, marked when a preview caused them.
  `_system.disconnectedAt` and the waiting room's `departureGraceSeconds`.
- A text not yet translated into the language the steps chose (`c.setDefaultLanguage`) is shown in
  English, or else in the language it was first written in, instead of stopping the step. A version
  that is there but empty is shown as it is, nothing, as the Content page warns.
- A tie change an experiment's `onEdit` makes itself is answered as made (`applied: true`): the
  participant's page says "Tie cut.", not "Your change was sent."
- The new participant page's comment links to the previous default page (SVGGraph) on GitHub, since
  a release doesn't carry that file.

### [v3.0.0] - 2026-10-28
The first release of this fork ([TianzhuQin/breadboard](https://github.com/TianzhuQin/breadboard)),
based on Breadboard v2.4.0. Existing experiments keep working, and a v2 database can be copied
over (see [docs/install.md](docs/install.md#upgrading)). Details for experiment authors are in
[docs/whats-new.md](docs/whats-new.md).

#### Added
- A new researcher console at the server's address, with every feature of the previous one
  (which stays at `/legacy-admin`): experiments, steps, content, parameters, participant page,
  images, previews, sessions and data, Mechanical Turk, settings. New: a live session view, a
  network view where researchers draw and cut ties, a script console with run-selection, light
  and dark themes.
- A new participant page for new experiments (`BBGame`, `BBNetwork`): the participant and their
  neighbors on a ring that fits phones and computers; ties added or cut by clicking, with
  confirmation, reasons for unavailable moves and an accessible list of every move. Restyled
  sign-in and message pages.
- Visibility: `g.setVisibility("egoTies")` shows participants the ties among their neighbors.
  Neighbors appear under random aliases; engine bookkeeping is no longer sent to them.
- Tie editing by participants and researchers (`edgeEdits.allow`, `edgeEdits.adminEdit`), with
  validation, proposals and an `edgeEdit` event naming who made each change.
- Autopilot: sessions open, fill a waiting room, start, replace people who drop out with computer
  players, and finish with no researcher present; set on the console's Autopilot page or in the
  settings. One link (`/play/{experiment}/login`, or the Prolific URL) leads to whichever session
  is open.
- Prolific entry (`/game/{e}/{i}/prolific?PROLIFIC_PID=...`) with pseudonymous ids and the
  completion screen.
- Steps with a time limit (`step.duration`).
- Experiments can be renamed, and Settings changes the default language.
  `-Dbreadboard.resetPassword=<email>` resets a forgotten password.
- Choices can have a colour (`color:` in `a.add`); `player.private.finished` ends the participant
  page on its last screen without the network.
- **Example: network game**, offered by New experiment: a complete experiment that runs by itself
  (waiting room, ties among neighbors, cutting ties on the network, timed rounds, a score and
  recorded picks), to try Breadboard out or build on.
- Release packaging (`bin/make-dist`, `bin/smoke-dist`), continuous integration, and
  documentation in `docs/`, including a Chinese quick start.

#### Changed
- A new install creates its database at first start; releases no longer ship a pre-built one.
- Start scripts generate a private `application.secret` instead of using a published one, check
  for Java 8, run in UTC and remove a stale `RUNNING_PID`.
- Much smaller downloads: the participant page's script went from 5.6 MB to 0.5 MB and the
  previous console's from 21 MB to under 3 MB; the release zip from 140 MB to about 60 MB. The
  new console loads its code editor only on the pages that have one.
- Sessions close to newcomers whenever their game starts, unless `breadboard.allowLateJoin` is
  set.
- Participants whose session isn't running (it finished, or the server restarted) are told so
  plainly, with what to do next; only Mechanical Turk workers are asked to return the HIT.
- CSV exports are in true UTC and keep values exactly.
- Login events no longer store IP address, user agent or referer.
- A participant page never stays blank: until the experiment shows something (and between two
  screens) it says the participant is in and to keep the page open. The waiting room says when
  enough people are there and the session starts soon.
- Releases log at INFO level to a daily `logs/application.log` (30 days kept), set in
  `conf/logger.xml`; Play ignored the levels in the settings file, so releases logged debug output.
- Researcher sign-in ignores the email's case, and signing in on another browser no longer signs
  the first one out (signing out or changing the password still signs out every browser, and the
  console says why). The first account needs a valid email address.
- A participant who closed the page goes back to their seat through the sign-in page or the
  study's shared link, also after the game has started; IDs are trimmed. Late arrivals, unknown
  sessions and cut-off links get clear pages.
- Stopping a session tells its participants; stopping and resuming are recorded in the data
  (`sessionStopped`, `sessionResumed`), and the session CSV keeps events that have no data.
- The Autopilot page shows the experiment's own waiting-room settings and doesn't replace them
  unless asked to; its Resume is in the status card. The waiting room tells participants and the
  console how many seconds remain before the start.
- New sessions start from the last session's launch parameters. Duplicated steps get their own
  step name. The Recruitment page warns when an experiment never gives Prolific participants a
  completion code. Uploads that aren't images are refused.

#### Fixed
- Anyone who knew a participant's page address could take over their seat. Every way in now
  hands out a personal token.
- Pages and previews could act with the researcher's sign-in; previews now run in a sandbox with
  a token for that player only. Sign-out and Mechanical Turk approval take a POST.
- A step without choices ended at once; idle and drop timers dropped players who had answered.
- Removing tracked ties threw an error after logging the removal.
- A tie cut confirmed just as a round ended was made in the next round and used up its allowance;
  each `edgeEdits.allow()` is now a turn of its own.
- Debug logging wrote researchers' session tokens to the log.
- `recruitment.completeProlific` was only loaded with `breadboard.experimental` on, so Prolific
  completion codes failed in a release; it is always loaded now.
- Reloading a finished or stopped page lost its last screen (a score, a completion code); after a
  stop, the game's timers kept recording into the stopped session.
- The participant's network could be drawn inside out, in a corner, when the game started while
  the page was still being laid out.
- The console window itself could scroll away (with a blank area below) on pages with hidden
  helper text or switches.
- A Script console typo was reported on Live session as an error in the experiment; tie cuts
  showed as participants disconnecting; a researcher's preview of a connected participant kept
  taking their seat after it was closed.
- OnLeaveStep never ran; `ReadyUpSequence` ignored its options.
- Simultaneous joins could fail with ConcurrentModificationException.
- Groovy interpolated strings reached browsers as objects instead of text.
- A fresh database built from the evolutions lacked `experiments.file_mode`, and so did v2.3
  databases copied over; upgrading a v2.2 database crashed on it, and never actually gave its
  experiments the then-default participant page.

### [v2.3.0] - 2018-02-26
#### Added
- Added module for administering AMT HITs within breadboard
- Import and export experiments from the browser
- Supports translating experiment content in multiple languages
- Improved performance: now defaults to a polling for client updates
- Player client is now responsive and mobile friendly
- Improved security

#### Changed
- UI updates including a new taskbar and tabs that show changed / saved state
- Players dialog now lists players by ID and formats player data in JSON format 
- Style, Client Html and Client Graph dialogs have been moved to tabs of a Customize dialog
- Parameters inputs now enforce selected type, min, and max values
- Breadboard now supports JDK 7/8/9

#### Fixed
- Fixed an issue where the bandwidth test fails when AMT workers join a game
- Fixed an issue where player timers may be inaccurate if their computer's clock differs from the server
- Fixed an issue where a.remove(Vertex player) and a.remove(String playerId) behavior was inconsistent

### [v2.2.4] - 2016-07-05 
#### Added 
- Added support for html5 'required' attribute in HTML forms added to custom property of public actions
- Added LICENSE file to download
- Now client login will check password if a password parameter has been set for the experiment
#### Changed 
- Changed 'Disallow previous players?' options in the AMT dialog to be less confusing
### Fixed 
- Resolved 'Not a valid Play application' error with Windows breadboard.bat batch file
- Fixed issue with recording connections in data when using the Watts-Strogatz graph
- Changed default Client HTML to support required input elements added using the custom property of the a.add method
- Resolved 'No such Property: onLeaveStep' error when launching new experiment instance with nodes in the graph
- Fixed bug where multiple instances with the same name will be highlighted in the Launch dialog if one is RUNNING
- Fixed an error where edge properties are no longer displayed in the graph dialog after browser refresh

### [v2.2.3] - 2016-04-01
#### First public release!
#### Added 
- Now can launch on Windows PCs using breadboard.bat 
- Settings can now be modified using the application-prod.conf file 

### [v2.2.2] - 2016-03-09
#### Fixed 
- Fixed custom PlayerActions with regards to checkboxes

### [v2.2.1] - 2016-03-01
#### Added
- The wattsStrogatz graph algorithm implementing the Watts-Strogatz small world algorithm
- A edge.randV() function that returns a random vertex attached to the edge
- Settings can now be set using an application-prod.conf file in the root of the breadboard directory

#### Changed
- Removed the confusing 'Extend HIT' and 'Assign Qualification' buttons from the AMT Assignments dialog
- Updated the README

#### Fixed 
- Fixed support for checkboxes and radio buttons as input options for custom player choices

### [v2.2.0] - 2015-12-02
#### Added 
- A Client Graph dialog that allows users to modify the players' graph view without modifying source code
- Now hashing and salting admin passwords with BCrypt
- breadboard with an empty Users table prompts user to add first user 

#### Fixed 
- Added proper syntax highlighting to the Client HTML dialog

### [v2.1.0] - 2015-11-05
#### Added 
- A Client HTML dialog that allows users to modify the players' client view without modifying source code

#### Changed
- The admin graph function name from 'graph' to 'Graph' to match javascript conventions
- Moved TimersCtrl to its own client-timer.js file
 
### [v2.0.0] - 2015-10-29
#### Added
- The initial release version of breadboard v2

### A note on version numbers:
The version number will be incremented based on the following system, given a version number vX.Y.Z (e.g. v2.1.0): when
X (major) is incremented it means a new codebase that is not backward compatible with other major versions, when Y 
(minor) is incremented it means new features have been added that may require database evolutions but are compatible 
with databases created with software with the same major version number, and when Z (patch) is incremented it means
 that bugs with the features of the minor version have been fixed.
