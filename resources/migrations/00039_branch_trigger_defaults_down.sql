UPDATE repositories SET branch_trigger_exclude_match = '' WHERE branch_trigger_exclude_match IS NULL;
ALTER TABLE repositories ALTER COLUMN branch_trigger_exclude_match SET NOT NULL;
ALTER TABLE repositories ALTER COLUMN branch_trigger_exclude_match SET DEFAULT '';
ALTER TABLE repositories ALTER COLUMN branch_trigger_include_match SET DEFAULT '^.*$';
