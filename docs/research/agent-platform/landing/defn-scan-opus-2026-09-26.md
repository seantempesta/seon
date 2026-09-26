---
type: landing
lane: defn-scan-opus
date: 2026-09-26
status: committed 9b6df1ff4; not adopted (RESET NEEDED); the regressions are not yet proven on a JVM
---

# Landing: per-call projection scans in a defn, and require-sensitive digests (2026-09-26)

## Broken first

1. **Not adopted. RESET NEEDED.** Default (pid 89341) runs `bb07fdf08`, and lanes may not redefine default's Vars.
   So neither fix has run on the live agent path. The two new tests are not in default's program:
   `bin/test-check default --test seon.program-test/a-require-a-definition-does-not-use-leaves-its-digest-unchanged`
   answered "The requested test is not eligible." They cannot run before the restart.
   - What was proven: an unarmed scratch copy of the new digest code ran on default and passed all four regression
     cases.
   - What is projected, not measured: the defn after-numbers below, which come from per-call costs.
2. **The defn is still not sub-second, and this lane's fix is a minor part of the cost.** On pid 89341, 5 contracted
   defns on branch `defn-scan-opus` measured:
   - p50 1.6 s with root's test runs idle, and 3.5 s during them;
   - 1.66-2.13 GB allocated JVM-wide per form;
   - **166,000 armed calls per form.**

   The main cause is outside my files. A contracted defn writes `:seon.fn/spec`, which is a projection range
   (`schema.clj:3057` `projection-ranges`). So every defn produces a **new projection value**
   (`seon.schema/load-projection` runs 1.6 times per defn) with a fresh, empty holder. Every whole-projection table
   then derives again over about 3,550 forms, although a function contract changes none of them. Armed calls per
   defn:
   - `seon.schema.internal/reference-id`: 26,000;
   - `entity-maps`: 22,800 (a stack sample puts it under `seon.db/write-entity-schemas` → `projection-cache-value`);
   - `schema.datahike/compiled-storage`: 9,500;
   - `storable-attribute-in?`: 7,500.

   Task proposed in `tmp/defn-scan-opus/tasks.edn` for `seon.db.edn`. Owners: db.clj and schema.clj (held).
3. **Adopting 9b6df1ff4 changes every stored function and test digest once.** The resolver context inside the digest
   changes shape. The first boot or adoption after the restart therefore republishes the whole program (compare the
   ~80 s of the c029d48cb boot) and reselects tests once. After that, a require edit moves only the digests of the
   definitions that use it.
4. **A `::probe-memo` key was written** into the holder of the branch projection by one JVM-mode probe. It is
   harmless, and the branch is retired.

## Defect 1: `shapes-in` walked every form on every call (task `400bcf52dd53`, part)

**Reproduced** on pid 89341 with 5 contracted defns (`(defn dscanN "doc" {:malli/schema [:=> [:cat :int] :int]} [x]
(inc x))`) on this lane's branch. Armed calls per defn:
- `seon.program/shapes-in`: 9.4;
- `source-family?`: 33,351 (9.4 × 3,548 forms);
- `declaration-families`: 0 (its callers are in `seon.test`).

`shapes-in` costs 15.5 ms and 1.67 MB per call under load. `declaration-families` costs 14.3 ms and 1.65 MB.

**Cause.** `shapes-in` (program.cljc:257) is a function of the projection value, but it filtered all of
`:seon.schema.projection/forms` on each call. Its callers each asked again:
- sci/eval.clj:912, 1489, 1732, 1970, 2610;
- fn.clj:2589, 2683, 3621, 3909, 3935;
- program.cljc:1257.

`seon.db` memoized its own copy under `::program-shapes`, a second cache for the same answer. The sampler caught
that copy missing on each new projection (`write-digest-error` → `projection-cache-value` → `shapes-in`).

**Fix.** `shapes-in` and `declaration-families` now memoize themselves on the projection's own holder
(`seon.schema/projection-cache-value`), the way `program-attributes` already did. Each projection value has one walk,
whoever asks. The duplicate memo in db.clj is deleted by the patch `tmp/defn-scan-opus/db-program-shapes.patch`
(db.clj is held by one-commit). It applies cleanly.

**Regression.** `seon.program-test/program-shapes-derive-once-per-projection`:
- `shapes-in` and `declaration-families` return the identical value per projection;
- another projection derives its own shapes.

**Projected after:** about 2 walks per defn instead of 9.4. That saves about 26,000 armed calls, about 12 MB and
50-110 ms per defn, depending on load. It is not measured, because the fix is not adopted.

## Defect 2: a require edit moved every digest in its namespace (task `434fed5a3a67`, from perf-boot)

**Reproduced** on default's stored rows (JVM mode, read-only). Swapping `seon.program`'s `seon.cache` require moved
**57 of 57** `seon.program` function digests with the whole-context digest. With the used context it moves **1**:
`authored-shapes`, the only function that calls `seon.cache/lookup`.

**Cause.** `definition-digest` (program.cljc:384) hashed the namespace's whole resolver context, which both producers
supply:
- `seon.fn` analysis, fn.clj:833;
- `seon.fn/stored-namespace-context` via `db/definition-digest-of`.

