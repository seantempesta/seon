---
type: landing
status: landed
lane: fix-ns-from-kondo
created: 2026-09-24
issue: docs/seon/issues/reader-ns-conform-allocates-per-namespace-form.md
---

# Lane fix-ns-from-kondo — 2026-09-24

A file's namespace context now comes from the clj-kondo analysis that
publication already runs. `seon.fn.analyzer/analyze` attaches each `ns`
form's clause entries to its namespace definition. `seon.fn/namespace-context`
reads only that entry. The reader's `clojure.core.specs.alpha` conform is no
longer on the file path.

## Broken or unproven first

- **The new regression has no installed run.** Default (pid 68592) ran this
  lane's code from the checkout: `seon.fn.analyzer/with-namespace-clauses`
  resolved, and `read-jvm-form` and the `reader` alias were gone.
  - `bin/test-check default --test seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`
    was refused with "The requested test is not eligible". The source was
    published but not adopted, so the program rows do not have the new test yet.
  - `--test seon.fn-test/a-file-changed-after-capture-analyzes-to-the-captured-spans`
    passed 6/6 in 3.5 s (run ae5e2a56911f).
  - `--ns seon.fn-test` then lost its PREPL. Default pid 68592 exited with
    `java.lang.OutOfMemoryError: Requested array size exceeds VM limit` while
    several lanes were running `seon.fn-test`, `admission-gate-test` and
    `agent-namespace-test` (`data/clusters/default/logs/seon.log`; heap dump
    `data/heap/java_pid68592.hprof`, 17 GB).
  - The earlier boot OOMs at 16:37 and 16:39 are traced in
    `tmp/oom-diagnosis/summary.txt` to a stored 557M-character failure message.
    This lane did not attribute the 16:50 OOM. **RESET NEEDED**: default is
    down.

- **clj-kondo's analysis does not record a `:rename` local.** The root ruling
  assumed the analysis carries every clause. It does not.
  - `analyze-libspec` (reference-code/clj-kondo
    `src/clj_kondo/impl/analyzer/namespace.clj:273-281`, fork 57252e07) keeps
    renames in the in-memory ns map only.
  - `reg-namespace-usage!` (`src/clj_kondo/impl/analysis.clj:143-155`) emits
    `:from`, `:to` and `:alias`. The `:refer` var usages
    (`namespace.clj:220-231`) name the original var, not the local.
  - This lane refuses a `:rename` in a file's `ns` form by name, with
    `:seon.fn/index-refused-error`. None of the 442 `src`/`test` files uses
    one. REPL forms are unaffected, because `runtime-namespace-form` never
    writes `:rename`.
  - Root fix: accrete the renamed locals into the clj-kondo fork's analysis
    output. That is outside this lane.
- **`:as-alias` is marked only on alias-symbol metadata.** clj-kondo puts
  `:as-alias true` on the alias symbol (`namespace.clj:243-262`); it is not
  documented. `namespace-usage` now turns it into `::analyzer/as-alias` at the
  producer.
- **Caller not converted: `test/seon/contracts_compile_test.clj:94-110`.** It
  calls `@#'seon.fn/namespace-context` with the old `(contexts entry)`
  arguments, so it now throws an arity error. The file is not owned by this
  lane (ledger: free). The conversion is to analyze `{path text}` with
  `analyzer/analyze ::sources`, then call `namespace-context` on the ns
  definition entry.
- **Default did not run HEAD at lane start.** The assignment said it did.
  Default pid 46768 still had `seon.fn/read-jvm-form` and no `reader` alias,
  so its loaded `seon.fn` was from before 78161085b. `bin/seon status`
  refused with a `seon.cluster.boot/readable-response` contract failure
  (missing `:seon.error/at`). Evidence is below.

## Evidence

Commit: 9bba9e000.

### Equivalence

Probe: all 442 `src`/`test` `.clj`/`.cljc` files, analyzed once through
`analyzer/analyze ::sources`. The probe compared each namespace definition's
context from three implementations:

| Comparison | Namespaces | Differences |
|---|---|---|
| new versus HEAD's reader (78161085b) | 442 | 0 |
| new versus the pre-78161085b parser | 442 | 0 |

The files include 102 namespaces with imports and 5 `:as-alias` requires.

- In default, a raw clj-kondo prototype also matched the old parser for all 441
  namespaces.
- Class regression: `seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`.
  It covers:
  - a prefix list with a bare member
  - a prefix list whose only member has options
  - `:as-alias`
  - `:refer`
  - vector and bare imports
  - a top-level `require` after the `ns` form, which is not a clause
  - the `:rename` refusal

### Cost

Measured per 442-namespace pass after 5 warm passes, averaged over 10. The
machine's load average was 12–17, so times are noisy. Allocation is stable.

| Implementation | Where measured | Time per pass | Allocation per pass |
|---|---|---|---|
| HEAD reader conform | snapshot JVM | 478–753 ms | 313.5 MB |
| HEAD reader conform | default JVM | 124 ms | 320 MB |
| old parser (7c761e0c9) | snapshot JVM | 19–53 ms | 13.9 MB |
| new: attach clauses and read contexts | snapshot JVM | 26–38 ms | 6.0–6.6 MB |
| attach step only | snapshot JVM | 19 ms | 5.3 MB |

- The attach step binary-searches each sorted analysis vector for the `ns`
  form's span (`entries-within`).
- A per-file `group-by` over all 155,349 var usages cost 15–36 ms by itself.
  This lane uses the binary search instead.

## Sizes

| File | Added | Removed | Net |
|---|---|---|---|
| `src/seon/fn.clj` | 31 | 39 | −8 |
| `src/seon/fn/analyzer.clj` | 48 | 5 | +43 |
| `test/seon/fn_test.clj` | 35 | 18 | +17 |

The source net is +35 lines.

## Verification limits

- The snapshot comparison (`git archive` of HEAD plus this diff, deleted after
  the owner's one-JVM correction) ran `seon.fn-test` and `seon.fn.analyzer-test`
  in plain `clojure.test`. The same tests failed before and after the change.
  The pass count was 211 before and 210 after, a difference this lane did not
  explain.
- `docs/seon/issues/reader-ns-conform-allocates-per-namespace-form.md` is not
  owned by this lane. For files, the cost it describes is gone. The reader's
  conform remains for REPL forms, so root decides the issue's status.
