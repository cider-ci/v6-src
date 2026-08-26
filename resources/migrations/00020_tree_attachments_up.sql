CREATE TABLE tree_attachments (
  id           UUID        PRIMARY KEY DEFAULT uuidv7(),
  tree_id      TEXT        NOT NULL CHECK (length(tree_id) = 40),
  path         TEXT        NOT NULL,
  content_type TEXT        NOT NULL DEFAULT 'application/octet-stream',
  content      BYTEA       NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tree_id, path)
);
