---
type: issue
status: open
severity: friction
tags: [issue, operator, database, wave/operator-artifact-follow-up]
---

# Give offline roster discovery a current read-only helper

## Problem

When every cluster JVM is down, the Babashka operator cannot load the pinned
Datahike classes. Truthful dormant-cluster discovery therefore starts a full
`clojure -M:dev` JVM, loads the store and registry owners, and only then reads
the roster. Cold `status` pays 12–14 seconds, and cold `init` pays that load
before starting its separate initialization JVM. The fallback also calls the
creating `open-store!` owner, so observing an incomplete genesis can perform
its recovery transition rather than remain read-only.

## Evidence

- `script/seon/fresh_operator.clj` `offline-roster` launches the source JVM
  because Babashka fails loading current Datahike JVM classes.
- Three cold probes measured 11.99–13.87 seconds before the roster result;
  live-JVM status, which reads `registry/roster` through the held store, took
  approximately 0.42 seconds.
- An AOT jar loaded Datahike in approximately 0.96 seconds, but the current
  artifact does not contain the fresh registry/store owners or their digest
  invalidation contract.
- Raw Konserve `:branches` access is rejected because it would duplicate
  `seon.cluster.registry/roster` and bypass the store fence/open owner.

## Owner

The operator artifact boundary plus `seon.cluster.registry/roster`.

## Acceptance

An all-JVMs-down operator reads the current persisted roster through the one
registry owner in approximately the live-path latency, performs no store
creation or recovery during `status`, and introduces no second roster or raw
Konserve interpretation. The artifact/helper is invalidated with current
source so it cannot execute stale store semantics.
