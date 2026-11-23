# Scripts on the executor

Scripts run without a terminal: stdin is `/dev/null`, there is no pty.
Programs that would still ask a question are told not to, through these
environment variables set for every script:

| Variable | Value | Effect |
|---|---|---|
| `DEBIAN_FRONTEND` | `noninteractive` | apt, dpkg and debconf take defaults instead of asking |
| `NEEDRESTART_MODE` | `a` | needrestart restarts services without a dialog |
| `GIT_TERMINAL_PROMPT` | `0` | git fails instead of prompting for credentials |
| `ANSIBLE_SSH_USETTY` | `false` | Ansible does not allocate a tty on its ssh connections (`-tt`), so apt and friends on the target stay non-interactive too |
| `CI` | `true` | |

The task's and the script's own `environment_variables` override them. A
script that really needs them gone sets `interactive: true`:

```yaml
scripts:
  console:
    interactive: true
    body: ...
```

Why: 2026-11-23 a madek deploy hung for an hour on the executor in a
grub-pc debconf dialog on the target host, reached through Ansible's
`ssh -tt … apt install -y python3`. The server timed the trial out, but
the executor kept the processes alive. Now a trial the server has ended
(timeout, abort) is final: the executor's later reports are refused with
409 and the next sync tells it to kill the trial's whole process tree.
