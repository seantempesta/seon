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

**Installed (after root's `start --head` to 3ff123708, which contains 9411e8640):**
`bin/test-check default --test seon.schema-test/a-predicate-namespace-that-fails-to-load-is-not-an-unresolved-predicate`
→ run d18688f04c81, executed 1, pass 6, fail 0, 775 ms.

## Follow-up 2 — bootstrap admission falsely refused any entity reaching `:inst`

- **Cause (REPL, live default):** outside an evaluation delta, `schema.edn/admit`
  with an identity takes `schema/assert-complete-contract!` with no handed
  projection. Its registry was `(mr/fast-registry compiled-forms)` — declaration
  FORMS — while every consumer reads an entry as a compiled schema.
  `assert-entity-partition!` (`(m/properties (mr/schema registry attr))`) then
  compiled `:inst` against Malli's defaults: `{:type :malli.core/invalid-schema
  :schema :inst}` → `::schema.edn/unresolved-reference` for
  `:seon.config/error`. Correcting only that reader exposed the same class in
  `internal/assert-error-declaration!` (`(mr/schema registry :seon.error/base)`
  handed a vector to `entity-entries`). So the producer — the registry — is fixed,
  not its readers: it is now `mr/lazy-registry` compiling each form in scope, as
  `projection-registry` does (Malli `registry.cljc:81-95` at the pinned gitlink,
  one cache atom per call).
- The missing projection is not an ambiguous nil here: outside a delta the
  caller hands the complete population, and the complete-population gate is the
  declared bootstrap rule; it now reaches a correct verdict.
- **Evidence (snapshot, armed):** regression
  `seon.schema-test/bootstrap-admission-compiles-referenced-attributes-in-its-own-registry`
  5 pass twice (1598 ms, 944 ms: two full-population admissions); HEAD 1 fail.
  `admit :seon.config/error` 165–374 ms after vs 695–1416 ms (refusing) before;
  `admit :seon.error/at` 186–286 ms vs 569–705 ms — the lazy cached registry
  compiles each reached form once instead of per reference. The regression's
  ~1 s is those two armed admissions over the ~1.3k-form population (named cost:
  `seon.schema.edn/admit` → `assert-complete-contract!`).
  `seon.schema-test`, `contracts-compile-test`, `schema.edn-test`,
  `schema.admission-gate-test`, `schema.admission-test`: failure sets identical to
  HEAD (the 25 + 4 + 4 errors are fixture custody outside `seon.test/run`).
- Size: src +10 −2 (5 of them comment lines), test +26.

## Follow-up 1 — `projection-with-schema` and `[:maybe X]`: premise refuted

- The claim that `projection-with-schema` admits a `[:maybe X]` value schema
  while activation refuses it does not reproduce. Live default (installed
  archive ce7384682 and again after the move to HEAD; `projection-registry` is
  unchanged between them): `projection-with-schema` over
  `projection-from-database` refuses `[:maybe :string]` (new key, `:agent` and
  `:core`), `[:maybe :seon.schema/key]`, and a replaced existing key
  `:seon.schema/key` as `:seon.schema/nilable-value-schema`, in 1.5–2.3 ms; the
  incremental path recompiles changed keys through the same
  `projection-registry` gate (`schema.clj` `projection-with-declarations` →
  `projection-registry` → `internal/assert-non-nilable-value-schema!`).
- No source change. One regression pins the single verdict:
  `seon.schema-test/a-nilable-value-schema-gets-one-verdict-at-build-and-replacement`
  (snapshot, armed: 2 pass twice, 103 ms / 17 ms). Test +20.
- **Out-of-scope finding:** `[:or :nil :string]` as a named value schema is
  admitted by build, replacement and `register!` alike, while
  `internal/nilable-union?` treats that spelling as nilable for map values.
  `assert-non-nilable-value-schema!` (`src/seon/schema/internal.cljc`) checks only
  `:maybe`. Not mine; owner: internal.cljc.

## Follow-up 3 — one core guard: an undefined slot must justify itself

- Owner ruling 2026-09-24: core may use `:any`/`:some` only with the recorded
  justification. `seon.schema.admission/core-polymorphism-finding` runs, for a
  `:core` request, on each changed declaration that `registration-finding`
  admitted, and blocks as `:schema-polymorphism` naming the slot's path. The
  judgement is `internal/permissive-positions`' existing `justified?`
  (polymorphic-boundary exemption + nonblank reason + a generator property) —
  the same rule `seon.fn` already reads; no second walker.
- Live default: 0 of the packaged declarations carry an unjustified
  `:any`/`:some`, so no current file gains a finding.
- **Divergence to rule on:** `register!`/activation with `:core` source still
  admit a bare `:any` (only the hook refuses it), so
  `one-contract-text-gets-one-verdict-at-every-entrance` holds only because its
  cases carry no bare core `:any`. One verdict everywhere would move this check
  into `internal/assert-complete-schema!` (not mine).
- Evidence (snapshot, armed, guard armed): regression
  `seon.schema.admission-gate-test/a-core-undefined-slot-must-justify-itself`
  5 pass (6 ms); HEAD admission 3 fail. Whole namespace 10 tests, 41 pass, 0 fail
  twice (5.6 s, 6.1 s — whole-namespace, dominated by
  `one-contract-text-gets-one-verdict-at-every-entrance` building the packaged
  projection).
- Size: src +35 −2, test +30.

## Totals and limits

Commits 9411e8640 (S1), ab7cc7190 (bootstrap registry), the follow-up-1 test
commit, and the follow-up-3 commit. Net: src +57 −9, test +124. Follow-ups 2 and 3
are proven only in the snapshot; default runs 3ff123708 (row S1 only).
**RESET NEEDED** / `start --head`, then:
`bin/test-check default --ns seon.schema.admission-gate-test` and
`bin/test-check default --test seon.schema-test/bootstrap-admission-compiles-referenced-attributes-in-its-own-registry`.
