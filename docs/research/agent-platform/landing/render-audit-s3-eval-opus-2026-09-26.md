---
type: landing
created: 2026-09-26
lane: render-audit-s3-eval-opus
commit: b1f291fbc
status: committed, unadopted and untested; turn.patch for root; RESET NEEDED
---

# render-audit-s3-eval-opus: render forms evaluated, nested references, no enrichment (audit slice 3)

Before this slice, s2 (de1724492) put a pair's REPL entries on the node unevaluated, marked `source-output?`. `shown-result`
also printed every evaluation failure with `structural?` forced and enriched contract errors with `:seon.error/doc`. Now
`shown-result`, the print stage of the one REPL path, gives the render the evaluator as a value
(`:seon.sci.eval/evaluate-source`). `declared-projection` in render.clj calls that evaluator for every source-producing
pair, at the root and at every depth. Each form is read once, evaluated through `seon.sci.eval/evaluate` in the viewing
agent's SCI world, and printed with `;=> ` and its shown result.

## Broken, deviations and open decisions first

1. **Where evaluation happens (root's REVISION: "lazy, at context build").** This slice evaluates when the saved
   observation is built (`shown-result`). That observation is the text both the MCP reply and the next context read
   (`transcript/history-entries` → `repl/response`, err-s4). It does not run in turn settlement. The next context
   therefore evaluates nothing and reads the text again, with nothing evaluated twice. I did not build lazy
   evaluation in the transcript, for three measured reasons:
   - `agent-history-under`/`history-entries` and the MCP bridge hold no SCI context, so lazy evaluation needs context
     plumbing through transcript.clj and mcp.clj, both owned by other lanes.
   - After a JVM restart, `result/eN` is unbound and the live value is gone. A lazy render could then show neither the
     results nor the floor value. The owner's earlier ruling was "100% present in the next turn".
   - Every evaluation is shown at least once (the MCP reply or the next context), so laziness saves nothing on the
     common path.

   The regression `the-next-context-reads-the-evaluated-render-without-evaluating` states the property. **Root to rule:**
   (a) keep this (recommended, done); (b) lazy evaluation in transcript plus MCP with a content-digest cache: about 60
   lines across two foreign files, and it loses renders after a restart; (c) evaluate in turn completion (the
   turn.patch site): no gain over (a).
2. **render.clj edited outside my ledger row.** s2 released the file at 05:05Z. Both deliverables (selection passes the
   nested reference; evaluation replaces unevaluated faces) live in `declared-projection`: +35/−14. Root: record the
   path as released by this lane.
3. **turn.clj hunks as a patch:** `tmp/render-audit-s3/turn.patch` applies cleanly to the current worktree (with
   one-commit-a's edits). It removes `structural` from the replacing value in the settlement refusal (`(not evaluated?)`)
   and from the disposition-refusal reprint in `evaluate-sources`.
4. **Kept on purpose:** `floor-text` still prints `:seon.cluster.eval/diagnostics` structurally, and so does turn's
   diagnostics `[value]` (two sites). The schema declares diagnostics as "a vector of `:seon.error/base` values that
   reads back as data". Turning that string into references (the audit's "diagnostic-only string path") changes the
   receipt schema, `repl/response`, transcript and MCP, so it is a separate slice. **Not done:** the
   `turn.clj:2270` diff-text replacement (turn.clj is held; `seon.db.diff.edn` is not mine).
5. **A nested source face is still a quoted string** inside the floor's collection (s2's printer, `print.cljc`). It is
   now evaluated text, but it prints inside `"..."`.
6. **Tests: none run** (lanes do not run tests). The static checks are green (below). **RESET NEEDED** (adoption)
   before the acceptance probes on a fresh MCP branch. Expected reds, which root should run:
   - `seon.sci.render-source-test` (new, 3 deftests)
   - `seon.sci.documentation-test`, `seon.contracts-plan-test`, `my.examples-test` (converted)
   - `seon.sci.eval-test` (s1's frames patch is still pending; failures now print by their pair)
   - `seon.sci.kernel-test`, `seon.error-test`, `seon.render.value-test`, `seon.render-simplification-test`,
     `seon.repl-grammar-test`, `seon.cluster.evaluate-source-test`
   - any golden that reads a failed evaluation's shown text as EDN data: failures used to be forced structural, and
     `repl/response` now answers a failed evaluation with its renderer's text

## What changed (b1f291fbc)

| Where | Change |
|---|---|
| `sci/eval.clj` `evaluated-source` (new, ~45) | Reads the entries once (`seon.sci.reader/read`, SCI ctx). Evaluates each event through `evaluate` (`:seon.sci.eval/event`, no second parse) with run, ordinal and handle removed and `::rendering?` set, so an inner result evaluates no further entries. Keeps comments in place; appends `;=> shown`. Any read or evaluation failure answers `{:seon.error/value refusal}` naming the form. |
| `sci/eval.clj` `shown-result` | **Deleted** the `enrich` block (−36): `:seon.error/doc`, `error/prepare-result` re-admission, and the contract-error validator on every print. Binds the handle (`bind-result!`) before printing; attaches the evaluator when ctx, time limit and caps are present. |
| `sci/eval.clj` failure path, `refuse-install` | **Deleted** the forced `structural?` options. The failure names its producer (`:seon.render.call/function 'seon.sci.kernel/failure-value`, s2 route 2); a contract refusal's own `:seon.error/declared-schema` meta wins (route 1). `failed-evaluation` now carries `:seon.eval/renderer`, so `repl/response` prints the pair's text rather than a quoted string. |
| `render.clj` `declared-projection` | A nested value under a held root gets `:seon.render.value/root (seon.print/value-at result/eN path)` (path quoted when it holds symbols or collections), replacing the plain removal. A source face is evaluated by the request's evaluator. When there is no evaluator or a form fails, it answers `{:seon.error/value …}`: the floor prints the value plus one `;; renderer failed …` line. |
| `error.clj` `held-reference` | Accepts `result/eN` or `(seon.print/value-at result/eN path)`. `kernel.clj` needed no change: its pair builds its forms from whatever `held-reference` returns. |
| `repl.clj` `live-response` | **Deleted** the structural fallback inferred from a saved emission without a renderer. |
| `seon.sci.eval.edn` | Declares `:seon.sci.eval/evaluate-source` (`:seon.instrument/callable`) and `:seon.sci.eval/rendering?`. |

Line counts: production +134/−75, net **+59** including the schema; tests +134 new, −20 net in converted files.

## Evidence (default JVM, branch `render-audit-s3`, now retired)

**Before** (HEAD as installed, MCP SCI on the branch):
- `(smoke-inc "a")` ≈1,650 chars ≈ **515 tokens**. The whole flat refusal, including `:seon.error/doc`, `result-id`,
  data blob, expected-shape digest and instrument bookkeeping.
- `(/ 1 0)` ≈520 chars ≈ **163 tokens**.

**After** (the committed `evaluated-source`, loaded as an unarmed scratch copy, calling the real armed `evaluate` in the
branch agent's own SCI ctx on the real held values; s1's pair text as input, since s1 is not loaded in default):

| Value | Shown | Chars / tokens (ceil/3.2) | Time |
|---|---|---|---|
| contract refusal | comment, `(mapv … (:seon.error/problems result/ed0679778c2d1))` `;=> [#:seon.error{:path [], :expected :int, :offending "a", :fix "Supply an integer at []."}]`, `(:seon.sci.eval/stacktrace …)` `;=> [[user/smoke-inc 1 1] [user 1 1]]`, contract comment | 499 / **156** | 13 ms |
| `(/ 1 0)` | two comments, `(:seon.sci.eval/stacktrace result/e6b6f52ee2330)` `;=> [[clojure.core// 1022 1] [user 1 1]]`, cause-chain comment | 220 / **69** | 5 ms |
| nested reference | `(:seon.sci.eval/stacktrace (seon.print/value-at result/e6b6f52ee2330 []))` `;=> …` (value-at resolves in the agent's world) | 132 / 42 | 6 ms |
| failing form `(/ 1 0)` | `{:seon.error/value {… :message "A render form failed: … Divide by zero" :offending ";; boom\n(/ 1 0)"}}` | — | 8 ms |

- Real inputs and outputs were validated against the checkout projection: `:seon.sci.eval/shown-request`, `evaluated-source`'s
  output union for all four cases, `held-reference`'s output for the symbol, the value-at form, the quoted path and nil,
  and `:seon.sci.eval/evaluate-source`.
- Static checks: `seon.contracts-compile-test/check` over the 8 touched sources and tests gave `[]` (134 ms). The schema
  EDN reads back. `clj-kondo --lint src script test` gave **0 errors**.
- Not measured: the armed cost of nesting `evaluate` inside the outer evaluation's arm. The inner evaluation inherits
  the arm, so the render shares the form's deadline. Root should time `(smoke-inc "a")` on parent and change after
  adoption (hot-path rule: 20% / 50 ms).

## Acceptance for root (after adoption, fresh MCP branch)

`(smoke-inc "a")` about 150 tokens of forms and results; `(/ 1 0)` about 70; `[(err)]` renders the nested pair through
`value-at`; a viewer-defined render function applies (s2's viewer stage, unchanged here); a throwing pair form shows
the floor value plus one diagnostic line. Then repeat the before/after token counts.

Released paths: `src/seon/sci/eval.clj`, `src/seon/render.clj`, `src/seon/error.clj`, `src/seon/repl.clj`,
`resources/seon/schemas/seon.sci.eval.edn`, `test/seon/sci/render_source_test.clj`,
`test/seon/sci/documentation_test.clj`, `test/seon/contracts_plan_test.clj`, `test/my/examples_test.clj`. The scratch
probe is `tmp/render-audit-s3/probe.clj`; its JVM namespace was removed.
