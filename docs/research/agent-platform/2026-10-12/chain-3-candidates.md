---
title: "Chain experiment 3: candidate subjects"
type: research
status: complete (lane chain3-pick, 2026-10-12); read-only on default, no source or task edits
created: 2026-10-12
---

# Chain experiment 3: candidate subjects

**Brief.** An ordinary function (SCI-interpretable on a branch, not the value printer, evaluator, writer, test runner,
boot or schema publication), small, with a reproduction form, an owner symbol, and existing tests that reach it. Not in
files lanes edit now (`src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/cluster/squash.clj`,
`src/seon/cluster/agent.clj`, `src/seon/task.clj`, `src/my/program.clj`, `src/seon/sci/eval.clj`).

**Method.** Open `:seon.task/kind :function` tasks in `resources/seon/tasks/*.edn` (732 entries) filtered to ordinary
namespaces. Each candidate's defect was reproduced read-only on default in JVM mode as root (`mcp__seon__eval_clj`,
`mode jvm`, forms only, no `def` of a Seon var). Tests were found with `rg` over `test/`.

## Top 3

| Rank | Task | Owner | Reproduction (today) | Wrong output | Reaching tests | Why it suits a two-step chain | Risk |
|---|---|---|---|---|---|---|---|
| 1 | `seon.ref/a-reference-with-no-path-is-a-missing-file` | `seon.ref/section`, `seon.ref/section-error` (ref.clj:343 calls `locate`) | `(seon.ref/section "#the-goal")`, `(seon.ref/section "#")` | Both throw `seon.ref/locate refused argument 0 path ... expected :seon.ref/path (a string of length at least 1)`. The brief's expected answer is a `:seon.ref/section-error` with `:seon.ref/section-missing :file`, as `"no-such-file.md#the-goal"` answers today (verified: returns `:file`, nearest = the five checkout root Markdown files). | Tagged red deftests already exist: `a-reference-with-no-path-is-a-missing-file` and `a-reference-with-no-path-answers-an-error-that-holds-no-path` in `test/seon/ref_test.clj` (proof present, no new test needed). `seon.ref/section` is reached by `my.program_read_test`, `ref_test` (`:seon.ref/nearest` tests) and `error_test` replies. | The fix is two producer edits (guard in `section`, optional path in the section-error contract), both in one ordinary namespace; the proof is already written, so the Design step has little to decide and Implement has a clear end. | The fix changes a registered schema (`:seon.ref/section-error` in `resources/seon/schemas`). Schema publication is platform; the chain must prove it by publishing on a branch of the live store, which the step may not do. Choose this only if root accepts that. |
| 2 | `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` | `seon.sci.forgive/forgive`, `seon.sci.forgive/scan` | `(seon.sci.forgive/forgive "a]a" :code)` answers text `"aa"`, one repair (dropped `"]"` at line 1, column 1). Forgiving that result again answers text `"aa"` with spans `[[0 2 1 1]]`, where the first pass had `[[0 1 1 1] [1 2 1 2]]`. | Idempotence fails on the spans (the task also lists `"('"` and `"(\\"` cases). The printed text of the second pass is stable, so the defect is visible only in the spans and repairs. | Three tagged deftests in `test/seon/sci/forgive_test.clj` at lines 219, 232 and 238 (`:seon/tasks` names the task); `forgive_test.clj` also runs `forgive/segments`, `forgive/forgive` on fixtures (`test/seon/sci/forgive_replies.edn`), and `test/seon/sci/library_aliases_test.clj` reaches it. | `scan` is one pure function over text; the change is local and its proofs exist. The subject is in the same namespace family as experiment 1's subject, so the chain has known context. | `forgive` runs before reads of agent replies, so an error there is seen by every reply; that is on the REPL path. Experiment 1's subject (`segments`, same namespace) was not finished; pick this only with that in view. |
| 3 | `seon.render.var/the-offered-check-names-an-unquoted-symbol` | `seon.render.var/render-ai` (src/seon/render/var.clj:139 builds `(list 'my.test/check {:seon.test/changed [sym]})`) | Not re-run: confirmed by reading line 139 only. The task reports the offered form, run as shown, refused with `a namespaced symbol, got an instance of clojure.lang.AFunction$1`. | The offered operation carries the function object where its symbol belongs. | No tagged proof exists; the task requires a new test evaluating the offered form. `rg` finds no test naming `render-ai` in `test/seon/render/`. | Small producer change (quote the symbol); the proof is new, so the Design step must write it, which is what the chain is for. | Lower confidence: the reproduction was not re-run on this HEAD, and the reaching tests are unknown. Confirm on default before launch. |

## Rejected

- `seon.problems/ai-prose-returns-printed-text-not-data`: a shape choice with a golden test, not a wrong value.
- `seon.repl/directory-render-returns-text-the-printer-clips-mid-form`: the defect is real, but the owner is the REPL
  entrance (`src/seon/repl.clj` is on the one REPL path) and its proof needs a golden transcript.
- `seon.ai/a-dsml-tool-call-reply-reads-as-prose`: the owner is `seon.turn/continuing-reply?` (reply path); its tagged
  coverage is partly `forgive_test.clj:246`. Too entangled for a first ordinary-function chain.
- `seon.batch/a-request-cost-counts-only-the-evaluation-arm`, `seon.config/a-config-read-walks-the-whole-registry`:
  cost tasks, proofs need a measured bound; not a clean two-step shape.

## Notes

- Read-only probes on default: `seon.ref/section` and `seon.sci.forgive/forgive` answered as above. The `forgive` probe
  first failed on arity and mode (`:code` or `:reply`, not a keyword like `:sci`); those were refusals, not defects.
- Residue on default: one probe bound `user/t1` in the host JVM (`def` in a probe, not persisted). Disposable; nothing
  else written.
- Experiment 2's `G9` (no reaching tests for a function called through its callers) did not affect these three: each
  owner has a direct `rg` hit in `test/`.
