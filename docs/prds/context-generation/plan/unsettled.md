---
type: prd
status: active
tags: [prd, agent, context, architecture]
---

# The working edge — context-generation program

*THE one live record of current state and ordering (owner ruling,
2026-08-29): write-through in the session it changes, path-limited
commits. The sci-execution-runtime `unsettled.md` is tombstoned and
historical. Dates are absolute; a stale claim here is a defect.*

## Current state (2026-08-29, evening)

**Design track (owner still forming — NO implementation until he says):**
rulings 47–55 sealed in the [ledger](design-ideas-ledger-2026-08-13.md):
the population invariant + symbol identities (47), scalar identity +
result projections + the rename pass scope (48), keys-law as amended
(49), the full-parse bridge (50, design verified against kondo:
[full-parse-bridge-design-2026-08-29.md](full-parse-bridge-design-2026-08-29.md)),
graph closure + settled-form usages + derived self-improvement (51),
VIEW-1-ONLY stable regeneration + coverage-set help + backward
demand-driven generation (52/52a/52b), faces return forms (53), the
write door + missile rule + steward drive scenario (54), instance
args (55). The consolidation of 47–55 + the
[render-data plan](render-data-plan-2026-08-28.md) into one
implementable generator spec is OFFERED, awaiting the owner's go.

**Base-system track (active):** platform tier GREEN; bulk tier legible
(runner: serialized loads, bounded exchanges, one-retirement-one-
failure); walk acquisition 8.5 ms; hook feedback restored after a
15-day silent outage; fixture derivation primitive + 53-site sweep
landed; five graph-consequence regressions fixed; schema lifecycle
over persisted references repaired; the bare-remainder singletons
landed (`c5036aaa2`) — and their one refused red exposed the
25-minute curation replay storm, killed by the population-revision
prelude cache (`e8c8ea6d0`: the prelude derives once per program
population, not once per settled form). The masking meta-lesson:
four Aug-14 breakages hid behind the 12-day bulk blackout.

## The ordering (owner rulings, 2026-08-29 question round)

1. **Doomed-nine deletion pass** (owner: bare reads fully green before
   any rename): delete dead `walk/prose` + `effect/context-suffix` (+
   their tests); neutralize the 6 `render.web-test` + 3
   `render.value/ns-test` reds with wave-G/S2-F issue links — never
   polish, delete or park with a named replacement.
2. **Stale-green visibility lane** (before the freeze): persistent
   operator-owned bare-gate results branch; `bin/seon status` derives
   per-namespace "all current tests last known green; oldest proof
   basis T, N days ago"; unknown ≠ green. Bundles the dev-cache
   `ensure-cache` wiring (same never-stale-silently class).
3. **The atomic identity freeze** (orchestrator, quiet tree):
   `:seon.fn/sym`/`:seon.test/sym` string→symbol + the sym↔`/ns` drift
   regression (47/48a) + receipts→evals rename (48c). Retype + reset,
   never migrate. `bin/seon init` + full gates close it.
4. **The full-parse bridge lane** (50, born compliant on the clean
   identities) — then result projections at settlement (48b).
5. Then the generator work — gated on the owner's context-design go.

## Open blockers the edge tracks

- [generated-model-attempt-traces-diverge-from-durable-facts](../../../seon/issues/generated-model-attempt-traces-diverge-from-durable-facts.md)
  — exact seed `202607280402` + shrunk case recorded; the one
  legitimate red expected in bare until fixed.
- The 69-GB store-growth class (exclusive-sweep wave) and the
  dev-cache staleness issue (rides item 2).
- `effective-config` deferred census rows in lane-protected files
  (effect_test launcher rows 2–3) — sweep them when those files quiet.
- `seon.cluster.curate-test` re-verify after the visibility lane
  lands: its residual red ("first-party program namespace
  seon.dev.fresh-operator-test could not be loaded") coincides with
  that lane's in-flight edits to exactly that file — torn-snapshot
  suspicion, not yet attributed.

## Standing session-start line

Read THIS file end to end, then the ledger's newest rulings, then
`bin/seon status` + `git log --oneline -15`. The program README's
reading order covers the rest.
