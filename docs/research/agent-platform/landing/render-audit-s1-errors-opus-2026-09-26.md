---
type: landing
created: 2026-09-26
lane: render-audit-s1-errors-opus
status: committed 713bd774c e4a41ae91, unadopted and untested; eval_test patch for root
---

# render-audit-s1-errors-opus — error values whole, schema pairs select (2026-09-26)

Error values already existed whole or nearly so (`seon.sci.kernel/failure-value`, the instrument refusal), and the base pair
`seon.error/render-ai` printed prose. This slice makes each named error schema's AI pair select what an agent needs, as REPL
entries (`;;` comments plus forms that read the held value through its result handle). It also removes the producer-side
frame trimming. Implements slice 1 of `render-specificity-audit-2026-09-26.md` (0f7a8dda7).

## Broken or not yet true first

- **Nothing an agent sees has changed yet.** Evaluation errors are still printed through the floor with `structural?` forced
  (`sci/eval.clj:3876/3980`, `turn.clj:3851/3922/4937`: slice 3), and the pair selection for errors is error-specific and
  suppresses nested source pairs (`render.clj:363/501/1183`: slice 2). The pairs added here are declared but not reached on
  the evaluation path until those land.
- **Integration order for e4a41ae91 (frames kept whole):** while structural printing is still forced, agents will see the 50
  evaluator frames (~3.5 KB) that smoke-6 complained about. Root should integrate e4a41ae91 after slice 3, or at least after
  slice 2.
- **Foreign test red:** `seon.sci.eval-test/an-agents-own-exception-is-minimal-complete-data` asserts the trimmed value
  (<600 bytes, no evaluator frames). The patch that converts it is `tmp/render-audit-s1/eval_test-frames-whole.patch`. It
  keeps the one-link, class and message assertions and drops the trim assertions; the pair-level assertions now live in
  `kernel_test`. The file belongs to another lane, so root has to apply the patch.
- **Base pair still prose.** `seon.error/render-ai` (inherited by about 60 error schemas) still returns a prose string. There
  are about 33 direct prose assertions across `instrument_test`, `html_views_test` (goldens), `refusal_grammar_test`,
  `returned_error_test`, `error_result_test`, `db_test` and `error_test`, and most of those files belong to other lanes.
  Converting the base to forms is the remaining part of the audit's "replace prose-only output" and needs those test files.
  **Existing defect:** `render-faults-ai` declares `:seon.render/source` but, for acquired faults, joins the prose from
  `render-ai`, so it returns non-source text as source. The base conversion fixes that as well.
- Task `a57e8b27de67` (contract refusal bookkeeping) is met by the pair under Three points: nothing is removed from the
  value. Its intent text ("keep … the rest is derivable") should be re-read against that law. The task file is not mine.
- **Tests not run:** lanes do not run tests. Root should run: `seon.sci.kernel-test` (the two new deftests),
  `seon.error-test/a-held-error-observation-renders-as-held-without-a-read`, `seon.sci.eval-test` (after the patch),
  `seon.error.refusal-test` and `seon.mcp-test`.

## Changes

| Commit | Change | Lines (src/test) |
|---|---|---|
| 713bd774c | `seon.error/held-reference`, `source-entries`, `render-contract-ai`; `seon.sci.kernel/render-ai`; `:seon.render/ai` declared on `:seon.instrument/contract-error`, `:seon.sci.kernel/execution-error` and `:seon.sci.kernel/error` (HTML stays inherited from base). `rendered-error-value` pulls only a bare identity (a signature without occurrences or a message), no longer every value that carries a signature. | +82/−1 src, +86 test |
| e4a41ae91 | `failure-value` keeps every first-party frame (the default of `refusal/chain`). Deleted `refusal/called-frames` and `chain`'s `frames-of` arity. | +8/−25 src, +2 test |

Net production: about +64 lines. The added pair code is the selection the law places in pairs. The deletions are the
producer trim and the unconditional re-pull.

## Real values (MCP SCI on branch `render-audit-s1`, now retired; JVM 32075 at 36a3714b4)

Token estimates use ceil(chars/3.2), the shipped prior in `seon.ai.tokens`.

