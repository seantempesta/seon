---
type: issue
status: open
severity: friction
tags: [issue, operator, directory-claims, wave/directory-claims]
---

# Reset does not reclaim the external claims it advertises

## Problem

`bin/seon status` reports `invalid external claims: 8 total (5 orphaned
for absent roots; 3 malformed); reclaim with `bin/seon reset --force``.
Running exactly that command (2026-08-28, completed clean: republish +
refork, footprint 9.82 -> 0.26 GiB) left the same 8 invalid claims in
the next `status`. Either reset does not touch the external claim
registry, or it reclaims a different class than status counts — in
both cases the status line's own remedy is false, which trains
operators to ignore the counter.

## Owner

The operator claim registry and `reset`'s reclamation step
(`src/seon/operator.clj` / `script/seon/fresh_operator.clj`): make
reset actually reclaim invalid external claims, or make status name
the real remedy.

## Acceptance

After a `bin/seon reset --force` on a root whose status reports
invalid external claims, the next `bin/seon status` reports zero.
