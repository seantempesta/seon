---
type: landing
created: 2026-09-26
lane: render-audit-s2-floor-opus
commit: de1724492
---

# Landing: render-audit-s2-floor-opus (audit slice 2, common selection and general floor)

Commit **de1724492**. Authority: `render-specificity-audit-2026-09-26.md` §5 slice 2; CLAUDE.md "Three points, and cost decides", "Render ordinary values totally", "Apply presentation limits once", "The REPL path is total".

## Broken / not done first

1. **Render forms are not evaluated yet.** The slice's "evaluate selected forms through the existing stages" is not implemented. The evaluation stage (`seon.turn/evaluate-sources`/`preview-sources`) lives in turn.clj and runs inside the same evaluation whose value is being printed. Its forms name `result/eN`, which `bind-result!` binds only after `shown-result` prints. The preamble also rules that render forms are "recorded the same way". So evaluating them belongs to the REPL path owner (slice 3: `sci/eval.clj` `shown-result`, `turn.clj`), not to the floor. This slice instead **stops suppressing source producers**. Their REPL entries are the node's face, and the projection says so with `:seon.render.call/source-output? true`, so the REPL path can evaluate and record the forms. Until slice 3 consumes that flag, agents see the forms unevaluated. Every line is runnable, but the audit's full acceptance is not met. Priced options for root are below.
2. **Nested values have no held reference.** Slice 1's `held-reference` accepts only a `result/…` symbol, so `declared-projection` removes `:seon.repl/handle`/`:seon.render.value/root` for nested nodes. Without that, a nested error's pair would read the root's handle. Nested pairs therefore fall back to their comment-only forms. A nested reference could be the form `(seon.print/value-at result/eN [path…])`, but that needs `held-reference` (error.clj, slice 1) to accept a form. Interface proposal for root.
3. **No test run.** Tests are unrun (root runs them). Default does not run slice 1 or this slice (restart held), so the MCP after-view through the agent path is **not** shown. The before/after below comes from scratch copies of the committed files, loaded as `s2.render`/`s2.render.value` in default's JVM and called with the branch agent's real SCI ctx and database. Those scratch copies are **unarmed**. Their contracts were checked separately (below). **RESET NEEDED** (adoption) before the MCP proof.

## Design (what exists → one owner)

`seon.render.value/declared` called `seon.render/declared-projection`, which ran its own explicit→declared chain. That chain skipped the viewer-namespace stage, dispatched errors by `:seon.error/at`, excluded `:seon.error/base`, suppressed source producers, and guessed failures from returned error shapes. Now the floor asks the one `seon.render/selection` for every map, at the root and at every depth.

`declared-producers` takes the same route for every schema:

1. the producer's declared schema (metadata `:seon.error/declared-schema`, the producers' existing declaration key);
2. else the root function's most specific satisfied return (`most-specific`: an `:and` refinement drops its parent, so `:seon.error/value` loses to a specific error);
3. else the reaching attribute's schema;
4. else the owning row, at any depth.

A value nobody declared is data for the floor. The invocation outcome and the producer's contract (`produce`, `declared-return?`) decide failures, never the returned value's members.

## Deleted (render.clj / value.clj)

- Error arm and base fallback in `declared-producers` (~12). The base exclusion in `named-alternatives` (1), replaced by the general `most-specific`/`refined-names`/`canonical-name` (~35 with docs).
- `declared-producer` (9). Nested source suppression in `declared-projection` (~16). Duplicate explicit→declared precedence (~6).
- `invoke-producer` (11) and `invoked` (9). `valid-projection?` with its error bypass (4). The error-shaped renderer-return check (1). `raw-output`'s `declared-absence?`/`failed?` (~12), now `produce`.
- `source-return?`'s `:seon.error/value` clause (rewritten, 12→7). `render-form-value`'s second invalid-output construction (~14).
- Floor `diagnostic-line`'s error-inside-error extraction (~6): the failure now prints as bounded data. `link-lines`' first-party frame filter: now the nearest 10 frames plus "… N further frames".
- `prepare`/`render-prepared` `:seon.error/at` result guessing (2): each now reads its own result's defining key.
- `print.cljc`: no schema-specific branch found. It is unchanged. Nested AI text is still quoted.

