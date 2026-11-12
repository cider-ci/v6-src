-- Jobs with a run_when entry of type cron are only created for repositories
-- that opt in (legacy migration 429): a forked or imported project must not
-- start running nightly deploys on its own.
ALTER TABLE repositories
  ADD COLUMN cron_trigger_enabled boolean NOT NULL DEFAULT false;
