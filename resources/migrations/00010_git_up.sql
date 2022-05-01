

CREATE TABLE repositories (
    id uuid DEFAULT uuid_generate_v4() NOT NULL,
    git_url text NOT NULL,
    name character varying,
    public_view_permission boolean DEFAULT false,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    update_notification_token uuid DEFAULT uuid_generate_v4(),
    proxy_id uuid DEFAULT uuid_generate_v4() NOT NULL,
    branch_trigger_include_match text DEFAULT '^.*$'::text NOT NULL,
    branch_trigger_exclude_match text DEFAULT ''::text NOT NULL,
    remote_api_endpoint character varying,
    remote_api_token character varying,
    remote_api_namespace character varying,
    remote_api_name character varying,
    remote_api_type text,
    remote_fetch_interval text DEFAULT '1 Minute'::text NOT NULL,
    remote_api_token_bearer character varying,
    send_status_notifications boolean DEFAULT true NOT NULL,
    manage_remote_push_hooks boolean DEFAULT false NOT NULL,
    branch_trigger_max_commit_age text DEFAULT '12 hours'::text,
    cron_trigger_enabled boolean DEFAULT false,
    all_executors_permitted boolean DEFAULT true NOT NULL,
    all_users_permitted boolean DEFAULT true NOT NULL,
    CONSTRAINT branch_trigger_max_commit_age_not_blank CHECK ((branch_trigger_max_commit_age !~ '^\s*$'::text)),
    CONSTRAINT check_valid_remote_api_type CHECK ((remote_api_type = ANY (ARRAY['github'::text, 'gitlab'::text, 'bitbucket'::text]))),
    CONSTRAINT foreign_api_authtoken_not_empty CHECK (((remote_api_token)::text <> ''::text)),
    CONSTRAINT foreign_api_endpoint_not_empty CHECK (((remote_api_endpoint)::text <> ''::text)),
    CONSTRAINT foreign_api_owner_not_empty CHECK (((remote_api_namespace)::text <> ''::text)),
    CONSTRAINT foreign_api_repo_not_empty CHECK (((remote_api_name)::text <> ''::text)),
    CONSTRAINT foreign_api_token_bearer_not_empty CHECK (((remote_api_token_bearer)::text <> ''::text))
);


-------------------------------------------------------------------------------

