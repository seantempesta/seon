---
type: issue
status: open
severity: friction
tags: [issue, sci, storage, admission, bounded-execution]
---

# A blocking realization is not bounded by admission's interrupt

## Problem

`seon.sci.admit` calls the evaluation's own `:interrupt-fn` before projecting
every node, and that is what makes an unbounded lazy source terminate. But the
interrupt is a POLL, not a preemption, and the walk obtains a child BEFORE it
can poll on that child: `frame-advance` realizes `(first remaining)` /
`(next remaining)` to get the next value, and only then does the loop's
`::open` step call the interrupt on it. A source that blocks INSIDE its own
realization is therefore never asked, and the deadline is observed only once
the block releases.

Measured by the `verify-storage` lane, 2026-09-07
([report §2.2](../../prds/context-generation/research/verify-storage-bound-2026-09-07.md)):

```clojure
(def blocking (lazy-seq (do (Thread/sleep 5000) [1])))
;; interrupt-fn throws sci/interrupt! once 1000 ms have passed
;; measured: blocking-elapsed-ms => 5005, against a 1000 ms deadline
```

The exact seam is `Thread/sleep` (or any blocking call) inside `lazy-seq`
realization, entered from admission's own walk while it asks the source for
its next child.

## Why it is not a blocker today

No blocking primitive is reachable from agent source. Five spellings were
probed live through `seon.cluster.agent/submit-source!` and every one refused
at analysis: `(Thread/sleep 10)`, `(java.lang.Thread/sleep 10)`,
`(Thread. (fn []))`, `(clojure.core.async/timeout 10)`,
`(.take (java.util.concurrent.LinkedBlockingQueue.))`.

That mitigation is a property of the SCI binding table, not of this seam, and
**nothing asserts it**. A JVM-side value handed to admission — a fault's
evidence, a render producer's return, a capability request — is under no such
restriction.

## What would close it

Either a regression that asserts the binding-table property (no blocking
primitive resolves from agent source, derived from the table rather than a
hand list), or a bound at the seam that does not depend on the source
cooperating — admission's realization running under the same
`seon.flow` executor deadline that already bounds every other execution
surface, so a block is cut rather than waited out.

Filed by the `storage-bound-repair` lane, 2026-09-07, out of scope for that
assignment.
