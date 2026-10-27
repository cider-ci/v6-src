-- Accepted repository entries may restrict job keys: {"url": .., "branches":
-- [..], "jobs": [pattern]}; all given parts of one entry must match.
-- NOTE: "jobs" is a soft restriction (routing). Unlike url and branches it is
-- not verified on the origin: renaming a job in cider-ci.yml circumvents it.
DROP FUNCTION IF EXISTS cider_ci_trial_accepted(text, text, text, jsonb);

CREATE OR REPLACE FUNCTION cider_ci_trial_accepted(
  p_repository_id text, p_url text, p_commit_id text, p_job_key text, p_entries jsonb)
RETURNS boolean AS $$
DECLARE
  entry          jsonb;
  entry_branches jsonb;
  entry_jobs     jsonb;
BEGIN
  FOR entry IN SELECT * FROM jsonb_array_elements(p_entries) LOOP
    IF jsonb_typeof(entry) = 'string' THEN
      entry := jsonb_build_object('url', entry #>> '{}');
    END IF;
    CONTINUE WHEN NOT cider_ci_pattern_matches(p_url, entry->>'url');
    -- the job is checked before the branches: an entry for other jobs must
    -- not accept a commit the deploy job's entry restricts to branch heads
    entry_jobs := entry->'jobs';
    IF entry_jobs IS NOT NULL AND jsonb_typeof(entry_jobs) <> 'null' THEN
      IF jsonb_typeof(entry_jobs) = 'string' THEN
        entry_jobs := jsonb_build_array(entry_jobs);
      END IF;
      CONTINUE WHEN NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(entry_jobs) AS jp(pattern)
        WHERE cider_ci_pattern_matches(p_job_key, jp.pattern));
    END IF;
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
