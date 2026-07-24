---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, schema]
---

# Paged initialization misses a public core-predicate binding

## Evidence

After paged initialization accepted every page and the completion marker
allowed acquisition, fresh pod startup failed during SCI program
reconstruction:

```text
Unable to resolve symbol: seon.db.protocol/ordinary-wire-value?
```

The reproduced operator evidence is
`tmp/orchestrator/initpage-up.log`; the pod log is
`logs/operator/pod/be86bc09-9509-418d-bc35-a05e91565d76.log`.

This is not an R39 private-function failure.
`seon.db.protocol/ordinary-wire-value?` is a public `defn` and is registered as
a core predicate in `seon.db.protocol`. The build inventory also classifies it
as a public export. The failure therefore belongs to the protected schema
projection or SCI predicate-binding acquisition path exposed after paging, not
to private corpus-row publication.

## Expected owner

The schema acquisition path must make every registered public core predicate
binding available before SCI reconstructs schemas that name it. Do not add a
second predicate registry, rename the predicate, or weaken its schema.

## Acceptance

- A fresh paged reset reaches pod readiness.
- `seon.db.protocol/ordinary-wire-value?` resolves while its registered schema
  is reconstructed.
- A recurring reset-boundary proof covers the real initialization and
  acquisition path.

## Resolution

Resolved by `719bb8e1d`, `7b75a821a`, `3b58e9314`, and `b8216c27a`.
Committed projections now carry their computed core-predicate compile options
through every client reconstruction consumer, including dependency walks and
Malli instrumentation. The one registered predicate-function authority
therefore supplies `seon.db.protocol/ordinary-wire-value?` on the client tier;
no second registry or symbol list was introduced.

The real reset boundary also exposed a separate operator-design flaw:
readiness was aborted by guessed total duration. The operator now keeps the
readiness advertisement as the success event and applies the R27
`:seon.config.operator/pod-boot-stall-timeout-ms` only when its pod log shows
no concrete progress. Initialization page receipts, committed acquisition
pages, projection/instrumentation phase transitions, and the existing
heartbeat advance that observation. The superseded total-duration fact is
absent.

Evidence:

- The recurring current-corpus paged initialization proof passed at
  `tmp/test-cljs-20260723-154503-8988.log`.
- Predicate reconstruction and instrumentation proofs passed at
  `tmp/test-cljs-20260723-150820-64774.log` and
  `tmp/test-cljs-20260723-150853-65162.log`.
- The focused operator stall/config/progress proof is retained at
  `tmp/orchestrator/predfix-operator-focused.log`.
- A fresh isolated `predfix` reset reached pod readiness in 271 seconds. Its
  transcript is `tmp/orchestrator/predfix-up.log`; the pod log contains all 97
  initialization-page receipts, committed acquisition/projection progress,
  successful instrumentation of 925 functions, and `auto-boot ready`, with no
  unresolved predicate. `tmp/orchestrator/predfix-down.log` records the
  subsequent operator-owned shutdown.
