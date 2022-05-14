DROP FUNCTION IF EXISTS clean_branch_update_events();
DROP FUNCTION IF EXISTS clean_repository_events() CASCADE;
DROP FUNCTION IF EXISTS repository_event() CASCADE;

DROP TABLE branch_update_events;
DROP TABLE branches_commits;
-- DROP TABLE commit_arcs;
-- DROP TABLE commits;
DROP TABLE branches;
DROP TABLE repository_events CASCADE;
DROP TABLE repositories;
