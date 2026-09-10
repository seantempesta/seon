---
type: issue
status: open
severity: friction
tags: [issue, agent, runtime, wave/context-fixes]
---

# Include runtime listen patterns in durable turn eligibility

## Problem

Runtime listen patterns now route a payload-free wake to their agent, but
the work derivation still seeks only schema-declared listened attributes.
An otherwise idle agent takes a Flow pass without opening a turn for an
authored amount listen. Delivery alone does not refresh its read history.

## Evidence

2026-09-09 evidence-listens scratch root, Juniper fixture, no-provider:
`:example/amount` transaction `536871016` advanced the actual mailbox
proc count from 6 to 7 in 63.936291 ms. An unrelated customer change left
that count at 7. The latest Juniper turn still opened at `536870997`.
The runtime held `:seon.listen/attribute :example/amount`.

`src/seon/turn.clj`'s `wake-attribute-set` and `unanswered-wakes` call
`wake/agent-wake-datoms`, which seeks `:avet attribute agent-eid` only for
the schema-derived attribute set. Authored patterns may constrain an
ordinary scalar value instead of storing the agent as the datom value.

## Owner

The existing wake/work derivation in `seon.cluster.wake` and `seon.turn`.
The bounded assignment covered `route!` delivery, not this turn-admission
policy. Reuse the durable listen pattern facts; do not store another wake
queue or add another listener.

## Acceptance

An amount change matching Juniper's authored listen opens a no-provider
turn through the ordinary graph, refreshes its changed read, and is
answered once by the existing transaction-basis rule. An unrelated
attribute does neither. Define and verify retraction, reboot, and
listen-edit semantics at that same authority.
