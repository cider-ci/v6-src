-- Per commit: can the whole submodule tree be resolved through the configured
-- projects (i.e. are all submodule commits present on this server)? Computed
-- on branch updates, re-checked when any repository fetch brings new commits,
-- and on demand ("Check now" on the commit page).
CREATE TABLE commit_submodule_resolutions (
  repository_id text        NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  commit_id     text        NOT NULL,
  state         text        NOT NULL CHECK (state IN ('resolved', 'unresolved', 'none', 'error')),
  submodules    jsonb       NOT NULL DEFAULT '[]',
  total         integer     NOT NULL DEFAULT 0,
  unresolved    integer     NOT NULL DEFAULT 0,
  error         text,
  checked_at    timestamptz NOT NULL DEFAULT now(),
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (repository_id, commit_id)
);

CREATE INDEX commit_submodule_resolutions_state_idx ON commit_submodule_resolutions (state, created_at);
