---
type: issue
status: open
severity: blocker
tags: [issue, turn, loop, runtime, class/absence-of-signal]
---

# After the data batch, a wake opens a turn under the runtime component and it never settles — silently

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
