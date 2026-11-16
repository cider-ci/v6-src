-- Every attempt to post a job state as a GitHub commit status, succeeded or
-- not: the commit page lists them. job_status_pushes keeps only the current
-- state per job; this is the history. Pruned after 30 days by the daemon.
CREATE TABLE status_push_attempts (
  id           uuid        PRIMARY KEY DEFAULT uuidv7(),
  job_id       uuid        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
  project_id   text        NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
  commit_id    text        NOT NULL,
  job_state    text        NOT NULL,
  github_state text        NOT NULL,
  context      text        NOT NULL,
  target       text        NOT NULL,
  success      boolean     NOT NULL,
  error        text,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX status_push_attempts_commit_idx ON status_push_attempts (project_id, commit_id, created_at DESC);
CREATE INDEX status_push_attempts_created_at_idx ON status_push_attempts (created_at);
