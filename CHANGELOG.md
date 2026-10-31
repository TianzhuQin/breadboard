# Changelog

### [v3.1.0] - 2026-10-29
Easier to install, safe to leave running by itself, steadier for participants on weak connections,
in Chinese for participants of a Chinese study, and ready for a lab of several researchers. Existing
experiments keep working; to upgrade from 3.0, see [docs/install.md](docs/install.md#upgrading).

#### Installing and running
- Downloads for macOS (Apple silicon and Intel), Windows and Linux carry their own Java 8 (a pinned
  Azul Zulu 8 build, with its licence files in `jre/`): nothing else to install. On a Mac,
  `Start Breadboard.command` starts it with a double-click. The launchers open the console in the
  browser once the server is up (and again when Breadboard is started a second time while it runs),
  and print the addresses it is reached at: on this computer, and on each network it is on.
- Your settings go in `conf/local.conf`, which the launchers create and which you copy into each new
  release, the port (`http.port`) and memory (`breadboard.memory`) included; a release replaces
  `conf/application-prod.conf`. Curly quotes in it (TextEdit's Smart Quotes, a Chinese input
  method) are named with their line before the server starts, and a `conf/local.conf` the server
  can't read is refused, with how to fix it.
- The launchers say what is wrong and what to do: the port taken (by Breadboard itself or by another
  program, also one on `[::1]` only), with a command for another port that works from where you are;
  a `JAVA8_HOME` without Java 8; a folder they can't write; the Windows launcher opened inside the
  zip. They work behind a proxy set in `http_proxy` (common in China), and accept as `JAVA8_HOME` the
  folder Finder shows for a JDK. A server window that stops taking output (a click in a Windows
  console, Ctrl+S, a pipe nobody reads) no longer holds the server up.
- Keep-running mode: the launcher starts the server again after a crash or when it runs out of
  memory, says why in its window and in `logs/application.log`, checks the port first, and gives up
  after 5 restarts in 10 minutes. A server that never started (a mistake in `conf/local.conf`, its
  port taken) isn't started again, and `logs/start-errors.log` keeps what Java printed. Out of
  memory, it leaves `logs/out-of-memory.hprof`, and what Java said in `logs/jvm-pid<N>.log`.
- Ready-made recipes for starting it with the computer: launchd (macOS), a Task Scheduler task
  (`breadboard-task.xml`, Windows; Java's own errors go to `logs/service-errors.log`) and systemd
  (Linux). `/health` (GET or HEAD) for uptime monitors.
- While the server runs, the computer doesn't go to sleep by itself (macOS and Windows; on Linux the
  launcher asks systemd, and GNOME, to keep it awake).
- On macOS and Linux, the database, its backups, the logs and `conf/local.conf` can be read only by
  the account the server runs as, also when they were copied from an earlier release.
- On a new server, the first account can be created from another computer (or through a proxy) only
  with the setup code the server prints in its window and log. The setup page waits by itself while
  the server is still starting.
- Automatic backups of the database while the server runs, every day and before an upgrade: the
  newest 14 of each kind (daily, made by hand, before an upgrade) are kept in `backups/`.
  Settings → Backups lists them, backs up on request and downloads them;
  [docs/install.md](docs/install.md#backups) says how to go back to one.
- Stopping the server: a stop that hangs ends after 20 seconds, and the database is always closed
  properly, so nothing committed in its last moments is lost. The running session's game ends first,
  and its participants' pages say what to do (open the study's link again in a few minutes; from
  Prolific, open the study again there or return it). At the next start, sessions that were running
  are marked Stopped, with a `sessionInterrupted` event, also after a crash or a power cut; Resume
  starts their game again.
- Session ids follow on after a crash: they used to skip up to 32 numbers.
- The docs cover Gatekeeper on macOS 15, Windows' Unblock and firewall prompt, running from a
  read-only folder, and what to do when something goes wrong.

#### Running studies by itself (the autopilot)
- A mistake in the steps no longer leaves an unattended session hanging. When the steps of a session
  the autopilot opens don't load, it stops the session before anyone joins (`stepsFailedToLoad`).
  When a step fails during the game in a way that keeps the next step from getting going, the
  session ends, its participants are told, and the data records `sessionFailed` (`step`, `line`,
  `error`). Either way the autopilot pauses, and its page names the step and line, with a link that
  opens the step there. A failure the game goes on after is recorded (`stepFailed`) and shown. A
  session a researcher runs by hand is never ended this way: a start that didn't get going is taken
  back, so Start works again.
- **Longest session** (Autopilot page) ends the autopilot's own session when its game has run that
  long (`sessionTooLong`), and opens the next one; three in a row and the autopilot pauses. New
  settings start at 2 hours; settings saved with 3.0, and a settings-file autopilot without
  `maxSessionMinutes`, keep no limit. Once a step has failed, a game with nothing left to wait for
  during a minute is ended too (`sessionStalled`), and the autopilot pauses.
- Participants always see how their session ended for them, and their page stops reconnecting
  (never a game that no longer answers, also after missing a reload of the engine): the researcher
  stopped it, it ran out of time, had to stop because of a problem with the study, was full, or
  ended because the server stopped or restarted, with what to do on Prolific for those who came from
  there. A completion code on screen stays. Ending a session never holds up the engine.
- **Maximum players** is a real maximum: the first to arrive are seated, and anyone else there as the
  game starts is told the session is full, with a link to the next one (`sessionFull`). While a full
  room counts down, the study's links send nobody more to it.
- Between sessions and while a game runs, the study's links (sign-in and Prolific) show a page that
  waits for the autopilot's next session, checking every few seconds, and takes people in once it
  opens; Prolific participants read that they can keep it open or return the study. When the
  autopilot won't open one (it is off, paused or done, doesn't open the next session by itself, or
  something holds it up, which the Autopilot page reports), the links say no session is open.
