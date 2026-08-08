---
type: prd
status: draft
tags: [prd, runtime, database, agent]
---

# Branch-work design inputs — ingredients, not a design (owner-directed 2026-08-08)

The owner's framing, recorded before any design exists (verbatim core):
agents work by default on a branch of their cluster; commit-and-merge
makes changes live and visible to others; code and data merge together
(they are the same thing); the environment on the branch is fully
responsive "as if it was already real (because it always is)"; merging
is gated on tests and standards; agents iterate to resolve upstream
merge failures. And, on redefinition (2026-08-08 night): *"allow agents
to redefine anything with warnings that the redefinition won't be
accepted back into the main system if it invalidates data in the
database OR breaks existing contracts in other namespaces. So if the
agent wants to refactor and migrate data then we can accept it all…
we need to make sure that we are always storing the agent's run/session
data and anything else we need to remember the agent's work regardless
of whether they commit and merge… we are really only restricting
non-system data and functions, tests, and schemas from auto syncing."*

**Standing instruction: do NOT implement the first design.** Derive the
capabilities that must be true under ANY resolution, build those, and
let them define the eventual design's vocabulary.

## The truths that hold regardless of design

1. An agent's history (runs, turns, receipts, captures, messages) is
   never lost to a non-merge. WORK PRODUCT (functions, tests, schemas,
   non-system domain data) is what gating governs.
2. Redefinition on a private basis is free; VISIBILITY is what is
   earned.
3. A merge verdict is a measurement, never a diff heuristic: does the
   delta invalidate stored data; does it break contracts other
   namespaces hold; do the reaching tests pass; do the standards hold.
4. A migration (code + data transformation together) is acceptable as
   one unit when its measurements pass.
5. The agent iterates against merge failures with full context — the
   verdicts are renders, not process.

## The ingredients (each buildable now, design-agnostic)

1. **`my.branch` verbs + `seon.db` branch/commit reads** — designed and
   probed ([branch-verbs-design-2026-08-07.md](../research/branch-verbs-design-2026-08-07.md));
   the missing reads are a
   [filed issue](../../../seon/issues/seon-db-has-no-branch-or-commit-reads.md).
   Needed by root/ops regardless.
2. **The replay proof** — session curation's ruled `prove!`
   (re-execute an ordered delta on a fresh fork at a target basis,
   no model call). Owed to curation; it IS "merge = replay onto the
   current head, failures come back to the agent" if that design wins.
3. **Fact classification as a declared schema property** — system vs
   work-product is a property on the DECLARATION (Malli properties,
   ruling #47's mechanism), never a hand list or namespace convention.
   Truth 1 requires the distinction to exist and be queryable; every
   candidate design consumes it.
4. **Explicit commit destination at the effect door** — which branch a
   durable fact lands on becomes explicit request data (today implicit
   in the connection). Composes with ingredient 3: destination derived
   from the attribute's classification.
5. **Merge-impact queries** (each independently a live diagnostic
   today): (a) *data invalidation* — the usage guard generalized to a
   basis pair: which stored datoms fail the delta's schemas; (b)
   *contract breakage* — from the program graph: callers in OTHER
   namespaces of changed functions whose declared contracts changed
   incompatibly (accretion vs breakage per the sealed rules); (c)
   *standards* — every function has an example test beyond the
   generative one; every schema generates; both are Datalog now.
6. **Reaching-tests-at-basis** — the changed-tier selector already
   computes "tests reaching this delta"; parameterize it by basis so a
   gate can run it on a branch head. Mostly landed with the tiering.
7. **Branch lifecycle hardening** — the known `branch!` roster-race
   fork fix, retirement, per-branch GC surfaces. The test-infra fork
   constructor needs these anyway.
8. **Warnings as renders** — a branch agent's context shows derived
   "this will not merge because…" blocks computed from ingredient 5,
   omitted when clean (the reactive-context rule; nothing stored).

## Non-goals until the owner seals a design

No default-on branching, no merge verb exposed to agents, no
auto-sync policy, no visibility change for existing clusters. The
within-run schema-key ruling (guard-decides) stands as the interim
behavior on the shared basis until this design supersedes it.
