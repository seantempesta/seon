---
type: issue
status: open
severity: blocker
tags: [issue, testing, error, flow]
---

# Register the fault committer before the cluster graph can emit

## Problem

The per-cluster graph was resumed before its report/error fan-out was
installed. A cluster proc could therefore emit its first fault into the source
error channel before the fault committer's tap existed. Core.async's `mult`
consumes immediately and explicitly drops values received with no taps, so the
earliest and most diagnostic fault of a fresh cluster could vanish.

## Evidence

The original attribution was wrong. At clean commit `48eb25ab7`,
`tmp/full-gate-2026-08-10b.log:986-1038` failed at `armed_test.clj:403`, the
later recurrence wait requiring at least two messages. Reaching that line
proves the test had already observed and asserted the first durable fault and
its notification at `armed_test.clj:347-396`. That historical agent-graph
injection therefore does not demonstrate first-fault loss.

The ordering defect is independently established in the current source:

- `src/seon/cluster.clj:2232-2234` performed source `start → resume → fanout`;
- `src/seon/cluster/agent.clj:444-457` already uses the correct
  `start → join fanout → resume` order; and
- core.async pin `dc35f3e0d7bc2eef502e77982f48641f025c8051` documents and
  implements no-tap dropping at
  `reference-code/core.async/src/main/clojure/clojure/core/async.clj:797-837`.

The fix now keeps the source graph paused until `start-error-fanout!` has
installed every tap, then resumes it. The class regression throws from the
cluster armer's first resume transition and queries the resulting
`:seon.error` datom with proc and process provenance.

## Owner

Owner: the per-cluster graph construction order in `src/seon/cluster.clj`, with
the recurring real-boot proof in `test/seon/cluster/armed_test.clj`.

## Acceptance

- The class regression injects at the first cluster-proc resume transition and
  observes one queryable fault datom with proc and process provenance.
- Three consecutive focused runs pass.
- `bin/test --changed` passes for the owned source and test paths.

Verification is pending the W1 integration landing. Current armed-cluster runs
fail before the injection while W1's bootstrap/run/loop settlement work is in
flight; that foreign boundary blocks the green gate, not this coherent fix.
