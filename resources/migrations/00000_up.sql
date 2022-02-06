CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS migrations (
  id INT NOT NULL PRIMARY KEY,
  created_at timestamp with time zone NOT NULL DEFAULT current_timestamp
);

INSERT INTO migrations (id) VALUES (0) ON CONFLICT DO NOTHING;
