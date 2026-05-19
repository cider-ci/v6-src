# Phase 1 — Git Tracking Surface

Detailed plan for completing the repository / branch / commit / GPG layer in v6, as defined in `CLAUDE.md` ("Porting Roadmap, Phase 1").

> **Working document.** Refine as work progresses. Mark items done inline rather than rewriting.

---

## 1. Goal

Bring the existing repo-sync foundation to an **end-user-usable** state:

- A user can register a project + repository, see it fetched automatically, browse its branches, commits, and individual commit details, and trust the GPG-signed commits where keys are configured.
- An admin can force a fetch, edit a repository, and delete it.

The execution-engine side (jobs/tasks/trials) is **out of scope** here and stays in Phase 2+.

---

## 2. Current state (verified, 2026-05-12)

What is **already built** in v6:

- **Schema** (`resources/migrations/00010_git_up.sql`):
  - `repositories`, `branches`, `commits`, `branches_commits`, `commit_arcs`, `submodules`
  - Event tables: `repository_events`, `branch_update_events`, `tree_id_notifications`
  - Stored procedure `update_branches_commits(branch_id, new_commit_id, old_commit_id)` with force-push handling via `is_ancestor()` (called from `branches.clj`)
  - GPG columns: `commits.signed_message`, `commits.signature` (text)
- **Sync daemons** (`cljc-src/cider_ci/server/projects/repositories/`):
  - `fetch_and_update/scheduler.clj` — 1-sec tick, per-repo `remote_fetch_interval`, thread pool sized by settings
  - `fetch_and_update/fetch.clj` — `git fetch ... --force --tags --prune "+*:*"` on bare repos
  - `branch_updates/*` — branch list extraction + upsert + `branches_commits` maintenance
  - `state/*` — in-memory state atom polled from `repository_events`
- **Commit ingestion** (`git_sql.clj`, `git/commits.clj`): walks `git rev-list --parents`, extracts metadata + GPG signature into DB
- **GPG library** (`utils/git_gpg.clj`): `valid-signature-fingerprint` already verifies a commit against a supplied ASCII pubkey
- **Routes**: `/projects/` (list) and a stub `/commits/`
- **UI**: project list page with fetch + branch-update status; stub commits page
- **Specs**: `spec/features/projects/{crud,gpg}_spec.rb`

What is **missing** for Phase 1:

| Area | Gap |
|---|---|
| GPG | No `commit_signatures` table, no trusted-key store, no verify-on-ingest call, no `signature_valid` projection |
| Routes | No detail endpoints for repositories, branches, commits, submodules |
| UI | No repository detail, branch list/detail, commit detail/history |
| Admin actions | No "force fetch now", "delete repo", "edit repo settings" surface |
| Webhooks | No push webhook receivers (legacy had `web/push`) |
| CLI | No manual `fetch` / `update` subcommands |
| Tests | Only happy-path project CRUD + a GPG spec; no force-push, no branch-delete, no commit-detail coverage |

---

## 3. Work breakdown

Ordered roughly by dependency / value. Each item is sized to a reviewable chunk.

### 3.1 GPG verification — make it real

Currently GPG signatures are *stored* but never *checked*. Decisions needed first (see §6), then:

1. **New table `gpg_keys`** (migration `00011_gpg_up.sql`):
   - `id uuid PK`, `fingerprint text unique`, `name text`, `ascii_key text`, `description text`, `created_at`, `updated_at`
   - `user_id uuid` nullable, FK → `users.id` ON DELETE CASCADE. NULL means "globally trusted" (admin-managed; e.g. release-signing keys).
   - Check constraint: only admins can insert/update rows with `user_id IS NULL` (enforced in handler, not SQL).
2. **New column `commits.signature_fingerprint text`** (nullable) — populated when verification succeeds. NULL means "not verified" (could be no key, no signature, or bad signature; the UI distinguishes by also checking `signature IS NOT NULL`).
3. **Verification wiring**: after a commit row is inserted, if `signature` is non-null:
   - Build the trust set: all global keys (`user_id IS NULL`) + all per-user keys whose owner has an `email_addresses` row matching the commit's `author_email` or `committer_email`.
   - Iterate that set and call `git-gpg/valid-signature-fingerprint`. Store the matching fingerprint, or leave NULL.
