-- GitHub commit status pushes: what has been posted for a job. job_state is
-- the job state last handled (posted, or skipped because the project cannot
-- push); a job whose state differs is (re)pushed, failures are retried at
-- next_attempt_at.
CREATE TABLE job_status_pushes (
  job_id          uuid PRIMARY KEY REFERENCES jobs (id) ON DELETE CASCADE,
  job_state       text,
  github_state    text,
  posted_at       timestamptz,
  last_error      text,
  last_error_at   timestamptz,
  attempts        integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX jobs_updated_at_idx ON jobs (updated_at);
