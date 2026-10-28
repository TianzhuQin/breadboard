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

- **macOS**: double-click `Start Breadboard.command`. The first time, macOS may say it can't check
  who made the file: right-click it and choose **Open** (on newer macOS, click **Open Anyway** in
  System Settings → Privacy & Security). Or, in Terminal: `cd` into the folder and run
  `./breadboard.sh`.
- **Windows**: double-click `breadboard.bat`. (If Windows SmartScreen warns about it, choose
  **More info** → **Run anyway**.)
- **Linux**: `./breadboard.sh` in the folder.

The console opens in your browser once the server is up (or open <http://localhost:9000>). On a new
install it asks you to create the first researcher account. Stop the server with Ctrl+C, or by
closing its window. If the port is taken, the launcher says so: often Breadboard is running
already, and its address is the one to open.

Anything after the launcher's name is passed to the server as a setting:

```bash
./breadboard.sh -Dhttp.port=8080
JAVA8_HOME=/opt/zulu8 ./breadboard.sh      # another Java 8 than the one in jre/
BREADBOARD_NO_BROWSER=1 ./breadboard.sh    # don't open the browser
```

On first start the launcher creates:

| Path | What |
|---|---|
| `db/breadboard.h2.db` | The database. On a new install its tables are created at first start. |
| `conf/application.secret` | A random key that signs researchers' sign-ins and participants' invitation links. Keep it private and keep it across upgrades. Set `APPLICATION_SECRET` in the environment to use your own instead. |
| `logs/application.log` | The server's log. A new one starts every day, and the last 30 days are kept (`application.<date>.log`). How much it records is set in `conf/logger.xml`. |
| `backups/` | Copies of the database (see [Backups](#backups)). |

## Settings

The settings are in `conf/application-prod.conf`, each with a comment. The ones to look at
before participants join:

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

Then in `conf/application-prod.conf`:

```
breadboard.rootUrl="https://breadboard.example.org"
breadboard.secureCookies=true
session.secure=true
```

Leave `breadboard.wsUrl="auto"`: pages connect back to the address they were loaded from. Open
the console at the public address too, so the participant links it shows use it.

To keep Breadboard running after you log out, run it as a service under an account of its own.
With systemd, set it up once:

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

**On a Mac that stays on** (a lab computer), launchd can start Breadboard at login and again if
it stops. Save this as `~/Library/LaunchAgents/org.breadboard.plist`, with the path to your
Breadboard folder in both places:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>org.breadboard</string>
  <key>ProgramArguments</key>
  <array><string>/Users/you/breadboard-v3.1.0/breadboard.sh</string></array>
  <key>WorkingDirectory</key><string>/Users/you/breadboard-v3.1.0</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
</dict>
</plist>
```

and load it with `launchctl load ~/Library/LaunchAgents/org.breadboard.plist` (`unload` to stop
it).

**On Windows**, Task Scheduler can start it when the computer starts: create a task that runs
`breadboard.bat` in the Breadboard folder "At startup", with "Run whether user is logged on or
not", and under Settings "If the task fails, restart every 1 minute". Set the environment variable
`BREADBOARD_NO_BROWSER=1` for the account it runs as, so it doesn't try to open a browser.

**Checking that it is up**: `GET /health` answers `{"status":"ok", ...}` with status 200 while the
server and its database work, and 503 otherwise. Point an uptime monitor (or your proxy's health
check) at `https://breadboard.example.org/health`. It needs no sign-in and says nothing about
experiments or participants.

For an upgrade: unzip the new release next to the old one and `chown` it the same way, then
`sudo systemctl stop breadboard`, copy the database and settings over (see [Upgrading](#upgrading)),
point the `/opt/breadboard` link at the new folder, and `sudo systemctl start breadboard`.

**A restart ends running sessions.** The game state of a session lives in the server's memory,
so stopping the server ends any session in progress. Participants who come back are told the
session isn't running, and are asked to open the study's link again: with the autopilot on, that
link leads to the session it opens after the restart. Restart between sessions.

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
3. Copy `db/breadboard.h2.db` and `conf/application.secret` from the old folder into the new one
   (and `dev/`, if you use file mode, and `backups/`), and carry over any settings you changed in
   `conf/application-prod.conf`.
4. Start the new release. It backs up the database (`backups/...-upgrade.zip`) before it changes
   anything in it.

### From Breadboard 2.x

The same steps, with the database from the 2.x install (its `application.secret` was a published
default, so don't copy that). The new release adds what its tables need at first start; this is
tested with databases from 2.2, 2.3 and 2.4. Then:

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

**"Breadboard needs Java 8"**: the launcher found no Java, or another version. It looks in
`JAVA8_HOME`, then in the `jre` folder of the downloads that include Java, then (on macOS) for any
Java 8 installed, then `JAVA_HOME`, then `java` on the PATH. Download the release for your
platform, which includes Java, or install Java 8 and set `JAVA8_HOME` to it.

**"Port 9000 is in use"** (or "Breadboard is already running on port 9000"): another program, or
a Breadboard started earlier, holds the port. If it is Breadboard, open its address; otherwise
start on another port with `-Dhttp.port=9001`.

**The first start of a new install stopped halfway** (the server says the database "could not be
brought up to date"): stop it, delete the `db` folder, which holds nothing yet, and start again.

**"This application is already running"**: a server is running from this folder, or one crashed
and left `RUNNING_PID` behind. The launchers remove a stale one themselves; if you start the
server another way, delete `RUNNING_PID` after checking that no server is running.

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
