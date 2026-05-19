
-- gpg_keys ------------------------------------------------------------------

CREATE TABLE gpg_keys (
  id uuid DEFAULT public.uuid_generate_v4() NOT NULL PRIMARY KEY,
  fingerprint text NOT NULL,
  name text NOT NULL,
  ascii_key text NOT NULL,
  description text,
  user_id uuid REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT gpg_keys_fingerprint_unique UNIQUE (fingerprint)
);

-- user_id IS NULL means globally trusted (admin-managed)
CREATE INDEX gpg_keys_user_id_idx ON gpg_keys USING btree (user_id);


-- commits: add verified signature fingerprint --------------------------------

ALTER TABLE commits ADD COLUMN signature_fingerprint text;

CREATE INDEX commits_signature_fingerprint_idx ON commits USING btree (signature_fingerprint);
