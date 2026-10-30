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
  the zip first.) The first time, Windows Firewall asks whether Java may communicate on networks.
  For the lab's computers to reach Breadboard, tick the kind of network this computer is on,
  **Private networks** or **Public networks** (Windows often counts a network it joins for the first
  time as public; Settings → Network & internet says which), and click **Allow access**, which needs
  an administrator. Breadboard works on this computer either way. If SmartScreen still warns, choose
  **More info** → **Run anyway**.
- **Linux**: unzip it (if `unzip` is missing: `sudo apt install unzip`) and run `./breadboard.sh` in
  the folder.

The launcher says where Breadboard is reached: `On this computer: http://localhost:9000`, and
`On this network: http://192.168.…:9000`, this computer's address on its network (a lab's), for the
other computers there: a line for each network it is on (Wi-Fi and an Ethernet adapter, say), the
one it reaches the internet through first. The console opens in your browser once the server is
up. On a new install it asks you to create the first researcher account. Opened from another
computer (a server you reach over the network, or through a proxy), it also asks for a
**setup code**: the server prints it in its window and in `logs/application.log`, so only whoever
runs the server can create that first account. Stop the server with Ctrl+C, or by closing its
window. Started again while it runs (a second double-click, say), the launcher opens its console
again. If the port is taken, the launcher says so: often Breadboard is running already, and its
address is the one to open.

Anything after the launcher's name is passed to the server as a setting (on a Mac, in Terminal,
`sh`, a space and the dragged `breadboard.sh` take the place of `./breadboard.sh`):

```bash
./breadboard.sh -Dhttp.port=8080           # this time on port 8080
JAVA8_HOME=/opt/zulu8 ./breadboard.sh      # another Java 8 than the one in jre/
BREADBOARD_NO_BROWSER=1 ./breadboard.sh    # don't open the browser
BREADBOARD_KEEP_RUNNING=0 ./breadboard.sh  # don't start it again if it stops by itself
```