- **Reload engine** carries your running session on, and the autopilot's when you are the account it
  runs as. It asks first when a game is in progress or anyone is connected. The game starts again
  from the beginning, with the session's launch parameters; the pages of those who held a seat get
  back in by themselves, and the autopilot goes on running the session. The data records
  `engineReloaded` (`restarted`, `by`), and nothing of the old game after it: a choice or tie change
  made during the half-second rebuild doesn't count, and errors of the old game aren't reported as
  the restarted game's. From another account, the console says that a reload would end the
  autopilot's session and pause it. A resumed session's steps also load with its launch parameters.
- A session a researcher opens by hand is theirs: it runs with the steps' own waiting room and
  takeover, and ending it doesn't pause the autopilot. The Autopilot page shows it, with who opened
  it, and the autopilot opens its own once it has ended; **Resume** or a save hands it over.
- A stopped session stays stopped: neither computer players nor a choice made as it stops play it on
  to its end, so it can be resumed, and nothing is recorded in it after `sessionStopped`. A page
  closed after a game finished no longer hands its seat to the computer in the finished session.
- Prolific participants who already took part get their completion code again instead of a second
  seat, also after a restart. `recruitment.completeProlific` for someone who didn't come from
  Prolific does nothing (the server's log says why); it used to stop the step, so the game stalled.
- The autopilot opens no new session while the disk that holds the database is almost full.
- Long unattended runs: memory stays level (replaced script engines and their classes are freed,
  computer players share one timer, seats of finished sessions are released), and rebuilding the
  engine, as every new session and **Reload engine** do, takes about half a second instead of two
  or more.
- The Autopilot page leaves the waiting room and takeover to the steps of an experiment that sets
  them, and suggests giving a lone participant ten minutes before calling the session off. Its
  status says why it paused, and its recent activity lists step failures and what stopped or ended
  its session. A setting that needs fixing is scrolled into view, clear of the save bar. While its
  own session is open, Steps and the top bar say that its next session uses what you save, instead
  of asking for a reload.
- Autopilot sessions are named like "Autopilot · 2026-10-30 13:20:47 UTC", which Sessions & data
  shows and finds as typed, and their data files download as
  `Autopilot - 2026-10-30 13-20-47 UTC_14.csv`.

#### Participants' connections
- A page reconnects within a second after a short drop, backs off up to 30 seconds while the network
  is down, and tries again at once when the network or the page comes back. A connection that dies
  without closing is noticed: the page says so within about 20 seconds, and the server counts the
  participant as gone after 30, so the computer takes over after the usual delay.
- Two tabs of one participant no longer take the seat from each other: the older one says the study
  is open elsewhere, with **Use it here**, and a tab that reconnects by itself leaves the seat to the
  page the participant opened or chose last. A network that blocks the live connection is named.
- While the page reconnects, a note at the top moves the page down rather than covering it, and its
  buttons are disabled. Answers that waited too long are dropped rather than sent late, and the
  server drops a choice or tie change that reaches it more than 10 seconds after it was given
  (`answerDropped`): the page then shows it as still to be made.
- An outage is recorded once, as a `connectionGap` with how long the page was out of touch, not as
  a disconnection and a connection a moment apart, or not at all.
- Someone whose browser was closed gets back to their seat for 24 hours. People away for a moment as
  the game starts keep their seat (the computer plays it until they are back), and someone away
  longer is told the session went ahead without them (`missedStart`). A drop of a few seconds in the
  waiting room no longer restarts the wait.
- A researcher's preview of a participant only watches: it never disturbs their page, never counts
  as them being there, and in a real session adds nobody to the game (a preview of someone who isn't
  in it says "Not in the game"). If they leave, the computer takes over as usual. Previews of the
  test players a researcher creates still play them.
- The study's shared link asks a browser that already has a seat whether to continue as that person
  or join as someone else, so a study can be tried in several tabs of one browser.
- Identical updates are no longer sent again, which helps weak connections keep up. The network
  keeps everyone in their place when ties change; a newcomer sits next to someone they are tied to.

#### Participants' pages in Chinese
- In a session whose steps choose Chinese (`c.setDefaultLanguage("zho")`, as the example does from
  its `language` launch parameter), participants see Breadboard's own words in Chinese too, from the
  sign-in page to the end: the waiting room and its countdown, the network's hints, confirmations
  and list of changes, the notes when the connection drops, the end screens, the Prolific finish
  screen, and the pages before and between sessions, which follow the session they lead to or else
  the experiment's most recent session (as does the page for a link that was cut off). They used to
  be English only, around the study's own Chinese texts; a session in any other language gets them
  in English, as before.
- The console, a preview's notes for the researcher, Mechanical Turk's pages and experiments that
  still use the previous AngularJS participant page stay in English. The Chinese quick start says
  how to make a study's pages Chinese, and what stays English.

#### The console and the lab
- Researcher accounts: Settings → Researchers adds, removes and gives new passwords to the lab's
  accounts (shown once; **Copy sign-in details** gives the server's address on the lab's network
  when the console is open at localhost). Every account works on every experiment on the server.
- Work on any experiment while the autopilot or a colleague runs another: its Build pages and data
  are there, and the Run pages say what the engine runs and how to run yours. Every console (the
  previous one too) hears when a session opens or ends and follows the sessions of the experiment it
  works on. Before anything that would end a running session, the console says whose it is: the
  autopilot's, a colleague's, or yours.
- Sessions & data: **Download all data** gives every listed session's events in one file, each row
  with its session and launch parameters. **For Excel** (remembered in this browser) adds the
  byte-order mark Excel and WPS need to show Chinese, also to Live session's **Download data** and
  the Players page's **Download CSV** (`excel=true` on the server's CSV addresses); without it the
  files are as before. The files saved from **View events** write times as the server's do
  (`2026-10-28T13:24:23:757Z`).
