---
type: research
status: complete
tags: [research, schema, config, performance]
---

# Asking the declarations one question per item — measured and repaired, 2026-08-07

The sibling of the [bare-suite hang](parallel-turns-hang-cause-2026-08-07.md).
That fix killed the per-ATTRIBUTE instance at the Datahike encode seam; this
one kills the per-CONFIG-KEY, per-PRINT-OPTION, and per-REGISTRY-KEY
instances, at the callers that own them.

## What I read, end to end, before editing

- [packaged-forms-rereads-every-schema-resource-per-call](../../../seon/issues/packaged-forms-rereads-every-schema-resource-per-call.md)
  — the assignment;
- [parallel-turns-hang-cause-2026-08-07.md](parallel-turns-hang-cause-2026-08-07.md)
  — the fix shape this follows (resolve once and pass; deliberately NOT
  memoized);
- [seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md) — complete,
  including "Current versus pinned database values", "Derived state", and the
  deletion list;
- [env-phase1-w1-notes-2026-08-07.md](env-phase1-w1-notes-2026-08-07.md);
- `src/seon/schema/edn.clj`, the resolution chain in `src/seon/schema.clj`,
  and every first-party caller of `schema-definition`, `registered-schemas`,
  and `identity-attr?`.

## The class

`schema/candidate-forms` (`src/seon/schema.clj:590-599`) falls through to
`seon.schema.edn/packaged-forms` when no candidate overlay, packaged
population, projection state, or projection is supplied. That fallback
enumerates the classpath schema directory, reads all 152 EDN resources,
merges 1,885 declarations, and revalidates every key's placement — per call.

So a function shaped `(keep (fn [k] … (schema/schema-definition k)) keys)` is
`(count keys)` complete resource populations. Three such functions were live,
and the worst was not the one the issue named.

## Measurement

`tmp/repro/declaration_population_per_call.clj`, `clojure -M:dev`, median of 3,
reads counted at the one read seam (`schema.edn/read-schema-resource`). The
`per-item control` rows re-create the pre-repair shape in the same run, so the
comparison is reproducible from one checkout:

| Operation | Before | After |
|---|---|---|
| one unbound resolution (the floor) | 14.5 ms / 152 reads | unchanged |
| `seon.config` registration defaults | 1,003 ms / 12,464 reads | — |
| `config/default-decisions` (contains it) | — | 24.8 ms / **152 reads** |
| `config/default-population` | — | 16.4 ms / 152 reads |
| `print/default-options` | 67.9 ms / 912 reads | 11.3 ms / **152 reads** |
| `seon.reconcile` identity scan | 21,209–25,917 ms / 286,672 reads | 11.4 ms / **152 reads** |

The reconcile scan is the headline: `identity-attributes` asked
`schema/identity-attr?` once per registry key over all 1,885 keys, so one call
performed 1,886 complete resource populations — **twenty-six seconds** and a
quarter of a million file reads to answer a question about a map already in
hand. It is reached from `reconcile/plan`, which config application calls on
every apply.

`print/option-defaults` is the most frequent: `effective-options` calls it on
every `emit`, so every rendered value paid 912 file reads.

## The repair

Resolve once per operation and pass the value — the same shape as the hang
fix, and what the PRD's derived-state rule requires ("keyed by complete
identity … and read exactly once"). No memoization: `resource-population` is
pure on purpose, and a process-global cache of declaration facts is on the
PRD's deletion list. Nothing was added that Phase 3 must then delete; the
population is an ordinary immutable value passed as an ordinary argument.

- `seon.schema/declaration-population` — the public name for "THE population
  in hand for this operation", with the cost documented on it. Its body is the
  existing private `candidate-forms`; it introduces no second resolution rule.
- Population-taking arities where the question is not a plain `get`:
  `identity-attr?`, `valid-candidate-value?`, `explain-candidate-value`
  (accretion — the existing arities are untouched and still resolve one each).
- `seon.config` threads one population from each operation's entry point
  (`default-decisions`, `default-population`, `compile-manifest`,
  `read-manifest`, `apply-compiled!`, `effective`) through every helper. The
  helpers now REQUIRE it, so a future caller cannot silently reintroduce the
  per-key shape.
- `seon.print/option-defaults` and `seon.reconcile/identity-attributes` read
  one population and answer every item from it.

## A fourth instance, found only on a live cluster

The load-only measurement above missed one. On a booted cluster (isolated
operator root `tmp/decl-pop-operator`, clusters `declpop`/`declpop2`, never
the shared default), `config/default-decisions` still cost **83,144 resource
reads / 6,506 ms** after the threading landed — 546 populations. A stack
sample at the read seam named the culprit:

