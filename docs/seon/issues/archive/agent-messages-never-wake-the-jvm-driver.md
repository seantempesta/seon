---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database]
---

# Wake on the one inbound rule, not on `:origin :human`

## Resolution

Resolved by the commit that archives this note.

`seon.agent.driver` now queries only the database-owned candidate facts:
addressed messages whose recipient is an agent and which are not already the
cause of a run. It pulls each candidate message and recipient, then delegates
admission to `seon.agent.message/waking-inbound?`. The query no longer
duplicates an origin or sender rule, so the message layer remains the one
owner.

The landmine-8 amplification audit found the wake edge disjoint from the wake
path's own writes. The listener watches only
`:seon.agent.message/to`. Opening a run commits:

- the run's id, agent, cause, start instant, status, process, claim epoch, and
  lease;
- the recipient agent's `:seon.agent/run` pointer.

Neither this transaction nor the later turn, execution-plan, receipt, lease,
timing, or terminal transactions rewrite the original message's
`:seon.agent.message/to`. The opening transaction also records the original
message as `:seon.agent.run/cause`, so the pending query immediately damps that
same message. A new explicit reply can wake its recipient, which is the desired
edge; `waking-inbound?` rejects its sender and `:core` messages.

## Proof

The focused writer run reports 12 tests, 60 assertions, zero failures, and
zero errors. Its two new recurring regressions prove:

- an `:agent`-origin message addressed adversarially to both sender and
  recipient makes only the recipient claimable; and
- after the driver admits that wake with its real `open-run-tx-data`, the same
  message cannot be enumerated again.

The final retained full run,
`tmp/plan-evidence/test-writer-2026-07-26-wake-final.log`, reports 549 tests,
3,865 assertions, three failures, and zero errors. The driver tests pass. The
only red assertions are the two independent
`composes-the-established-frozen-prompt-projections` assertions and the
out-of-scope
`no-stored-attribute-promises-an-order-the-database-cannot-keep` design
contract.

A live agent-to-agent proof was not run. `bin/seon up` built the current
artifact, but the shared default cluster was applied at an older release and
refused the pod until `bin/seon cluster apply default`. Mutating that shared
database was not justified for this proof, so the supervisor was stopped
cleanly with `bin/seon down` and the recurring tests remain the evidence.

## Problem

An agent-to-agent message can never start a run on the JVM driver. The wake
query admits only `:seon.agent.message/origin :human`, so a message an agent
sends — which carries `:agent` — is never enumerated and its recipient is never
made claimable.

This contradicts the message layer's own stated invariant. `waking-inbound?`
exists so that "a message wakes under exactly the rule it renders under — no
drift" (its own comment). The driver does not call it.

Consequence: multi-agent collaboration does not work. The final system gate
requires agents that "message each other", so this blocks the gate outright.

## Evidence

Verified 2026-07-26 at `71f3cb0e0`.

- `:seon.agent.message/origin` is `[:enum :human :agent :core]`
  (`src/seon/agent/message.cljc:68`).
- An agent-sent message is written with `:seon.agent.message/origin :agent`
  (`src/seon/agent/message.cljc:425`).
- The one rule, `seon.agent.message/waking-inbound?`
  (`src/seon/agent/message.cljc:299-307`), defines waking as `from ≠ me` and
  `origin ∉ #{:core}`. An `:agent` message therefore **is** a waking inbound.
- The driver's `pending-message-query`
  (`src/seon/agent/driver.clj:332-341`) hardcodes
  `[?message :seon.agent.message/origin :human]` and never calls
  `waking-inbound?`.

So the render side and the wake side now disagree: an agent's inbound renders
in its transcript but never causes a turn.

## Owner

`seon.agent.driver` owns wake enumeration. `seon.agent.message` owns the
delivery rule and must remain the single authority — the driver consumes it
rather than restating it.

## Acceptance

- The wake enumeration and `waking-inbound?` agree by construction: the driver
  applies the message layer's rule instead of a second origin predicate. Two
  places encoding one rule is the defect, so a fix that merely swaps `:human`
  for a wider literal set is insufficient.
- An agent sending to another agent causes exactly one run for the recipient.
- A `:core` message still does not wake an idle agent.
- An agent's own write never re-wakes it (the existing `from ≠ me` clause), and
  the wake attribute stays disjoint from attributes the wake path itself
  commits — see [[../../../prds/sci-execution-runtime/roadmap.md]] landmine 8.
- One recurring regression under `test/` claimed by `bin/test-writer` drives an
  agent-to-agent message end to end.

Related: [[../run-is-unrecoverable-before-its-plan-commits]].