4. **User UI**: each user can upload / list / revoke their own GPG keys at `/me/gpg-keys`.
5. **Admin UI**: admins can additionally manage global keys at `/admin/gpg-keys`.
6. **Commit detail**: derive display from two columns:
   - `signature IS NULL` → "unsigned"
   - `signature IS NOT NULL AND signature_fingerprint IS NULL` → "signed, untrusted"
   - `signature_fingerprint IS NOT NULL` → "signed by {key name}"
7. **Spec**: extend `spec/features/projects/gpg_spec.rb` to cover all three states, plus the per-user-vs-global distinction.

### 3.2 Read-side routes

Add Reitit routes in `routes.cljc`, paired handlers under `server/resources/`. Auth: `:public` for browsing public projects, `:user` otherwise (mirrors legacy).

| Route | Handler |
|---|---|
| `GET /projects/:project-id` | project detail (with embedded repository summary) |
| `GET /projects/:project-id/repositories/:repo-id` | repository detail |
| `GET /projects/:project-id/repositories/:repo-id/branches` | branch list |
| `GET /projects/:project-id/repositories/:repo-id/branches/:branch-name` | branch detail + recent commits |
| `GET /projects/:project-id/repositories/:repo-id/commits/:commit-id` | commit detail |
| `GET /projects/:project-id/repositories/:repo-id/commits/:commit-id/blob/*path` | blob content (single file by path) |

The blob endpoint is needed for the SPA to render `cider-ci.yml` and similar; legacy `repository.web/path-content` is the model. **No tree-listing endpoint** in Phase 1 — fetching a single file by known path is sufficient. A full tree browser is Phase 7.

### 3.3 Write-side routes (admin actions)

| Route | Effect |
|---|---|
| `POST /projects/:id/repositories/:repo-id/fetch` | set the repo's "pending" flag to trigger an immediate fetch |
| `PATCH /projects/:id/repositories/:repo-id` | edit repo settings (fetch interval, trigger filters, …) |
| `DELETE /projects/:id/repositories/:repo-id` | cascade-delete; rely on FK `ON DELETE CASCADE` |

Plus GPG key CRUD endpoints under `/me/gpg-keys` (user-scoped) and `/admin/gpg-keys` (global).

**Push webhooks are deferred** out of Phase 1 (see §6).

### 3.4 Frontend pages (Reagent, classic-webapp style)

Per the CLAUDE.md frontend principle: page-style navigation, server data fetched on page load, form-submit semantics for writes. **No live state**, **no optimistic UI**. Use React Bootstrap tables/cards directly.

| Page | Notes |
|---|---|
| Project detail | Already partial — add repository panel(s) with fetch status, last-fetched timestamp, manual "fetch now" button |
| Branch list (per repo) | Table: name, current commit short-id, last commit date, signed indicator |
| Branch detail | Branch metadata + recent commits table |
| Commit detail | Author/committer/dates/subject/body, parents, tree-id, signature status, link to `cider-ci.yml` raw view |
| Blob view (single file by path) | Minimal — fetch one blob by path at a commit, render as `<pre>`. **No directory browser** in Phase 1 (deferred to Phase 7). |
| User: my GPG keys | Per-user list + upload form at `/me/gpg-keys` |
| Admin: global GPG keys | At `/admin/gpg-keys` |
| Admin: repository settings | Edit form for the columns in `repositories` |

Refresh of "fetching now" status: **poll the page on user request**, not auto-refresh. (Add SSE only if it becomes annoying — Phase 8.)

### 3.5 CLI subcommands

Extend `cider-ci.main` and `server/main.clj` with read-only diagnostics:

```
clj -M -m cider-ci.main server repo list
clj -M -m cider-ci.main server repo fetch <repo-id>   # one-shot, blocking
clj -M -m cider-ci.main server repo status <repo-id>
```

Implementation: reuse the same fetch/branch-update functions the daemon uses; just call them synchronously and exit.

### 3.6 Test coverage

Bring rspec coverage of Phase 1 functionality up to a useful baseline:

