# Changelog

### [v3.1.0] - 2026-10-28
Easier to install, safer to leave running, and ready for a lab of several researchers.

#### Added
- Downloads for macOS (Apple silicon and Intel), Windows and Linux that carry their own Java 8:
  nothing else to install. On a Mac, `Start Breadboard.command` starts it with a double-click; the
  launchers open the console in the browser once the server is up.
- Researcher accounts: Settings → Researchers adds, removes and gives new passwords to the lab's
  accounts (passwords are shown once, to pass on). Every account works on every experiment on the
  server.
- Automatic backups of the database while the server runs: every day and before an upgrade, the
  newest 14 kept in `backups/`. Settings → Backups lists them, backs up on request and downloads
  them; [docs/install.md](docs/install.md#backups) says how to go back to one.
- `GET /health` for uptime monitors and service managers; examples for starting Breadboard again by
  itself with systemd, launchd (macOS) and Task Scheduler (Windows).
- Sessions & data: **Download all data**, every listed session's events in one file, each row with
  its session and launch parameters.
- The console says where computers on the same network reach the server, wherever it shows
  participant links while it was opened at localhost (links that work on this computer only).
- Help in the menu under your email: the console's guide, the Chinese quick start, troubleshooting.

#### Changed
- The autopilot no longer sends participants into a broken session: when the steps of a session it
  opens don't load, it stops that session before anyone joins and pauses, naming the step and line
  (`stepsFailedToLoad`). While it runs an experiment, that experiment's Steps page says so.
- On a new server, the first account can be created from another computer (or through a proxy) only
  with the setup code the server prints in its window and log.
- The study's shared link asks a browser that already has a seat whether to continue as that person
  or join as someone else, so a study can be tried in several tabs of one browser.
- The network game example: its network size and waiting room are launch parameters, and
  everything participants read is on the Content page; it is offered first when there is no
  experiment yet.
- The Autopilot page leaves the waiting room and takeover to the steps of an experiment that sets
  them, and suggests giving a lone participant ten minutes before calling the session off.
- No "Reload needed" after saving steps while no session runs: the next session loads them.
- Sessions that were running when the server stopped are marked Stopped when it starts again, with a
  `sessionInterrupted` event; Resume starts their game again.
- Experiment names are unique on the server, and every open console updates its experiment list
  when anyone makes, imports, renames or deletes an experiment.
- The participant's network keeps everyone in their place when ties change; a newcomer sits next to
  someone they are tied to.
- The launchers say when the port is taken (and by Breadboard itself, if so) instead of printing a
  stack trace; the Windows launcher keeps the jars in the build's order.
- The events drawer says in plain words what the well-known events mean, and engine errors on the
  Live session and Autopilot pages are explained as on the Steps page.
- The sidebar fits laptop screens; Live session says when the console isn't connected; the sign-in
  page says how to get a new password; the previous console opens in a new tab and links back.
- Participant pages no longer print Vue's notices for developers in the browser console, and their
  scripts and styles are sent compressed.

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
