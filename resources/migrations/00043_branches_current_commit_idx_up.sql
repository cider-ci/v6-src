-- The dispatcher orders pending trials by the distance of their commit to
-- the nearest branch head; the lookup "is this commit a head" per candidate
-- wants an index.
CREATE INDEX branches_current_commit_id_idx ON branches (current_commit_id);