- `spec/features/projects/repository_fetch_spec.rb` — create repo, wait for first fetch, assert branches appear
- `spec/features/projects/force_push_spec.rb` — push a non-FF update to a fixture repo (under `data/repositories/`), assert `branches_commits` is corrected and orphaned commits stay reachable via other branches but not via the force-pushed one
- `spec/features/projects/branch_delete_spec.rb` — remote branch delete reflected in DB after sync
- `spec/features/projects/commit_detail_spec.rb` — UI flow: project → branch → commit
- Extend `gpg_spec.rb` to upload a key and assert signature flag on the relevant commit

Fixture repos go under `data/repositories/` (already used). Each spec should be runnable individually as documented in CLAUDE.md.

---

## 4. Migrations / schema changes

Single new migration in this phase:

- **`00011_gpg_up.sql` / `00011_gpg_down.sql`**:
  - `CREATE TABLE gpg_keys (id uuid PK, fingerprint text UNIQUE NOT NULL, name text, ascii_key text NOT NULL, description text, user_id uuid NULL REFERENCES users(id) ON DELETE CASCADE, created_at, updated_at)` — `user_id IS NULL` ⇒ global trust
  - `ALTER TABLE commits ADD COLUMN signature_fingerprint text`
  - Index on `commits.signature_fingerprint`
  - Index on `gpg_keys.user_id`
  - (`fingerprint` is already unique via the column constraint — no separate index needed)

No changes anticipated to `repositories`, `branches`, `commits` core columns — schema is already complete.

Possible later (NOT in Phase 1, listed for visibility):
- A `commit_signatures` join table if we want multi-key / multi-attestation support. Not needed yet.
- A `webhooks` table if push webhooks become user-configurable.

---

## 5. Daemon / executor architecture decisions

Confirmed direction (consistent with legacy, already implemented in v6):

- **Sync runs inside the server process.** Do NOT split into a separate worker service in Phase 1 — the legacy did this in-process and v6 already follows. Revisit only if a deployment outgrows a single JVM.
- **Per-repository thread-pool concurrency**, bounded by `settings.git_fetch_and_update_max_concurrent` (already in schema). Default keeps as-is.
- **Per-repo locking** via `(locking (str "fetch-and-update-lock_" id) ...)` — already in v6. Keep.
- **Bare repos on disk** at `{repositories-path}/{repository-id}` — already in v6. Path comes from server config; document the env/config knob in CLAUDE.md once verified.
- **Shell `git`**, not JGit, for the fetch itself. JGit imports in `core.clj` are residual and unused for fetching; keep them only if used for read operations (tree walks etc.) — otherwise rip out to avoid confusion.

GPG verification: also in-process, synchronous on ingest. If it ever becomes a bottleneck (large historical re-ingest), wrap the verification call in the same thread pool as ingestion — not a separate one.

Webhook receiver (when added): plain HTTP endpoint in the server process, sets the same "pending" flag the manual fetch button sets. No queue.

---

## 6. Decisions (resolved 2026-05-12)

1. **GPG key scoping** → **per-user + global admin fallback.** Single `gpg_keys` table with nullable `user_id`. A commit is verified against the union of (a) all global keys and (b) keys belonging to users whose `email_addresses` match the commit's author or committer email. Per-repository allowlists are not introduced.

2. **Push webhooks** → **deferred** out of Phase 1. The 1-sec poll loop gives near-real-time behaviour at typical fetch intervals. Revisit when polling latency becomes a user complaint.

3. **Submodule recursion** → **verify-only in Phase 1.** Confirm the existing `submodules` table gets populated by current ingestion code; add a single spec. Defer submodule *checkout* to Phase 5 (Executor).

4. **Tree / blob endpoints** → **minimal.** Only the single-file-by-path blob endpoint. No tree-listing route. Full file browser is Phase 7.

5. **Signature state encoding** → **two-column, two-state.** Use `commits.signature` (already present) plus the new `commits.signature_fingerprint`. UI derives the three display states (unsigned / signed-untrusted / signed-trusted) at render time. No additional boolean.

---

## 7. Out of scope (deferred)

To prevent scope creep, the following stay explicitly out of Phase 1:

- Jobs / tasks / trials — Phase 2+
- Executor service — Phase 5
- Attachments — Phase 6
- Push webhooks — defer (see §6)
- Live UI updates (SSE / WebSocket) — Phase 8
- Full file browser — Phase 7
- Multi-tenancy on repositories / fine-grained permission UI — TBD