| Value | Before (floor, as MCP shows it) | Pair: source + evaluated results |
|---|---|---|
| `(smoke-inc "a")` contract refusal | 1,549 chars ≈ **485 tokens** (arglists, arm, caller, check, malli, problem-count, doc, expected-shape and data-blob digests, …) | 361 + 125 chars ≈ **152 tokens** (target ≤400) |
| `(/ 1 0)` execution error | 480 chars ≈ 150 tokens (trimmed value); about 3,850 chars ≈ 1,205 tokens with the frames restored | 179 + 38 chars ≈ **68 tokens** (target ≤200) |
| Contract refusal without a handle | — | 232 chars ≈ 73 tokens (comments only, scalars bounded by `scalar-text`) |

Pair output for the contract refusal. Its forms were evaluated against the held value and the results are shown after each
form:

```clojure
;; user/smoke-inc refused its :seon.fn.arity/input.
(mapv
  (fn [problem]
    (select-keys
      problem
      [:seon.error/path :seon.error/expected :seon.error/offending :seon.error/fix]))
  (:seon.error/problems result/ecdc8b0be6ae0))
;=> [{:seon.error/path [], :seon.error/expected :int, :seon.error/offending "a", :seon.error/fix "Supply an integer at []."}]
(:seon.sci.eval/stacktrace result/ecdc8b0be6ae0)
;=> [[user/smoke-inc 1 1] [user 1 1]]
;; Contract: (doc user/smoke-inc); the whole refusal: result/ecdc8b0be6ae0
```

`(doc user/smoke-inc)` was checked as a runnable read in the agent world: 17 ms, returns summary, arglists, in and out.

Pair output for the execution error:

```clojure
;; Execution error (ArithmeticException) at (REPL:1:1).
;; Divide by zero
(:seon.sci.eval/stacktrace result/e6739996f38b0)
;=> [[clojure.core// 1022 1] [user 1 1]]
;; Cause chain: (:seon.error/chain result/e6739996f38b0)
```

Cost of keeping frames: `(seon.error.refusal/chain e nil)` on a real agent `(/ 1 0)` gave 1 link, **50 first-party
frames, 3,474 chars**, taking 12 ms for the whole form. Capture cost does not change: `stack-frames` already converted the
whole stack before the old filter.

## Proof

- **Two regressions, one per pair, in `test/seon/sci/kernel_test.clj`.** Both use real values from the agent's evaluate
  path. Each reads the pair's source back and evaluates it in a SCI world where the handle names the value, then asserts
  the selected results, the absence of bookkeeping and evaluator frames, and the token ceilings (≤200 and ≤400).
- **One regression in `error_test`:** a held observation renders with zero read evidence; a bare identity is read.
- **Static checks** were run on a `git archive` of HEAD plus this diff. `seon.contracts-compile-test/check` over the five
  touched files: 0 findings in 193 ms. The new contracts are present in the indexer's stored form. Both schema resources
  read as EDN. clj-kondo reports no new warnings.
- **Real inputs validated against the carried projection:** `:seon.render/unit` with and without a handle, the entries
  input of `source-entries`, and the `:seon.render/source` output.
- **Probe timings:** every MCP form finished in 27 ms or less, except the first `defn` (119 ms). No operation took more
  than a second.

## Coordination (through root)

- **For slice 2:** the pairs return `:seon.render/source`. They read the held value through `:seon.repl/handle`, or through
  `:seon.render.value/root` (`seon.error/held-reference`). A nested error needs a reference to itself in that member;
  without one, the pair falls back to bounded comments.
- **For slice 3:** `:seon.error/doc` enrichment can be deleted. The contract pair offers `(doc fn)` as a read.

Released paths: `src/seon/error.clj`, `src/seon/error/refusal.clj`, `src/seon/sci/kernel.clj`,
`resources/seon/schemas/seon.instrument.edn`, `resources/seon/schemas/seon.sci.kernel.edn`,
`test/seon/sci/kernel_test.clj`, `test/seon/error_test.clj`. `seon.error.edn` was not changed. No RESET NEEDED: publication
and adoption are root's.
