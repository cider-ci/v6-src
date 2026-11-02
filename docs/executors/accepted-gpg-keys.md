# Executors: accepted GPG keys

An executor can be restricted to commits that are **signed with one of a
given set of GPG keys**. Like the accepted repositories, this keeps
untrusted code off an executor, with a different trust anchor: not where
the commit comes from, but who signed it.

## Configuration

The executor reads a YAML list of ASCII armored public keys from
`/etc/cider-ci/accepted-gpg-keys.yml` (or from the env variable
`CIDER_CI_EXECUTOR_ACCEPTED_GPG_KEYS`, YAML or JSON). With Ansible it is set
by `ci_executor_accepted_gpg_keys` (`deploy/roles/executor`):

```yaml
ci_executor_accepted_gpg_keys:
  - "{{ lookup('file', 'files/gpg/release-manager.asc') }}"
  - |
    -----BEGIN PGP PUBLIC KEY BLOCK-----
    ...
    -----END PGP PUBLIC KEY BLOCK-----
```

- An **empty list** (or no file) accepts any commit, signed or not.
- With keys, a commit must be signed with one of them: the primary key or
  one of its subkeys.
- A key that cannot be parsed, or a file that is not a list, accepts
  nothing (fail closed) and is shown on the executor's page.
- The file is re-read on every sync; no restart is needed.

## How it is enforced

**On the server.** The executor reports the fingerprints of its keys on
every sync. The server only dispatches a trial to the executor when the
job's commit was **verified** against one of them (`commits.signature_fingerprint`).

The server verifies a commit signature with the keys it knows: the keys
of the user whose email address is the commit's author or committer
(added by hand or imported from GitHub, see
[github-sign-in.md](../github-sign-in.md)) and global keys. **A key given to
an executor must therefore also be known to the server**, otherwise no
commit is ever verified against it and nothing is dispatched. The
executor's page marks such keys as "unknown to the server".

**On the executor.** Before templates are rendered or any script runs, the
executor checks the signature of the checked out commit itself, with its
own keys as the only trust anchor. A commit that is not signed with an
accepted key makes the trial **fail** with an error, so a misconfigured or
compromised server cannot make the executor run code nobody with an
accepted key signed.

## Why a job is not dispatched

The job and task pages show the commit's signature (unsigned, signed with
an untrusted key, or signed by which key) and a "Match executors" button
that lists the executors able to run it, like the traits. An unsigned or
untrusted commit is only run by executors without accepted keys.

On the executors list the "GPG keys" column shows "any" or the number of
keys; the executor's page lists the keys with their owner.

## Caveats

- Only **GPG** signatures are covered; commits signed with SSH keys count
  as unsigned.
- Verifying the signature is not the same as trusting the commit: the
  signer must be trusted to only sign what may run on the executor. Combine
  the keys with the accepted repositories and branches of a deploy
  executor as needed.
- The restriction applies to the job's commit only. Submodules are not
  checked.

## Related

- [accepted-repositories.md](accepted-repositories.md): restricting an
  executor by repository, branch and job.
- The executor's traits still have to match the task's traits.