The row already carries what the definition resolves:
- `:seon.fn/calls` and `:seon.fn/references`, which hold every resolved var usage (fn.clj:314-339);
- `:seon.fn/keywords`, where clj-kondo resolves `::alias/kw`.

`canonical-row` keeps these attributes, so the writer recomputes the same digest (checked on stored function and test
rows).

**Fix.** `used-resolver-context` keeps:
- the requires and aliases of the namespaces those usages name;
- the refers of the used symbols;
- all imports, because the analysis records no class usage.

Retargeting a used alias changes the resolved usages, so it still changes the digest.

Known limit: dropping a require that the definition never names, such as a side-effect load of multimethod
implementations, no longer changes that definition's digest. The namespace row's own digest still changes.

**Regression.** `seon.program-test/a-require-a-definition-does-not-use-leaves-its-digest-unchanged`:
- adding or removing an unused require, alias or refer leaves the digest unchanged;
- retargeting the alias that a call or a keyword resolves through changes it.

The scratch copy passed all four cases on default.

## Files, sizes, checks

- Commit `9b6df1ff4`: `src/seon/program.cljc` (+44/−19) and `test/seon/program_test.clj` (+44). Net source is +25:
  one 17-line function and the two memo wrappers. The db.clj patch removes 1 line net.
- `clj-kondo --lint src/seon/program.cljc test/seon/program_test.clj --cache false`: 0 errors, and no new warnings in
  the changed regions.
- `seon.contracts-compile-test/check` over both files against default's projection: `[]`.
- The subject mark: task `400bcf52dd53` has no `:seon/tasks` mark in code, so there was nothing to remove. It stays in
  `seon.program.edn` for root to settle; the remaining cause is proposed as the new `seon.db` task. Task
  `434fed5a3a67` was never appended, because this commit fixes it.

## Root runs after the restart

1. Apply `tmp/defn-scan-opus/db-program-shapes.patch` with the one-commit lane's release, or before the restart.
2. `bin/test-check default --test seon.program-test/program-shapes-derive-once-per-projection`, then
   `bin/test-check default --test seon.program-test/a-require-a-definition-does-not-use-leaves-its-digest-unchanged`.
3. `bin/test-check default --ns seon.program-test`, plus a platform check that the writer accepts defns (a digest
   mismatch between producer and writer would refuse every defn):
   - `seon.cluster.program-change-test`;
   - `seon.cluster.evaluate-source-test`.
4. Time the full-republication boot this causes once.
5. Re-measure with `tmp/defn-scan-opus/probe.clj`, `sym-counts` over `(defn-sources 5)` on a fresh branch.
   Expected: `shapes-in` walks ≤ 2 per defn, and `source-family?` ≈ 3.5-7k per defn instead of 33k.

## Evidence

In `tmp/defn-scan-opus/`:
- `probe.clj`: `call-counts`, `sym-counts`, stack sampler;
- `digest-check.clj`: the unarmed copy and the sensitivity count;
- `tasks.edn`;
- `db-program-shapes.patch`;
- `test-digest-before.txt`.

Branch `defn-scan-opus` is retired, and the scratch namespaces were removed from default.

## Follow-up (root's second and third asks): why a defn changes the projection at all

**Stopped before production edits: the cause is clear, but the fix is a cross-owner decision.** Three priced options
follow. The working tree carries none of my changes; the prototypes are saved as patches (see the end of this
section).

### Measured: the contract, not the defn, costs the time

4 defns of each kind on branch `defn-scan-opus-2`, default pid 89341, quiet machine, `sym-counts`. The last three
columns are armed calls per defn.

| form | p50 | allocated | armed calls | `load-projection` | `content-projection` | `source-family?` |
|---|---:|---:|---:|---:|---:|---:|
| `(defn f [x] (inc x))` | **134 ms** | 157 MB | 26,300 | 0 | 0 | 7,100 (2 × `shapes-in`, fixed by 9b6df1ff4) |
| `(defn f "doc" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` | **757 ms** | 1.21 GB | 189,900 | 1.75 | 5.75 | 40,800 |

A contract adds about 620 ms, 1.05 GB and 164,000 armed calls per defn.

### (1) Where `:seon.fn/spec` enters the projection

- `schema.clj:3054-3061` `projection-ranges`: `:seon.schema/function-contract-rows [:seon.fn/sym :seon.fn/spec false]`.
- `projection-attributes` (`schema.clj:3063`) therefore includes `:seon.fn/sym`, `:seon.fn/spec` and
  `:seon.schema.admission/source`. `seon.db/projection-cache-key` (`db.clj:1303`) and `declaration-content-key`
  (`db.clj:1328`) are keyed on those attributes.
- `build-projection` and `projection-with-declarations` compile each contract into the ONE registry
  (`projection-registry`, schema.clj:524). They index `function-dependencies` and `reverse-function-dependencies`,
  and fingerprint `:function-contracts` and `:function-admissions`.
