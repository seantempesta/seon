---
type: landing
status: in progress
lane: shrink-schema-a (Opus 5.5)
created: 2026-09-23
spec: docs/research/agent-platform/shrink-schema-2026-09-23.md (e62646deb) §2 S2, S3, S4
---

# Lane shrink-schema-a

## Proof harness (shared by every slice)

- Probe JVM: `tmp/shrink-schema-a/src` = `git archive HEAD` (72281edae), `reference-code/*`
  symlinked to the shared checkouts, the shared dependency class cache
  `target/dev-dependency-classes/01c789a1…` first on the classpath (hit: no dependency
  compiled). A plain `clojure.main` with an io-prepl on port 57711; no cluster, no store.
  Evaluated through `tmp/shrink-schema-a/ev.bb`.
- Live population: read once, read-only, from `default` (pid 81369, running HEAD's
  `seon.schema`: `build-projection` at line 2172) in the throwaway namespace
  `shrink-a-probe`: `[?k ?form-string]` over `:seon.schema/key`/`:seon.schema/form` and
  `[?f ?spec-string]` over `:seon.fn/sym`/`:seon.fn/spec` → 3,397 forms, 1,979 contracts,
  60 ms, spilled to `tmp/shrink-schema-a/live-rows.edn`.
- Answers (`tmp/shrink-schema-a/p-answers.clj`): forms, contracts, schema dependencies and
  their reverse, function dependencies and their reverse, and `m/form` of every registry
  entry. HEAD's answers are held in the probe JVM as `A-head`; each slice compares with `=`.

## S2 — dead public vars (commit below)

Deleted from `src/seon/schema.clj` by one script (`tmp/shrink-schema-a/delete-defs.bb`,
rewrite-clj top-level form spans): `maintain-projection-delta`, `projection-delta-identities`,
`activate!`, `activate-projection!`, `current-keys`, `form-string`, `register-all!`,
`registered?`, `schemas-in-namespace`, `clear-all!`, `candidate-shapes`, `explain-shape`,
`explain-shape-in`, `canonical-data-fingerprint`, plus the private `reference-candidate-keys`
(clj-kondo "unused private var" at HEAD already). Net −196 lines, +0.

Zero callers, re-verified per name with `rg -n -F` over `src test script dev bin resources`
and `.agents docs/seon AGENTS.md`: the only hits were same-named locals (`form-string` in
`fn.clj`, a destructured binding) and `predicate-registered?` in `schema/edn.clj` (a distinct
private fn), and one dated issue row naming `register-all!`
(`docs/seon/issues/anonymous-runtime-contracts-have-recurred.md:192`, historical).

Proof:
- `clj-kondo --lint src/seon/schema.clj`: 0 errors; warnings are HEAD's minus the removed
  unused-private-var (diffed against `git show HEAD:src/seon/schema.clj`).
- Probe JVM: `(load-file "src/seon/schema.clj")` 301 ms; `build-projection` over the live
  population, answers `=` `A-head`: **true**.
- Fresh JVM from the snapshot with this file: `(require 'seon.cluster.boot 'seon.db 'seon.fn
  'seon.schema.edn 'seon.schema.admission)` loads, 478 namespaces, 15,674 ms.

S2 landed as `575b0d710` (accepted by the orchestrator).

Orchestrator ruling (2026-09-23, after S2): no probe JVMs or scratch roots. Proofs run on
`default`'s JVM only (`bin/test-check`, MCP `eval_clj` in throwaway namespaces). The S2 probe
JVM above was stopped; its evidence stands as recorded.

## S3a — Malli fork: a loaded function Var is returned as the Var (`dbe35560b`)

Fork `reference-code/malli` branch `seon-ref-scope`, commit `8725a8cb`, pushed to
`seantempesta/malli`. `-loaded-qualified-value` (`src/malli/core.cljc:2889-2896`) returned
`[@target-var]`, so `[:fn 'ns/pred?]` captured the function object and a re-evaluated `defn`
was invisible to an already compiled validator. It now returns the Var when its value is a
function (a Var is `IFn`), and the value otherwise. The value case stays because `:gen/gen`
names a generator Var (`generator.cljc:466-484`; fork test `loaded-qualified-generator`), and
that must stay a generator. The change is +3/−1 in `core.cljc`.

