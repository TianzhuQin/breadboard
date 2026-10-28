# Developing Breadboard

How to build Breadboard from source, run it, test it and make a release. For running a release,
see [install.md](install.md).

## Requirements

- **A Java 8 JDK.** The server is built on Play Framework 2.2 and sbt 0.13, which only build and
  run on Java 8. Install one (for example [Azul Zulu 8](https://www.azul.com/downloads/?version=java-8-lts&package=jdk)
  or [Eclipse Temurin 8](https://adoptium.net/temurin/releases/?version=8)) and point
  `JAVA8_HOME` at it. `bin/sbt8` uses it (or `JAVA_HOME`) and stops if it isn't Java 8.
- **Node 20.19 or a later 20.x, or Node 22.12 or newer.** Vite 7, which builds the console,
  needs one of them. CI uses Node 22.
- **pnpm 10.** `frontend/package.json` names the exact version in `packageManager`
  (`pnpm@10.33.0`), and npm and yarn are refused. Without pnpm installed, run that version through
  npx (`npx -y pnpm@10.33.0 install`), as `bin/make-dist` does.
- bash, `curl`, `zip`, `unzip` and `shasum` for the scripts in `bin/`, and `python3` for
  `bin/smoke-dist`.

## Repository layout

| Path | What |
|---|---|
| `app/` | The server: controllers, actors, models (the script engine, sessions, autopilot), security, response filters, and the Play views that load the front end. |
| `conf/` | `routes`, the settings files (below), the database scripts in `evolutions/default/`, and the default participant page files in `defaults/`. |
| `groovy/` | The script engine's own Groovy scripts, loaded each time the engine is built, and their tests (`*_test.groovy`). |
| `test/` | The server's JUnit tests; `test/data/` holds experiment exports from older versions for the import tests. |
| `frontend/` | The front end, a pnpm workspace (below). |
| `public/` | Static files, served under `/assets`. The front-end builds write `public/admin/` and `public/bundles/`, which are not committed. |
| `project/` | The sbt build: `Build.scala` (version, dependencies), `plugins.sbt`, `sbt-repositories`. |
| `bin/` | `sbt8`, `run-local`, `make-dist`, `smoke-dist` (all described below). |
| `release/` | The launchers (`breadboard.sh`, `breadboard.bat`) and the `README.md` that go into the release zip. |
| `docs/` | This documentation. |

The settings files: `conf/application.conf` is what `bin/sbt8 run` and `bin/run-local` read,
`conf/application-dev.conf` is for working with the webpack dev server, and
`conf/application-prod.conf` is the one a release ships.

The front end:

| Folder | What | Built into |
|---|---|---|
| `frontend/admin` | The researcher console (Vue 3, TypeScript, Vite). Its [README](../frontend/admin/README.md) explains its layout. | `public/admin/` |
| `frontend/client` | The participant page (Vue 2, Vuetify). | `public/bundles/client.js` (root build); its own build makes the npm library in `client/dist/` |
| `frontend/core` | Code the other packages share: the websocket, participant tokens, tie edits. | `core/dist/`, which the participant page and the previous console import (the console takes only the websocket, from `core/src/`); `public/bundles/breadboard.js` (root build) |
| `frontend/design` | The previous AngularJS console (`/legacy-admin`) and the older AngularJS participant client. Built by the root package's `webpack.config.js`. | `public/bundles/design.js`, `client-angular.js` |

## Building

### The front end

```bash
cd frontend
pnpm install
pnpm run build:all
```

`build:all` builds the workspace packages first (`core`, then `client` and `admin`), then runs
webpack for `public/bundles/`. Each build empties its output folder first. What goes into `public/`
has no source maps; `BB_SOURCEMAPS=1 pnpm run build:all` adds them as separate `.map` files.

### The server

`bin/sbt8` runs sbt on Java 8. The first time, it downloads the sbt launcher into
`.sbt-launcher/` and checks its checksum. It resolves dependencies only from the https
repositories in `project/sbt-repositories`, in place of the old http addresses baked into sbt 0.13
and Play 2.2, which no longer serve anything.

```bash
export JAVA8_HOME=/path/to/jdk8
bin/sbt8 compile        # compile the server
bin/sbt8 stage          # build target/universal/stage, the server with public/ packed in
```

Build the front end before `stage`: it packs `public/` as it is. Without arguments, `bin/sbt8`
opens sbt's shell, where `compile`, `test` and `stage` run without starting sbt each time.

## Running locally

```bash
bin/run-local                                   # port 9000
bin/run-local 9100 -Dbreadboard.autopilot.experiment="My experiment"
```

`bin/run-local` starts the staged build (run `bin/sbt8 stage` first) with
`conf/application.conf`. Its database is in `.local-run/db/` and a random `application.secret`
in `.local-run/secret`, both created on first start; Play applies any new evolutions to the
database at every start. It sets `breadboard.rootUrl` to `http://localhost:<port>`. The first
argument is the port; any `-D` settings after it go to the server. `BREADBOARD_RUN_DIR` puts the
data somewhere else, and deleting `.local-run/` starts over. The server reads the engine's
scripts from the repository's `groovy/`. Open the console and create the first account, as in a
release.

### Working on the front end

The console:

```bash
cd frontend && pnpm --filter ./admin run dev
```

This rebuilds `public/admin/` on every change, unminified and with source maps. A server
started with `bin/sbt8 run` (Play's development mode, which also recompiles changed server code)
serves the new files on the next page load. A staged build carries its own copy of `public/`, so
with `bin/run-local`, run `bin/sbt8 stage` and restart it.

The participant page and the previous console:

```bash
cd frontend && pnpm start                                      # webpack dev server on port 8765
bin/sbt8 "run -Dconfig.file=conf/application-dev.conf"         # in another terminal
```

`pnpm start` serves the webpack bundles from memory at `http://localhost:8765/bundles/` and
rebuilds them on every change. `conf/application-dev.conf` sets `breadboard.assetsRoot` to
`http://localhost:8765`, so the pages load their bundles from the dev server. The new console's
page loads its files from there too, and the dev server doesn't serve them, so the new console
doesn't load in this setup. This configuration keeps its database in `db/` (created on first
start) and uses the placeholder `application.secret`, with which invitation links are disabled;
set `APPLICATION_SECRET` to use them.

## Tests

### Server

```bash
bin/sbt8 test                                   # every server test
bin/sbt8 "testOnly ControllerTest"              # one class
bin/sbt8 "testOnly models.StepTriggerTest"      # a class in a package
```

The tests share one Play application on an in-memory H2 database (`test/BaseTest.java`): it
starts once, applies every evolution, and empties all tables after each test. Test classes
therefore run one at a time (`parallelExecution in Test := false` in `project/Build.scala`). A
test that needs the application or the database extends `BaseTest`, which also has helpers to
create users, experiments, sessions, steps, content and parameters.

### Groovy scripts

The engine's scripts are tested in Groovy. Every `groovy/*_test.groovy` file registers cases,
and each case runs in a fresh engine with Breadboard's own scripts loaded:

```groovy
test("adds a player") {
  def v = g.addPlayer('p1')
  assert v != null
}

test.async("finishes later", 4000) { done ->     // timeout in milliseconds
  timers.newTimer().runAfter(50) { done() }
}

test.skip("not ready yet") { }                   // reported, not run
```

`bin/sbt8 test` runs them with the rest; `bin/sbt8 "testOnly GroovyScriptTests"` runs only
them. The server never loads files ending in `_test.groovy`, and releases don't include them.
The engine runs Groovy 1.8.6: no lambdas or method references.

### Front end

From `frontend/`:

```bash
pnpm run test:all                       # the unit tests of core, client and admin (vitest, jsdom)
pnpm --filter ./admin run typecheck     # type-check the console (vue-tsc)
pnpm --filter ./client run test:watch   # one package, rerunning on every change
```

Each package keeps its tests in its `test/` folder.

## Continuous integration

`.github/workflows/ci.yml` runs on every push to `main`, on every pull request, and on demand. A
new push cancels the run still going for the same branch or pull request.

| Job | What it does |
|---|---|
| Front end | Installs with `pnpm install --frozen-lockfile` on Node 22, runs `build:all`, type-checks the console and runs `test:all`. Keeps `public/admin` and `public/bundles` as the `frontend-build` artifact for 3 days. |
| Server tests | Runs `bin/sbt8 test` on Zulu Java 8, with the sbt and Ivy caches kept between runs. |
| Release package | After both pass: builds the release from the `frontend-build` artifact (`SKIP_FRONTEND=1 bin/make-dist`), then runs `bin/smoke-dist`. Keeps the zip and its `.sha256` as the `breadboard-release` artifact for 7 days. |

## Making a release

1. Set the new version in:
   - `project/Build.scala`: `appVersion` (with the `v`, e.g. `"v3.1.0"`); it names the zip.
   - `conf/application.conf`, `conf/application-dev.conf` and `conf/application-prod.conf`:
     `application.version`, the same value.
   - `frontend/package.json` (and `frontend/admin/package.json`): `version`, without the `v`.
2. Add a section for it at the top of `CHANGELOG.md`.
3. Build the release:

   ```bash
   JAVA8_HOME=/path/to/jdk8 bin/make-dist
   ```

   It builds the front end (`pnpm install --frozen-lockfile`, `build:all`; set
   `SKIP_FRONTEND=1` to use what is already in `public/`) and the server (`sbt dist`), then lays
   out `target/release/breadboard-<version>/`: the launchers (the `.bat` with Windows line
   endings), the server's jars in `lib/` with `lib/classpath.txt` (their order in Play's own start
   script, which the release leaves out: the launchers run Java themselves),
   `conf/application-prod.conf`, the engine's Groovy scripts without tests, empty `db/` and
   `logs/`, `release/README.md` as `README.md`, `LICENSE.md`, `CHANGELOG.md` and `docs/`. It writes
   `breadboard-<version>.zip` and `breadboard-<version>.zip.sha256` next to it. The output is
   not in `dist/` because Play copies whatever is in `dist/` into the package it builds.
4. Check it:

   ```bash
   bin/smoke-dist                                           # the newest zip in target/release/
   bin/smoke-dist --db /path/to/old/db/breadboard.h2.db     # on a copy of an older database
   ```

   `bin/smoke-dist` unzips the release into a temporary folder and starts it with
   `breadboard.sh` on an empty database (port 19000, or `PORT`). It checks the release's layout
   and the generated `application.secret` (which must not show on the server's command line),
   loads both consoles, the bundles and a participant
   page, creates the first account and signs in, then restarts on the same database and checks
   again. It fails if the server logs an error. With `--db` it starts on a copy of an older
   release's database instead; when that database already has accounts, it only checks that
   the server starts and serves its pages.
5. Commit, tag and publish:

   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   gh release create vX.Y.Z target/release/breadboard-vX.Y.Z.zip target/release/breadboard-vX.Y.Z.zip.sha256
   ```

   Use the version's `CHANGELOG.md` section as the release notes.

## Database changes

A change to the database needs two parts.

1. **An evolution**: `conf/evolutions/default/N.sql`, numbered after the last one, with a
   `# --- !Ups` and a `# --- !Downs` section. It has to work on H2 in MySQL mode, which is what
   every configuration uses.
2. **The same change made by Breadboard itself**, for databases that already exist.

Which one a database gets depends on how it is run:

| Database | What creates or changes its tables |
|---|---|
| A new, empty one, with evolutions off (a release, `application-dev.conf`) | `Global.createTablesIfEmpty` (`app/Global.java`): when there is no `experiments` table, it applies every evolution and records them in `play_evolutions`. |
| `bin/run-local` and the server tests | Play applies the evolutions (`applyEvolutions.default=true`). |
| An existing one, with evolutions off | Nothing from the evolutions: releases run with `evolutionplugin=disabled`, so Play never rewrites a database copied over from an older release. Breadboard's own code has to make the change. |

For the third case, make the change at startup in `Global.onStart`, or the first time the code
needs it. `models.AutopilotSettings` does the latter: before its first read or write it runs
`create table if not exists autopilot_settings (...)`, the same definition as evolution 31.
Columns are added the way `Global` adds them, with `alter table ... add column if not exists`.

Write both parts so that they do nothing on a database that already has the change: use
`if not exists` in the evolution too, as evolutions 31 and 32 do, and keep the two definitions
the same. A database can get the change from either one first. Then check an upgrade with
`bin/smoke-dist --db` on a database from the previous release.
