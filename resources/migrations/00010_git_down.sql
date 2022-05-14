DROP FUNCTION IF EXISTS add_fast_forward_ancestors_to_branches_commits(branch_id uuid, commit_id character varying) CASCADE;
DROP FUNCTION IF EXISTS clean_branch_update_events() CASCADE;
DROP FUNCTION IF EXISTS clean_repository_events() CASCADE;
DROP FUNCTION IF EXISTS fast_forward_ancestors_to_be_added_to_branches_commits(branch_id uuid, commit_id character varying) CASCADE;
DROP FUNCTION IF EXISTS repository_event() CASCADE;
DROP FUNCTION IF EXISTS update_branches_commits(branch_id uuid, new_commit_id character varying, old_commit_id character varying) CASCADE;

DROP TABLE IF EXISTS branch_update_events CASCADE;
DROP TABLE IF EXISTS branches_commits CASCADE;
DROP TABLE IF EXISTS commit_arcs CASCADE;
DROP TABLE IF EXISTS commits CASCADE;
DROP TABLE IF EXISTS branches CASCADE;
DROP TABLE IF EXISTS repository_events CASCADE;
DROP TABLE IF EXISTS repositories CASCADE;
DROP TABLE IF EXISTS submodules CASCADE;
