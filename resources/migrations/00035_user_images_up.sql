-- The avatar of a user: imported from GitHub at each sign-in with GitHub.
-- Users without an image get a generated identicon (see
-- cider-ci.utils.identicon), nothing is stored for them.

CREATE TABLE user_images (
  user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  content_type text NOT NULL CHECK (content_type ~ '^image/'),
  data bytea NOT NULL,
  source text CONSTRAINT user_images_source_check CHECK (source IN ('github')),
  github_avatar_url text,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL
);
