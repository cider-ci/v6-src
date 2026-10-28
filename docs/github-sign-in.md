# Sign in with GitHub

Users can sign in with their GitHub account. Who may sign in, and who becomes
an admin, is decided by membership in GitHub organizations and teams.
Password sign-in remains available.

There is no sync with GitHub. Every sign-in with GitHub refreshes the account
instead. Since a session lasts 7 days, that happens about once a week:

- the name and the GitHub login;
- the admin flag, from the matching rule;
- the verified email addresses;
- the public GPG keys.

The GitHub token is used during the sign-in only and is not stored.

## Setup

1. **Register an OAuth App** on GitHub, preferably owned by the organization
   (*Organization settings → Developer settings → OAuth Apps*):
   - Homepage URL: the server, e.g. `https://ci.example.org`
   - Authorization callback URL: `https://ci.example.org/sign-in/github/callback`

   Organizations with *OAuth app access restrictions* (the default for new
   organizations) must approve the app. Otherwise their memberships are
   invisible and their members are refused. An app owned by an organization is
   approved for that organization automatically.
2. **Configure the server** in `/etc/cider-ci/github-sign-in.yml`. Ansible
   writes it from `ci_server_github_sign_in`; see
   `deploy/roles/server/defaults/main.yml`.

   ```yaml
   client_id: Ov23li...
   client_secret: ...            # keep it in secrets.yml
   rules:                        # ordered, the first match wins
     - {organization: example-org, team: ci-admins, admin: true}
     - {organization: example-org}
     - {organization: other-org}
   # optional:
   # api_endpoint: https://github.example.org/api/v3        # GitHub Enterprise
   # oauth_base_url: https://github.example.org/login/oauth # GitHub Enterprise
   # callback_base_url: https://ci.example.org  # default: External base URL
   ```

   - The server re-reads the file when it changes; no restart is needed.
   - The path is set with `--github-sign-in-config` or `GITHUB_SIGN_IN_CONFIG`.
   - Without the file, or without client id, secret or rules, the sign-in page
     shows no GitHub button.
   - The redirect URL sent to GitHub is the *External base URL* (admin
     settings) plus `/sign-in/github/callback`. It must match the callback URL
     of the OAuth App.

## Rules

- A rule matches an **active** member of the organization. A pending
  invitation does not count, and private membership does.
- With `team`, the user must also be a member of that team of the
  organization. The team is given by slug or by display name.
- Organization and team names are case-insensitive.
- The **first** matching rule wins. `admin: true` makes the account an admin,
  any other rule a regular user.
- No matching rule means the sign-in is refused.

The admin flag is set at every sign-in with GitHub. Someone who left the admin
team loses admin rights at their next sign-in. An account made admin by hand
is reset by its next sign-in with GitHub as well.

Removal from an organization takes effect at the next sign-in, at most
7 days later. Running sessions are not revoked; an admin can delete the
account to end them.

## Accounts

- **Matching:** accounts are matched by the GitHub user id. Renaming the
  GitHub account is harmless.
- **Separate accounts:** an existing password account is not linked
  automatically. Its owner gets a second account.
- **Login:** GitHub accounts have no CIDER-CI login (and no password). The
  GitHub login is shown instead.
- **Email addresses:** the **verified** addresses are imported, the primary
  one first.
  - An address that belongs to another account is skipped and logged.
  - Imported addresses that GitHub no longer reports are removed at the next
    sign-in.
  - Addresses added by hand are kept.
- **GPG keys:** the public GPG keys of the GitHub account are imported, from
  `/users/<login>/gpg_keys`.
  - Signed commits by the account's email addresses become verified, including
    commits already fetched.
  - Keys removed on GitHub are removed at the next sign-in, and so are the
    verifications they provided.
  - A fingerprint that is already configured (by hand or for another account)
    is skipped.
  - If GitHub cannot be reached for the keys, the sign-in still succeeds and
    the imported keys are kept.
- **Badges:** imported addresses and keys carry a "GitHub" badge.
- **SSH keys:** commits signed with SSH keys are not verified; only GPG
  signatures are.

The OAuth scopes are `read:org`, for the memberships, and `user:email`.
