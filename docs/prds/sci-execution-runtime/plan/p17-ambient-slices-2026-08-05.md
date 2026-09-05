---
type: prd
status: active
tags: [prd, runtime, schema]
---

# P17 call preparation — implementation slice order (2026-08-05)

P12 graduated 2026-08-05 (714 arities, zero incomplete; census in the
p12-indexing lane summary), so P17 is dependency-ready. The RULED
design is [ambient-injection-prd-2026-08-05-r2-draft.md](ambient-injection-prd-2026-08-05-r2-draft.md)
(header records the four rulings: SCI-fork hook seam; derived
all-or-nothing shorter arities with `db?`/`connection?` predicate
dispatch preserving ruling #41's positional shortcut; required-key-only
map injection into present top-level argument maps; providers as
declared rows). Slices, each one lane, in order:

1. **S1 — provider rows + plan derivation.** The `:seon.ambient/*` row
   schema, the two database providers, the Datalog plan query over P12
   facts, the cluster-local plan cache keyed
   `[function-identity contract-tx provider-basis-tx]` with the
   listener-plus-basis-comparison invalidation exactly as the r2 draft
   specifies. Proof: the draft's registry-derived and
   provider-publication-race falsifiers.
2. **S2 — the SCI hook primitive. LANDED 2026-08-08** (`1029a4de7`,
   fork `6ee57c9`; evidence
   [p17-s2-notes-2026-08-08.md](../research/p17-s2-notes-2026-08-08.md)).
   The optional call-preparation hook
   in the maintained fork routing both the analyzed call path and
   `kernel/invoke`; derived-arity preparation (all-or-nothing +
   predicate dispatch; declared arity always wins); required-key map
   fill; the three failure faces. Proof: the draft's behavior matrix
   (positional, map-key, explicit-caller, supplied-nil, undeclared,
   unavailable-with-body-counter, nested direct call, compiled
   first-party, two-cluster custody/plan isolation) plus the
   MANDATORY empty-plan hot-path benchmark before acceptance.

   Two things S3 and S4 inherit from how it went. **The fork gated its
   hook on `sci.lang.Var` and most first-party functions are bound as
   RAW host Vars**, so installing the state was necessary and not
   sufficient; any future binding path must keep callee identity
   visible. And the first live drive immediately exposed
   [`transact!`'s dynamic-var-conditioned return shape](../../../seon/issues/transact-returns-a-different-shape-depending-on-a-dynamic-var.md),
   which S4 owns together with the bespoke-elision deletion.
3. **S3 — `(doc f)` renders derived call shapes** as declared-looking
   arities (the ruled visibility half), and the program graph exposes
   them by query.
4. **S4 — the `seon.db` conversion sweep.** Delete the bespoke elision
   (`current-database-value`, `current-connection`, per-function
   realignment); the same positional-shortcut behavior now flows
   through the general planner; convert the named non-owner readers
   (the draft's explicit current-reader sweep list); prove no second
   ambient path remains.

Graduation is the r2 draft's gate section verbatim, ending in the
reset-boundary two-cluster live proof.
