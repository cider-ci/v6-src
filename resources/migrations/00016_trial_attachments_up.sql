CREATE TABLE trial_attachments (
  id           UUID        PRIMARY KEY DEFAULT uuidv7(),
  trial_id     UUID        NOT NULL REFERENCES trials(id),
  path         TEXT        NOT NULL,
  content_type TEXT        NOT NULL DEFAULT 'application/octet-stream',
  content      BYTEA       NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (trial_id, path)
);
