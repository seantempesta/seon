---
type: issue
status: resolved
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


## Resolution — message handling seam, 2026-09-17

`open-call` decides against the writer's current agent/turn facts: an already
open turn, or the same turn identity for that agent, emits no rows. A genuine
identity collision still refuses. The automatic system-read append has the same
idle/history decision at its existing final writer boundary, so it cannot turn
the normal busy condition into a recording fault before opening is attempted.
Explicit submitted source keeps its separate busy refusal.

The canonical armed regression
`seon.turn-test/a-wake-meeting-an-open-turn-releases-without-a-fault` passes in
the second path-isolated fast iteration. The settlement regression covers the
same-ID and different-ID stale opening shapes, permanent route history, and
a wake arriving after opening. Such a newer wake is answered by the next
qualifying turn; an older turn cannot answer beyond its basis. The fixture's
installation order was not changed or established as the cause.

The [landing note](../../prds/steward-platform/research/message-wake-model-2026-09-17.md)
records complete fast results. The orchestrator still owns the cold gate and
live publication proof. Default PID 66052 was observed read-only, never reset,
stopped or adopted by this lane.
