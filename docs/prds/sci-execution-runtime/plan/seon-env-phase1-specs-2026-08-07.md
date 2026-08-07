---
type: prd
status: active
tags: [prd, runtime, platform, testing]
---

# seon.env Phase 1 — lane specs (ready to launch at bare green)

Launch condition (PRD ruling 7): bare `bin/test` green. Parent design:
[seon-env-prd-2026-08-07.md](seon-env-prd-2026-08-07.md) — every lane reads
it end to end, plus its own grounding named below. Lanes are file-disjoint
and launch together; W1 is the spine, the others are dependency-ready
against settled contracts.

## W1 — the environment value and the one constructor (spine)

Grounding: the PRD's value/construction/carriage sections; the
test-infrastructure spec's `source-base!`/`start-fork!` contract
(work items there overlap — this lane implements the shared constructor
once, not twice);
[env-phase0-flow-carriage-2026-08-07.md](../research/env-phase0-flow-carriage-2026-08-07.md)
(carrier key `:seon.env/environment`; refusal is Seon's own).

Deliverables:

- `resources/seon/schemas/seon.env.edn` — the environment schema, existing
  key names only; the value stores the CONNECTION, never a database value.
- `src/seon/env.clj` — construction from the boot layers in dependency
  order; refuse-up-front with flat errors naming the failed layer; subset
  construction for tests (store+facts, no web).
- `start-fork!`/`with-cluster` consume it (coordinate paths with the
  test-infrastructure landing — same owner, one mechanism).
- The environment `assoc`'d onto the cluster ctx (never via `sci/init`
  options — Phase 0 finding); per-turn fork carries it.
- `submit!`/`submit!!` require `:seon.env/environment` on submissions and
  merge it into work-fn/`complete!` arguments; refusal at `var-process` and
  the submit pair; the three `bound-fn*` sites stay UNTIL Phase 3 (deleting
  them is the sweep's same-change constraint, not this lane's).
- The evidence sink handle rides the environment (PRD ruling 9) as an
  optional declared member.

Acceptance: the Phase 0 probes graduate into `test/` as class regressions
(fork carriage, flow carriage, submission refusal); two-cluster isolation
proof; boot-layer failure yields the named flat error with the prepl still
answering; the reset-boundary live proof (schema/acquisition/process
changes need it — fixture load paths are not the boot path).

## W2 — the interrupt arm rides the environment

Grounding:
[interrupt-arm-does-not-cross-a-thread-hop.md](../../../seon/issues/interrupt-arm-does-not-cross-a-thread-hop.md)
(confirmed: work handed across a thread runs unbounded, uninterruptible);
[env-phase0-fork-carriage-2026-08-07.md](../research/env-phase0-fork-carriage-2026-08-07.md).

Deliverables: the arm travels with the work exactly as the environment does
— armed state carried on the ctx/fork and on submissions, so spawned work
counts fn-entries, observes the deadline, and is reachable by `interrupt!`.
Owned paths: `src/seon/sci/kernel.clj`, the submission seam touchpoints
coordinated with W1 (W1 owns flow.clj — this lane supplies the arm value,
W1 carries it). The `:seon.eval/fn-entries` under-report corollary dies
with the fix; the probe graduates as the class regression.

Acceptance: Probe B's exact scenario inverted — detached work under a
300 ms limit is interrupted at ~300 ms; entries attributed; 5 consecutive
green runs.

## W3 — the call-preparation hook lands in the maintained fork

Grounding:
[env-phase0-runtime-ctx-hook-2026-08-07.md](../research/env-phase0-runtime-ctx-hook-2026-08-07.md)
(scratch branch `seon-env-hook-probe`, contract validated);
[sci-built-in-call-observer-is-read-from-the-analysis-context.md](../../../seon/issues/sci-built-in-call-observer-is-read-from-the-analysis-context.md).

Deliverables: graduate the probe branch into the fork's mainline pin —
review the ~30 lines against the fork's own conventions, fix the
analysis-ctx observer bug in the same change, add the loud refusal for
unknown `sci/init` option keys (Phase 0 ugly-output finding), run the
fork's full suite, bump the superproject pin in one reviewed commit.
Simple (unguarded-lookup) hook form — PRD ruling 8: the ~80 ns is accepted
until S1 plan-gating lands in Phase 3. Providers themselves are Phase 3
(S1); this lane lands the SEAM, proven by the probe's own falsifiers
running against the new pin.

Acceptance: fork suite green; probe falsifiers green against the pin;
superproject pin bump path-limited and reviewed by the orchestrator.

## W4 — per-fork installation cost, measured

Grounding: Phase 0 finding 2 (interpreted fns pin their defining ctx —
program functions must be host Vars or fork-created); the PRD's open
question on committed agent-authored program functions.

Deliverables: a measurement, not a mechanism — for a representative
committed corpus (N agent-authored interpreted defns), the cost of
(a) lazy per-fork installation on first call vs (b) eager re-creation at
fork time vs (c) the current install-into-base behavior's correctness
hazard demonstrated. One dated research file with numbers and a
recommendation; NO production changes. This measurement decides the Phase
2 design conversation with the owner.

## Standing constraints for every lane

Path-limited commits; never `git add -A`; the shared default cluster is
never reset or bounced; scratch clusters/roots under `tmp/`; neutral
engineering language; ugly output reported; probes that prove a fix
graduate as ONE regression per class and the probe file is deleted.
