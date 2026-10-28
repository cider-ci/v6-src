-- GitHub sign-in: accounts are matched by the GitHub user id; email
-- addresses and GPG keys imported from GitHub are marked by source so that
-- the next GitHub sign-in replaces exactly those.

ALTER TABLE users ADD COLUMN github_id bigint;
ALTER TABLE users ADD COLUMN github_login text;
CREATE UNIQUE INDEX users_github_id_idx ON users (github_id);

ALTER TABLE email_addresses ADD COLUMN source text
  CONSTRAINT email_addresses_source_check CHECK (source IN ('github'));

ALTER TABLE gpg_keys ADD COLUMN source text
  CONSTRAINT gpg_keys_source_check CHECK (source IN ('github'));
