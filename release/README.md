# Breadboard

Breadboard runs experiments in which people interact over a network: each participant sees their
neighbors, makes choices, and may add or cut ties, while the researcher watches and steers the
session from a web console, or lets the autopilot run it.

The full documentation is in `docs/` (also at
<https://github.com/TianzhuQin/breadboard/tree/main/docs>); `docs/zh-CN.md` is a quick start in
Chinese.

## What you need

- macOS, Linux or Windows, and a modern browser.
- **Java 8**, which comes with the downloads whose names end in `-macos-arm64` (Apple silicon
  Macs), `-macos-x64` (Intel Macs), `-linux-x64` or `-windows-x64`: nothing to install. The
  download without a platform in its name needs a Java 8 JDK of your own (newer versions of Java
  don't run this server), for example
  [Azul Zulu 8](https://www.azul.com/downloads/?version=java-8-lts&package=jdk); if another Java is
  your default, point `JAVA8_HOME` at the Java 8 one.

## Start it

- **macOS**: double-click `Start Breadboard.command`. The first time, macOS blocks it, as it does
  any file from the internet whose maker it can't check. On macOS 15 and later, Control-click →
  **Open** no longer gets past that; only **Open Anyway** in System Settings → Privacy & Security
  does. The way that always works: open Terminal, type `sh` and a space, drag `breadboard.sh` from
  this folder into the Terminal window, and press Return.
- **Windows**: before you unzip the download, right-click it, choose **Properties**, tick
  **Unblock** and click **OK** (otherwise Windows may refuse to run `breadboard.bat`, which came
  from the internet). Then right-click the zip, choose **Extract All**, and double-click
  `breadboard.bat` in the new folder. The first time, Windows Firewall asks about Java: tick
  **Private networks**, so that the lab's computers can reach Breadboard, and click **Allow**.
- **Linux**: unzip it (if `unzip` is missing: `sudo apt install unzip`) and run `./breadboard.sh`
  in this folder.

The console opens in your browser once the server is up (and again if you start Breadboard a second
time while it runs). Its window also says where Breadboard is:
`On this computer: http://localhost:9000`, and for the other computers on your network (a lab's)
`On this network: http://192.168.…:9000`. The first time, the console asks you to create your
researcher account; add your colleagues' accounts in Settings → Researchers. Stop the server with
Ctrl+C in its window, or by closing the window. If it stops by itself (it ran out of memory, say),
it is started again after a few seconds. If it can't start at all (a mistake in `conf/local.conf`,
say), its window says why, and so does `logs/start-errors.log`.

While the server runs, a Mac or a Windows computer doesn't go to sleep by itself. On Linux, started
in a desktop session (not over SSH or as a service), the launcher asks systemd (and on GNOME the
desktop too) to keep the computer awake; if the computer still suspends (some desktops do, such as
Cinnamon on Linux Mint), turn off automatic suspend in the power settings. For a long study, keep a laptop plugged in with its lid open: with
the lid closed, it sleeps anyway. On a Mac, also turn on **Prevent automatic sleeping … when the
display is off** (System Settings → Battery → Options; on a Mac mini or iMac, System Settings →
Energy).

Another port: put the line `http.port=8080` in `conf/local.conf` (see below), and every start uses
it, a double-click too. To use one just once, start it in Terminal: type `sh` and a space, drag
`breadboard.sh` into the window, type ` -Dhttp.port=8080` and press Return (on Linux,
`./breadboard.sh -Dhttp.port=8080`; on Windows, in a Command Prompt in this folder,
`breadboard.bat -Dhttp.port=8080`).

## Your settings

The settings you change go in `conf/local.conf`, one per line. Breadboard makes this file at first
start; open it in a text editor (TextEdit, Notepad) and add lines such as:

```
breadboard.rootUrl="https://breadboard.example.org"
breadboard.allowLateJoin=true
```

The quotes must be straight ones (`"`). TextEdit's Smart Quotes, on unless you turned it off, makes
them curly (`“ ”`) as you type, and so does a Chinese input method's punctuation. Turn Smart Quotes
off (in TextEdit, Edit → Substitutions → Smart Quotes) before you type, or type those quotes again
afterwards. Breadboard won't start with curly quotes in `conf/local.conf`, and says on which line
they are.

Then restart Breadboard. `conf/application-prod.conf` lists every setting with what it does, but
don't change it there: a new release brings its own, while `conf/local.conf` is yours to keep.

Two settings there are for the launchers: `http.port=8080` sets the port (9000 otherwise), and
`breadboard.memory=2g` how much memory the server may use for its data (1g otherwise; give it more
if it runs out of memory).

## Before participants join

Participants need an address they can reach, for example `https://breadboard.example.org`. The
links the console gives you for participants use the address you opened the console at, so open
it at that public address before copying them. For Mechanical Turk, also set `breadboard.rootUrl`
in `conf/local.conf` to it: HITs send workers there.

For a server on the internet, put it behind a reverse proxy that provides HTTPS. With
[Caddy](https://caddyserver.com), the whole configuration is:

```
breadboard.example.org {
  reverse_proxy localhost:9000
}
```

Then set `breadboard.secureCookies=true` and `session.secure=true` in `conf/local.conf`.

## Where things are kept

| Path | What |
|---|---|
| `db/breadboard.h2.db` | The database: experiments, sessions, accounts and data. |
| `backups/` | Copies of the database, made every day and before an upgrade; the newest 14 are kept. Settings → Backups lists them. |
| `conf/local.conf` | Your settings (see above) |
| `conf/application-prod.conf` | Every setting, with what it does (don't change it here) |
| `conf/application.secret` | Generated at first start; signs sign-ins and invitation links. Keep it private. |
| `logs/application.log` | The server's log (a new one every day; 30 days are kept; set in `conf/logger.xml`) |
| `groovy/` | The engine's scripts (part of Breadboard; don't edit) |
| `jre/` | Java, in the downloads that include it |

On macOS and Linux, only the account that runs Breadboard can read `db/`, `backups/`, `logs/` and
`conf/local.conf`.

## Backups

The server copies its database into `backups/` once a day, and before a newer release changes it;
the newest 14 copies are kept. The console's Settings → Backups shows them, makes one when you ask,
and downloads them. Copy them to another disk now and then. To go back to one: stop Breadboard,
unzip the copy into `db/` (replacing `breadboard.h2.db`), and start Breadboard again.

## Upgrading from an earlier release

1. Stop the old server and make a copy of its `db/breadboard.h2.db`.
2. Copy that file into this folder's `db/`. From a 3.x release, also copy `conf/application.secret`;
   if you use file mode, copy the `dev/` folder too.
3. Copy your `conf/local.conf` into this folder's `conf/`. Releases before 3.1 had none: if you
   changed settings in the old `conf/application-prod.conf` (such as `breadboard.rootUrl`,
   `breadboard.secureCookies`, `session.secure`, AWS keys), put just those lines in
   `conf/local.conf` instead.
4. Start this release. It updates the database's tables by itself.

Experiments keep their participant pages. The new researcher console opens at the server's
address; the previous one is still at `/legacy-admin`. Researchers sign in again after the upgrade.
The 2.x launchers also served HTTPS on port 9443 with a self-signed certificate; these don't, so
use a reverse proxy as above (or see `docs/install.md`).

## More

Source code, documentation and new releases: <https://github.com/TianzhuQin/breadboard>.
What changed in this release: `CHANGELOG.md`. Breadboard's licence: `LICENSE.md`. `jre/` is Azul
Zulu 8 (OpenJDK), GPLv2 with the Classpath Exception; its licence files are in `jre/`.
