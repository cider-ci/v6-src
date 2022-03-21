CREATE TABLE users (
  id uuid DEFAULT public.uuid_generate_v4() NOT NULL PRIMARY KEY,
  name text,
  email text NOT NULL,
  is_admin boolean DEFAULT false NOT NULL,
  is_system_admin boolean DEFAULT false NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);


CREATE UNIQUE INDEX email_users ON users USING btree (lower(email::text));

