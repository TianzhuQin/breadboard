# Breadboard

Breadboard runs experiments in which people interact over a network: each participant sees their
neighbors, makes choices, and may add or cut ties, while the researcher watches and steers the
session from a web console, or lets the autopilot run it.

The full documentation is in `docs/` (also at
<https://github.com/TianzhuQin/breadboard/tree/main/docs>); `docs/zh-CN.md` is a quick start in
Chinese.

## What you need

- **Java 8.** Newer versions of Java don't run this server. Install a Java 8 JDK, for example
  [Azul Zulu 8](https://www.azul.com/downloads/?version=java-8-lts&package=jdk) or
  [Eclipse Temurin 8](https://adoptium.net/temurin/releases/?version=8). If another Java is your
  default, point `JAVA8_HOME` at the Java 8 one.
- macOS, Linux or Windows, and a modern browser.

## Start it

- macOS and Linux: open a terminal in this folder and run `./breadboard.sh`
- Windows: double-click `breadboard.bat`

Then open <http://localhost:9000>. The first time, it asks you to create the researcher account.
Stop the server with Ctrl+C in its window.

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
| `db/breadboard.h2.db` | The database: experiments, sessions, accounts and data. Back it up by copying it while the server is stopped. |
| `conf/application-prod.conf` | The settings |
| `conf/application.secret` | Generated at first start; signs sign-ins and invitation links. Keep it private. |
| `logs/application.log` | The server's log (a new one every day; 30 days are kept; set in `conf/logger.xml`) |
| `groovy/` | The engine's scripts (part of Breadboard; don't edit) |

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