Net production lines: render.clj +195/−188, value.clj +40/−30, schemas +2. That is **+19 net**, mostly docstrings on the refinement rule. Tests: +29/−6.

## Evidence (default JVM pid 32075, branch `render-audit-s2`, scratch copies, basis 536871062)

Before is `seon.render.value/prepare` (armed). After is `s2.render.value/prepare` (unarmed). Both take the same unit: the agent ctx, db, profile and caps.

| Value | Before | After |
|---|---|---|
| **Error**: a `seon.db/pull` refusal returned by the contracted call | message sentence | same (declared schema → `seon.error/render-ai`) |
| **Error** with the declaration stripped | message sentence (guessed base pair) | whole flat map as data (no guess) |
| **Error**: stored fault entity 34837 at the root (function `seon.db/pull`) | full entity map (function arm stopped selection) | "class clojure.lang.Symbol cannot be cast … Occurrences: 1." (row owner's pair) |
| **Entity**: `[fault {:who agent}]` | both maps as data | fault → its pair in the vector. The agent stays data: no satisfied terminal pair |
| **Viewer namespace**: `[{:point/x 3} {:y [{:point/x 4}]}]` with `user/render-point` defined on the branch | data | `["point at 3" {:y ["point at 4"]}]` |
| **Source producer**: `user/render-y` returns `;; a y point\n(inc 1)` | n/a | root: that text, `source-output? true`. Nested: the same text as a string node |
| **Large collection**: 40 pulled tasks | 5 runs: 40.0–46.9 ms | 5 runs: 32.8–45.2 ms. Identical text (task-ai is a source producer and renders at distance 0 once evaluated. Today the text is the same) |

- The first cut regressed the 40-task vector by +52% (30→46 ms): `public-functions-in` scans the whole snapshot for every map (0.27 ms). This is fixed by deriving `seon.render/viewer` once per `prepare` and carrying `:seon.render/viewer-functions` on the unit.
- Caveat: the armed old version was timed against the unarmed new one.
- Static checks: `seon.contracts-compile-test/check` over render.clj, value.clj and print.cljc returned no findings (645 ms). The three schema resources read as EDN.
- Lint: `src script test` has 0 errors.
- The real `viewer` output was validated against its contract. `most-specific` of `[:seon.error/value :seon.db/invalid-read-error]` is `[:seon.db/invalid-read-error]`.

## Root runs

- `bin/test-check default --ns seon.render.value-test` (new `selection-is-one-rule-at-every-depth`, plus `a-failing-renderer-never-replaces-the-value`)
- `bin/test-check default --ns seon.render.entity-pairs-test`
- `bin/test-check default --ns seon.render.history-test`
- `bin/test-check default --ns seon.render-simplification-test`
- `bin/test-check default --ns seon.render.call-test`
- `bin/test-check default --ns seon.render.web-test`
- `bin/test-check default --ns seon.error-test`
- `bin/test-check default --ns seon.sci.kernel-test`
- Then repeat the three MCP values on a fresh branch after adoption.

## Options for the evaluation stage (root)

1. **Simplest (recommended).** Slice 3's `shown-result` reads `:seon.render.call/source-output?` and the root source text. It hands the forms to `evaluate-sources` as further recorded evaluations of the same run, after `bind-result!`. The shown text is the entries plus their results. About 30–50 lines in sci/eval + turn, no floor change. Nested sources stay comment-first (distance 0).
2. Evaluate nested source entries too: a preview fork per nested node, with the rendering set carried through a binding. Sacrifice: a fork per node (tens of ms each) and re-entrance risk. It is not recorded as ordinary evaluations.
3. Keep the forms unevaluated (the current state). Agents run the forms themselves. Sacrifice: the audit's acceptance is not met.

Branch retired. Scratch namespaces were removed from the JVM. Scratch files are in `tmp/render-audit-s2-floor-opus/`.
