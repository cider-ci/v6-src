

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
-------------------------------------------------------------------------------

CREATE TABLE branches (
    id uuid DEFAULT uuid_generate_v4() NOT NULL,
    repository_id uuid NOT NULL,
    name character varying NOT NULL,
    current_commit_id character varying(40) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


CREATE TABLE branches_commits (
    branch_id uuid NOT NULL,
    commit_id character varying(40) NOT NULL
);

