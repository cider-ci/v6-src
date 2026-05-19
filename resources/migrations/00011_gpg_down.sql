ALTER TABLE commits DROP COLUMN IF EXISTS signature_fingerprint;
DROP TABLE IF EXISTS gpg_keys;
