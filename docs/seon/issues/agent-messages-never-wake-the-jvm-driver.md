---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Wake on the one inbound rule, not on `:origin :human`

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
  commits — see [[../../prds/sci-execution-runtime/roadmap.md]] landmine 8.
- One recurring regression under `test/` claimed by `bin/test-writer` drives an
  agent-to-agent message end to end.

Related: [[run-is-unrecoverable-before-its-plan-commits]].
