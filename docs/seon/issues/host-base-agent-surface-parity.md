---
type: issue
status: open
tags: [issue, agent, architecture]
---

# The host base does not resolve the agent-facing surface (q34, W5-0 gate)

## Observed (2026-07-22, live default cluster, host-tier dial ON)

Two independent live evidences on HEAD:

1. Direct probes: `seon.db/entity` and `my.blob/get` are UNRESOLVED on
   the supervised JVM host (they route to the preflight
   unresolved-symbol path). Host boot log: `base-loaded=166/172
   base-failed=6 base-excluded=112`.
2. A real DeepSeek drive (`wet-mammals-go`, 8 turns, post-q29): the
   agent completed its computation but could not reach `message/user`,
   `seon.agent.message/user`, or `seon.agent.lifecycle/complete` — all
   unresolved on the host — retried spellings for 8 turns and the run
   closed `:no-forms`. Under the dial, agents currently CANNOT message
   the human or complete tasks. (Turns all `:done`; q29's containment
   fix held throughout.)

## What this is

The W5 cutover's real precondition: the complete agent-facing surface
(`my.*` toolkit, `seon.agent.message`, `seon.agent.lifecycle`,
`seon.db`, `my.blob`, capability fns) must resolve on the host tier —
whether via base loading, capability registration
(`register-host-capabilities!`, host/context.clj:1377), or an explicit
exclusion with a designed alternative. `base-excluded=112` is currently
an unaudited blackout list.

## Acceptance (owned by W5-0)

- A CENSUS: for every namespace/var the child tier exposes to agents,
  its host-tier disposition (resolved / capability-routed / excluded
  with reason). The census is a conformance gate, not a document —
  computed from source + the live host, red when a new agent-facing
  var lacks a disposition.
- A live drive on the host tier where an agent uses db, blob,
  messaging, and lifecycle completion end-to-end (the q29 drive rerun,
  but finishing with `:completed`).
- The W5-0 retirement preflight includes this gate green BEFORE any
  cutover drive.

## Provenance

Split out of `host-preflight-candidate-ranking-crashes.md` (that crash
is FIXED `16a040e6` and archived; this parity gap is what its probes
tripped over).
