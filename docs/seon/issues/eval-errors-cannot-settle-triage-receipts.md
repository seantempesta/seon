---
type: issue
status: open
severity: blocker
tags: [issue, sci, runtime, errors, durability]
---

# Let evaluation errors settle their triage receipt

## Problem

The evaluation failure path produces `:seon.cluster.eval/triage-edn`, but
`seon.cluster.run/receipt-settle-tx` rejects that key. An ordinary agent error
therefore throws out of the run loop and leaves its receipt and run open.

## Evidence

During the shared-cluster collision, agent A evaluated:

```clojure
(throw (ex-info "a-only collision failure" {:collision/agent :a}))
```

The loop escaped with `seon.instrument/contract-violated`: the
`:seon.cluster.eval/triage-edn` key was disallowed by
`receipt-settle-tx`. Receipt `["streams-error-run-a" 1]` remained without a
terminal fact and the run retained `:seon.cluster.run/process`. Concurrent
agent B still settled four receipts and closed cleanly, proving the error did
not cross into B's run.

`src/seon/cluster/loop.clj` deliberately carries `triage-edn` into
`terminal-tx`; `src/seon/cluster/run.clj` omits it from the settlement request
contract. The full fact query is in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The one receipt settlement contract and transaction function.

## Acceptance

- An SCI execution error commits one terminal receipt containing bounded
  triage evidence and a flat error value.
- The run reaches its defined error disposition without a host exception.
- A concurrent run remains independent and contains no foreign error facts.
- The regression executes through the production run loop.
