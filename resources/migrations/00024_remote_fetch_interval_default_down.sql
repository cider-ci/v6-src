ALTER TABLE repositories
  ALTER COLUMN remote_fetch_interval SET DEFAULT '1 Minute';

UPDATE repositories
  SET remote_fetch_interval = '1 Minute'
  WHERE remote_fetch_interval = '1 Hour';
