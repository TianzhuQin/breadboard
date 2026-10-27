# Breadboard researcher console

The console researchers use to build experiments and run sessions. It is served at `/` once you
sign in; the previous AngularJS console stays at `/legacy-admin` while people move over. Both
talk to the same server, and both can be open at once.

Vue 3, TypeScript and Vite. No UI framework: the components in `src/ui` and the design tokens in
`src/styles/tokens.css` are the whole design system (light and dark themes follow the computer,
or the choice made under Settings).

## Building

From `frontend/` (a pnpm workspace):

```bash
pnpm install
pnpm --filter ./admin run build      # just the console
pnpm run build:all                   # every package, the console included
```

The build writes `public/admin/` (`admin.js`, `admin.css`, and hashed `chunks/` and `assets/`),
which `bin/sbt8 stage` packs into the server. `public/admin` is not committed.

While working on it, `pnpm --filter ./admin run dev` rebuilds on every change; a server started
with `sbt run` serves the new files on the next page load.

## Checks

```bash
pnpm --filter ./admin run typecheck  # vue-tsc
pnpm --filter ./admin run test       # vitest, in jsdom
```

## Layout

| Folder | What is in it |
|---|---|
| `src/store` | `admin.ts`: everything the console knows about the server, and the actions pages call. Pages never use the socket themselves. |
| `src/lib` | The websocket (`connection.ts`, sends `LogIn` on every open), the live network model (`graph.ts`), `http.ts`, theme. |
| `src/ui` | Buttons, fields, dialogs, menus, tabs, toasts, confirm, the code editor (CodeMirror 6). |
| `src/shell` | Sidebar, top bar (session, engine, connection), output drawer, experiment switcher. |
| `src/network` | The network view (canvas, d3-force), tie editing, filters, exports. |
| `src/pages/*` | One folder per page; see `src/router.ts` for the list. |

Page styles are global (not scoped), so every page prefixes its class names with its own short
block name, e.g. `.pd__head` on the player panel and `.prmd__…` in the parameter dialog. Pick a
prefix nobody uses yet.

`vite.config.ts` puts dependencies in a `vendor` chunk and shared code in an `app` chunk, so a
lazily loaded page never imports `admin.js` itself: the page loads it as `admin.js?v=<version>`,
and an import of the plain URL would start the app a second time.

## Talking to the server

- `GET /state` says who is signed in and where the websocket is. The console then opens
  `/connect` and sends `LogIn`.
- The server answers most actions with the full picture ("User JSON": the account, its
  experiments, the selected experiment and session) and streams the network as `addNode`,
  `nodePropertyChanged`, `addLink` and similar messages.
- Refusals come back as `{"error": "...", "request": "<Action>"}`, live events as
  `{"event": {...}}`, tie edits as `{"tieEdit": {...}}`.
- Steps, content, parameters, images, the participant page, experiments, data downloads,
  autopilot settings and Mechanical Turk use plain HTTP routes; see `conf/routes`.
