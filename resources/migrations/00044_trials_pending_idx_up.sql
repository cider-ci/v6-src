-- The dispatch query must start from the few pending trials, not from all
-- tasks: without this the planner scanned every task and evaluated the
-- acceptance functions for tens of thousands of rows (1 s per sync on ZHdK
-- with 120k tasks and no pending trial at all).
CREATE INDEX trials_pending_idx ON trials (task_id) WHERE state = 'pending';
