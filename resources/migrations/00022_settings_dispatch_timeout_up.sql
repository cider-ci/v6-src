ALTER TABLE settings
  ADD COLUMN trial_dispatch_timeout interval NOT NULL DEFAULT '30 minutes';
