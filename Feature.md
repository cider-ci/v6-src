Execution End-to-End — Self-Testing via Integration Tests
==========================================================

## Status: Implemented (supersedes original goal)

The original goal was to run the demo project on trixie1+trixie2 as a manual
smoke test. That goal has been superseded by a fully automated self-CI setup:
v6 now tests itself by running its own RSpec integration test suite as parallel
CI tasks on trixie1 (server) and trixie2 (executor).

Each spec file in `spec/features/` becomes one task. trixie2 builds the
uberjar, then runs each spec in isolation: fresh DB, server on a dynamic port,
headless Firefox. The demo project (`cider-ci/fixtures/demo-project.bundle`)
is used inside the tests as a git fixture — the executor clones from it when
specs exercise the trial execution pipeline.

## Phases implemented (Phases 1–6 complete)

- **Phase 1** — git-tracking: branch/commit listing, repo sync loop (JGit),
  GPG signature verification
- **Phase 2** — Job model: parse `cider-ci.yml`, job state machine, trigger UI
- **Phase 3** — Tasks & Trials: job decomposition, trial state machine,
  retry policy, nested job/task/trial UI
- **Phase 4** — Dispatcher: trait-based trial dispatch, executor HTTP API
- **Phase 5** — Executor: ported from legacy (git checkout, script runner,
  trial lifecycle, attachment upload); ships as part of the uberjar
- **Phase 6** — Attachments: trial attachments (per-trial BYTEA storage),
  tree attachments (per-git-tree, shared across trials on the same source tree),
  both exposed via REST API and shown in the UI on trial and commit pages

## Self-CI state (as of 2026-08-26)

- Self-CI is running on trixie1+trixie2 for every push to master.
- ~44 of 46 spec tasks pass consistently.
- Two intermittent Firefox crash failures (`features_spec`, `trial_reliability_spec`)
  are resource-related (OOM on trixie2) and unrelated to application logic.

## What the demo project is

`data/repositories/cider-ci-demo-project` / `cider-ci/fixtures/demo-project.bundle`
is a bare git repo used as a test fixture. It is the remote the executor clones
when a trial needs a working-directory checkout. It is not a CI subject with its
own `cider-ci.yml`; its purpose is to provide a realistic git history for specs
that exercise the git-fetch, branch-tracking, and trial-checkout paths.

## Open todos

**Stability**
- Investigate and fix the trixie2 Firefox OOM crashes to reach 46/46 passing.
  Likely fix: RAM/concurrency limit for executor tasks on trixie2, or increase
  trixie2 RAM allocation.

**Phase 7 — Quality-of-life**
- Notifications (e.g. email or webhook on job pass/fail)
- Filters and search (jobs by state, commits by branch, etc.)
- Dashboards (aggregate pass-rate view across branches/projects)

**Phase 8 — Selective live updates**
- Live streaming of running trial script output (SSE or polling)
- Auto-refresh of job/task/trial status pages while executing
- Keep with "classic webapp first": only add live updates where genuinely needed
