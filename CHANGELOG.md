# Changelog

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

#### Fixed
- Anyone who knew a participant's page address could take over their seat. Every way in now
  hands out a personal token.
- Pages and previews could act with the researcher's sign-in; previews now run in a sandbox with
  a token for that player only. Sign-out and Mechanical Turk approval take a POST.
- A step without choices ended at once; idle and drop timers dropped players who had answered.
- Removing tracked ties threw an error after logging the removal.
- OnLeaveStep never ran; `ReadyUpSequence` ignored its options.
- Simultaneous joins could fail with ConcurrentModificationException.
- Groovy interpolated strings reached browsers as objects instead of text.
- A fresh database built from the evolutions lacked `experiments.file_mode`.

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
