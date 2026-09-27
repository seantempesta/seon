---
type: design
lane: shrink-eval-opus
date: 2026-09-27
status: design for Astra review; no source edits
responds-to: docs/research/agent-platform/review-eval-shrink-plan-2026-09-26.md (slice 2 REVISE)
owning task: d215b1ebde7c acquisition-rebuilds-the-whole-program
source: HEAD b1112f457 (sci/eval.clj line numbers below are at this commit)
---

# Slice 2 design: acquisition cost follows the branch's changed rows

**What exists.** `base-ctx` memoizes one whole interpreted program per `base-ctx-key`. That key is a
connection-scoped revision basis, so every agent, MCP and test branch derives its own base: 235 ms mean, up to
1-3 s. The derivation reads the whole program: all function, namespace and test rows, two digest maps,
~460 namespace pulls and ~4,000 host Var copies. Only the rows that differ from the loaded program make the
branch's context differ from the loaded program's.

**Principal approach.** Split the one memoized value into two values of explicit inputs:

1. **The loaded base.** The acquisition of the JVM's loaded program commit itself. It is shared by every branch
   in the process and keyed by the loaded program's identity. By definition it has no overrides, so it is almost
   all host Var copies and namespace bindings.
2. **The branch delta.** The rows where the branch differs from the loaded program (function, test and namespace
   rows, compared regardless of authorship), plus their affected callers. They are installed on the receiving
   context's own SCI fork of the loaded base. They are never cached across branches.

The delta's cost is proportional to the changed rows, their reverse closure, and one diff read. The live
evidence is that all four bases held in the cache now (four agent branches) have `interpreted-count` 0. Their
whole derivation duplicates the loaded base.

## 1. Inputs, and which value owns each (Astra's table)

| input | read by | owner after the split |
|---|---|---|
| loaded program and host bindings (`loaded-source`, `loaded-program`, `build-base-ctx`, `install-first-party-namespaces!`, `install-caller-host-bindings!`, `install-declared-classes!`) | construction | **loaded base**, key `[store identity, loaded commit]` |
| deferred test namespaces becoming loadable (`load-core-namespaces!` defers `test` roots) | host binding | loaded base. **Open item A** below |
| branch function population (sym, source, admission, privacy, digest; symbol removal) | `acquire-program!` :2830-2870, `kernel/cache-program!` | **delta**: rows differing from loaded, and rows absent on either side |
| branch namespace rows (source, admission, requires, aliases, imports, refers) | :2800-2890, :3005 | **delta**: namespaces whose canonical row differs, or which are absent on either side |
| branch tests | :2882, :3083 | **delta**: differing, added and removed test rows |
| call graph / declared invocation targets (`seon.fn/reverse-closure`) | :2842 | **delta**: the closure is computed from the delta's seeds over the *branch* value, as today |
| pulled declaration facts (`install-row!` spec, arglists, host-bound, bindings) | :1717-1814 | **delta**: pulled from the branch value for delta rows only |
| projection content | `derive-base-ctx` :3372, wrappers | loaded base uses the loaded commit's projection; the **delta** arms against the receiving branch's projection (`fork-cluster-ctx` already re-arms interpreted wrappers for the receiving projection, :3764) |
| arming config (`instrumentation-config`) and supplied-default declarations (`call-preparation/snapshot`) | `install-function-contract!` | **delta** only: in the loaded base, compiled Vars carry the JVM's arming, and no row of the loaded base is interpreted. **Verify at the REPL** that no interpreted wrapper exists in a loaded base (`interpreted-count` 0) |
| fault recorder | wrappers, refusal recording | the loaded base records its own refusals once per `[loaded commit, target]`; the **delta** records through the receiving context's recorder. The recorder leaves the base key |

The loaded base's key is `[store identity, loaded commit]`. It is built by deriving from the loaded commit's own
materialization and the loaded program's projection. No branch content goes into it, so it needs no
completeness proof over branch inputs. What it reads is one immutable commit.

## 2. The delta and the branch law (`overridden-row?` fix)

`CLAUDE.md:125`: other branches interpret the rows that differ and their affected callers; authorship is not the
test. Today `overridden-row?` (:1697) and the `authored` filter (:2853) run the loaded JVM Var for a
file-derived row that differs. The delta replaces both:

```text
delta(branch, loaded) =
  {:functions  {sym → branch-row-or-::absent}  for sym where digest(branch) ≠ digest(loaded), or present on one side only
   :tests      likewise for test rows
   :namespaces likewise over canonical namespace rows}
interpreted = delta function syms that are present on the branch
              ∪ (reverse-closure branch-value interpreted-seeds)
```

- A host-bound delta row still refuses by name (`host-bound-row?`, unchanged). An absent row (a deletion)
  unmaps its Var on the fork.
- **Diff cost.** The branch side is one `function-digests` query. Datahike's query cache reuses it across the
  branch's own data-only commits (`query.cljc:2582`, attribute-dependency reuse within one connection
  generation). The loaded side is `loaded-digests`, which slice 1 retains per commit. Namespace and test rows
  are two small queries.
- **Open item B: completeness of "differs".** `:seon.program/definition-digest` omits admission, calls,
  host-bound status and analysis evidence (`program.cljc:373`). Two choices:
  - **(B1) recommended.** Diff by `[digest admission host-bound?]` read in the same query, and treat
    re-analysis without a source change as a program change on the branch that made it.
    - The re-analysing writer rewrites `:seon.fn/calls`. `acquired-database?` then sees a new delta identity
      only if the calls enter the identity.
    - So the delta identity uses the canonical row (`program/canonical-row`) **of delta rows only**. Rows
      outside the delta are equal by digest and are not canonicalized.
  - **(B2)** Canonicalize every row per commit. That is complete but O(program) pulls, the cost this slice
    removes.

