-- "Push statuses now": the job's state is posted again, whatever the job's
-- age (the daemon otherwise only considers jobs updated within 24 hours).
ALTER TABLE job_status_pushes ADD COLUMN repost_requested_at timestamptz;
