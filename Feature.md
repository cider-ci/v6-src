
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

## Current state (as of 2026-08-19)

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

## Next steps

- Investigate and fix the trixie2 Firefox OOM crashes to get to 46/46 passing.
- Consider adding a RAM/concurrency limit for executor tasks on trixie2.