- Recruitment → Prolific: **From Prolific in every session** lists who joined and who finished in
  every session, with their codes and bonuses and **Copy bonus list**, so a session the autopilot has
  moved on from can still be paid; someone the game ended for without a code is marked **No code**.
  **When a participant finishes** says when the experiment gives the completion code by itself (a
  `prolificCode` parameter, as the example has); otherwise **Copy code** gives code that completes
  only Prolific participants. The page says when the study link holds newcomers until the
  autopilot's next session, warns when it would say that no session is open (pause recruiting then),
  and says that Prolific needs the server's public address.
- What a Prolific participant writes under "Comments for the researcher" on the finish screen is
  saved in their session's data (`participantComment`), also after their game has ended and the next
  session has opened (for a week, also across a restart of the server); it used to be lost. When it
  can't be saved, or isn't confirmed yet, the screen says so, with **Try again**, instead of going on
  to Prolific without it.
- Engine errors on Live session and the Autopilot page are explained in plain words, as on the Steps
  page: "Line 71 of RoundStep", with a link that opens the step at that line (asking first to switch
  to the experiment the engine runs), and **Full error** for the rest. The events lists say what the
  well-known events mean, name the step and line of a failure, say when a researcher's preview signed
  in, and show failures in red and warnings in amber.
- Preview shows the session's test players as soon as it opens, also after a reload. Its tiles and
  player list say **Watching** where a preview only watches and **Away** where it stands in for
  someone who left, with what that means now; on a phone the player list folds away.
