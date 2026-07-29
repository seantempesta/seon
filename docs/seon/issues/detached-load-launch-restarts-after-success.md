---
type: issue
status: open
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
