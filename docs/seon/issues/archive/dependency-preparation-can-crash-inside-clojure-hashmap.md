---
type: issue
status: superseded
severity: friction
tags: [issue, flow]
---

# Make dependency preparation deterministic under concurrent development

## Triage — 2026-07-23

UNCLEAR. Current `script/seon/dev/artifact.clj:781-849` does place dependency
preparation under the checkout-wide artifact lock, but the recorded JVM
`HashMap` failure was intermittent and its cause unproved. Repeatedly race the
operator and focused preparation while retaining the external CLI failure log;
zero failures under the current lock closes it, recurrence localizes the
remaining external/shared-cache path.

## Problem

`bin/test-cljs` can fail before compilation while running `clojure -X:deps prep`
with a JDK `HashMap` tree-node `ClassCastException`. The same dependency
preparation normally succeeds, making the failure intermittent noise in the
correctness gate.

## Evidence

The focused `seon.client-initialization-test` run on 2026-07-17 failed in
`java.util.HashMap$TreeNode/moveRootToFront`, invoked by
`seon.dev.artifact/run-step!` for `clojure -X:deps prep :aliases [:cljs]`.
The immediately preceding and following focused CLJS preparations completed.
The operator watcher and other test processes were active at the time, so
concurrent access to shared Clojure dependency state is the first falsifiable
hypothesis; it is not yet proven.

## Owner

`seon.dev.artifact/prepare-dependencies!` owns dependency preparation and its
existing build lock. The Clojure CLI's cache behavior must be source-grounded
before changing that boundary.

## Acceptance

- Concurrent operator and focused-test preparation either serialize through
  the existing owner or use isolated dependency state proven safe by the
  Clojure CLI source.
- A repeated concurrent preparation probe completes without JVM collection
  corruption or redundant dependency work.
- `bin/test-cljs` reports an actionable retained failure if the external CLI
  itself fails.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
