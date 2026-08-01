---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, boot, database]
---

# Derive dormant clusters from the persisted roster

## Problem

The fresh operator derives cluster existence only from live advertisements,
registrations, and process-local open branch connections. When the operator
JVM is down, an intact cluster branch disappears from `:existing`, so ordinary
operator commands cannot address the sovereign database that still exists.

## Evidence

- `script/seon/fresh_operator.clj` `source-observations` and
  `derive-cluster-truth` never read the persisted Datahike branch roster.
- `src/seon/cluster/registry.clj` `roster` is the existing authority over
  `datahike.api/branches`.
- With every Seon JVM down, `:cluster-default` was present at commit ID
  `6a6bab67-adcc-51cc-8253-4d6718b37ee7` while the operator derived
  `{:seon.fresh-operator/existing []}`.

## Owner

`script/seon/fresh_operator.clj` cluster truth derivation.

## Acceptance

A discovered real-operator regression populates a cluster, stops its JVM,
finds the dormant name from the persisted roster, reopens it through
`bin/seon start`, and proves its facts survived unchanged.

## Resolution

Commits `1c46005f6` and `89874aaec` make
`seon.cluster.registry/roster` the persisted existence authority and retain
advertisements, registrations, and open connections only as the liveness
overlay. Commit `81e657ecb` makes the real-process regression's cleanup
identity-fenced and proves the two starts use different `(pid,
start-instant)` identities.

The recurring regression completed init → first JVM → population → full stop
→ dormant `0/0` discovery → second JVM reopen with identical marker, agent,
and message counts → live `1/1`. The final shared-root proof on 2026-07-31
started persisted `default` as the only live cluster; `bin/seon status`
reported `1/1 clusters alive` and no orphan JVMs.
