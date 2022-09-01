CREATE TABLE settings (
  id int DEFAULT 0 NOT NULL PRIMARY KEY CHECK (id = 0),
  external_base_url text NOT NULL DEFAULT 'http://localhost:3838',
  git_fetch_and_update_max_concurrent INT CHECK (git_fetch_and_update_max_concurrent > 0),
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);

INSERT INTO settings (id) VALUES (0);


CREATE OR REPLACE FUNCTION update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
  BEGIN
    NEW.updated_at = now();
    RETURN NEW;
  END;
  $$;


