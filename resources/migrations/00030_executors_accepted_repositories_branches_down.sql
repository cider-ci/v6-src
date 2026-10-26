DROP FUNCTION IF EXISTS cider_ci_trial_accepted(text, text, text, jsonb);
DROP FUNCTION IF EXISTS cider_ci_pattern_matches(text, text);
-- back to plain url patterns (branch restrictions are dropped)
ALTER TABLE executors ADD COLUMN accepted_repositories_array text[] NOT NULL DEFAULT '{^.*$}';
UPDATE executors
  SET accepted_repositories_array = COALESCE(
    (SELECT array_agg(CASE WHEN jsonb_typeof(e) = 'string' THEN e #>> '{}' ELSE e->>'url' END)
     FROM jsonb_array_elements(accepted_repositories) AS e), '{}');
ALTER TABLE executors DROP COLUMN accepted_repositories;
ALTER TABLE executors RENAME COLUMN accepted_repositories_array TO accepted_repositories;