`JAVA8_HOME` can be the JDK's folder, its `bin` folder, or on macOS the JDK's folder as Finder shows
it (`zulu-8.jdk`); one that holds no Java 8 is ignored, with a warning. The settings you change go in
`conf/local.conf` (see [Settings](#settings)), the port and the memory too, so that every start
uses them, a double-click included.

**Keeping it running.** Started in a terminal, by `Start Breadboard.command` (macOS), or by
double-clicking `breadboard.bat`, the launcher starts the server again when it stops by itself (it
ran out of memory, or crashed), after a short wait that grows each time (5 s up to a minute), and
gives up after 5 restarts within 10 minutes. It says why on its window and in
`logs/application.log`. Before each restart it checks the port again, and stops if another program
has taken it. A server that stops before it ever started (a mistake in `conf/local.conf`, its port
taken) isn't started again, as it would only fail the same way: the launcher says so, and
`logs/start-errors.log` keeps what Java printed as errors. Ctrl+C, closing the window, or stopping
it as a service ends it for good. `BREADBOARD_KEEP_RUNNING=0` turns this off, and `=1` turns it on
anywhere else. Under systemd or launchd, the launcher becomes the server and the service manager
starts it again. Run by Task Scheduler as a service (see [Running it for good](#running-it-for-good)),
`breadboard.bat` starts it again itself and never gives up, also when it couldn't start: after 5
restarts in 10 minutes, it tries once a minute.

**Sleep.** While the server runs, the launchers keep the computer from going to sleep by itself
(on macOS with `caffeinate`, on Windows by asking Windows to stay awake), and let it sleep again
once the server has stopped. On Linux, started in a desktop session by someone logged in at the
computer, the launcher asks systemd to keep the computer awake (`systemd-inhibit`), and on GNOME
the desktop too (`gnome-session-inhibit`); if the computer still suspends (some desktops do, such as
Cinnamon on Linux Mint), turn off automatic suspend in the power settings. The screen still turns
off and locks as usual. Started over SSH or as a
service it can't (systemd doesn't let an ordinary account do it there), and servers seldom sleep
anyway. A laptop still sleeps when its lid is closed. For a long study that runs by itself, keep
the computer plugged in with its lid open; on a Mac, also turn on **Prevent automatic sleeping …
when the display is off** (System Settings → Battery → Options; on a Mac mini or iMac, System
Settings → Energy).

When the server runs out of memory it stops at once rather than limping on, and writes
`logs/out-of-memory.hprof` (delete it once you no longer need it: the next one is only written when
that file is gone). Java says so, and what it ran out of, in `logs/jvm-pid<N>.log` (see below). To
give it more memory, set `breadboard.memory` in `conf/local.conf`. A server whose shutdown is stuck
(an experiment's script that never ends) is stopped after 20 seconds. When it stops normally, a
running session's game stops at once (no page joins it again, no computer player or timer plays it
on), a `serverStopping` event is recorded in it, and its participants are told the session ended
because the server was stopped or restarted, and that they can open the study's link again in a
few minutes to join the session open then (those from Prolific: open the study again from Prolific,
or return it). Someone given a completion code keeps it on screen.

On first start the launcher creates:

| Path | What |
|---|---|
| `db/breadboard.h2.db` | The database. On a new install its tables are created at first start. |
| `conf/application.secret` | A random key that signs researchers' sign-ins and participants' invitation links. Keep it private and keep it across upgrades. Set `APPLICATION_SECRET` in the environment to use your own instead. |
| `conf/local.conf` | Your settings ([Settings](#settings)), with only comments at first. On macOS and Linux the launcher lets only its owner read it, as it may hold keys. The account the server runs as must be able to read it. |
| `logs/application.log` | The server's log. A new one starts every day, and the last 30 days are kept (`application.<date>.log`). How much it records is set in `conf/logger.xml`. |
| `logs/jvm-pid<N>.log` | What Java itself says, such as that the server ran out of memory, which would otherwise go to the server's window. One for each start, N being Java's process id; at the next start, those in which Java said nothing are deleted, and at most 10 are kept. |
| `backups/` | Copies of the database (see [Backups](#backups)). |

On macOS and Linux, `db/`, `backups/` and `logs/`, and what is in them, can be read only by the
account the server runs as: they hold participants' data and researchers' password hashes, and a
lab computer's other accounts could otherwise read them. What the server makes is private from the
start, and at every start the launcher makes private what is already there (copied from an earlier
release, say), when it is that account's. Copy backups elsewhere as that account (or with `sudo`).

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

Text values go in straight double quotes (`"`). TextEdit's Smart Quotes, on unless you turned it
off, makes them curly (`“ ”`) as you type, and so does a Chinese input method's punctuation. Turn
Smart Quotes off (in TextEdit, Edit → Substitutions → Smart Quotes) before you type, or type those
quotes again afterwards. With curly quotes in `conf/local.conf`, the launchers don't start the
server, and say on which line they are.

In a text value, a backslash starts an escape, as in JSON. So on Windows, write a path with forward
slashes, `breadboard.backup.dir="D:/Breadboard backups"`, or with each backslash doubled,
`"D:\\Breadboard backups"`. A single backslash either stops the server from starting or, as `\b` in
`"D:\backups"` does, quietly changes the path.

The account the server runs as must be able to read `conf/local.conf`. Java would take a file it
can't read for one that isn't there, and the server would run without your settings, so the
launchers refuse to start instead, and say so. A copy made with `sudo` belongs to root: give it to
the server's account, e.g. `sudo chown breadboard: /opt/breadboard/conf/local.conf`. As the file
may hold keys, `breadboard.sh` (macOS and Linux) lets only its owner read it: at every start, if
the file belongs to the account it runs as, it takes the group's and others' access away
(`chmod go-rwx`), so a copy from an earlier release is private too.

The ones to look at before participants join:

| Setting | Default | |
|---|---|---|
| `breadboard.rootUrl` | `http://localhost:9000` | The public address. Mechanical Turk HITs send workers here. (The links the console shows for participants use the address the console was opened at: see [console.md](console.md).) |
| `breadboard.secureCookies` | `false` | `true` when participants reach the server over HTTPS. |
| `session.secure` | (off) | `true` when researchers reach the console over HTTPS. |
| `http.port` | `9000` | The port. The launchers read it from `conf/local.conf` (only there) and pass it on; `-Dhttp.port=` given when starting wins. |
| `breadboard.memory` | `1g` | How much memory the server may use for its data (Java's heap), in megabytes or gigabytes: `1500m`, `2g`. Read by the launchers, only from `conf/local.conf`. An `-Xmx` in `JAVA_OPTS` (as 3.0's launchers suggested) or given when starting wins over it. |
| `breadboard.allowLateJoin` | `false` | Let people join a session after its game started. |
| `breadboard.clientUpdateRate` | `500` | How often participants' pages are updated, in milliseconds. |
| `amt.accessKey`, `amt.secretKey` | from `AMT_ACCESS_KEY`, `AMT_SECRET_KEY` | Your AWS keys, for Mechanical Turk. |
| `breadboard.backup.everyHours` | `24` | How often the database is copied into `backups/`. |
| `breadboard.backup.keep` | `14` | How many copies of each kind are kept (the daily ones, those made by hand, those made before an upgrade). |
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
make there yourself, like the secret: the account must be able to read it, and no one else should,

```bash
sudo install -m 600 /dev/null /opt/breadboard/conf/local.conf
sudo chown breadboard: /opt/breadboard/conf/local.conf
```

Then edit it with `sudoedit /opt/breadboard/conf/local.conf`, which keeps its owner.

For an upgrade: unzip the new release next to the old one, `sudo systemctl stop breadboard`, and
copy the database and settings over (see [Upgrading](#upgrading)). Then `chown` the new folder
(`sudo chown -R breadboard: /opt/breadboard-v3.2.0`; for a read-only folder, the folders and files
above): what you copied with `sudo` is root's, and the server couldn't write the database or read
the secret and `conf/local.conf`. Point the `/opt/breadboard` link at the new folder, and
`sudo systemctl start breadboard`.

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
  <key>StandardOutPath</key><string>/dev/null</string>
  <key>StandardErrorPath</key><string>/Users/you/breadboard-v3.1.0/logs/launchd.log</string>
</dict>
</plist>
```

Start it with

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/org.breadboard.plist
```

From then on it also starts whenever you log in. To stop it, and keep it stopped:
`launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/org.breadboard.plist`.

What the server prints for a window (`StandardOutPath`) goes nowhere: what it logs is in
`logs/application.log` anyway, and this copy, kept, would only grow, with colour codes. That also
throws away what experiments print (`println`). When the server runs out of memory, Java says so in
`logs/jvm-pid<N>.log`, not there, and launchd starts it again.
`logs/launchd.log` (`StandardErrorPath`) gets what goes wrong outside the server's log, such as why
the launcher couldn't start the server, or what Java says when it can't start (a crash of Java
leaves its report in `hs_err_pid*.log` in the Breadboard folder).

It needs you logged in: after a power cut or a restart, a Mac with FileVault on waits at the
login window for a password (it can't log in by itself), and Breadboard is back only once someone
logs in. Keeping the Mac awake: see **Sleep** under [Starting it](#starting-it).

### Windows (Task Scheduler)

`breadboard-task.xml`, next to `breadboard.bat`, has Task Scheduler start Breadboard whenever the
computer starts, whether or not anyone signs in. Set it up once:

1. Open `breadboard-task.xml` in Notepad, replace `C:\path\to\your\Breadboard\folder` with the
   folder that holds `breadboard.bat`, and save. Write an `&` in it as `&amp;` (a folder named
   `R&D` is `R&amp;D` there): a bare `&` makes the file unreadable to Task Scheduler.
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
limit, runs on battery too, and if it fails, Task Scheduler tries again every minute.
`schtasks /End /TN Breadboard` stops it at once (do that between sessions);
`schtasks /Delete /TN Breadboard /F` removes the task.

What the server says is in `logs/application.log`, and so is why `breadboard.bat` couldn't start it
(no Java 8, the port taken, a database it can't write). What Java prints as errors itself, outside
that log, is in `logs/service-errors.log`: a mistake in `conf/local.conf`, for one, which it finds
before the server's log starts. Look there when `logs/application.log` only says, again and again,
that Breadboard stopped by itself (exit code -1): the task shows as running meanwhile, since
`breadboard.bat` keeps trying.

If neither log says anything, look at the task's **Last Run Result** in Task Scheduler, or at its
**Last Result** in what `schtasks /Query /TN Breadboard /V /FO LIST` prints, which gives the same
code in decimal. `0x41301` (267009) means that it is running. `0x1` (1) means that
`breadboard.bat` refused to start and couldn't write why (its `logs` folder can't be written).
Other codes, such as `0x8007010B` (-2147024629) or `0x2331` (9009), mostly mean that the folder in
the task is wrong.

### Checking that it is up

`GET /health` answers `{"status":"ok", "database":"ok", "diskFreeMb": …, "version":"v3.1.0",
"uptimeSeconds": …}` with status 200 while the server works: it writes to the database (so a full
disk or a locked database shows), checks there are more than 100 MB free where the database is, and
that the server's internals are running. Otherwise it answers 503 with `"status":"failing"` and a
`"reason"`. `HEAD /health` gives the same status without the body. Point an uptime monitor (or your
proxy's health check) at `https://breadboard.example.org/health`. It needs no sign-in and says
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
once a day, and before a newer release changes it (`...-upgrade.zip`). The console's
**Settings → Backups** lists them, makes one when you ask (before a big change, say;
`...-manual.zip`), and downloads them. The newest 14 of each kind are kept: the daily ones, those
made by hand, and those made before an upgrade, so making many by hand never pushes out the daily
ones. Each one's name says when it was made (in UTC, e.g. `breadboard-2026-10-30-133336.zip`), and
the console shows that time, also for backups copied from an earlier release. The settings above
change how often, how many and where.

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
   new folder's `conf/local.conf` instead, just those lines, not the whole file. If you copied them
   as another account than the server's (with `sudo`, say), give them to the server's account
   afterwards, as when you installed it (e.g. `sudo chown -R breadboard: /opt/breadboard-v3.2.0`):
   otherwise the server can't write the database or read the secret and `conf/local.conf`.
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

**"Another program is using port 9000"** (on Windows **"Port 9000 is in use"**), or **"Breadboard
is already running on port 9000"**: another program, or a Breadboard started earlier, holds the
port. If it is Breadboard, open its address. Otherwise stop that program, or use another port: the
line `http.port=9001` in `conf/local.conf` for every start, or `-Dhttp.port=9001` once, as the
message shows. (A proxy set for the internet, such as `http_proxy`, doesn't matter: the launchers
check the port directly.)

**The server's window shows nothing new**: the window is probably paused. A click into a Windows
console window starts selecting text, which pauses it, and Ctrl+S pauses a terminal. The server
doesn't wait for it: what it prints waits for the window, and once about a megabyte is waiting, the
rest is left out of the window, with a line that says how many lines were. `logs/application.log`
has everything the server logs; what experiment scripts print (`println`, chat messages) is only in
the window, and what Java itself says (that the server ran out of memory) is in
`logs/jvm-pid<N>.log`. Only a crash of Java itself, which is rare, waits for the window to show its
first lines: the server is started again once the window goes on. Press Esc (or Ctrl+Q in a
terminal) to let the window go on.

**A setting in `conf/local.conf` has a typo**: the server doesn't start, and says which line of
`conf/local.conf` it couldn't read, in its window and in `logs/start-errors.log` (as a service also
in `logs/service-errors.log` on Windows, `logs/launchd.log` on a Mac, `journalctl -u breadboard`
with systemd). Fix that line (text values go in double quotes; for a path on Windows, see
[Settings](#settings)) and start it again.

**"Line … of conf/local.conf has curly quotes"**: the quotes on that line are curly (`“ ”`), as
TextEdit's Smart Quotes types them, where the server needs straight ones (`"`). Type them again with
Smart Quotes off (see [Settings](#settings)), and start it again.

**The first start of a new install stopped halfway** (the server says the database "could not be
brought up to date"): stop it, delete the `db` folder, which holds nothing yet, and start again.

**"Breadboard is already running from this folder"**: a server started from this folder still runs
(the message gives its process number and address). Started in a terminal or by a double-click, the
launcher opens that server's console in the browser; otherwise open its address. To start it again,
stop it first. A `RUNNING_PID` left behind by a crash is removed by the launchers themselves; if you
start the server another way, delete `RUNNING_PID` after checking that no server is running.

**"db/ can't be written by …"**: the folder belongs to another account (often after unzipping with
`sudo`). Fix its owner as the message says, e.g. `sudo chown -R breadboard: /opt/breadboard`.
**"conf/local.conf can't be read by …"** is the same for your settings file, often one copied with
`sudo`: `sudo chown breadboard: /opt/breadboard/conf/local.conf`. When the folder or file is the
account's own, only without write or read access for it, the message says `chmod` instead (e.g.
`chmod u+rw ".../conf/local.conf"`), which needs no administrator. (On Windows: right-click the
file, **Properties**, **Security**, and give the account that runs Breadboard read access.)

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
