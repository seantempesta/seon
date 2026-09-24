---
type: landing
lane: fix-nil-schema-predicate
status: landed-unadopted
created: 2026-09-24
report: docs/research/agent-platform/ambiguous-nil-producers-2026-09-24.md row S1
---

# Lane fix-nil-schema-predicate (2026-09-24)

What exists: `seon.schema/runtime-predicate` is the one resolver that may load a
predicate's namespace (`requiring-resolve`); `loaded-predicate-var` and
`converged-predicate-var` feed `compilable-form`. Malli's own resolution
(`reference-code/malli` gitlink 56394c54e34415a333d6281c4bfddf7122d02bd5,
`src/malli/core.cljc:2894-2900` `-loaded-qualified-value`, called by `m/eval` at `:2905`) resolves only a
qualified symbol whose namespace is already loaded (`find-ns` + `ns-resolve`) and
never requires. So no second resolver was added; the fix narrows the one catch.

## Broken or unproven first

- **Not installed.** Default runs a committed archive; the edit cannot reach the
  running JVM without adoption, and `bin/test-check default --test …` answers
  "The requested test is not eligible." **RESET NEEDED** (or `start --head` after
  commit), then `bin/test-check default --test seon.schema-test/a-predicate-namespace-that-fails-to-load-is-not-an-unresolved-predicate`.
- **Not a new declared Seon error.** The compile failure now propagates as
  `requiring-resolve`'s whole `Compiler$CompilerException` (class, message,
  `:clojure.error/*` ex-data, cause chain) — the "resurface the whole cause"
  branch of the catch law — distinct from nil → `:seon.schema/unresolved-predicate`.
  A wrapping `:seon.schema/…` error would need a schema in
  `resources/seon/schemas/seon.schema.edn`, which this lane does not own and no
  existing key fits (registry searched for load/compile/namespace errors). If the
  owner wants the wrapper: two entries beside `:unresolved-predicate` there, then
  `ex-info` with the CompilerException as cause in `runtime-predicate`.
- S2 (`converged-predicate-var` swallowing a `require :reload` failure) is a
  separate census row, not in this commit.

## Change (row S1)

- `runtime-predicate`: catches only `java.io.FileNotFoundException` (RT.load
  throws it unwrapped for the requested root namespace; a missing *transitive*
  require arrives wrapped in a CompilerException, so it propagates too). Gains a
  contract `[:=> [:cat :qualified-symbol] [:or :nil [:fn clojure.core/var?]]]`;
  the redundant `qualified-symbol?` guard moved into the contract (all nine
  callers pass qualified symbols).
- Regression `seon.schema-test/a-predicate-namespace-that-fails-to-load-is-not-an-unresolved-predicate`:
  a scratch classpath root handed through `Compiler/LOADER` (no broken source
  joins the project classpath) with a broken, a transitively broken and a fine
  namespace, via public `schema/core-predicate-registered?`. Nothing still means
  nothing: absent namespace and missing Var answer false.

## Evidence

- Live JVM probe (read-only, scratch namespace, removed afterwards):
  `requiring-resolve` throws `FileNotFoundException` alone for an absent
  namespace; `CompilerException` (`:compile-syntax-check`) for a compile error;
  `CompilerException` caused by `FileNotFoundException` for a missing transitive
  require; nil for a loaded namespace without the Var.
- All 66 predicate symbols in `packaged-forms` resolve in default under the new
  rule: no live population changes verdict.
- Cost (live, per call): resolved 0.052 µs before / 0.069 µs after (noise,
  sub-µs); absent namespace 188 µs before / 191 µs after.
- Snapshot JVM (`git archive HEAD` + this diff, shared `reference-code` and
  `.cpcache` linked, `clojure -M:dev:test`, contracts armed through
  `seon.test.arm/initialize-contracts!`, 1856 Vars, `runtime-predicate` armed):
  regression 6 pass / 0 fail twice (10 ms, 6 ms); on HEAD source 3 fail.
  `a-declaration-compiles-against-a-predicate-its-loaded-owner-lacks` 13 pass.
  Whole `seon.schema-test` + `seon.contracts-compile-test`: failure set identical
  to HEAD except the regression (25 errors are the fixture's "needs an executing
  test handle" outside `seon.test/run`, same on HEAD; 4 fails + 1 fail pre-existing).
  Arming 4.6 s (acquisition). Snapshot peak 3.8 GB.
- clj-kondo: 0 errors on both files.

## Size

src +12 −5, test +48.
