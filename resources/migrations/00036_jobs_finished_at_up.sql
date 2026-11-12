-- When the job reached its final state (passed, failed, aborted, defective);
-- NULL while it is pending or executing and again after a retry. Deploy jobs
-- are judged by when they last ran, not by when they were created.
ALTER TABLE jobs ADD COLUMN finished_at timestamptz;
UPDATE jobs SET finished_at = updated_at
  WHERE state IN ('passed', 'failed', 'aborted', 'defective');
