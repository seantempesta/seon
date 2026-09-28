# Per-file derivation reuse — evidence (boot-source-perf-opus, 2026-09-27)

Default's JVM at e999fdd1b, head 6ab9977e, warm, contracts armed. Forms:
`proof.clj` (JVM, throwaway branches) and `proof_sci.clj` (branch SCI edit).

## 1. What one file's derivation exercises (20 files, src/test, clj/cljc, 5–5,766 lines)

- Analysis: 92–112 armed definitions per file. Populate: 126–140. The union
  across both phases is about 210, and only 19 of them vary by file.
- Every file goes through database-owner plumbing. Analysis alone uses at least
  21 seon.db and 9 seon.schema.datahike definitions for every file.
- Per-file reach is therefore narrow compared with today's seal (464 static
  definitions), but it is the same for every file. Keying on each file's
  exercised set is almost the same as one key over the exercised set.
- Re-deriving an unchanged file writes one datom (`:seon.db/process`
  provenance). The stored entities equal the re-derived ones.

## 2. Output versus plumbing (stored call graph and trace)

The classes come from the stored call graph, applied to the exercised set:

- **Output (86):** reached from the value producers `seon.fn/analyze-entities`
  (rows) and `seon.fn/desired-entities` (the stored entity; `index!` writes only
  the difference between desired and stored) without entering seon.db or
  seon.schema.datahike. That covers seon.fn, seon.fn.analyzer, seon.program,
  seon.fn.signature, seon.id, seon.fs, seon.schema canonicalization, and the
  packaged forms.
- **Orchestration (25):** reached only from `populate-source!`: schema
  adoption, transaction compilation, reconciliation.
- **Plumbing (103):** reached only through a database-owner entry point. Its
  values reach rows only as read results or as refusals.

Today's seal misses 14 output definitions, including `desired-entities`,
`with-contract-facts` and `program-entities`. An edit to them does not re-derive
unchanged files today.

Natural experiments (definition digests between commits):

| change | defs changed | in today's seal | exercised output changed | result |
|---|---|---|---|---|
| P 6ab98892 -> head | 325 | 121 | 0 (orchestration: `index!`, an added refusal) | per-file-keyed tx = full tx (10,434 datoms), definitions equal |
| H 6ab99276 -> head | 35 | 3 | 0 | per-file-keyed tx = full tx (3,918 datoms), definitions equal |

Keyed/full ms, P->head (32 paths): analysis 2,577/10,375, populate 6,860/6,869.
H->head (18 paths): analysis 1,435/11,184, populate 3,501/5,032.

## 3. clj-kondo's disk cache

It holds per-namespace `:defs` signatures, not analysis output. `to-cache`
writes ns-data (`reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:65-78`).
`load-when-missing` reads `[lang :defs ns]` for the namespaces a run requires
(`:127-146`), and `sync-cache*` merges those for linting (`:171-208`). No
per-file analysis result is kept, so the store's published entities are the
only persistent cache for derived rows. Kondo's cache feeds cross-namespace
findings (unresolved-var, invalid-arity), a cross-file input.

## 4. Output-changing edit (SCI, throwaway branch `boot-perf-edit`, retired)

The edit makes `seon.fn.analyzer/literal-value?` stop treating strings as
literal. It changed the rows of 4 of the 20 files: db.clj, fn.clj, fn_test.clj
and vector_storage_test.clj. Keying on exercised definitions would invalidate
18 files; static reach would invalidate all 20. That is a sound superset, not
an exact set.

The install gate refuses edits to `test-markers`, `may-read-vectors?` and
`reads-cells?` even when the edit keeps behavior identical, because their
reaching tests are red in the gate's evaluation (`with-vector-world` and
`throwaway-test-var` are unresolved, and a fixture needs an execution handle).

## Gate evidence for task d653ed4b633f (install-gate-interprets-file-tests-in-sci)

On branch `boot-perf-edit` (e999fdd1b, 2026-09-27), the gate refused REPL installs whatever their behavior:
- a behavior-identical redefinition of `seon.program/test-markers` (with its docstring trimmed): the
  `seon.test-runner-test/a-huge-execution-failure-becomes-bounded-text` test failed with "Unable to
  resolve symbol: throwaway-test-var", and the fixture tests failed with "The canonical fixture
  needs an executing test handle".
- `seon.db/may-read-vectors?`: the
  `seon.schema.vector-storage-test/datalog-over-a-vector-attribute-means-membership` test failed
  with "Unable to resolve symbol: with-vector-world".
- `seon.schema.datahike/reads-cells?`: refused in the same way.
- `seon.fn.analyzer/literal-value?` has no gating test and installed.

## Cold-boot attribution and restart readiness (2026-09-27, disposable root `tmp/boot-source-perf/work-root`)

Recorded with JFR (`JAVA_TOOL_OPTIONS=-XX:FlightRecorderOptions=stackdepth=1024 -XX:StartFlightRecording=...settings=fast.jfc`,
2 ms Java sampling), start after changed files, classes hit.

| boot | changed inputs | source layer | ready |
|---|---|---|---|
| e686980fa move (before cd769af69) | 5 large src files | 52.2 s | 60.1 s |
| cd769af69 move | the same 5 + db_test | 6.8 s | 10.6 s |
| cd769af69, 18 files (20,896 lines incl. db.clj, fn_test) | 18 | 19.0 s (first), 5.5 s, then 7.9 s ready | 26.2 s, 8.26 s, 7.98 s |

- Before cd769af69 the writer thread spent ~25 s in `reconcile-tx-in`. Each
  stored-collection comparison read `carried-projection` of the transaction's
  value. That derived `loaded-builder`'s reach query on every read during boot
  (298 of 581 writer samples), and read `declaration-content-key` before the
  revision lookup (182 samples). This is the 4x cold multiplier.
- In the 5.5 s source layer of an 18-file boot:
  - keys + code identity: 31%. Most of it is `loaded-analyzer-digest`
    materializing the published database's projection (112 samples) and
    `analyzer-declarations` (132).
  - clj-kondo: 28%.
  - seon analysis: 26%.
  - populate: 9%.
- The first 18-file boot after the move took 26.2 s. Repeats of the same set
  took 8.3 s and 8.0 s. The cause is unmeasured; a stale kondo cache entry
  forcing a rerun is suspected.
- A work root moved by `start` after a nuke analyzed every dependency jar
  (`ensure-dependency-analysis!`, about 30 s on pool threads), because the
  nuke's archive kept a private `.clj-kondo/.cache` while the next archive linked
  the root's shared analysis cache. default keeps the shared cache and does not
  pay this.
