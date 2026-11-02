-- GPG keys an executor accepts commit signatures of, reported by the executor
-- on every sync: entries {"fingerprint": primary, "fingerprints": [primary and
-- subkeys], "key_id", "user_ids"}. An empty list accepts any commit, signed or
-- not; otherwise only trials of commits whose verified signature_fingerprint
-- is one of the fingerprints are dispatched. An entry without fingerprints
-- (e.g. {"error": ...}, a key the executor could not parse) matches nothing.
ALTER TABLE executors
  ADD COLUMN accepted_gpg_keys jsonb NOT NULL DEFAULT '[]';

CREATE OR REPLACE FUNCTION cider_ci_signature_accepted(p_fingerprint text, p_keys jsonb)
RETURNS boolean AS $$
  SELECT p_keys IS NULL
      OR jsonb_typeof(p_keys) <> 'array'
      OR jsonb_array_length(p_keys) = 0
      OR (p_fingerprint IS NOT NULL
          AND EXISTS (SELECT 1
                      FROM jsonb_array_elements(p_keys) AS k
                      WHERE jsonb_typeof(k->'fingerprints') = 'array'
                        AND k->'fingerprints' ? upper(p_fingerprint)))
$$ LANGUAGE sql IMMUTABLE;
