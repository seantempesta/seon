---
type: research
status: reviewed — approved for the gate with one required follow-up
created: 2026-09-17
tags: [review, orchestrator, call-graph, program-graph, selection]
---

# Orchestrator review — call-graph fidelity fix (`15a35c2a7` … `94a2836ca`)

Read: the landing note (267 lines) in full; the analyzer attribution hunk
(`15a35c2a7`), the declared-function-values commit stat (`af800d1a0`), the
reference-facts and selection-widening hunk (`7eeed900d`), the stats of
`7907afc7a`, `b13705fc7`, `9a0ae9bc9`; the four fixture regressions' names.

**Accepted.**
- Slice 1: `:protocol-impls` consumed; kondo's `:defmethod` and
  `:dispatch-val-str` retained; body usages attributed to the protocol method
  or multimethod identity by innermost span containment
  (`attributed-usages`, `src/seon/fn/analyzer.clj:323`). This is the
  span-join the research asked for, done in the analyzer, not a name rule.
- Slice 2: calls derived from declared function values and Var quotes
  (`#'f` in graph definitions; capability handlers; schedule task functions,
  whose schema now declares the target identity) — declared-symbol edges
  from facts the system already stores.
- Slice 3: unresolved call shapes retained as `:seon.fn/references` on the
  caller, and, when no first-party caller can be attributed, as
  `:seon.fn/unresolved-references` on the file's namespace row; selection
  widens through them (P3: unknown never reads as "no test needed").
- Interim measured on `default` (hot-reloaded, partially adopted): functions
  with no incoming edge 409 → 301; public functions with no reaching test
  312 → 170; capability handlers with zero reach 8 → 0; `seon.print`
  zero-reach 28 → 14. No key changed meaning; no reset.
- Boundary honestly stated: six smoke runs refused with the typed unknown
  (default's program lacked `:seon.fn/destroys` facts until adoption), the
  S1 parity regression unverified in process, the final publication waiting
  on the reachability permit during a store collection. The cold gate is the
  proof.

**Required follow-up before S9 stage 1 relies on selection:** the widening
is too coarse. With references counted as reach, `gate-set` for
`seon.fs.jvm/read` went from 0 tests to 1,016, `seon.id/digest` 495 → 1,184,
`seon.db/q` 1,058 → 1,220, and the whole-graph coverage query 286 ms →
4,240 ms. A selection that answers "most of the suite" for a leaf change is
correct but useless as "the minimum implied by what changed". Widening
through a reference must be scoped: a reference edge participates in reach
only for targets that have NO resolved call edge into them from the same
caller set, and an unresolved file-level reference widens to that file's
tests, not to every test. Measure selection size for ten single-function
changes before and after; the regression asserts the exact selected set.
This is a slice on the same owner; it does not block the gate.

**Gate requested:** batch 106 = platform, then `seon.fn-test
seon.program-test seon.fn.analyzer-test seon.test-reaching-test`.

## Addendum — `3f0be21ed` and `51d904a9b` reviewed (orchestrator, 2026-09-16 21:05Z)

Read in full: both diffs to `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`,
`src/seon/test/selection.clj`, `src/seon/issue/detect.clj`, the test changes,
and the landing note's follow-up sections.

**Approved.** Macro usages are reference edges, never calls with arity; an
unresolved file reference selects only that file's own tests instead of the
whole suite; `gate-sets` acquires the declared and file relations once per
operation and hands them to each walk; parser findings are preserved by
binding the kondo reader's exception atom; `assert-clean-analysis!` now runs
before the manifest is built (this is what caught my own missed caller in
`8d48f1c51`). The first version's reference *fallback* (references consulted
only at a target with no resolved caller) was refused in review as reading
less than the writer admits; `51d904a9b` unions calls and references in the
indexed walk, the Datalog rules, and manifest selection, and the mixed-caller
regression requires the tests of both callers.

Measured by the lane, one read-only evaluation on default: bulk selection
8.877 s → 8.449 s with the union; default then held zero
`:seon.fn/references` facts, so the populated cost is measured by batch 106
on the converged base. The lane is stopped; gate 106 is next.
