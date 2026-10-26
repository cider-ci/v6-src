DROP FUNCTION IF EXISTS cider_ci_repository_accepted(text, text[]);
ALTER TABLE executors DROP COLUMN IF EXISTS accepted_repositories;
