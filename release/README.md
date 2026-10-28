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

- **macOS**: double-click `Start Breadboard.command`. The first time, macOS may say it can't check
  who made it: right-click the file and choose **Open** (on newer macOS, click **Open Anyway** in
  System Settings → Privacy & Security). Or open Terminal in this folder and run `./breadboard.sh`.
- **Windows**: double-click `breadboard.bat`.
- **Linux**: run `./breadboard.sh` in this folder.

The console opens in your browser once the server is up (or open <http://localhost:9000>). The
first time, it asks you to create your researcher account; add your colleagues' accounts in
Settings → Researchers. Stop the server with Ctrl+C in its window, or by closing the window.

Another port: `./breadboard.sh -Dhttp.port=8080` (or `breadboard.bat -Dhttp.port=8080`).

## Before participants join

Participants need an address they can reach, for example `https://breadboard.example.org`. The
links the console gives you for participants use the address you opened the console at, so open
it at that public address before copying them. For Mechanical Turk, also set `breadboard.rootUrl`
in `conf/application-prod.conf` to it: HITs send workers there.

For a server on the internet, put it behind a reverse proxy that provides HTTPS. With
[Caddy](https://caddyserver.com), the whole configuration is:

```
breadboard.example.org {
  reverse_proxy localhost:9000
}
```

Then set `breadboard.secureCookies=true` and `session.secure=true` in
`conf/application-prod.conf`.

## Where things are kept

| Path | What |
|---|---|
| `db/breadboard.h2.db` | The database: experiments, sessions, accounts and data. |
| `backups/` | Copies of the database, made every day and before an upgrade; the newest 14 are kept. Settings → Backups lists them. |
| `conf/application-prod.conf` | The settings |
| `conf/application.secret` | Generated at first start; signs sign-ins and invitation links. Keep it private. |
| `logs/application.log` | The server's log (a new one every day; 30 days are kept; set in `conf/logger.xml`) |
| `groovy/` | The engine's scripts (part of Breadboard; don't edit) |

## Backups

The server copies its database into `backups/` once a day, and before a newer release changes it;
the newest 14 copies are kept. The console's Settings → Backups shows them, makes one when you ask,
and downloads them. Copy them to another disk now and then. To go back to one: stop Breadboard,
unzip the copy into `db/` (replacing `breadboard.h2.db`), and start Breadboard again.

## Upgrading from an earlier release

1. Stop the old server and make a copy of its `db/breadboard.h2.db`.
2. Copy that file into this folder's `db/`. From a 3.x release, also copy
   `conf/application.secret`; if you use file mode, copy the `dev/` folder too.
3. Carry over the settings you changed in the old `conf/application-prod.conf` (such as
   `breadboard.rootUrl`, `breadboard.secureCookies`, `session.secure`, AWS keys): copy the
   values into this folder's file rather than the whole file, which has new settings.
4. Start this release. It updates the database's tables by itself.

Experiments keep their participant pages. The new researcher console opens at the server's
address; the previous one is still at `/legacy-admin`. Researchers sign in again after the upgrade.
The 2.x launchers also served HTTPS on port 9443 with a self-signed certificate; these don't, so
use a reverse proxy as above (or see `docs/install.md`).

## More

Source code, documentation and new releases: <https://github.com/TianzhuQin/breadboard>.
What changed in this release: `CHANGELOG.md`.
