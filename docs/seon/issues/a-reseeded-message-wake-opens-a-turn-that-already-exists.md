---
type: issue
status: open
severity: friction
tags: [turn, wake, message, fixture]
created: 2026-09-17
---

# A reseeded message wake opens a turn that already exists

## Observed on a fresh default (pid 66052, 2026-09-17 04:24:41Z, right after the Juniper reseed)

Fault `9aee2b65…`, kind `:seon.turn/refused`, transition
`seon.turn/open-call`, rule `:seon.turn/run-exists`, agent `juniper`,
trigger `[:seon.message/id "8a52612d"]`: the turn loop asked to open a turn
for the fixture's message wake while that agent already had an open turn.
A refused transition became a durable fault on a cluster that had done
nothing but boot and reseed.

## Why it matters

The fixture install and the running agent loop race (AGENTS §5 rule 9: "a
fixture never retracts what a running loop is settling"), and the loop's
own open decision arrives at the writer as a refusal instead of a no-op:
"a turn is already open for this agent" is ordinary state for a wake that
arrives mid-turn, not an error. The wake should be answered by the open
turn's settlement (turn PRD §14 `:t` rule) and the open-call should decide
"already open" as its normal outcome.

## Owner

The message-wake-model lane (handling claim / answering rule seams) at its
next resume; the fixture's install order is the secondary suspect.
