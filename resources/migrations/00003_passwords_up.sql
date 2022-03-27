CREATE TABLE passwords (
  user_id uuid NOT NULL PRIMARY KEY  REFERENCES users ON DELETE CASCADE ,
  password_hash text NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);
