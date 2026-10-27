# Executors: accepted repositories, branches and jobs

An executor can restrict which trials it runs. This is used to keep
untrusted code off an executor and, most of all, for dedicated **deploy
executors** that deploy projects to test, staging or production
environments.

## Configuration

The executor reads a YAML list from `/etc/cider-ci/accepted-repositories.yml`
(or from the env variable `CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES`). With
Ansible it is set by `ci_executor_accepted_repositories`
(`deploy/roles/executor`).

```yaml
# a plain string is {url: <string>}
- '^https://github\.com/(cider-ci|leihs|Madek)/.+$'

# a deploy executor for staging
- url: 'https://github.com/Madek/Madek.git'
  branches: ['^master$']
  jobs: ['^deploy_staging$']

# a deploy executor for the dev environments
- url: 'https://github.com/Madek/Madek.git'
  jobs: ['^deploy_dev.*$']
```

- An entry is `{url, branches, jobs}`; `branches` and `jobs` are optional
  and may be a single pattern or a list.
- A pattern is an exact value, or a regex when it is written `^...$` (it
  must then match the whole value). An invalid regex matches nothing.
- All given parts of **one entry** must match; a trial is accepted when
  **some entry** matches.
- Without the file every repository is accepted; an empty list, an
  unreadable file or one that is not a list accepts nothing.

The executor reports its list on every sync. The server shows it on the
executor's page and only dispatches matching trials. The executor checks
again before it fetches anything; a trial that does not pass fails.

## The three restrictions and how strong they are

| Part       | Matches                           | Verified on the origin | Strength |
|------------|-----------------------------------|------------------------|----------|
| `url`      | the repository's git url          | yes                    | hard     |
| `branches` | the branch of which the commit is the **head** | yes, backed by the git host's branch protection | hard |
| `jobs`     | the job key in `cider-ci.yml`     | no                     | **soft** |

### `url` (hard)

The executor fetches the code from the CIDER-CI server (git proxy), but it
also checks with `git ls-remote` on the repository's real remote (the
"origin") that the commit is reachable from one of its branches or tags. A
commit that exists only on the CIDER-CI server is refused. The check can be
switched off with `ci_executor_verify_origin: false`
(`CIDER_CI_EXECUTOR_VERIFY_ORIGIN=false`).

### `branches` (hard)

With `branches` only the current **head** of a matching branch is accepted,
on the server (`branches.current_commit_id`) and on the executor (the head
on the origin, asked for every trial). Older commits of the branch and
heads of other branches are refused. Protect the branch on the git host
(e.g. only reviewed merges into `master`); the executor then runs only what
the git host allowed onto that branch, independent of the CIDER-CI server
and of `cider-ci.yml`.

Entries with `branches` need the origin check; with the check switched off
they refuse every trial.

### `jobs` (soft)

`jobs` matches the **job key**, i.e. the key of the job in `cider-ci.yml`
(e.g. `deploy_staging`, `deploy_dev_x_with_data_reset`). Without `jobs` any
job is accepted.

This is **not a security mechanism**:

- Anyone who can push a commit can rename a job or add a job with a
  matching key; nothing on the git host protects a job key.
- The executor learns the job key only from the server, so its own check
  does not help against a compromised or misconfigured server.

Use `jobs` for routing: to keep a deploy executor from picking up test
jobs, or to send `deploy_dev*` and `deploy_staging` to different
executors. On executors that deploy anything that matters, always put
`jobs` on the **same entry** as `url` and `branches`; the branch
restriction is what protects the environment.

The job is matched before the branches are considered. An entry for other
jobs (e.g. `{url: …, jobs: ['^test.*$']}`) therefore does not relax the
branch restriction of a deploy job's entry.

## Related

- The job's `branch` run_when directive in `cider-ci.yml` decides when a job
  is triggered. It is a convenience, not a restriction; use `branches` on the
  executor for that.
- The executor's traits (`ci_executor_traits`) still have to match the
  task's traits, in addition to the accepted repositories.
