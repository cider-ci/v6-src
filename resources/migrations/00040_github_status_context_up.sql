-- The context under which job states are posted as GitHub commit statuses
-- ("<prefix> - <job name>"); GitHub branch protection rules name it. NULL:
-- "Cider-CI@<host of the external base url>". The setting is the default,
-- a project may override it (e.g. to keep a legacy CI's name).
ALTER TABLE settings ADD COLUMN github_status_context text
  CONSTRAINT settings_github_status_context_not_empty CHECK (github_status_context <> '');
ALTER TABLE repositories ADD COLUMN github_status_context text
  CONSTRAINT repositories_github_status_context_not_empty CHECK (github_status_context <> '');
