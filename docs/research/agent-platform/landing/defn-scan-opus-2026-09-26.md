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
