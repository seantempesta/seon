---
type: issue
status: open
severity: major
tags: [issue, agent, flow, runtime]
---

# Closed agent mailboxes fault on durable notice delivery

## Problem

A terminal-refusal settlement fault must stop the affected agent from taking
another live pass over its still-running receipt before boot recovery marks the
receipt interrupted. Closing that agent's process-local mailbox provides the
required fence: wakes are re-derived from database facts after reboot.

The routing listener treats delivery to that deliberately closed mailbox as
`:seon.cluster.wake/undeliverable-wake`, however. Committing the original core
fault also commits its durable explanation message; routing that message to the
closed mailbox therefore produces another core fault. The recurrence fence
bounds the chain, but the lifecycle closure is reported as a new fault instead
of being recognized as the intentional quarantine state.

## Evidence

`refused-refusal-settlement-faults-and-recovers-on-reboot` drives the real agent
graph and fault committer. The required
`:seon.cluster.loop/terminal-refusal-settlement-refused` fact commits once and
the R41 panic counter advances. The log then records bounded
`:seon.cluster.wake/undeliverable-wake` faults from
`seon.cluster.wake/route!` when the durable explanation message targets the
closed mailbox.

This also contradicts
`docs/prds/sci-execution-runtime/research/zombie-constructibility-2026-07-28.md`,
which describes an `offer!` to a closed wake channel as harmless.

## Owner

The `seon.cluster.agent` disarm/quarantine boundary and
`seon.cluster.wake/route!`.

## Acceptance

- A terminal settlement core fault prevents every further pass for that agent
  until a fresh arm after recovery.
- Durable messages committed while the agent is quarantined remain database
  facts and are available to the fresh mailbox after reboot.
- Intentional mailbox closure produces no secondary core fault.
- Delivery to a genuinely live but non-accepting route remains loud.
