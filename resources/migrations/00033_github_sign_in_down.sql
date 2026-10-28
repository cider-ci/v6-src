ALTER TABLE gpg_keys DROP COLUMN source;
ALTER TABLE email_addresses DROP COLUMN source;
DROP INDEX users_github_id_idx;
ALTER TABLE users DROP COLUMN github_login;
ALTER TABLE users DROP COLUMN github_id;
