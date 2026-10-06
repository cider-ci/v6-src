DROP INDEX IF EXISTS jobs_priority_created_at_idx;

ALTER TABLE tasks
  DROP COLUMN priority;

ALTER TABLE jobs
  DROP COLUMN priority;
