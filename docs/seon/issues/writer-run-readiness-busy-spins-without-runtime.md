---
type: issue
status: active
tags: [issue, runtime, database]
---

# Writer run-readiness! busy-spins when a ready source has no runtime

## Evidence (verified from source)

`src/seon/db/writer.clj:3316-3333` — `run-readiness!` loops:
`take-ready!` → `readiness-runtime` absent → `requeue-ready!` +
`Thread/yield` → recur. With one ready source whose runtime is not
registered, this is a full-speed busy-spin on a writer thread (yield is
not a wait). Found by the poll/timeout census
(`research/poll-timeout-census-2026-07-23.md`, "suspected defect").

## Open questions (trace needed)

- Can `readiness-runtime` be legitimately absent for a ready source
  (startup ordering before registration? runtime death?), or is absence
  always a bug upstream?
- Does `take-ready!` block when the queue is empty (making the spin only
  reachable via the requeue path)?

## Direction (R42)

Detect-and-respond, not spin: absence of a runtime for a ready source
should park the source until a runtime REGISTERS (event: registration),
or fail loud if absence is impossible by construction. No sleep-loop
fix.

## Acceptance

- The spin is unreachable by construction (source parked on the
  registration event, or absence proven impossible and asserted loudly).
- One regression: a ready source with no runtime does not consume CPU
  and is delivered exactly once when the runtime registers.
