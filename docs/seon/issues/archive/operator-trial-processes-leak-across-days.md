---
type: issue
status: superseded
severity: friction
tags: [issue, component, architecture]
---

# Operator trial processes leak across days

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — test-simplification batch.** Today's exact-generation reaper
does not prove interrupted trial-fixture cleanup; the real-process fixtures
and their finally/reaping contract close with the test consolidation batch.

## Observed (2026-07-20)

Six `detach.py`-launched trial processes from a
`tmp/branch-sigint-reuse-<uuid>/` operator trial started 2026-07-15
08:46 were still running five days later (pids 16075/16076/16077 and
16802/16804/16805 — `script/seon/dev/detach.py owner … trial-process/…`
wrapping disposable `python3 -c 'http.server…'` workloads). Their trial
directories under `tmp/` therefore cannot be pruned as stale, and the
processes accumulate per interrupted or non-reaping trial run.

## Expected owner

The operator test fixtures that launch real detached trial processes
(`test/seon/dev/` process/branch trials) should reap their detached
workloads in a `finally`, or the trial process record should carry a
deadline the next operator invocation can harvest. `bin/seon down`
reaps recorded supervisor children, but these trial processes belong to
deleted/abandoned trial process-dirs no live supervisor owns.

## Acceptance

After a full `bin/seon test operator` run (including an interrupted
one), no `detach.py`/trial workload from that run survives; a
days-later `ps` shows no `tmp/branch-sigint-reuse-*` processes.

## Note

Not killed during the 2026-07-20 hygiene sweep — the sweep lane had no
process-mutation grant; the pids above are the evidence for the owner.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
