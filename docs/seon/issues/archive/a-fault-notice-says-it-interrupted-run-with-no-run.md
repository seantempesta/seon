---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, runtime, render]
---

# Omit the run clause from a fault notice that has no run

## Problem

`seon.error/notice-ai-prose` promises in its own docstring that every clause is
"derived from a present fact and OMITTED when the fact is absent — never a
stored nil". One clause broke that promise. The `:your-run` arm rendered the
run unconditionally:

```clojure
:your-run (str "It interrupted run " run-id ".")
```

An error attributed to an AGENT but not to a run takes that arm — attribution
and the run are independent, and commit-tx drops a run ref whose entity does
not exist — so `run-id` is nil and the agent reads a sentence with a hole in
it. Scheduled maintenance faults are exactly that shape: attributed to root,
no run.

## Evidence

Live, cluster `escalation-probe` in isolated root `tmp/lane-escalation`,
2026-08-08. Four durable messages committed at boot, verbatim from
`:seon.cluster.message/content`:

```text
The reaper cannot read every external claim. (:seon.operator/reap-incomplete).
It interrupted run . Inspect error maintenance-error/maintenance-receipt/…
```

All four maintenance faults (compact, process-census, reap-dead-roots,
footprint) carry the same empty clause, and each one is a message an agent
reads in its prompt.

## Owner

`seon.error/notice-ai-prose` in `src/seon/error.clj` — the one derivation
behind the router, the explanation message's stored content, the `problems`
block, and the failover notice.

## Acceptance

- A fault notice for an error with no run says nothing about a run.
- A fault notice for an error WITH a run still names it.

## Resolution — 2026-08-08

The clause rides the RUN rather than the reason:
`(when run-id (str "It interrupted run " run-id "."))`. Found while probing the
[fault-loop issue](../a-failed-turn-wakes-itself-through-its-own-fault-message.md)
on a live cluster, and fixed in the same commit that deleted the hand-rolled
run-phase escalation. The `:recurring` arm added in that commit carries the
same clause and was written guarded from the start.

`seon.error-test` (50 tests, 276 assertions) and `bin/test --platform` green.
