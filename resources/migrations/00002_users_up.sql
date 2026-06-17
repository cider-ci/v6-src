CREATE TABLE users (
  id uuid DEFAULT uuidv7() NOT NULL PRIMARY KEY,
  login text CHECK (login ~ '^[A-Za-z]+[A-Za-z0-9]+$'::text),
  name text,
  is_admin boolean DEFAULT false NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);


CREATE UNIQUE INDEX login_users ON users USING btree (lower(login::text));



-- email_addresses ---------------------------------------------------------------------

CREATE TABLE email_addresses (
  id uuid DEFAULT uuidv7() NOT NULL PRIMARY KEY,
  email_address text NOT NULL CHECK (email_address::text ~* '\S+.*@.*\S+'::text),
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  is_primary boolean DEFAULT false NOT NULL ,
  created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE UNIQUE INDEX email_addresses_email_address_idx ON email_addresses USING btree (lower(email_address::text));
CREATE INDEX email_addresses_user_id_idx ON email_addresses USING btree (user_id);



-- passwords ------------------------------------------------------------------

CREATE TABLE passwords (
  user_id uuid NOT NULL PRIMARY KEY REFERENCES users ON DELETE CASCADE ,
  password_hash text NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- sessions -------------------------------------------------------------------

CREATE TABLE sessions (
  id uuid DEFAULT uuidv7() NOT NULL PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  token_digest text NOT NULL,
  data jsonb,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  valid_until timestamp with time zone DEFAULT (now() + INTERVAL '7 days') NOT NULL
);

CREATE INDEX user_id_sessions ON sessions (user_id);
CREATE INDEX digest_sessions ON sessions (token_digest);
