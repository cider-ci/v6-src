ALTER TABLE jobs
  ADD CONSTRAINT jobs_project_commit_key_unique
  UNIQUE (project_id, commit_id, key);
