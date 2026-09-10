---
type: issue
status: resolved
severity: blocker
tags: [issue, turn, loop, runtime, class/absence-of-signal]
---

# After the data batch, a wake opens a turn under the runtime component and it never settles — silently

## Problem

Observed 2026-09-09 19:18–19:20 on `default`, twelfth refork at HEAD
(`ae0e54841` + `1e778e880`), Juniper reseeded through the fixture:

- Turn `aa071259cfd8` (system turn 0) opened and closed at 01:18:07Z with
  eight evaluations — the data-first opening is correct.
- Turn `9fc9bc9ef8ad` opened at the same instant, trigger = the seeded
  message (`:seon.message/inbox` edge, id `1634a293`), and is STILL open
  two minutes later with zero evaluations and zero faults anywhere.

`loop-live` (`e12ba7535`) fixed the duplicate-graph race and the loop proof
is green on the canonical fixture after the data batch, so the live path
differs from the fixture path again — and this time nothing reports it
until the 600 s backstop. Candidates: the settle writer looking for the
turn by the retired relationship (`:seon.turn/agent`) instead of
`:seon.runtime/turns`; `closed-at` vs `closed-tx`; the fixture's direct
arm racing the armer on the new runtime shape; the no-provider virtual
selection reading settings through the old component path.

A turn that cannot settle must fault within seconds, not sit silent for
ten minutes (AGENTS §2.3). Fix the cause AND the silence.

## Evidence and resolution

Fixed in `2b6913f4b`. Both default and a fresh scratch boot had Juniper's
open turn but no armed graph. The live installer disarmed for cleanup and
never rearmed; disarm also mistook an idle permit for the proc's stop
acknowledgement, allowing a selected wake to write after cleanup. Runtime
ownership, closed-tx, and no-provider selection were correct.

Disarm now awaits the existing stop transition, the shared live installer
leaves the graph armed, and the existing completion observer is capped by
the evaluation limit. The recurring canonical proof uses the real live
installer, armer, wake listener, runtime component, and SCI execution.

Fresh seeded wake: 3,362 ms; subsequent wake: 600.820375 ms, evaluations
stored, turns-left 20 → 19. Exactly one verification message on default
closed in 7,900 ms, with four evaluations and zero new core faults.
A deliberately unavailable permit with a 100 ms evaluation limit produced
a durable fault naming its open turn after 228.792375 ms including commit.
Default was never stopped, restarted, or reforked. Scratch was stopped and
removed.

The scoped gate passed 4 tests / 137 assertions; platform passed 84 / 505.
Full measurements, scripts, and independent remaining findings are in the
[landing note](../../../prds/context-generation/research/loop-live-landing-2026-09-09.md).

## Owner

Turn loop and agent graph lifecycle; shared live Juniper fixture.

## Acceptance

Verified: the live fixture's seeded runtime turn settles within the agent's
evaluation limit; ordinary wakes store evaluations and decrement turns-left
once; an unavailable permit faults under that limit instead of waiting for
the 600 s backstop. The independent generated-runtime-pull evaluation error
is recorded separately and does not prevent settlement.
