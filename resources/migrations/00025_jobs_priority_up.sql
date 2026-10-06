-- Job priority (legacy parity): higher is dispatched first, default 0.
-- Set from `priority:` in the job's cider-ci configuration, overridable at
-- run time via the job page. Tasks get the task-level `priority:` for the
-- secondary ordering within a job.
ALTER TABLE jobs
  ADD COLUMN priority integer NOT NULL DEFAULT 0;

ALTER TABLE tasks
  ADD COLUMN priority integer NOT NULL DEFAULT 0;

CREATE INDEX jobs_priority_created_at_idx ON jobs (priority DESC, created_at ASC);
