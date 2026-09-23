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

## TIMINGS (over 1 s)

| Operation | ms | Justification |
|---|---:|---|
| probe JVM `(require 'seon.schema 'seon.schema.edn 'seon.schema.datahike)` | 6,437 | compiles first-party source of the schema closure; dependency classes hit the shared cache; once per probe JVM |
| first `build-projection` over the live population | 10,780 | includes `require` of every predicate-owning namespace (524 loaded); the warm build is 289 ms unarmed |
| fresh-JVM HEAD load (system closure) | 15,674 | **over 10 s**: proportional to all first-party source, compiled from source by design (only dependencies are class-cached). Same class as `docs/seon/issues/from-zero-boot-takes-minutes.md`; row for the orchestrator to fold |
