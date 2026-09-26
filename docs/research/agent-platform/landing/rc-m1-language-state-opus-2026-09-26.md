---
type: landing
lane: rc-m1-language-state-opus
status: partial (four commits; two patches for root; RESET NEEDED)
commits: 0cbf4336f afb300669 28c2603fd 582e9482e
date: 2026-09-26
base: 67b02c99f
---

# rc-m1-language-state-opus — landing (2026-09-26)

Design, in brief. What exists: an agent form goes read → SCI eval → admission
(`seon.sci.eval/admit-program-change!` analyzes the form with `seon.fn/source-rows`
against SCI's namespace row) → settlement batch re-analysis (`turn.clj`, deleted)
→ gate re-analysis (`gate-function-install`, deleted) → writer re-analysis
(`turn/analyze-settlement`, kept as the basis check). The core-error dial was
read at five decision sites. The approach: each decision moves to its owner and
the downstream copies are deleted. Admission's analysis is the one analysis. The
configured SCI context decides executability. The program's namespace rows decide
what a qualified prefix names. There is no dial: a fault is always recorded and
delivered beside the result.

## Broken things first

- **RESET NEEDED.** Nothing here is live. Default runs an older archive.
- **Two patches for root must land together with, or right after, the commits**:
  - `tmp/rc-m1-language-state-opus/slice0b.patch` (133 files, +394/−735). It covers
    the non-owned dial carriers: cluster, cluster/boot, cluster/agent, flow,
    instrument (the `wrap-interpreted` positional mode is removed), sci/eval,
    sci/kernel, test, test/accretion, error, schema, render/web, render/value and
    harness_costs. It also deletes the dial attribute (`seon.config.edn`,
    `config/default.edn`) and fixes the test residue. It applies on HEAD
    (`git apply --check`). Rebase it if rc-m2b commits eval/kernel/instrument/accretion first.
  - `tmp/rc-m1-language-state-opus/me4-tests.patch` (eval_test and
    branch_execution_test). Commit 28c2603fd makes class usage interpretable, so these
    tests get new subjects: `seon.render.value/bounded-text` (proxy) and `datum`.
- **Expected reds until triaged by root.** These tests assert behaviour that was deleted:
  - `seon.test.one-request-test/a-refused-recording-write-is-a-stored-core-fault…` expects a throw. A stored fault now returns.
  - `seon.cluster.turn-test/spending-the-last-episode-run-is-a-loud-core-fault` expects a throw. It should now assert the stored fault instead.
  - `seon.flow-test/a-panicked-proc-with-saturated-control-still-stops-its-graph` needs a storage failure to reach the stop path.
  - `instrument_test` dual-mode loops are now duplicates.
  - The `datahike_parity.edn` baseline needs its explained update: its forms drift because the schemas changed.
- **Parts of M1 not done** (they are cross-owner, and eval.clj is held by rc-m2b). Options are below.
  - The M5 follow-ups in eval.clj (print after record; renderer diagnostics as faults) are also not done, because the file was held.

## What landed

| commit | cause removed | src lines | regression |
|---|---|---|---|
| 0cbf4336f | Task 6b8d26a16ce7 slice 0a. `fault!` no longer reads a dial. It returns the receipt beside the result and stops/throws only when storage fails. `seon.fault/policy`, `db/panic-on-core-error?`, admission's panic branch, and the dial's carriage through effect/drive/turn/render and 11 request schemas are deleted. | +119/−257 (incl. schemas and owned tests) | `seon.fault-test/a-stored-fault-returns-its-receipt-beside-the-result-and-a-carried-receipt-is-not-recounted`; `an-unknown-write-outcome-stops-and-throws-without-retry` |
| afb300669 | The live symptom `(let [t0 (System/nanoTime)] (defn f …))` refused with "clj-kondo holds no analysis of System". The synthesized ns form required every qualified prefix, so kondo read the static member as a Var. A prefix is now required only when the program declares that namespace (Clojure `Compiler.java:7524` namesStaticMember). | +16/−2 | `seon.fn-test/a-static-member-call-is-a-class-member-never-a-required-namespace` |
| 28c2603fd | Task bd7cb669b45d. Class usage never makes a declaration host-bound. The copied `sci.opts/default-classes` list and its Throwable/Error exception are deleted, because the SCI context is `:classes {:allow :all}` (eval.clj:398). | +6/−11 | `seon.fn-test/host-binding-is-a-declaration-fact-not-a-caller-closure` (updated); `canonical-program-has-complete-host-binding-facts` |
| 582e9482e | Task 2d2e18e61f60 (part). Gate and settlement consume admission's one analysis. That analysis runs against the namespace SCI evaluated the form in. Deleted: the settlement batch re-analysis, `analyze-submitted`, and the gate's `analyze-forms`. | +11/−74 | covered by the existing turn/gate tests; root runs them |

Net src for the owned commits is about −190. With the patch applied, slice 0 totals about −530 across src+tests.

## Evidence (JVM read-only / SCI on branch `rc-m1`, retired)

- **Static member.**
  - Reproduced on a fresh branch: the refusal text quoted above.
  - Cause: `runtime-analysis-batch`'s source was `(ns user (:require [System] …))`.
  - Fixed filter: `{user java.util.UUID System seon.db Math my.message}` becomes `{user seon.db my.message}`, in 1.9 ms.
  - The analysis with the fixed requires gives no refusal and host-bound `#{}`.
- **ME-4.**
  - SCI on the branch evaluates `(java.util.Date.)`, `System/currentTimeMillis`, `java.io.File`, a hinted `.getName` and `Math/abs` fine.
  - On the sample, the old callers were `{clock constructor literal hinted implementation admitted-import}`. The new ones are `{implementation admitted-import}`.
- **One analysis.** Admission's `analyzed-rows` row and `seon.fn/analyze-form`'s re-analysis differ only in `:seon.fn/form-span`, which `definition-row` drops (probe on default, 27 ms).
- **Slice 0.**
  - Contract compile over all 22 touched src files is green against the patched snapshot (0 findings, 434 ms). Every schema EDN file reads.
  - The unarmed `compile-settings` over the patched checkout gives a valid effective config with 82 dials and no `on-core-error`.
  - The armed live wrapper refuses, as expected, until adoption.
  - clj-kondo error counts are unchanged except for the deleted test's var, which the patch removes.

## Root runs

1. Apply `slice0b.patch` and `me4-tests.patch`. Commit. Adopt. Verify that the retired attribute `:seon.config/on-core-error` drops through `attribute-change-tx` on the live store.
2. `bin/test-check default --ns seon.fault-test`, `--ns seon.fn-test`, `--ns seon.sci.branch-execution-test`, `--ns seon.turn-test`, `--ns seon.config-test`, `--ns seon.sci.admit-test`, `--ns seon.flow-test`, `--ns seon.instrument-test`. Triage the expected reds above.
3. The acceptance forms on a fresh branch: the System/nanoTime `let`; `(defn d [] (java.util.Date.))` then `(d)` on a later turn; `(ns x (:require [clojure.string :as s])) (defn f [] (s/join [1]))` in one submission.

## Rest of M1: three priced options (recommendation marked)

1. **Writer validates basis, no recomputation** (simplest). `analyze-settlement` compares the admission row's resolved function symbols and namespace digest against the writer db, and re-analyzes only when they differ. Cost: about 2 h, turn.clj + fn.clj, about −10 net. Sacrifice: namespace synthesis stays textual.
2. **(Recommended) Option 1, plus SCI namespace state replaces `runtime-namespace-form` / `referenced-program-namespaces`.**
   - Admission already carries SCI's namespace row, and this passes it to `source-rows` as the only namespace context.
   - It deletes the text scan and today's afb300669 filter (about −30).
   - It also sets MCP omission to preserve `*ns*` (mcp.clj, rc-m5's file).
   - Cost: about 4 h across fn.clj, eval.clj and mcp.clj, after rc-m2b releases eval.clj.
3. **Option 2, plus a fork-point branch diff (task 9949c640e249) and regeneration retirement.**
   - `overridden-row?` compares against the branch's fork commit (`versioning.cljc`) instead of `::loaded-database`.
   - It retires the regeneration-specific carriage once retained-context adoption proves equivalent.
   - Cost: 1–2 days across eval.clj acquisition/adoption, about −100–150. Sacrifice: needs the audit's full private-state and adoption proof matrix first.
