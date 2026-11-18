-- Repository branch trigger filter, legacy defaults:
--   include ^.+$   every branch is auto-triggered (pushes, cron, dependencies)
--   exclude NO_CI  a branch whose name contains NO_CI is pushed but not tested;
--                  overrides the include; NULL disables the check,
--                  ^.*$ auto-triggers no branch at all.
-- Jobs can still be started by hand on any branch.
ALTER TABLE repositories ALTER COLUMN branch_trigger_include_match SET DEFAULT '^.+$';
ALTER TABLE repositories ALTER COLUMN branch_trigger_exclude_match DROP NOT NULL;
ALTER TABLE repositories ALTER COLUMN branch_trigger_exclude_match SET DEFAULT 'NO_CI';
UPDATE repositories SET branch_trigger_include_match = '^.+$' WHERE branch_trigger_include_match = '^.*$';
UPDATE repositories SET branch_trigger_exclude_match = 'NO_CI' WHERE branch_trigger_exclude_match = '';