- "Reload needed" comes from the server: every researcher's console says the same, also after a
  refresh. It doesn't show after saving steps while no session runs: the next session loads them.
- **New session** says that the session opens right away and that its game starts when you, or its
  waiting room, start it (the button is called Start the game everywhere). Live session's links say
  that participants type an ID to join, any name or number, one seat per ID, and where computers on
  the same network reach the server while the console is open at localhost. Parameters says which
  sessions get a parameter's default: the autopilot's and the first New session; after that, New
  session fills in the last session's values (**Use default** brings a new default in).
- The network game example: its network size and waiting room are launch parameters, everything
  participants read is on the Content page, and it is offered first, named Network game, when there
  is no experiment yet. Its first round no longer shows "Your score: 0 of 0", a cut tie is recorded
  as `TieCut` (who cut it, whom, in which round) instead of `Disconnected`, and for an account whose
  Default language isn't English its texts stay English, with that language added next to them to
  translate.
- The first-account form says what **Default language** does (the language participants' texts are
  written in; the console stays in English). A new server names the languages in English, also on a
  computer set to another language.
- Experiment names are unique on the server, at most 120 characters long, and can't contain `/` or
  `\`; every open console updates its list when anyone makes, imports, renames or deletes an
  experiment. Import fills in the experiment's own name from the zip, with "(copy)" when it is taken;
  the previous console's Import keeps the chosen file and name when a name is refused.
- Help in the menu under your email: the console's guide, the Chinese quick start (which follows the
  console's names step by step, including Prolific's completion code with the example, the Autopilot
  settings and opening the CSV files in Excel or WPS), and troubleshooting.
- The sidebar fits laptop screens, every page says when the console isn't connected, and the sign-in
  page says how to get a new password; on a phone, the sidebar closes after you switch experiments,
  Parameters shows each parameter as a card, and stat cards wrap. The previous console opens in a new
  tab.
- Smaller things: computer players are named alike everywhere ("AI 6 (_6)"); events recorded in the
  same millisecond keep their order in the data; a finished or stopped session's Network card says
  the session has ended; Content's list previews no longer put a space before punctuation;
  participant pages no longer print Vue's notices for developers, and their scripts and styles are
  sent compressed.

#### For experiment authors
Details are in [docs/whats-new.md](docs/whats-new.md).
- Breadboard's own words to participants follow `c.setDefaultLanguage` (English, or Chinese for
  `zho`): the server records each session's language as its steps load, the page gets it
  (`Breadboard.language`, the `bb:language` event), and both read the words from
  `conf/i18n/eng.json` and `zho.json`; in Groovy, `bbText`. A refused tie change is told to the
  participant in their language and recorded in English, as before. A choice `a.add` gets without a
  name is "Option 1", "Option 2", …, or "选项 1", "选项 2", … in Chinese.
- A text not yet translated into the language the steps chose is shown in English, or else in the
  language it was first written in, instead of stopping the step. A version that is there but empty
  is shown as it is, nothing, as the Content page warns.
- Forms and chat stop listening once they are done: a finished form, and a chat switched off with
  `disableTextChat` (which now really switches it off), leave no listeners behind. Turning chat on
  again, or starting a form again, no longer handles each message twice, and ending a form no longer
  runs `onDone` again for someone who had finished it.
- The page's connection: `Breadboard.connection` and the `bb:connection` event; `ConnectionStatus`
  passes `state`, `tryNow` and `useHere` to its slot. Choices and tie changes say when they were
  given (`bbSentAt`, in the server's time as the page reckons it from the heartbeat's `pong`).
- New events in the data: `stepFailed`, `stepsFailedToLoad`, `sessionTooLong`, `sessionStalled`,
  `sessionFailed` (`step`, `line`, `error`), `serverStopping`, `sessionInterrupted`, `missedStart`,
  `sessionFull`, `engineReloaded` (`restarted`, `by`), `prolificComplete` (`player`,
  `completionCode`, `bonus`), `participantComment` (`player`, `text`), `aiChoice` (the computer's
  picks), `connectionGap` (`player`, `awayMs`) and `answerDropped` (`player`, `ageMs`);
  `clientConnected` and `clientDisconnected` only on real changes, marked when a preview caused
  them. New on the player: `_system.disconnectedAt`; on the waiting room: `departureGraceSeconds`.
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
