-- commits.depth is the generation number: 0 for a root commit, else 1 + the
-- largest depth of the parents (for a linear history: the number of commits
-- since the root). The commits page shows head.depth - commit.depth as
-- "n commits behind the head".
--
-- The server's incremental update reset all existing depths to 0 at every
-- import, so the stored values are wrong: recompute them all. Breadth first
-- over a frontier: a commit gets its depth in the round its last parent got
-- one, so each arc is looked at once; cheap even for long histories.

CREATE OR REPLACE FUNCTION recompute_commit_depths() RETURNS integer AS $$
DECLARE
  rounds integer := 0;
  n integer;
BEGIN
  UPDATE commits SET depth = NULL WHERE depth IS NOT NULL;

  -- two fixed temp tables; dropping and renaming them inside the loop
  -- confused plpgsql's cached plans
  CREATE TEMP TABLE frontier (id character varying(40) PRIMARY KEY) ON COMMIT DROP;
  CREATE TEMP TABLE next_frontier (id character varying(40) PRIMARY KEY, depth integer) ON COMMIT DROP;
  INSERT INTO frontier
    SELECT c.id FROM commits c
    WHERE NOT EXISTS (SELECT 1 FROM commit_arcs a WHERE a.child_id = c.id);
  UPDATE commits SET depth = 0 WHERE id IN (SELECT id FROM frontier);

  LOOP
    -- children of the frontier whose parents all have a depth now
    TRUNCATE next_frontier;
    INSERT INTO next_frontier
      SELECT a.child_id, max(parents.depth) + 1
      FROM commit_arcs a
      JOIN commits parents ON parents.id = a.parent_id
      WHERE a.child_id IN (SELECT ca.child_id FROM commit_arcs ca JOIN frontier f ON f.id = ca.parent_id)
        AND EXISTS (SELECT 1 FROM commits ch WHERE ch.id = a.child_id AND ch.depth IS NULL)
      GROUP BY a.child_id
      HAVING bool_and(parents.depth IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    EXIT WHEN n = 0;
    rounds := rounds + 1;
    UPDATE commits c SET depth = nf.depth FROM next_frontier nf WHERE c.id = nf.id;
    TRUNCATE frontier;
    INSERT INTO frontier SELECT id FROM next_frontier;
  END LOOP;
  DROP TABLE frontier;
  DROP TABLE next_frontier;
  RETURN rounds;
END;
$$ LANGUAGE plpgsql;

SELECT recompute_commit_depths();
