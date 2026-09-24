---
type: landing
status: landed
lane: fix-fn-correctness
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/reading-2026-09-24.md (namespace clauses); docs/research/agent-platform/fail-loud-dev-resilient-prod-2026-09-24.md (row 15)
---

# Lane fix-fn-correctness — 2026-09-24

This lane fixed three defects in `seon.fn`.

- `namespace-context` now reads `ns` clauses through the one reader, `seon.sci.reader/read`.
- A file deleted between listing and reading is now the declared publication refusal.
- The unreachable `span-refused!` is deleted.

## Broken or unproven first

- **Namespace reading got more expensive: 310 MB of allocation per full pass instead of 13 MB.**
  - The reader's `clojure.core.specs.alpha` conform costs about 0.7 MB per `ns` form. The old parser used 0.03 MB.
  - A full pass over 437 files took 133 ms at best, against 7–34 ms before. That is more than 50 ms.
  - Allocation is stable. The times are noisy because the machine's load average was about 16.
  - An incremental publication pays about 0.3 ms per changed file.
  - This lane did not fix it. The only cheaper route is a second parser, which is the defect being fixed. The cost belongs to the reader.
  - Filed as `docs/seon/issues/reader-ns-conform-allocates-per-namespace-form.md`.
  - The whole-publication share of this cost was not measured.
- **Default does not run commit 78161085b yet.** Default restarted at 16:02:59Z on a HEAD that already had fa13c41a8 and 7c761e0c9, but not 78161085b.
  - The namespace-clause fix is proven only in a `git archive` snapshot.
  - Default picks it up at its next `start --head`. No RESET NEEDED.
- **Installed `bin/test-check default --ns seon.fn-test` is red: 8 failures and 15 errors** (run c041ee6ff636, 44 s).
  - Many fixture writes are refused with `:seon.program/definition-digest :seon.error/unknown`.
  - One refusal names the edge `:clj-kondo/unknown-namespace/visitFile`.
  - There is also one `StackOverflowError` and one `:malli.core/invalid-schema`.
  - None of these names a function this lane changed. No pre-change installed baseline exists, so they are **not attributed**.
  - In the snapshot (plain `clojure.test`, no custody), the reds are the same before and after every commit: 43 custody errors and one `packaged-forms-calls` failure (2 instead of 1).
- **Residue: imports still come from `fn.clj`.**
  - `import-bindings` still reads `:import` specs from the form that the reader returns, because the reader has no import grammar.
  - Kondo records imports as `:java-class-usages` with `:import true`, but `seon.fn.analyzer` drops that key. That file is outside this lane.
- **Test deleted: `source-context-is-derived-once-per-file-population`.**
  - It used `with-redefs` on the deleted `read-jvm-form`, which is also forbidden by law.
  - What it checked still holds structurally: `analysis-rows-by-file` builds `namespace-contexts` once per namespace definition. No test replaces it.

## Commits

1. **fa13c41a8 — a deleted file is the declared refusal.**
   - `source-context` catches only `NoSuchFileException`.
   - It throws `:seon.fn/index-refused-error` with:
     - phase `:analysis`
     - `:seon.cluster.boot/disposition :refused`
     - `:seon.source/changed-paths`
     - the exception as its cause
   - This is the shape efe39880e uses.
   - The file becomes a removal on the next refresh, when `observed` no longer has it.
   - Regression: `a-file-deleted-after-listing-is-the-declared-refusal`.
2. **7c761e0c9 — `span-refused!` deleted.**
   - Every analyzer caller hands kondo the captured text:
     - `::analyzer/sources` at `analyzed-artifacts` and the file artifact
     - the synthesized stdin buffer at `runtime-analysis-batch`
   - So spans describe the sliced bytes by construction.
   - The docstring's claim that "the analyzer reads every file itself" was false. Only a test-built `::paths` analysis could reach the refusal.
   - Deleted: `span-refused!`, `current-file-digest`, the guards in `character-offset`, `exact-source` and `exact-form-span`, and that test.
   - The existing class regression `a-file-changed-after-capture-analyzes-to-the-captured-spans` stays.
   - An out-of-range span is now an analyzer defect and faults loudly with its index exception.
3. **78161085b — clauses from the one reader.**
   - `namespace-context` takes `:seon.ns/requires`, `aliases` and `refers` from the `seon.sci.reader/read` namespace event and passes them through `stored-namespace-context`.
   - Imports come from that event's `::reader/form`.
   - A reader failure is now a loud refusal, not silence.
   - `read-jvm-form` and the hand clause parser are deleted.
   - A bare dotted import (`java.util.UUID`) now binds `UUID`, as `clojure.core/import` does. It used to bind `java.util.UUID`.
   - Regression: `namespace-context-reads-clauses-by-clojures-own-ns-grammar`. It covers:
     - a prefix list with a bare member
     - `[clojure [edn :as e]]`
     - `:as-alias`
     - `:refer` with `:rename`
     - vector and bare imports

Also updated, at root's request: `docs/seon/issues/runtime-lint-does-not-resolve-namespace-aliases.md` is now **resolved**.
- Owner: `seon.fn/runtime-analysis-batch`.
- A live probe showed `analyzer/program-prelude` resolving to `seon.fn.analyzer/program-prelude`, with no findings.
- Residue: the synthesized form gets a bogus `[analyzer]` require.

## Evidence

- **Snapshot** `tmp/fix-fn-correctness/snap`: `git archive` of HEAD plus this diff, with `reference-code` and `.clj-kondo/.cache` symlinked. Tests ran under `call-with-projection` of the packaged projection.

| Test | With the fix | Against the file before the fix |
|---|---|---|
| deleted-file refusal | 5/5 pass | error `NoSuchFileException` |
| namespace clauses | 5/5 pass | 3 failures and 1 error (see below) |
| full `seon.fn-test` | no new red at any commit | — |

- The namespace-clause failures before the fix were:
  - requires `#{clojure.test clojure}`
  - no `e` alias
  - `java.util.UUID` bound as its own local
  - `No value supplied for key: [edn :as e]`
- **Installed** (default, pid 46768): `bin/test-check default --test …deleted… --test …changed-after-capture…` passed 11/11 in 971 ms (run 555c4ccb5e61).
- **Equivalence**: `namespace-context` gives identical requires, aliases, refers and imports, old versus new, for all 437 `src`/`test` files. The only difference is `fn.clj`'s own new `reader` require.

## Sizes

- `src/seon/fn.clj`: +49 / −101 (net −52)
- `test/seon/fn_test.clj`: +47 / −66 (net −19)
- Per commit, source: fa13c41a8 +16/−2, 7c761e0c9 +7/−56, 78161085b +26/−43

## Verification limits

- The deletion race was reproduced by deleting the file before `source-contexts` ran. No real concurrent `full-source-refresh!` was used.
- The `request!` classification of `:refused` is inherited from efe39880e and was not re-driven.
