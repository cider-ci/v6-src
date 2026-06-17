-- Jobs are keyed by commit_id (not tree_id as in the legacy schema).
-- Tree-keyed deduplication is deferred until job execution is implemented.

CREATE TABLE jobs (
  id          uuid        PRIMARY KEY DEFAULT uuidv7(),
  project_id  text        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  commit_id   text        NOT NULL,
  key         text        NOT NULL,
  name        text,
  description text,
  state       text        NOT NULL DEFAULT 'pending',
  spec        jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid        REFERENCES users(id) ON DELETE SET NULL,
  CONSTRAINT jobs_state_valid CHECK (state IN (
    'pending', 'executing', 'passed', 'failed',
    'aborting', 'aborted', 'defective'))
);

CREATE INDEX jobs_project_commit_idx ON jobs (project_id, commit_id);
