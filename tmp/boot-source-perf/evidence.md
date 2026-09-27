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