```
seon.schema$candidate_registry (schema.clj:675)
seon.schema$malli_form_QMARK_  (schema.clj:722)
  … malli internals …
seon.schema.datahike$malli__GT_datahike_attr_in (datahike.clj:220)
seon.schema.datahike$storable_attribute_in_QMARK_ (datahike.clj:274)
```

`seon.schema/malli-form?` is a REGISTERED CORE PREDICATE
(`register-core-predicate!`), so Malli calls it with the value and nothing
else. It cannot be handed a population, and it needs one to build its
registry — every attribute derivation therefore reads the whole classpath.
Threading cannot reach it; only the environment can, which is precisely
what Phase 1 is for.

Until then the operation SUPPLIES the population it already resolved, through
the existing per-operation seam `schema/call-with-forms`, for the extent of
admission. This is not a cache and not a second mechanism: it is the same one
value the operation resolved, made visible to a callee that cannot take an
argument, for one call. Measured live, identical result both ways:

| `seon.config/admit-initialization` | reads | ms |
|---|---|---|
| population threaded only | 82,992 | 6,495 |
| population also supplied | **0** | **11.6** |

## Live proof at the reset boundary

Schema and config-application code changed, so a fixture load path is not
sufficient. Own isolated operator root, fresh cluster, final code:

```
bin/seon --root tmp/decl-pop-operator init      # :current-src 6a767606-…
bin/seon --root tmp/decl-pop-operator start declpop2
● declpop2 boot: repl / store / branch / recovery / config
● declpop2 boot: program / work-launcher / agents / web
```

Through that cluster's own prepl: 65 effective config keys,
`:seon.config/on-core-error :panic`, `:seon.config.eval/time-limit-ms 30000`,
agents present — config compiled and applied by the changed code. And
`compile-manifest` = **152 reads / 26.8 ms — one population**.

One number from that probe belongs to the OTHER issue and is recorded there:
`config/effective` performs 84,664 resource reads, all of them inside
`db/pull '[*]` decoding the config row attribute by attribute.

## The class regression

`test/seon/schema/declaration_population_test.clj` counts reads at the one
read seam and asserts each operation performs exactly ONE population,
whatever its item count — the wanted behavior, not the deleted shape. It is
non-vacuous by construction: the first assertion fails if the unbound
resolution ever stops reading resources, and each operation's assertion fails
on the old code by the arithmetic above (12,464 ≠ 152). A second test asserts
that a population in hand is never re-resolved.

`bin/test seon.schema.declaration-population-test`: 2 tests, 6 assertions, 0
failures, 0 errors.

## What this does NOT do

- It does not memoize, and it does not make ONE unbound resolution cheaper.
  A single `schema-definition` with nothing supplied still costs 14.5 ms and
  152 file reads. The issue's acceptance criterion 1 ("N calls perform one
  population") is therefore met per OPERATION, not per process — which is
  what the sealed design permits. The remaining floor disappears when Phase 1
  supplies `:seon.schema/projection` from the environment at these call sites.
- The read side of `seon.db` is untouched and still resolves per attribute
  ([db-read-decoding-resolves-declarations-per-attribute](../../../seon/issues/db-read-decoding-resolves-declarations-per-attribute.md),
  Phase 3 scope).
- `src/seon/sci/eval.clj:1516` calls `program/with-contract-facts` without
  `:seon.program/schema-forms` while holding a projection whose forms it could
  pass. It is inert today (a projection is bound, so no disk read happens) and
  it is one call, not a loop — recorded here rather than edited, since that
  file is another lane's owner.

## Defect met in passing, fixed

`.clj-kondo/.cache/v1/cljc/seon.schema.transit.json` was a stale cache entry
from when `seon.schema` was a `.cljc`. It recorded `identity-attr?` as
one-arity only, and the CLJS branch of any `.cljc` linted against it — so the
edit hook BLOCKED a correct two-arity call in `seon/reconcile.cljc` with
`invalid-arity`, reproducible outside the hook. `seon.schema` has been
`src/seon/schema.clj` alone for some time; the entry was deleted and the lint
is clean. Anyone hitting an impossible `invalid-arity` from a `.cljc` should
suspect a stale language-specific cache entry for a namespace that changed
extension.

## Ugly output met (standing order)

1. **The failure mode has no face at all.** A function whose name and
   docstring read like a map lookup silently performed 286,672 file reads.
   Nothing logged, nothing warned, and the only symptom was a test that never
   finished. Both instances found so far were found by thread dump. A
   resolution that falls back to the classpath is worth one loud
   development-mode warning naming the caller — it is never what the caller
   meant.
2. **`clj-kondo`'s `invalid-arity` names the callee but not the evidence.**
   "seon.schema/identity-attr? is called with 2 args but expects 1" cannot be
   acted on when the source plainly declares two arities; it does not say
   which language branch or which cached analysis it came from. Half an hour
   went into finding the stale cache file by hand.
