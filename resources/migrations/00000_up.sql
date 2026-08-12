CREATE TABLE IF NOT EXISTS migrations (
  id INT NOT NULL PRIMARY KEY,
  created_at timestamp with time zone NOT NULL DEFAULT current_timestamp
);

INSERT INTO migrations (id) VALUES (0) ON CONFLICT DO NOTHING;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Provide uuidv7() for PostgreSQL < 17 (PG17+ has it as a pg_catalog built-in;
-- this creates public.uuidv7() which is a harmless fallback on newer versions).
CREATE OR REPLACE FUNCTION uuidv7() RETURNS uuid LANGUAGE sql AS $$
  SELECT (
    lpad(to_hex(floor(extract(epoch from clock_timestamp())*1000)::bigint), 12, '0') ||
    '7' ||
    lpad(to_hex(floor(random()*4096)::int), 3, '0') ||
    lpad(to_hex(floor(random()*16384 + 32768)::int), 4, '0') ||
    lpad(to_hex(floor(random()*281474976710656)::bigint), 12, '0')
  )::uuid
$$;
