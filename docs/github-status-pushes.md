# GitHub commit status pushes

CIDER-CI posts the state of every job as a
[commit status](https://docs.github.com/en/rest/commits/statuses) to the
GitHub repository of the project. Pull requests and branches then show the CI
result with a link back to the job, and GitHub branch protection can require
it ("required status checks").

## Configuration (per project)

Edit the project (admins), section "GitHub commit statuses":

- **Push job states as commit statuses** (`send_status_notifications`, on by
  default).
- **Token**: a GitHub token with write access to the commit statuses of the
  repository (fine-grained token: "Commit statuses: Read and write"). The
  token is write-only: it is never shown again or returned by the API; leave
  the field blank to keep it, or tick "Remove the token". **Without a token
  nothing is pushed.** There is deliberately no server-wide token: whether and
  with which token a project pushes is decided by the project alone.
- **API endpoint**: only for GitHub Enterprise (e.g.
  `https://github.example.org/api/v3`); for `github.com` URLs
  `https://api.github.com` is used.
- **Owner / Repository**: derived from the Git URL
  (`https://github.com/<owner>/<repo>.git`, `git@github.com:<owner>/<repo>.git`
  and `ssh://` URLs); set them only if they differ.

The project page shows where the statuses go (or why nothing is pushed), the
number of posts and failures of the last 24 hours and the last error.

## What is posted

For each job and state change (pending included), to the job's commit:

| Job state                         | GitHub state |
|-----------------------------------|--------------|
| pending, executing                | `pending`    |
| passed                            | `success`    |
| failed                            | `failure`    |
| aborting, aborted, defective      | `error`      |

- **context**: `Cider-CI@<host> - <job name>`; `<host>` is the host of the
  "External base URL" in the admin settings. Because the context names the
  server, the statuses of several CIDER-CI instances (e.g. the legacy CI) can
  be posted to the same commit side by side.
- **description**: `<job state> - <job name>` (at most 140 characters).
- **target_url**: the job page,
  `<external base URL>/projects/<project>/commits/<commit>/jobs/<job>`; the
  "External base URL" setting must therefore be the public URL of the server.

Only the current state is posted: a job that passes quickly may go straight to
`success` without a `pending` before.

## Delivery

- The server polls every 2 seconds for jobs updated within the last 24 hours
  whose state has not been posted yet (`job_status_pushes`).
- A failed post (network error, GitHub error such as 401 or 404) is shown on
  the job and project page and retried after 10 seconds, doubling up to one
  hour, as long as the job was updated within the last 24 hours.
- Changing the project's push settings retries failed posts immediately.
- **Push statuses now** on the project page posts the states of the jobs of
  the last 24 hours again (e.g. after the statuses were deleted on GitHub).

Differences to the legacy CI: statuses are posted to the job's commit only
(no fan-out to other commits with the same tree), failed posts are retried,
and `send_status_notifications` is honoured.
