# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CIDER-CI v6 is a CI/CD server application. The backend is Clojure (JVM/http-kit), the frontend is ClojureScript (React/Reagent), and integration tests use Ruby/RSpec with Capybara and Selenium WebDriver (Firefox).

Tool versions are managed by [mise](https://mise.jdx.dev/) (see `.tool-versions`): Java (Zulu 21), Node 22, Ruby 3.1, Firefox ESR, GeckoDriver.

### Relationship to the legacy application

This codebase is **a ground-up rewrite — the next major version** of an older CIDER-CI application. Only a minimal subset of the legacy functionality has been ported so far, plus some extensions on top. Expect most legacy features to be missing here; they will be ported incrementally.

The legacy application lives at `/Users/tom/Programming/CIDER-CI/ZHdK/inventory/cider-ci` (a deployment reference checkout) and consists of two parts:
- a **Ruby on Rails** component
- a **Clojure** component using older, now-outdated libraries (Leiningen, older Ring stack, etc. — see `lein-dev-plugin`, `server`, `user-interface`, `executor` subdirectories there)

Consult that path when you need to understand original behaviour, data models, or feature semantics that haven't yet been re-implemented in v6. Do **not** copy code verbatim — the v6 stack (deps.edn, next.jdbc, Reitit, Reagent, shadow-cljs) is intentionally modernized.

### Migration strategy

The goal is to transfer the old codebase into v6, with different treatment per layer:

- **Rails part → full rewrite** in Clojure on the v6 stack. The Rails code is a reference for behaviour and data shape, not a source for porting.
- **Clojure backend → port with light adjustments.** Much of the old Clojure backend logic can be reused with adaptations for the new stack (Leiningen → deps.edn, old Ring/Compojure → Reitit, old JDBC → next.jdbc + HoneySQL, etc.).
- **Frontend → full replacement/rewrite** as a Reagent SPA.

### Frontend design principle

The SPA should stay **deliberately simple** in dynamic behaviour. Aim for a **classic webapp look-and-feel** by default:
- Page-style navigation, server-rendered-feeling flows, form-submit → reload semantics where reasonable
- Minimal client-side state, minimal reactive UI tricks
- React Bootstrap components used in their plain form

Only add richer dynamic behaviour (live updates, optimistic UI, complex client state) **in specific places where it is actually needed**. Do not introduce SPA-style interactivity preemptively across the app.

## Domain model (from legacy)

The CIDER-CI execution model has four levels — keep this terminology consistent when porting:

- **Project** — a Git project under CI, tracked through one or more **repositories**
- **Branch / Commit** — tracked refs and their tips
- **Job** — a top-level CI run triggered by a commit; defined by a `cider-ci.yml` in the repo
- **Task** — a parallelizable unit of a job (a job decomposes into many tasks)
- **Trial** — a single execution attempt of a task on an **executor** (a task may be retried → multiple trials)
- **Executor** — an external agent process that picks up trials, runs the scripts, reports results and uploads attachments/artifacts

Tagline from the legacy README: *"highly parallelized and resilient integration testing."*

## Legacy → v6 porting roadmap (rough)

This is the high-level plan for bringing legacy functionality into v6. It is intentionally coarse — refine as work progresses.

**Already scaffolded / in progress in v6**
- DB layer (next.jdbc + HikariCP), SQL migrations
- Users, auth, sign-in/out, initial setup
- Projects, repositories, branches, commits (basics)
- SPA shell (Reagent), shared routes (Reitit)

**Phase 1 — finish the "git-tracking" surface**
- Branch + commit listing, detail views
- Repository sync loop (git fetch poller; legacy did this in the server process via JGit)
- GPG signature verification on commits (utility already present in `cider-ci.utils.git-gpg`)

**Phase 2 — Job model**
- Parse `cider-ci.yml` from a commit's tree → Job specification
- Schema for jobs (state machine: pending → executing → passed/failed/aborted)
- UI: trigger a job on a commit, list jobs per branch/commit

**Phase 3 — Tasks & Trials**
- Decompose Job → Tasks (parallel matrix, traits, env)
- Trials schema + state machine; retry policy
- UI: nested job/task/trial views

**Phase 4 — Dispatcher**
- Match pending trials to available executors by traits/load
- HTTP API for executor ↔ server (the legacy protocol can largely be reused; adapt to the new HTTP/auth stack)

**Phase 5 — Executor**
- Port the legacy executor service. Legacy executor logic lives in `server/src/cider_ci/executor/` in the old tree and is one of the strongest candidates for **port with light adjustment** (git checkout, script runner, trial lifecycle, attachment upload)
- Likely a separate Clojure entry point (sibling to `server/`)

**Phase 6 — Attachments / artifacts**
- File storage for trial outputs and logs
- Retrieval API + UI

**Phase 7 — Quality-of-life**
- Notifications, filters, search, dashboards

**Phase 8 — Selective live updates**
- Only where users actually need them (e.g. running trial output). Prefer SSE or polling over full Sente/WebSocket replication — keep with the "classic webapp first" principle.

### Notes for porting
- Legacy DB schema reference: `/Users/tom/Programming/CIDER-CI/ZHdK/inventory/cider-ci/server/resources/migrations/` (numbered SQL files, latest `433_structure.sql`-style). Useful as a data-model source even though v6 writes its own migrations from scratch.
- Legacy Rails `user-interface/` is **not** a porting source for code — only for understanding required views/flows. The v6 frontend is a fresh Reagent SPA.
- Legacy used JSON-ROA for its API; v6 should expose plain JSON REST via Reitit. Do not port the JSON-ROA client/server machinery.
- Legacy `clj-utils` is partially superseded by `cljc-src/cider_ci/utils/` here — port utilities on demand, don't bulk-import.

## Development Commands

### Server
```bash
./bin/server-db-migrate   # run DB migrations (PostgreSQL required)
./bin/server-run          # start dev server on localhost:3838 (with REPL)
```

### ClojureScript
```bash
./bin/cljs-watch    # incremental build (shadow-cljs watch)
./bin/cljs-build    # single build
./bin/cljs-release  # production release build (advanced optimizations)
```

### CSS
```bash
./bin/css-watch     # watch Sass files
./bin/css-build     # single build (Sass → PostCSS autoprefixer)
npm run css         # same as css-build via npm
npm run css-lint    # stylelint check
```

### Integration tests (RSpec)
```bash
bundle exec rspec spec/features/          # full suite
bundle exec rspec spec/features/foo_spec.rb        # single file
bundle exec rspec spec/features/foo_spec.rb:42     # single example
```

Key test env vars:
- `CIDER_CI_TRIAL_ID=1` — disables pry-on-failure (use in scripts/CI)
- `NOPRY_ON_EXCEPTION=true` — same effect
- `CI_HTTP_HOST` / `CI_HTTP_PORT` — server address (default `localhost:3838`)
- `CIDER_CI_DATABASE_NAME` — database name (default `cider_ci_v6`)

## Architecture

### Shared source: `cljc-src/cider_ci/`
All Clojure and ClojureScript source lives here. Files with `.cljc` or paired `.clj`/`.cljs` siblings are shared between backend and frontend. The namespace root is `cider-ci`.

### Backend key namespaces
| Namespace | Role |
|---|---|
| `cider-ci.server.db` | PostgreSQL via next.jdbc + HikariCP; migrations; row-event hooks |
| `cider-ci.server.http` | HTTP auth, CSRF, outgoing HTTP client |
| `cider-ci.server.html` | SPA entry point, static asset serving |
| `cider-ci.server.resources.*` | REST handlers: projects, users, commits, sign-in/out |
| `cider-ci.server.projects.repositories` | Git clone/fetch, branch/commit tracking (JGit) |
| `cider-ci.server.routing` | Reitit route table (shared with CLJS for client-side routing) |
| `cider-ci.utils.*` | CLI, system, Git/GPG, UUID, duration, NIO helpers |

### Frontend
ClojureScript with Reagent (React wrapper). Routes are defined in `cider-ci.server.routes` (`.cljc`) and shared with the backend. UI components use React Bootstrap v5 and Font Awesome. Compiled output goes to `resources/cider-ci/public/js/`.

### Database
PostgreSQL. Migrations are plain SQL files in `resources/migrations/` (numbered `NNNNN_up/down.sql`). HoneySQL is used for query building.

### Notable dependency
`logbug/logbug` is a **local dependency** at `../../CLOJURE/logbug` (see `deps.edn`). It must be present on disk.

## Full-stack dev workflow

1. Start PostgreSQL, then: `./bin/server-db-migrate`
2. `./bin/server-run` (Clojure server + nREPL on 3838)
3. `./bin/cljs-watch` (shadow-cljs dev server on 8020, outputs to `resources/`)
4. `./bin/css-watch`
5. Open `http://localhost:3838` — first run shows initial-setup UI

## mise plugin setup (from README)

```bash
mise plugin install geckodriver https://github.com/DrTom/asdf-geckodriver.git
mise plugin install firefox https://github.com/DrTom/asdf-firefox.git
```