CREATE TABLE repository_events (
    id uuid DEFAULT uuid_generate_v4() NOT NULL,
    repository_id uuid,
    event text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY repository_events
    ADD CONSTRAINT repository_events_pkey PRIMARY KEY (id);

CREATE INDEX index_repository_events_on_repository_id
  ON repository_events USING btree (repository_id);


-------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION clean_repository_events() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  DELETE FROM repository_events
    WHERE created_at < NOW() - INTERVAL '3 days';
  RETURN NULL;
END;
$$;

CREATE TRIGGER clean_repository_events AFTER INSERT
  ON repository_events FOR EACH STATEMENT
  EXECUTE PROCEDURE clean_repository_events();

-------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION repository_event() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  CASE
    WHEN TG_OP = 'DELETE' THEN
      INSERT INTO repository_events
        (repository_id, event) VALUES (OLD.id, TG_OP);
    WHEN TG_OP = 'TRUNCATE' THEN
      INSERT INTO repository_events (event) VALUES (TG_OP);
    ELSE
      INSERT INTO repository_events
        (repository_id, event) VALUES (NEW.id, TG_OP);
  END CASE;
  RETURN NULL;
END;
$$;


CREATE TRIGGER repository_event AFTER INSERT OR DELETE OR UPDATE
  ON repositories FOR EACH ROW
  EXECUTE PROCEDURE repository_event();

CREATE TRIGGER repository_event_truncate AFTER TRUNCATE
  ON repositories FOR EACH STATEMENT
  EXECUTE PROCEDURE repository_event();


-------------------------------------------------------------------------------
--- branches ------------------------------------------------------------------
-------------------------------------------------------------------------------

CREATE TABLE branches (
    id uuid DEFAULT uuid_generate_v4() NOT NULL,
    repository_id uuid NOT NULL,
    name character varying NOT NULL,
    current_commit_id character varying(40) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY branches ADD CONSTRAINT branches_pkey PRIMARY KEY (id);

CREATE INDEX branches_lower_name_idx ON branches USING btree (lower((name)::text));

CREATE UNIQUE INDEX index_branches_on_repository_id_and_name ON branches USING btree (repository_id, name);



--- branch_update_events ------------------------------------------------------

CREATE TABLE branch_update_events (
    id uuid DEFAULT uuid_generate_v4() NOT NULL,
    branch_id uuid NOT NULL,
    tree_id character varying(40) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY branch_update_events
    ADD CONSTRAINT branch_update_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY branch_update_events
    ADD CONSTRAINT branch_update_events_branches FOREIGN KEY (branch_id) REFERENCES branches(id) ON DELETE CASCADE;

CREATE INDEX index_branch_update_events_on_created_at ON branch_update_events USING btree (created_at);


CREATE FUNCTION clean_branch_update_events() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  DELETE FROM branch_update_events
    WHERE created_at < NOW() - INTERVAL '3 days';
  RETURN NULL;
END;
$$;

CREATE TRIGGER clean_branch_update_events AFTER INSERT ON branch_update_events FOR EACH STATEMENT EXECUTE PROCEDURE clean_branch_update_events();


-------------------------------------------------------------------------------
--- commits -------------------------------------------------------------------
-------------------------------------------------------------------------------

CREATE TABLE commits (
    id character varying(40) NOT NULL,
    tree_id character varying(40),
    depth integer,
    author_name character varying,
    author_email character varying,
    author_date timestamp with time zone,
    committer_name character varying,
    committer_email character varying,
    committer_date timestamp with time zone,
    subject text,
    body text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY commits ADD CONSTRAINT commits_pkey PRIMARY KEY (id);


CREATE INDEX commits_created_at ON commits USING btree (created_at);

CREATE INDEX commits_author_date_idx ON commits USING btree (author_date);

CREATE INDEX commits_committer_date_idx ON commits USING btree (committer_date);

CREATE INDEX commits_depth_idx ON commits USING btree (depth);

CREATE INDEX commits_tree_id_idx ON commits USING btree (tree_id);

CREATE INDEX commits_body_idx ON commits USING gin (to_tsvector('english'::regconfig, body));

CREATE INDEX commits_author_name_idx ON commits USING gin (to_tsvector('english'::regconfig, (author_name)::text));

CREATE INDEX commits_author_email_idx ON commits USING gin (to_tsvector('english'::regconfig, (author_email)::text));

CREATE INDEX commits_commiter_nane_idx ON commits USING gin (to_tsvector('english'::regconfig, (committer_name)::text));

CREATE INDEX commits_commiter_email_idx ON commits USING gin (to_tsvector('english'::regconfig, (committer_email)::text));

CREATE INDEX commits_subjects_idx ON commits USING gin (to_tsvector('english'::regconfig, subject));


-------------------------------------------------------------------------------


CREATE TABLE branches_commits (
    branch_id uuid NOT NULL,
    commit_id character varying(40) NOT NULL
);

ALTER TABLE ONLY branches_commits
    ADD CONSTRAINT branches_commits_pkey PRIMARY KEY (commit_id, branch_id);



CREATE TABLE commit_arcs (
    parent_id character varying(40) NOT NULL,
    child_id character varying(40) NOT NULL
);

CREATE INDEX index_commit_arcs_on_child_id_and_parent_id ON commit_arcs USING btree (child_id, parent_id);

CREATE UNIQUE INDEX index_commit_arcs_on_parent_id_and_child_id ON commit_arcs USING btree (parent_id, child_id);

ALTER TABLE ONLY commit_arcs
    ADD CONSTRAINT commit_arc_commit_fkey FOREIGN KEY (parent_id) REFERENCES commits(id) ON DELETE CASCADE;




CREATE FUNCTION fast_forward_ancestors_to_be_added_to_branches_commits(branch_id uuid, commit_id character varying) RETURNS TABLE(branch_id uuid, commit_id character varying)
    LANGUAGE sql
    AS $_$
        WITH RECURSIVE arcs(parent_id,child_id) AS
          (SELECT $2::varchar, NULL::varchar
            UNION
           SELECT commit_arcs.* FROM commit_arcs, arcs
            WHERE arcs.parent_id = commit_arcs.child_id
            AND NOT EXISTS (SELECT 1 FROM branches_commits WHERE commit_id = arcs.parent_id AND branch_id = $1)
          )
        SELECT DISTINCT $1, parent_id FROM arcs
        WHERE NOT EXISTS (SELECT * FROM branches_commits WHERE commit_id = parent_id AND branch_id = $1)
      $_$;



CREATE FUNCTION add_fast_forward_ancestors_to_branches_commits(branch_id uuid, commit_id character varying) RETURNS void
    LANGUAGE sql
    AS $$
      INSERT INTO branches_commits (branch_id,commit_id)
        SELECT * FROM fast_forward_ancestors_to_be_added_to_branches_commits(branch_id,commit_id)
      $$;


CREATE FUNCTION update_branches_commits(branch_id uuid, new_commit_id character varying, old_commit_id character varying) RETURNS character varying
    LANGUAGE plpgsql
    AS $_$
      BEGIN
        CASE
        WHEN (branch_id IS NULL) THEN
          RAISE 'branch_id may not be null';
        WHEN NOT EXISTS (SELECT * FROM branches WHERE id = branch_id) THEN
          RAISE 'branch_id must refer to an existing branch';
        WHEN new_commit_id IS NULL THEN
          RAISE 'new_commit_id may not be null';
        WHEN NOT EXISTS (SELECT * FROM commits WHERE id = new_commit_id) THEN
          RAISE 'new_commit_id must refer to an existing commit';
        WHEN old_commit_id IS NULL THEN
          -- entirely new branch (nothing should be in branches_commits)
          -- or request a complete reset by setting old_commit_id to NULL
          DELETE FROM branches_commits WHERE branches_commits.branch_id = $1;
        WHEN NOT is_ancestor(new_commit_id,old_commit_id) THEN
          -- this is the hard non fast forward case
          -- remove all ancestors of old_commit_id which are not ancestors of new_commit_id
          DELETE FROM branches_commits
            WHERE branches_commits.branch_id = $1
            AND branches_commits.commit_id IN ( SELECT * FROM with_ancestors(old_commit_id)
                                EXCEPT SELECT * from with_ancestors(new_commit_id) );
        ELSE
          -- this is the fast forward case; see last statement
        END CASE;
        -- whats left is adding as if we are in the fast forward case
        PERFORM add_fast_forward_ancestors_to_branches_commits(branch_id,new_commit_id);
        RETURN 'done';
      END;
      $_$;



