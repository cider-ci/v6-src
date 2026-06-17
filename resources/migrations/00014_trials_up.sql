CREATE TABLE trials (
  id            uuid        PRIMARY KEY DEFAULT uuidv7(),
  task_id       uuid        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  executor_id   uuid,
  state         text        NOT NULL DEFAULT 'pending',
  token         uuid        NOT NULL DEFAULT uuidv7(),
  error         text,
  result        jsonb,
  started_at    timestamptz,
  finished_at   timestamptz,
  dispatched_at timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT trials_state_valid CHECK (state IN (
    'pending', 'dispatching', 'executing', 'passed', 'failed',
    'aborting', 'aborted', 'defective'))
);

CREATE INDEX trials_task_idx ON trials (task_id);
