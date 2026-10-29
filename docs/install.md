# Installing and running Breadboard

## Requirements

- macOS, Linux or Windows. One CPU core and 2 GB of memory are enough for sessions of a few
  dozen participants.
- **Java 8**, which the platform downloads include (see below). Breadboard runs on the Play
  Framework 2.2, which doesn't start on Java 9 or newer, so the download without Java needs a
  Java 8 JDK, for example [Azul Zulu 8](https://www.azul.com/downloads/?version=java-8-lts&package=jdk)
  or [Eclipse Temurin 8](https://adoptium.net/temurin/releases/?version=8). It can sit next to
  other versions: point `JAVA8_HOME` at it and the launchers use it.
- Participants need a current browser, on a computer or a phone.

## Starting it

From the [releases page](https://github.com/TianzhuQin/breadboard/releases), download the zip for
your computer and unzip it:

| Download | For |
|---|---|
| `breadboard-<version>-macos-arm64.zip` | Macs with Apple silicon (M1 and later) |
| `breadboard-<version>-macos-x64.zip` | Macs with an Intel processor |
| `breadboard-<version>-windows-x64.zip` | Windows |
| `breadboard-<version>-linux-x64.zip` | Linux |
| `breadboard-<version>.zip` | Any of them, with a Java 8 you install yourself |

Then start it:

- **macOS**: double-click `Start Breadboard.command`. The first time, macOS blocks it, as it does any
  file from the internet whose maker it can't check. On macOS 15 and later, Control-click → **Open**
  no longer gets past that; only **Open Anyway** in System Settings → Privacy & Security does. The
  way that always works: open Terminal, type `sh` and a space, drag `breadboard.sh` from the folder
  into the Terminal window, and press Return.
- **Windows**: before you unzip the download, right-click it, choose **Properties**, tick
  **Unblock** and click **OK**. Otherwise Windows (Smart App Control) may refuse to run
  `breadboard.bat`, which came from the internet. Then right-click the zip, choose **Extract All**,
  and double-click `breadboard.bat` in the new folder. (Opened inside the zip, it says to extract
  the zip first.) The first time, Windows Firewall asks about Java: tick **Private networks**, so
  that the lab's computers can reach Breadboard, and click **Allow**. If SmartScreen still warns,
  choose **More info** → **Run anyway**.
- **Linux**: unzip it (if `unzip` is missing: `sudo apt install unzip`) and run `./breadboard.sh` in
  the folder.

The launcher says where Breadboard is reached: `On this computer: http://localhost:9000`, and
`On this network: http://192.168.…:9000`, this computer's address on its network (a lab's), for the
other computers there. The console opens in your browser once the server is up. On a new install
it asks you to create the first researcher account. Opened from another computer (a server you
reach over the network, or through a proxy), it also asks for a **setup code**: the server prints
it in its window and in `logs/application.log`, so only whoever runs the server can create that
first account. Stop the server with Ctrl+C, or by closing its window. If the port is taken, the
launcher says so: often Breadboard is running already, and its address is the one to open.

Anything after the launcher's name is passed to the server as a setting:

```bash
./breadboard.sh -Dhttp.port=8080
JAVA8_HOME=/opt/zulu8 ./breadboard.sh      # another Java 8 than the one in jre/
BREADBOARD_NO_BROWSER=1 ./breadboard.sh    # don't open the browser
BREADBOARD_KEEP_RUNNING=0 ./breadboard.sh  # don't start it again if it stops by itself
```

`JAVA8_HOME` can be the JDK's folder, its `bin` folder, or on macOS the JDK's folder as Finder shows
it (`zulu-8.jdk`); one that holds no Java 8 is ignored, with a warning. The settings you change go in
`conf/local.conf` (see [Settings](#settings)).

**Keeping it running.** Started in a terminal, by `Start Breadboard.command` (macOS), or by
double-clicking `breadboard.bat`, the launcher starts the server again when it stops by itself (it
ran out of memory, or crashed), after a short wait that grows each time (5 s up to a minute), and
gives up after 5 restarts within 10 minutes. It says why on its window and in
`logs/application.log`. Ctrl+C, closing the window, or stopping it as a service ends it for good.
`BREADBOARD_KEEP_RUNNING=0` turns this off, and `=1` turns it on anywhere else. Under systemd or
launchd, the launcher becomes the server and the service manager starts it again. Run by Task
Scheduler as a service (see [Running it for good](#running-it-for-good)), `breadboard.bat` starts it
again itself and never gives up: after 5 restarts in 10 minutes, it tries once a minute.

**Sleep.** While the server runs, the launchers keep the computer from going to sleep by itself
(on macOS with `caffeinate`, on Windows by asking Windows to stay awake), and let it sleep again
once the server has stopped. A laptop on battery still sleeps when its lid is closed. For a long
study that runs by itself, keep the computer plugged in with its lid open; on a Mac, also turn on
**Prevent automatic sleeping … when the display is off** (System Settings → Battery → Options; on a
Mac mini or iMac, System Settings → Energy).

When the server runs out of memory it stops at once rather than limping on, and writes
`logs/out-of-memory.hprof` (delete it once you no longer need it: the next one is only written when
that file is gone). A server whose shutdown is stuck (an experiment's script that never ends) is
stopped after 20 seconds. When it stops normally, the participants of a running session are told
the session has stopped, and a `serverStopping` event is recorded in it.

On first start the launcher creates:

| Path | What |
|---|---|
| `db/breadboard.h2.db` | The database. On a new install its tables are created at first start. |
| `conf/application.secret` | A random key that signs researchers' sign-ins and participants' invitation links. Keep it private and keep it across upgrades. Set `APPLICATION_SECRET` in the environment to use your own instead. |
| `conf/local.conf` | Your settings ([Settings](#settings)), with only comments at first. On macOS and Linux only your account can read it, as it may hold keys. |
| `logs/application.log` | The server's log. A new one starts every day, and the last 30 days are kept (`application.<date>.log`). How much it records is set in `conf/logger.xml`. |
| `backups/` | Copies of the database (see [Backups](#backups)). |

## Settings

Put the settings you change in `conf/local.conf`, one per line, then restart Breadboard:

```
breadboard.rootUrl="https://breadboard.example.org"
breadboard.allowLateJoin=true
```

The launchers make this file at first start; open it in a text editor (TextEdit, Notepad). It is
read after `conf/application-prod.conf`, which lists every setting with what it does, so its values
win. Don't change `application-prod.conf` itself: each release brings its own, while
`conf/local.conf` is yours, and you copy it into the next release when you upgrade. (A setting can
also be given when starting, e.g. `./breadboard.sh -Dbreadboard.allowLateJoin=true`.)

The ones to look at before participants join:

| Setting | Default | |
|---|---|---|
| `breadboard.rootUrl` | `http://localhost:9000` | The public address. Mechanical Turk HITs send workers here. (The links the console shows for participants use the address the console was opened at: see [console.md](console.md).) |
| `breadboard.secureCookies` | `false` | `true` when participants reach the server over HTTPS. |
| `session.secure` | (off) | `true` when researchers reach the console over HTTPS. |
| `breadboard.allowLateJoin` | `false` | Let people join a session after its game started. |
| `breadboard.clientUpdateRate` | `500` | How often participants' pages are updated, in milliseconds. |
| `amt.accessKey`, `amt.secretKey` | from `AMT_ACCESS_KEY`, `AMT_SECRET_KEY` | Your AWS keys, for Mechanical Turk. |
| `breadboard.backup.everyHours` | `24` | How often the database is copied into `backups/`. |
| `breadboard.backup.keep` | `14` | How many copies are kept. |
| `breadboard.backup.dir` | `backups` | Where they go (relative to the Breadboard folder, or a full path, e.g. on another disk). |
| `breadboard.backup.enabled` | `true` | `false` stops the copies. |

Autopilot settings are easier to set on the console's Autopilot page; see
[console.md](console.md#running-sessions-with-nobody-at-the-console).

## Putting it on a server

Participants must reach the server over the internet, which in practice means HTTPS. The
simplest way is a reverse proxy in front of Breadboard that gets a certificate by itself:

**Caddy** (`/etc/caddy/Caddyfile`):

```
breadboard.example.org {
  reverse_proxy localhost:9000
}
```

**nginx**: websockets need the upgrade headers.

```nginx
server {
  listen 443 ssl;
  server_name breadboard.example.org;
  # ssl_certificate ... (e.g. from certbot)
  location / {
    proxy_pass http://127.0.0.1:9000;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 1h;
  }
}
```

Then in `conf/local.conf`:

```
breadboard.rootUrl="https://breadboard.example.org"
breadboard.secureCookies=true
session.secure=true
```

Leave `breadboard.wsUrl="auto"`: pages connect back to the address they were loaded from. Open
the console at the public address too, so the participant links it shows use it.

## Running it for good

To keep Breadboard running after you log out, and have it start again with the computer, run it as
a service.

### Linux (systemd)

Run it under an account of its own. Set it up once:

```bash
sudo useradd --system --home-dir /opt/breadboard --shell /usr/sbin/nologin breadboard
sudo unzip breadboard-v3.1.0-linux-x64.zip -d /opt
sudo chown -R breadboard: /opt/breadboard-v3.1.0
sudo ln -sfn /opt/breadboard-v3.1.0 /opt/breadboard
```

and describe the service in `/etc/systemd/system/breadboard.service`:

```ini
[Unit]
Description=Breadboard
After=network.target

[Service]
User=breadboard
WorkingDirectory=/opt/breadboard
# (with the download that has no Java: Environment=JAVA8_HOME=/opt/zulu8)
ExecStart=/opt/breadboard/breadboard.sh
# Java ends with 143 when systemd stops it: a normal stop, not a failure.
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now breadboard
```

`Restart=on-failure` starts it again if it stops by itself.

**A folder the server can't write**, such as one root keeps read-only under `/opt`, works too, as
long as the folders the server writes exist and are the account's, and Java's pid file
(`RUNNING_PID`, which would go into the folder) goes somewhere the account can write. The folders
are `db`, `logs`, `backups`, `dev` (file mode) and `experiments` (the previous console's exports):

```bash
cd /opt/breadboard
sudo mkdir -p db logs backups dev experiments
sudo chown breadboard: db logs backups dev experiments
```

and in the service, `RuntimeDirectory=breadboard` and
`ExecStart=/opt/breadboard/breadboard.sh -Dpidfile.path=/run/breadboard/RUNNING_PID`. It can't make
`conf/application.secret` there either: make one that only the account can read (or copy the one
from an earlier folder the same way),

```bash
sudo sh -c 'umask 077; openssl rand -hex 32 > /opt/breadboard/conf/application.secret'
sudo chown breadboard: /opt/breadboard/conf/application.secret
```

or put `APPLICATION_SECRET=` and a long random text in a file only root can read
(`sudo install -m 600 /dev/null /etc/breadboard.env`, then add the line) and give it to the service
with `EnvironmentFile=/etc/breadboard.env`. Not with `Environment=`: anyone can read a unit file,
and `systemctl show` prints it. Your settings go in `/opt/breadboard/conf/local.conf`, which you
make there yourself (with `sudo`).

For an upgrade: unzip the new release next to the old one and `chown` it the same way, then
`sudo systemctl stop breadboard`, copy the database and settings over (see [Upgrading](#upgrading)),
point the `/opt/breadboard` link at the new folder, and `sudo systemctl start breadboard`.

### macOS (launchd)

On a Mac that stays on (a lab computer), launchd can start Breadboard when you log in, and again if
it stops. Keep the Breadboard folder in your home folder (such as `/Users/you/breadboard-v3.1.0`),
not in Desktop, Documents or Downloads: macOS doesn't let a background job read those. Save this as
`~/Library/LaunchAgents/org.breadboard.plist`, with the path to your Breadboard folder in each
place:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>org.breadboard</string>
  <key>ProgramArguments</key>
  <array><string>/bin/sh</string><string>/Users/you/breadboard-v3.1.0/breadboard.sh</string></array>
  <key>WorkingDirectory</key><string>/Users/you/breadboard-v3.1.0</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>/Users/you/breadboard-v3.1.0/logs/launchd.log</string>
  <key>StandardErrorPath</key><string>/Users/you/breadboard-v3.1.0/logs/launchd.log</string>
</dict>
</plist>
```

Start it with

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/org.breadboard.plist
```

From then on it also starts whenever you log in. To stop it, and keep it stopped:
`launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/org.breadboard.plist`. What the server prints
goes to `logs/launchd.log`. It needs you logged in: after a power cut or a restart, a Mac with
FileVault on waits at the login window for a password (it can't log in by itself), and Breadboard
is back only once someone logs in. Keeping the Mac awake: see **Sleep** under
[Starting it](#starting-it).

### Windows (Task Scheduler)

`breadboard-task.xml`, next to `breadboard.bat`, has Task Scheduler start Breadboard whenever the
computer starts, whether or not anyone signs in. Set it up once:

1. Open `breadboard-task.xml` in Notepad, replace `C:\path\to\your\Breadboard\folder` with the
   folder that holds `breadboard.bat`, and save.
2. Open Command Prompt as administrator (Start, type `cmd`, right-click Command Prompt, **Run as
   administrator**), go to that folder, and register the task:

   ```
   cd /d "C:\Users\you\breadboard-v3.1.0"
   schtasks /Create /TN Breadboard /XML breadboard-task.xml /RU "%USERDOMAIN%\%USERNAME%" /RP
   ```

   It asks for your Windows password: the task runs as you, also while nobody is signed in. If you
   change your password later, run the same command again with `/F` at the end.
3. Start it now with `schtasks /Run /TN Breadboard`, or restart the computer.

The task runs `breadboard.bat` with `BREADBOARD_SERVICE=1` and `BREADBOARD_NO_BROWSER=1`, without a
window: it never waits for a key after an error, opens no browser, and starts the server again
whenever it stops by itself (after 5 restarts in 10 minutes, once a minute). The task has no time
limit, runs on battery too, and if it fails, Task Scheduler tries again every minute. What the
server says is in `logs/application.log`. `schtasks /End /TN Breadboard` stops it at once (do that
between sessions); `schtasks /Delete /TN Breadboard /F` removes the task.

### Checking that it is up

`GET /health` answers `{"status":"ok", "database":"ok", "diskFreeMb": …}` with status 200 while the
server works: it writes to the database (so a full disk or a locked database shows), checks there
are more than 100 MB free where the database is, and that the server's internals are running.
Otherwise it answers 503 with `"status":"failing"` and a `"reason"`. Point an uptime monitor (or
your proxy's health check) at `https://breadboard.example.org/health`. It needs no sign-in and says
nothing about experiments or participants.

**A restart ends running sessions.** The game state of a session lives in the server's memory,
so stopping the server ends any session in progress. When it starts again, such a session is
marked Stopped, with a `sessionInterrupted` event in its data; **Resume** (Sessions & data) starts
its game again from the beginning, as for a session a researcher stopped. Participants who come
back are told the session isn't running, and are asked to open the study's link again: with the
autopilot on, that link leads to the session it opens after the restart. Restart between sessions.

## Backups

Everything researchers create (experiments, sessions, their events, accounts) is in
`db/breadboard.h2.db`. The server copies it while it runs (nothing has to stop) into `backups/`:
once a day, and before a newer release changes it (`...-upgrade.zip`). The newest 14 are kept. The
console's **Settings → Backups** lists them, makes one when you ask (before a big change, say), and
downloads them. The settings above change how often, how many and where.

The copies are on the same disk as the database, so copy them somewhere else now and then (or set
`breadboard.backup.dir` to a folder on another disk or a synced drive).

**To go back to a backup**: stop Breadboard, unzip the backup (it holds one file,
`breadboard.h2.db`) into the `db` folder, replacing the file there, and start Breadboard again.
Everything since that backup is gone, so download the data of later sessions first if you need it.

You can also export each experiment from the console's Experiments page, and download each
session's data from Sessions & data.

## Upgrading

### From an earlier 3.x release

1. Stop the server.
2. Unzip the new release next to the old one.
3. Copy `db/breadboard.h2.db`, `conf/application.secret` and `conf/local.conf` from the old folder
   into the new one (and `dev/`, if you use file mode, and `backups/`). Coming from 3.0, which had
   no `conf/local.conf`: put the settings you changed in its `conf/application-prod.conf` into the
   new folder's `conf/local.conf` instead, just those lines, not the whole file.
4. Start the new release. It backs up the database (`backups/...-upgrade.zip`) before it changes
   anything in it.

### From Breadboard 2.x

The same steps, with the database from the 2.x install (its `application.secret` was a published
default, so don't copy that), and the settings you changed in its `conf/application-prod.conf` put
in the new folder's `conf/local.conf`. The new release adds what its tables need at first start;
this is tested with databases from 2.2, 2.3 and 2.4. Then:

- Researchers sign in again.
- Experiments keep their own participant pages, so their participants see what they saw
  before. New experiments get the new participant page; [whats-new.md](whats-new.md#the-participant-page)
  explains how to move an older one over.
- The previous console is still at `/legacy-admin` if you need it.
- If you used file mode, copy the `dev/` folder over too: it holds those experiments' files.
- The 2.x launchers also served HTTPS on port 9443, with a self-signed certificate. These don't:
  put a reverse proxy in front (see [Putting it on a server](#putting-it-on-a-server)), or start
  with `./breadboard.sh -Dhttps.port=9443` plus your certificate's `-Dhttps.keyStore=...` and
  `-Dhttps.keyStorePassword=...`. Update `breadboard.rootUrl` if it named port 9443.

Keep a copy of the old database until you have checked your experiments in the new version.

## Troubleshooting

**"Breadboard needs Java 8"**: the launcher found no Java, or another version. It takes the first
Java 8 of: `JAVA8_HOME`, the `jre` folder of the downloads that include Java, (on macOS) any Java 8
installed, `JAVA_HOME`, and `java` on the PATH. Download the release for your platform, which
includes Java, or install Java 8 and set `JAVA8_HOME` to it. **"JAVA8_HOME is …, which has no Java
8; ignoring it"**: `JAVA8_HOME` points at a folder without Java 8 (another version, or a typo); the
launcher goes on with the others. Point it at the JDK's folder, or remove it.

**"Extract the whole zip first"** (Windows): `breadboard.bat` was opened inside the zip, where the
rest of Breadboard isn't. Right-click the zip, **Extract All**, and open `breadboard.bat` in the new
folder.

**"Port 9000 is in use"** (or "Breadboard is already running on port 9000"): another program, or
a Breadboard started earlier, holds the port. If it is Breadboard, open its address; otherwise
start on another port with `-Dhttp.port=9001`. (A proxy set for the internet, such as
`http_proxy`, doesn't matter: the launchers check the port directly.)

**The server's window shows nothing new, and the console still works**: output to the window is
written by a thread of its own, so a window that stops taking it (a click into a Windows console
starts selecting text and pauses it, until Esc; Ctrl+S in a terminal, until Ctrl+Q) holds up
nothing. While it is paused, its INFO lines are left out of the window; `logs/application.log`
has them all.

**A setting in `conf/local.conf` has a typo**: the server doesn't start, and says which line of
`conf/local.conf` it couldn't read. Fix that line (text values go in double quotes) and start it
again.

**The first start of a new install stopped halfway** (the server says the database "could not be
brought up to date"): stop it, delete the `db` folder, which holds nothing yet, and start again.

**"Breadboard is already running from this folder"**: a server started from this folder still runs
(the message gives its process number and address): open it, or stop it first. A `RUNNING_PID` left
behind by a crash is removed by the launchers themselves; if you start the server another way,
delete `RUNNING_PID` after checking that no server is running.

**"db/ can't be written by …"**: the folder belongs to another account (often after unzipping with
`sudo`). Fix its owner as the message says, e.g. `sudo chown -R breadboard: /opt/breadboard`.

**Event values cut short**: values are kept up to 1 MB each; a longer one is cut, ending in
"…[truncated from N characters]".

**The console can't connect** (its top bar keeps saying "Reconnecting…"): a proxy in front of
Breadboard doesn't pass websockets. See the nginx settings above.

**Participants see "This session isn't running right now"**: the session finished, the server
restarted, or the console moved the engine to another session. Open a new session, or turn the
autopilot on, and give participants the study's link again.

**A forgotten password**: stop the server and start it once with the account's email:

```
./breadboard.sh -Dbreadboard.resetPassword=you@lab.org
```

(`breadboard.bat -Dbreadboard.resetPassword=you@lab.org` on Windows). The server prints a new
password on its console (not in the log) and signs the account out everywhere. Sign in with it,
change it in Settings, and start the server without the option from then on.
