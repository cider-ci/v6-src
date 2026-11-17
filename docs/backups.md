# Database backups

Two independent backups run on the server (Ansible role `backups`, part of
`server.yml`), each a systemd oneshot service with a daily timer, each
keeping `ci_backups_keep_days` (10) days of files under
`/var/local/cider-ci/backups/`:

| | full | config |
|---|---|---|
| Service / timer | `cider-ci-backup-full` | `cider-ci-backup-config` |
| Files | `full/full_<host>_<utc>.pgbin`, `full/latest.pgbin` | `config/config_<host>_<utc>.sql.gz`, `config/latest.sql.gz` |
| Content | the whole database, `pg_dump` custom format | the configuration tables as plain SQL inserts |
| Restore | `pg_restore` | `psql` into an empty, migrated database |
| Run by hand | `systemctl start cider-ci-backup-full` | `systemctl start cider-ci-backup-config` |

The config backup holds what one needs to rebuild a server from nothing:
`settings`, `repositories` (the projects with their triggers, webhook and
GitHub tokens), `users` with `passwords`, `email_addresses`, `gpg_keys` and
`user_images`, `executors` (traits, accepted repositories and GPG keys,
token hashes), and `migrations` so the schema version the data belongs to
is known. Not included: sessions, and everything git- or job-related
(commits, jobs, trials, attachments); the full backup has those. Both files
contain secrets (GitHub tokens, password and token hashes).

## On every deploy

`bin/server` runs the config backup and fetches it as
`deploy/inventories/zhdk/backups/latest-config_<host>.sql.gz`. That
directory is gitignored. To keep the current one in git, encrypt it to the
team's GPG keys (`files/gpg/*.asc`):

    deploy/inventories/zhdk/bin/backup-keep
    git add deploy/inventories/zhdk/backups/config_<host>_<utc>.sql.gz.gpg

Read one with `gpg -d <file>.gpg | gunzip | less`.

## Restoring

Full, into an existing database (drops and recreates its contents):

    pg_restore --clean --if-exists --no-owner --no-privileges -d cider_ci_v6 full_<host>_<utc>.pgbin

Config, into a fresh server: deploy it (the migrations create the schema and
the initial settings row), stop `cider-ci-server`, then

    psql -d cider_ci_v6 -c "TRUNCATE migrations, settings, repositories, users, passwords, email_addresses, gpg_keys, user_images, executors CASCADE"
    gunzip -c config_<host>_<utc>.sql.gz | psql -d cider_ci_v6 -v ON_ERROR_STOP=1 -1

The file sets `session_replication_role = replica` so the insert order does
not matter. The server then needs its git repositories fetched again, which
it does by itself. Executors keep working: their tokens are hashed in the
`executors` table, the plain tokens live only in the executors' env files.
The `migrations` rows must match the deployed server; restore a config
backup only into the schema version it was taken from, or run the
migrations after a restore from an older version.
