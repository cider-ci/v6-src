-- Branch updates whose job configuration could not be evaluated (typically a
-- submodule commit that was not pushed yet, or pushed after the project that
-- includes it). They are retried while the commit is within the repository's
-- branch_trigger_max_commit_age and removed once the evaluation succeeds or
-- the commit ages out.
CREATE TABLE pending_branch_triggers (
  repository_id text        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  commit_id     text        NOT NULL,
  branch_name   text        NOT NULL,
  error         text,
  attempts      integer     NOT NULL DEFAULT 1,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (repository_id, commit_id, branch_name)
);