## 3. The receiving context

`acquire!` for value V on context C:

1. `loaded ← loaded-program(V)`. `base ← (seon.cache/lookup ::loaded-base [store loaded-commit] …)`, a
   derivation of the loaded commit. Failures are not retained (`seon.cache/lookup` rethrows and removes).
2. `delta ← delta(V, loaded)`, and `identity ← [store, loaded-commit, delta identity, projection key]`.
   - `acquired-database?` becomes `(= identity (::acquired-identity snapshot))`. It is computed at most once per
     committed value (a `seon.cache` entry keyed by commit id: an immutable value, so it is never stale).
3. If the identity differs: fork the base (`copy-base-ctx`, unchanged), install the delta rows with
   `install-row!` in namespace order (the existing `namespace-order` code), then carry the context's own
   interns as `acquire!` does today (:3620-3640).

Private objects stay per world. An agent-authored data def (`(def a (atom 0))`, now a program row by ruling)
is in the delta, so it is evaluated on each branch's own fork and never shared. Compiled first-party roots are
shared exactly as today: every branch already copies the same JVM Var roots.

## 4. What disappears, and consumers converted in the same slice

| deleted | lines (eval.clj @ b1112f457) | its consumer, converted |
|---|---:|---|
| `revision-basis`, `program-basis`, `commit-id-of`, `remember-program-identity!`, `program-identity`, `program-identity-cache` | ~70 | `base-ctx-key`, `acquired-database?`, `record-refusals-once!` → the identity above |
| `watch-program-identity!` and both `d/listen` sites (`cluster-ctx*`, `fork-cluster-ctx`) | ~15 | nothing needs notification: identities derive from the value. Callbacks already registered live until the JVM restarts (every landing restarts); new code registers none |
| `acquisition-attributes` | ~18 | none left (`acquisition_attributes_test.clj` becomes the identity-collision test) |
| `loaded-basis`, `loaded-program-current?` | ~30 | the loaded commit id is in the identity; `acquire!` compares it |
| the lineage branch of `acquired-database?` | ~20 | identity equality |
| `base-context-cache` + `program-cache-policy` (core.cache atoms) | ~20 | `seon.cache` kind `::loaded-base`, weight `256 × installed Vars` (one byte budget, no per-kind dial) |
| `refusal-recording-cache` | ~5 | `seon.cache` kind keyed `[loaded commit or delta identity, target]`, retaining only successful recordings |
| the `authored` filter + `overridden-row?`'s admission test | ~15 | delta by difference |
| whole-program reads in `acquire-program!` for a delta acquisition | ~60 | `acquire-program!` splits into `derive-loaded-base` (the current body, run on the loaded commit) and `install-delta!` |

Estimate: −250 deleted, +140 added (delta, identity, cache kinds), net ≈ −110, in one loadable slice.
Tests converted in the same commit:

- `branch_release_retention_test` (private cache atoms → `seon.cache/retained ::loaded-base`, and inspect
  wrapper and recorder references);
- `acquisition_attributes_test`;
- `branch_execution_test`'s memo test (`base-ctx` twice → one `::loaded-base` miss).

## 5. Cost targets (measured before and after on the same probe)

| operation | today | target |
|---|---|---|
| first acquisition on a new branch with no delta | 235 ms mean (up to 1-3 s) | fork (~13 ms) + diff read (≤ 20 ms cold) |
| `acquired-database?` per form, same program | 0.06-3.6 ms | one identity lookup per new commit; ≤ 1 ms |
| loaded base derivation | same as today's derive (~400 ms) | once per loaded commit per process, or again after eviction |
| branch with k delta rows | whole derive | fork + O(k + closure(k)) installs |

## 6. Required regressions (Astra's six, mapped)

1. Sibling branches with equal content share one `::loaded-base` miss, before and after data-only commits.
   Eight branches, not four. Concurrent callers count one miss.
2. Different deltas give different results, and an unchanged transitive caller calls the branch's override.
   Cover: an older file-derived row across adoption (the branch-law fix, checking returned values), a
   host-bound override refused, a namespace change, function and test additions and retractions, and lazy
   first invocation.
3. A config, supplied-default or re-analysed call change on a delta row changes behaviour; unrelated data
   leaves the identity equal.
4. Projections with colliding fingerprints do not share delta arming. Adoption moves the loaded commit, and
   the old base is not used for the new commit.
5. Custody, fault routing and wrappers stay each branch's own. Private Vars, atoms and data-def roots do not
   leak between siblings.
6. A failed derivation retries. Eviction releases only `::loaded-base` values, never a live context.

## 7. Open items for Astra

- **A. Late host namespaces.** A deferred test namespace that becomes loadable under the same loaded commit
  (`seon.test/resolve-test` requires it) is absent from a base built earlier. Proposal: `install-delta!`
  also binds the host namespaces the receiving context names that the base lacks. This is the existing
  `host-namespace!` owner, per namespace, and no epoch is added.
- **B.** B1 vs B2 (above).
- **C.** Whether anything in `build-base-ctx` besides interpreted wrappers depends on the projection. If
  something does, the loaded base key adds the loaded commit's projection key (`seon.cache` `::projection`
  content key), which is still branch-independent.
- **D.** The one-argument `base-ctx` entrance keys the loaded identity as nil today (:3421). After the split,
  it resolves `loaded-program` before lookup, as the review asks.

## Verification limits

Line counts are estimates from function sizes, not a diff. The "four cached bases all `interpreted-count` 0"
figure is one read of the live cache (pid 59300), not a distribution. The first step of the slice is a counter
over a day's derivations: how many had an empty delta.
