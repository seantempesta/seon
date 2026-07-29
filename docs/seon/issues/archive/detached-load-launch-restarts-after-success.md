---
type: issue
status: superseded
severity: friction
tags: [issue, runtime]
---

# Prevent a detached load drive from restarting after success

## Problem

The one-shot load drive launched with `launchctl submit` restarted immediately
after its successful exit. The second process reused the same run id, deleted
the scratch runtime root, and appended a second segment to the provider timing
log before manual removal.

## Evidence

The accepted drive finished at `2026-07-29T06:15:22-04:00`. The first
`proxy-stopped` is line 256 of
`tmp/load-testing/evidence/ollama-p8-10a-600s-20260729/provider-timing.jsonl`;
a second `proxy-ready` follows on line 257. The restarted drive reached 50
additional turns before the exact launchd job was removed. The first
`run.edn`, phase row, and provider summary retained their `06:15:22`
modification times.

## Owner

The detached load-test launch recipe in
[[load-testing-2026-07-29]], not the Seon process operator.

## Acceptance

A detached one-shot drive survives the launching lane, runs exactly once, and
leaves one proxy-ready/proxy-stopped pair after normal success. Cleanup removes
the registered job without racing a restart.

## Stale verdict

Archived as stale on 2026-07-29. The recorded launchd restart is valid
historical evidence, but the claimed owner no longer exists in current,
tracked source: there is no detached load launcher or `launchctl submit`
recipe to correct. The maintained research document describes the completed
drive and points at an ignored, project-local `tmp/load-testing/scripts/run.sh`;
that script owns the proxy subprocess but contains no launchd registration.

Reverification:

```text
rg -n "launchctl submit|LaunchOnlyOnce|KeepAlive" \
  script bin src test \
  docs/prds/sci-execution-runtime/research/load-testing-2026-07-29.md
(no matches)

git ls-files tmp/load-testing/scripts/run.sh \
  docs/prds/sci-execution-runtime/research/load-testing-2026-07-29.md
docs/prds/sci-execution-runtime/research/load-testing-2026-07-29.md
```

Adding a launcher only to fix this ghost would create a second operator
surface. If detached load driving becomes maintained code again, its launcher
must publish a one-shot completion/cleanup contract and receive a new
regression at that real owner.
