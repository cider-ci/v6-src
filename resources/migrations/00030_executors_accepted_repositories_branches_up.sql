-- Accepted repositories become entries {"url": pattern, "branches": [pattern]}
-- ("branches" optional): with branches only trials whose commit is the head
-- of a matching branch are dispatched (deploy executors).
ALTER TABLE executors ADD COLUMN accepted_repositories_jsonb jsonb NOT NULL
  DEFAULT '[{"url": "^.*$"}]';
UPDATE executors
  SET accepted_repositories_jsonb = COALESCE(
    (SELECT jsonb_agg(jsonb_build_object('url', u))
     FROM unnest(accepted_repositories) AS u), '[]'::jsonb);
ALTER TABLE executors DROP COLUMN accepted_repositories;
ALTER TABLE executors RENAME COLUMN accepted_repositories_jsonb TO accepted_repositories;

-- exact value, or a regex when the pattern is written ^...$; an invalid regex
-- matches nothing instead of failing the dispatch query
CREATE OR REPLACE FUNCTION cider_ci_pattern_matches(s text, pattern text)
RETURNS boolean AS $$
BEGIN
  IF s = pattern THEN
    RETURN true;
  END IF;
  IF pattern LIKE '^%$' THEN
    BEGIN
      RETURN s ~ pattern;
    EXCEPTION WHEN invalid_regular_expression THEN
      RETURN false;
    END;
  END IF;
  RETURN false;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION cider_ci_trial_accepted(
  p_repository_id text, p_url text, p_commit_id text, p_entries jsonb)
RETURNS boolean AS $$
DECLARE
  entry          jsonb;
  entry_branches jsonb;
BEGIN
  FOR entry IN SELECT * FROM jsonb_array_elements(p_entries) LOOP
    IF jsonb_typeof(entry) = 'string' THEN
      entry := jsonb_build_object('url', entry #>> '{}');
    END IF;
    CONTINUE WHEN NOT cider_ci_pattern_matches(p_url, entry->>'url');
    entry_branches := entry->'branches';
    IF entry_branches IS NULL OR jsonb_typeof(entry_branches) = 'null' THEN
      RETURN true;
    END IF;
    IF jsonb_typeof(entry_branches) = 'string' THEN
      entry_branches := jsonb_build_array(entry_branches);
    END IF;
    IF EXISTS (SELECT 1
               FROM branches b, jsonb_array_elements_text(entry_branches) AS bp(pattern)
               WHERE b.repository_id = p_repository_id
                 AND b.current_commit_id = p_commit_id
                 AND cider_ci_pattern_matches(b.name, bp.pattern)) THEN
      RETURN true;
    END IF;
  END LOOP;
  RETURN false;
END;
$$ LANGUAGE plpgsql STABLE;