- So a contracted defn produces a new projection value with a fresh, empty `:compiled` holder. That triggers:
  - `load-projection`, 36-50 ms and ~94 MB per load, 16 ms and 79 MB of it `deleted-predicate-functions`
    walking every form;
  - every derivation keyed on the projection. A first call on the new projection cost:
    `cluster/declared-attributes` 43 ms, `error/observation-selector` 30 ms, `program-attributes` 9.6 ms,
    `db/write-entity-schemas` 7.2 ms, `db/write-attribute-plans` 5 ms, `call-preparation/contract-arities` 4.5 ms,
    `identity-only-descriptors` 2.5 ms, `declared-schema-keys` 1.4 ms, `retention-rules` 1.1 ms.

  An uncontracted defn changes none of this: 0 loads were measured.

### (2) What reads function contracts from the projection

The 15 sites outside schema.clj, all 4 slow tables above excluded:
- test.clj:2154-2155;
- fn.clj:3264;
- turn.clj:1350;
- render.clj:475, 863, 888;
- instrument.clj:596, 729, 769 (arming);
- call_preparation.clj:169;
- sci/eval.clj:943, 2049, 4800;
- render/web.clj:965.

Inside schema.clj, function contracts appear in about 100 lines: registry, dependencies, fingerprint,
`validate-declarations`, `function-arities-in` and render-contract assertions.

The one thing that needs contracts *beside* the data shapes is the unbreakable-graph refusal: a schema change that
breaks a contract referencing it is refused (`reverse-function-dependencies`). Nothing that reads data shapes needs
the contracts.

### (3) Options

1. **Recommended: contracts leave the projection.**
   - What changes:
     - `projection-ranges` drops `:function-contract-rows`, so the projection is the data shapes alone.
     - A contract compiles on demand against the unchanged projection:
       `(seon.cache/lookup ::contract [contract-form forms] …)`, using `m/schema` with the projection's registry
       options. Malli owns the compile; the cache key is the form plus the population identity.
     - The 15 readers take the contract from the fn row they already hold (`:seon.fn/spec`) or from that cache.
     - The schema-change refusal compiles only the contracts that the stored `:seon.fn.contract` facts name for the
       changed keys. That is a rare path, proportional to the change.
   - Guarantee: a defn leaves the projection identical, so there are 0 loads and 0 table re-derivations. The
     expected contracted defn is about the plain defn's 134 ms plus the contract's own compile and arming.
   - Cost: 1-2 days. It crosses the owners of instrument, sci/eval, turn, test, render, fn, call-preparation and db
     (the projection-attributes key). Net deletion is likely: the function branches of build, replace, fingerprint
     and validate, about 150-250 lines in schema.clj.
   - Sacrifice: a projection no longer answers "which functions break if this schema changes"; that becomes one
     query at schema admission.
2. **Contracts stay in the projection; data-shape derivations are keyed by the forms they read.**
   - What changes: `schema/projection-derivation`, which is `projection-cache-value` backed by
     `seon.cache/lookup [key code-class (members read)]`, and the replacement keeps the identity of the forms map
     when no form changed. `load-projection` walks only re-read definitions for deleted predicates.
   - Prototype: `tmp/defn-scan-opus/projection-derivation-mine.patch`, about +60 lines, with the 4 sites I own
     converted.
   - Guarantee: a contract-only change reuses every converted table. The load still runs (about 20 ms after the
     predicate fix), and contract-reading derivations recompute.
   - Cost: about half a day, plus one-line patches to about 20 derivation sites across 6 owners.
   - Expected result: roughly 100-200 ms and a few hundred MB off a contracted defn. It does not remove the
     projection churn.
   - Sacrifice: each converted site asserts "reads only forms", and a wrong assertion answers stale data.
3. **Smallest: share the holder on a contract-only replacement.**
   - What changes: `projection-with-declarations` hands the base's `:compiled` holder to a candidate whose forms,
     admissions of schemas and predicate bindings are unchanged. The contract-reading entries key on the contract
     form: `[::function-arities sym]`, output alternatives, declared output validators, `contract-arities`.
   - Cost: about 2 hours and about 30 lines; patches for call_preparation, render and error.
   - Guarantee: derivations do not churn. The load and the new projection value remain.
   - Sacrifice: correctness rests on auditing every holder key. A missed contract-reading entry answers stale
     contracts silently, so this is the kind of invalidation-by-convention the owner rejected.

### State

- Prototypes, reverted from the tree:
  - `tmp/defn-scan-opus/projection-derivation-mine.patch`: option 2, plus the `load-projection` deleted-predicate
    walk over re-read definitions only, which applies to any option;
  - `tmp/defn-scan-opus/db-program-shapes.patch`, still pending for db.clj.
- **Foreign hunk in schema.clj.** schema.clj holds an uncommitted hunk that is not mine: `canonical-value-string` as a
  local `letfn`, presumably perf-boot's. My WIP revert briefly checked the whole file out; I restored that hunk
  byte-exact from `tmp/defn-scan-opus/projection-derivation-wip.patch`, and it is intact.
- Branches `defn-scan-opus` and `defn-scan-opus-2` are retired, and the scratch namespaces were removed.
