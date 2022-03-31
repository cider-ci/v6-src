CREATE TABLE users (
  id uuid DEFAULT public.uuid_generate_v4() NOT NULL PRIMARY KEY,
  name text,
  email text NOT NULL,
  is_admin boolean DEFAULT false NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);


CREATE UNIQUE INDEX email_users ON users USING btree (lower(email::text));



-- passwords ------------------------------------------------------------------

CREATE TABLE passwords (
  user_id uuid NOT NULL PRIMARY KEY REFERENCES users ON DELETE CASCADE ,
  password_hash text NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- sessions -------------------------------------------------------------------

CREATE TABLE sessions (
  id uuid DEFAULT public.uuid_generate_v4() NOT NULL PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  token_digest text NOT NULL,
  browser text,
  ip text,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  valid_until timestamp with time zone DEFAULT (now() + INTERVAL '7 days') NOT NULL
);

CREATE INDEX user_id_sessions ON sessions (user_id);
CREATE INDEX digest_sessions ON sessions (token_digest);
