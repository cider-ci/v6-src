-- Repositories an executor accepts trials for, reported by the executor on
-- every sync (legacy parity). An entry is an exact git url or, when it starts
-- with ^ and ends with $, a regex. The default accepts everything, so
-- executors that do not report the list keep getting trials.
ALTER TABLE executors
  ADD COLUMN accepted_repositories text[] NOT NULL DEFAULT '{^.*$}';

-- An invalid regex entry counts as no match instead of failing the dispatch
-- query (the entries come from executor config files).
CREATE OR REPLACE FUNCTION cider_ci_repository_accepted(url text, entries text[])
RETURNS boolean AS $$
DECLARE
  entry text;
BEGIN
  IF url = ANY(entries) THEN
    RETURN true;
  END IF;
  FOREACH entry IN ARRAY entries LOOP
    IF entry LIKE '^%$' THEN
      BEGIN
        IF url ~ entry THEN
          RETURN true;
        END IF;
      EXCEPTION WHEN invalid_regular_expression THEN
        NULL;
      END;
    END IF;
  END LOOP;
  RETURN false;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