Fork regression in `eval-test` (`test/malli/core_test.cljc`): a validator compiled from
`[:fn 'malli.core-test/redefined-predicate?]` accepts 1. After `alter-var-root` of that Var to
`string?`, the SAME validator refuses 1. `clojure -M:test` over `malli.core-test`: 53 tests,
1,595 assertions, 0 failures, 0 errors. That was one fork-test JVM, started before the
no-parallel-JVM ruling and stopped once its summary printed.

Seon gitlink bumped `25710a67` → `8725a8cb`. `default` loads Malli from source
(`deps.edn:15`, `:local/root`), so it keeps the old `-loaded-qualified-value` until it is
moved to HEAD. At HEAD, Seon's `compilable-form` still replaces every `[:fn sym]` with the
Var before Malli sees it. The fork change is therefore observable in Seon only where Malli
resolves a symbol itself. The Seon half of S3 depends on it.

## S3b — Seon predicate machinery: blocked on file ownership (not started)

The machinery's public surface has callers outside this lane's paths:

| Var | callers outside `schema.clj` |
|---|---|
| `compilable-form` | `error.clj:1654`, `program.cljc:1059` (dirty: another lane), `fn/schema_shape.clj:110,429`, `instrument.clj:650` |
| `canonical-definition` | `fn.clj:688`, `fn/schema_shape.clj:81`, docstring `test/arm.clj:23` |
| `predicate-functions-in` | `error.clj:1654`, `fn.clj:2756,2814,2936`, `fn/schema_shape.clj:110,122,429`, `instrument.clj:408,621,632,652`, `sci/eval.clj:453,2848,2859` |
| `core-predicate-registered?` | `schema/edn.clj:507` (held) |
| tests | `contracts_compile_test`, `instrument_test`, `program_test`, `db_test`, `call_preparation_test`, `schema/projection_acquisition_test` |

Retiring these Vars is one loadable slice together with every caller (AGENTS: "Retire a public
Var and convert every caller in one loadable slice"). Keeping them as thin Vars
(`predicate-functions-in` returning `{}`, `canonical-definition` returning the authored form)
would leave shims.

## S4 — the same gap

Removed projection members still have readers outside this lane:

| Member | Readers outside `schema.clj` |
|---|---|
| `:seon.schema.projection/fingerprint` | `render/web.clj`, `render/walk.clj`, `sci/eval.clj` (S1) |
| `…/shape-index` | `call_preparation.clj` |
| `…/shape-rows` | `render/transcript.clj` |
| `…/compiled` | `render/walk.clj` |
| `…/schema-dependencies` | `instrument.clj`, `fn/schema_shape.clj` |
| `load-projection` | `db.clj` (the A2 half) |

`default` (pid 88504) runs S2 (`seon.schema/activate!` does not resolve; `build-projection`
at line 2146). It still runs the old Malli: `(-loaded-qualified-value 'clojure.core/int?)`
returns a function, not a Var. Its source snapshot is `data/source/e483b8ab…`.

## TIMINGS (over 1 s)

| Operation | ms | Justification |
|---|---:|---|
| probe JVM `(require 'seon.schema 'seon.schema.edn 'seon.schema.datahike)` | 6,437 | compiles first-party source of the schema closure; dependency classes hit the shared cache; once per probe JVM |
| first `build-projection` over the live population | 10,780 | includes `require` of every predicate-owning namespace (524 loaded); the warm build is 289 ms unarmed |
| `clojure -M:test` over `malli.core-test` (fork) | >300,000 wall (backgrounded at the tool timeout) | one fork namespace, 53 tests, run while the machine was thrashing; **over 10 s**, and a second JVM is now ruled out, so it is not repeated |
| fresh-JVM HEAD load (system closure) | 15,674 | **over 10 s**: proportional to all first-party source, compiled from source by design (only dependencies are class-cached). Same class as `docs/seon/issues/from-zero-boot-takes-minutes.md`; row for the orchestrator to fold |
