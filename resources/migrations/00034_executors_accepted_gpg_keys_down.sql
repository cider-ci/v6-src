DROP FUNCTION IF EXISTS cider_ci_signature_accepted(text, jsonb);
ALTER TABLE executors DROP COLUMN IF EXISTS accepted_gpg_keys;
