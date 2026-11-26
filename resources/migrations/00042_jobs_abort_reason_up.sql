-- Why a job was aborted by the server (not by a user): e.g. its commit is no
-- longer reachable from any branch head after a force push. Shown on the job
-- page.
ALTER TABLE jobs ADD COLUMN abort_reason text;
