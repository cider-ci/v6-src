CREATE TABLE tasks (
  id                          uuid        PRIMARY KEY DEFAULT uuidv7(),
  job_id                      uuid        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  name                        text        NOT NULL,
  state                       text        NOT NULL DEFAULT 'pending',
  spec                        jsonb,
  traits                      text[]      NOT NULL DEFAULT '{}',
  load                        float       NOT NULL DEFAULT 1.0,
  dispatch_storm_delay_seconds integer    NOT NULL DEFAULT 1,
  entity_errors               jsonb       NOT NULL DEFAULT '[]',
  created_at                  timestamptz NOT NULL DEFAULT now(),
  updated_at                  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT tasks_state_valid CHECK (state IN (
    'pending', 'executing', 'passed', 'failed',
    'aborting', 'aborted', 'defective')),
  CONSTRAINT tasks_load_positive CHECK (load > 0)
);

CREATE INDEX tasks_job_idx ON tasks (job_id);
