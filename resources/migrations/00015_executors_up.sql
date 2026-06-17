CREATE TABLE executors (
  id         uuid PRIMARY KEY DEFAULT uuidv7(),
  name       text NOT NULL UNIQUE,
  token_hash text NOT NULL UNIQUE,
  token_part text NOT NULL,
  max_load   float NOT NULL DEFAULT 4.0,
  traits     text[] NOT NULL DEFAULT '{}',
  enabled    boolean NOT NULL DEFAULT true,
  last_seen_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
