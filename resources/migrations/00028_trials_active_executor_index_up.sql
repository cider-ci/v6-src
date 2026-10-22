-- the trials an executor is currently working on (executor load overview)
CREATE INDEX trials_active_executor_idx ON trials (executor_id)
  WHERE state IN ('dispatching', 'executing', 'aborting');
