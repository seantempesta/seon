---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# message! can resolve a recipient at a stale basis to a dangling entity

## Evidence (2026-07-20 night, live default cluster)

Message `p7413gax9q6j` carries `:seon.agent.message/to {:db/id 3558}`;
entity 3558 no longer exists (pull returns nil), while the intended
recipient `real-hats-wave` is entity 7559. The sender was a long-lived
dev REPL session (`agent:default/root#5`, alive across several pod
restarts and agent retractions); the deterministic name generator had
recycled the name, and resolution bound the OLD retracted entity from a
stale database value. The message commits successfully, the wake path
sees no live recipient, and the agent never runs — a silent no-op that
cost the live battery hours of diagnosis. Battery circumstances L1/L5
are blocked on this class.

## Expected behavior

Recipient resolution must happen at a FRESH acquisition (or the
message's own transaction basis) — and a resolution that yields an
entity absent at the transaction's basis must fail loudly as a
steering `:seon/error` (never commit a dangling ref). The lookup-ref
form `[:seon.agent/id "..."]` inside the transaction would make the
writer enforce this by construction — likely the one-mechanism fix.

## Acceptance

Sending to a recycled name from a stale session either delivers to the
CURRENT entity or errors with directive text; a committed message's
`to` can never dangle at its own basis; regression test with a
retract-remint cycle.
