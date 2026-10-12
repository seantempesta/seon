---
title: History friction survey, 2026-09-28 to 2026-10-12
type: research
status: measured on HEAD; interpretive notes marked inferred
basis: analysis snapshot 45bfab18e (1,826 window commits on refactor/agent-platform). HEAD is now c78274fe1; its 4 later commits touch only docs/research survey files, so the code history is the same. Default not queried; no default numbers here.
method: |
  git log --since=2026-09-28 --format=... --name-only (commit metadata and file lists)
  git log --since=2026-09-28 --reverse -p -U0 -- '*.clj' '*.cljc' '*.cljs' (hunks)
  scratchpad survey/parse.py, analyze.py, report.py, gen.py (outside the repo; hunk lines mapped to the
  column-0 def form enclosing them at the new side or the parent)
---

## For root

Ten hottest rework chains (forms with 4+ touching commits, ranked by fix-subject commits; full list in Table A; items 2 and 3 are one chain, the health definition-regression metric and its test):
1. `seon.context/task-done?`: 8 touches, 4 fix-subject commits (062027664, 56d2efa61, c53c9306e, f68dd4086).
2. `seon.health/definition-comparisons`: 5 touches, 4 fix-subject commits (7090225a2, 0d02eaaf7, 6c071ada1, a8e3bde5b).
3. `seon.health-test/a-definition-slower-under-its-new-digest-is-a-regression`: 5 touches, 4 fix-subject commits (7090225a2, 0d02eaaf7, 6c071ada1, a8e3bde5b).
4. `seon.context/current-state`: 17 touches, 3 fix-subject commits (15ef7b9b4, 062027664, c53c9306e).
5. `seon.cluster.source/accept-merge!`: 13 touches, 3 fix-subject commits (735c6f519, c53c9306e, f68dd4086).
6. `seon.repl/response-entries`: 12 touches, 3 fix-subject commits (5c627afc9, 131082e0d, 4c3be3361).
7. `seon.turn/test-step`: 11 touches, 3 fix-subject commits (56d2efa61, c53c9306e, f68dd4086).
8. `seon.sci.eval/install-evaluated-rows!`: 9 touches, 3 fix-subject commits (8de6239e2, 30be17208, ad86afd43).
9. `seon.sci.eval/install-selected!`: 9 touches, 3 fix-subject commits (30be17208, a582e40e3, ceb09bab9).
10. `seon.sci.forgive/segments`: 7 touches, 3 fix-subject commits (18a68b248, 84a3c5e4d, 4c3be3361).

Revert lessons (git reverts only; mislabeled non-reverts listed in Notes):
- `b57ba36e0` reverted by e09df7854 after 3 min: content-valid analyzer cache: boot refused with 'Source changed during analysis' because jar entries went stale right after a rebuild; the revert's own message was wrong (amended).
- `b57ba36e0` reverted by 073a349a4 after 4 min: content-valid analyzer cache: boot refused with 'Source changed during analysis' because jar entries went stale right after a rebuild; the revert's own message was wrong (amended).
- `64cb9e80f` reverted by fb1437312 after 3 min: boot record on the process entity wrote an attribute before it was installed, so HEAD refused to boot; committed unproven.
- `c2b4f51f6` reverted by a755e5eda after 23 s: turn-test asserted a pass sequence the installed program did not produce; the cause was never diagnosed.
- `f0d96f147` reverted by 98d836c57 after 3 min: settlement test change still failed unchanged after restart; the tests moved to a later rewrite.
- `c722d8d22` reverted by c53c9306e after 50 min: the overnight batch of fences, reverted in one commit under the NO GUARDS OR FENCES rule.
- `f18ab95e3` reverted by c2284b109 after 75 min: Fressian datom-attribute cache measured slower end to end (21.6-28.2 to 24.0-34.4 ms) while its proof counted encoder calls; it also changed the stored node encoding.

Trend (per day; commits / fix-subject / git reverts / bump commits / signal hits):

| day | commits | fix-subject | reverts | bumps | regression | RESET NEEDED | red on HEAD | pre-existing |
|---|---|---|---|---|---|---|---|---|
| 2026-09-28 | 394 | 31 | 5 | 10 | 53 | 0 | 1 | 3 |
| 2026-10-06 | 15 | 8 | 0 | 1 | 8 | 0 | 0 | 0 |
| 2026-10-07 | 219 | 40 | 0 | 2 | 34 | 1 | 13 | 0 |
| 2026-10-08 | 481 | 27 | 0 | 9 | 17 | 2 | 5 | 3 |
| 2026-10-09 | 177 | 36 | 0 | 5 | 12 | 0 | 6 | 2 |
| 2026-10-10 | 188 | 23 | 0 | 5 | 7 | 1 | 49 | 3 |
| 2026-10-11 | 131 | 20 | 1 | 2 | 8 | 2 | 10 | 3 |
| 2026-10-12 | 221 | 18 | 1 | 6 | 21 | 4 | 23 | 5 |

Already filed (text grep of resources/seon/tasks, not semantic): task-done and reached-red in seon.turn.edn, seon.context.edn, seon.task.edn; accept-merge in seon.cluster.source.edn and seon.merge.edn; response-entries in seon.repl.edn; install-selected in seon.sci.eval.edn.
Not filed (no task text found): `seon.health/definition-comparisons`, `seon.sci.forgive/segments` chain, the analyzer-cache boot refusal, the fb1437312 boot-record refusal, the f18ab95e3 proxy-count proof.

## Table

### Table A: rework chains, functions (4+ touching commits, 2+ fix-subject)

| symbol | touches | fix-subject | fix sequence (max 6, oldest first) |
|---|---|---|---|
| `seon.context/task-done?` | 8 | 4 | 062027664 seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green: all-green ; 56d2efa61 A task ends done only when no test its change reaches is red, and a lo; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.health/definition-comparisons` | 5 | 4 | 7090225a2 health: what regressed is a query — each stored measure's newest value; 0d02eaaf7 health: a definition regression is a p50 move over at least 200 calls ; 6c071ada1 definition regressions rank by the time they add: the comparison deriv; a8e3bde5b definition-comparisons' input contract requires only what the median c |
| `seon.health-test/a-definition-slower-under-its-new-digest-is-a-regression` | 5 | 4 | 7090225a2 health: what regressed is a query — each stored measure's newest value; 0d02eaaf7 health: a definition regression is a p50 move over at least 200 calls ; 6c071ada1 definition regressions rank by the time they add: the comparison deriv; a8e3bde5b definition-comparisons' input contract requires only what the median c |
| `seon.context/current-state` | 17 | 3 | 15ef7b9b4 a-handled-message-stays-unread-in-every-later-pass: the current-state ; 062027664 seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green: all-green ; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.cluster.source/accept-merge!` | 13 | 3 | 735c6f519 Every refusal seon.cluster.source/land! answers carries its own :seon.; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.repl/response-entries` | 12 | 3 | 5c627afc9 repl reply: :repeated calls shown only beside a :slow finding (clue), ; 131082e0d printer-fix: the core prints with no database (:seon.render.value/unit; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form is one no-v |
| `seon.turn/test-step` | 11 | 3 | 56d2efa61 A task ends done only when no test its change reaches is red, and a lo; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.sci.eval/install-evaluated-rows!` | 9 | 3 | 8de6239e2 stale-caller (2): own definitions survive a re-acquired base and anoth; 30be17208 lazy callers: a branch change binds its reverse closure to interpret e; ad86afd43 A model pass's redefinition reaches the base's copy of the agent's tes |
| `seon.sci.eval/install-selected!` | 9 | 3 | 30be17208 lazy callers: a branch change binds its reverse closure to interpret e; a582e40e3 A test namespace's defs interpret in file order: seon.sci.eval/install; ceb09bab9 A namespace's fixtures are data on its entity, so a world acquired fro |
| `seon.sci.forgive/segments` | 7 | 3 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one stepper (se; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form is one no-v |
| `seon.turn/segmented-sources` | 7 | 3 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one stepper (se; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form is one no-v |
| `my.program/source` | 7 | 3 | f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source renders the d; 77367741d fanout-9b: my.program/nearest ranks every declared name by malli.error; 76525de62 my.program reads take what agents pass: a bare subject for callers, te |
| `seon.fn.analyzer/ensure-dependency-analysis!` | 6 | 3 | e09df7854 Revert b57ba36e0 (content-valid analyzer cache): boot refused twice wi; 073a349a4 Revert the content-valid analyzer cache (astra's b57ba36e0, whose comm; 050f43b80 host-cost: a reset re-arms nothing it already armed (current-wrapper?  |
| `seon.turn/reached-red` | 6 | 3 | 56d2efa61 A task ends done only when no test its change reaches is red, and a lo; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.sci.kernel/failure-value` | 5 | 3 | bbba30dc4 housekeeping: remove fixed/dangling known-issue tags (e13e5a9c557f, ac; f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source renders the d; 808176555 an agent's context binds slurp, spit and file-seq (task seon.sci.kerne |
| `seon.turn/record-reached-red!` | 5 | 3 | 56d2efa61 A task ends done only when no test its change reaches is red, and a lo; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.health-test/a-definition-regression-is-a-median-move-over-enough-calls` | 4 | 3 | 0d02eaaf7 health: a definition regression is a p50 move over at least 200 calls ; 2f666b53d reds-012ca: an arity refusal builds (violation passed a nil schema to ; 6c071ada1 definition regressions rank by the time they add: the comparison deriv |
| `seon.turn/end-task!` | 18 | 2 | f540268a1 A task's end reaches root once: the end message's id is the end's occu; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.turn/resume-turn` | 14 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms through fold-so; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form is one no-v |
| `seon.sci.eval/acquire-program!` | 14 | 2 | 050f43b80 host-cost: a reset re-arms nothing it already armed (current-wrapper? ; 30be17208 lazy callers: a branch change binds its reverse closure to interpret e |
| `seon.turn/settle-batch!` | 13 | 2 | 6d2ef7c68 settlement refusal of a form's facts: the refusal rides beside the val; e9002e761 pass-cost: per-form pass work moves to once per pass — namespace looku |
| `seon.test/member-result` | 13 | 2 | f68dd4086 A red on a branch is the change's only if the same test under the same; 48523bb20 Execution handles and the test program fallback stop holding a databas |
| `seon.sci.branch-execution-test/"One` | 12 | 2 | 85b4de483 OOM pid 3360: an is message is evaluated on every assertion; branch-ex; 30be17208 lazy callers: a branch change binds its reverse closure to interpret e |
| `seon.cluster.merge-test/"Three` | 10 | 2 | c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.cluster.turn-test/"Four` | 10 | 2 | 062027664 seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green: all-green ; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions are green (2 |
| `seon.test/run-request` | 10 | 2 | da1210491 seon.test/the-reaching-step-selects-every-branch-test-again: the autom; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.cluster.source/land-held!` | 10 | 2 | 735c6f519 Every refusal seon.cluster.source/land! answers carries its own :seon.; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.cluster.source/write-back!` | 9 | 2 | 0794ffc8b A landing into a file holding uncommitted edits waits for their commit; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.test-support/evaluate` | 9 | 2 | 98d836c57 Revert f0d96f147: the :seon.test/rows flag still hands analyze-forms a; 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its producer fixes |
| `seon.cluster.source/land!` | 9 | 2 | 735c6f519 Every refusal seon.cluster.source/land! answers carries its own :seon.; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.error/reply-value` | 8 | 2 | 32bc482b0 context-refine: an error reply (seon.error/reply-value, the error's AI; 137ff2bff fanout-4: three live deepseek-flash agents' fixes, reviewed by root |
| `seon.mcp/eval-clj` | 8 | 2 | cdce8a37e Expose JVM measurement scope and restore test-body call totals Proof r; a61342a5b Cost entities and a reply's live values rank by self time spent, and i |
| `seon.turn/evaluation-terminal-data` | 8 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms through fold-so; e9002e761 pass-cost: per-form pass work moves to once per pass — namespace looku |
| `seon.turn/task-end` | 8 | 2 | 56d2efa61 A task ends done only when no test its change reaches is red, and a lo; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.sci.eval/regenerate-agent-context!` | 7 | 2 | 8de6239e2 stale-caller (2): own definitions survive a re-acquired base and anoth; a7eff8886 A context's program snapshot holds a dynamic Var's root, never the req |
| `seon.competency/run-state` | 7 | 2 | 56d2efa61 A task ends done only when no test its change reaches is red, and a lo; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.sci.forgive/next-form` | 6 | 2 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one stepper (se |
| `seon.turn.batch/jvm-request` | 6 | 2 | e9002e761 pass-cost: per-form pass work moves to once per pass — namespace looku; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one stepper (se |
| `seon.task/propose!` | 6 | 2 | 36890687e One assignment has at most one open proposal, checked at the writer (t; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.profile-test/a-world-sink-holds-self-time-and-an-observation-is-its-delta` | 6 | 2 | 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its producer fixes; a61342a5b Cost entities and a reply's live values rank by self time spent, and i |
| `seon.render.value/make-node` | 6 | 2 | ffb788f52 review-printer-opus: the value printer's text stays inside its charge ; 131082e0d printer-fix: the core prints with no database (:seon.render.value/unit |
| `seon.turn/end-message` | 6 | 2 | f540268a1 A task's end reaches root once: the end message's id is the end's occu; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.turn/planned-sources` | 5 | 2 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one stepper (se |
| `seon.cluster.source/write-back-commits` | 5 | 2 | 0794ffc8b A landing into a file holding uncommitted edits waits for their commit; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.turn.batch/jvm-show` | 5 | 2 | e9002e761 pass-cost: per-form pass work moves to once per pass — namespace looku; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one stepper (se |
| `seon.turn.batch/derive-form` | 5 | 2 | 76daa8fd5 A definition keeps its namespace row (M8, G6): the pass names a stored; 7f0458ffd dogfood friction 3: a REPL deftest in a namespace with stored refers i |
| `seon.sci.documentation-test/an-example-ends-at-the-next-fixed-heading` | 5 | 2 | 21ca7c530 docstring-parts ends an Example at the next fixed heading and reads a ; 990976364 tasks (lane task-writer): six agent-sized tasks in non-host-bound func |
| `seon.cluster.message/send!` | 5 | 2 | f540268a1 A task's end reaches root once: the end message's id is the end's occu; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.cluster.source/write-and-commit!` | 5 | 2 | 0794ffc8b A landing into a file holding uncommitted edits waits for their commit; c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow |
| `seon.transact-feedback-test/non-temporal-stores-admit-writes-while-a-retention-rule-is-in-force` | 4 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms through fold-so; 0717d5a5f fanout-2 proofs: seon.db/the-missing-connection-refusal-has-no-fix-key |
| `seon.mcp-entrance-test/with-running-agent` | 4 | 2 | de7141e87 mcp-entrance-test: an agent runs its own test on its own branch (regre; 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its producer fixes |
| `seon.flow-world-test/a-proc-step-runs-in-its-environments-world` | 4 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa3 against ; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions are green (2 |
| `seon.health/regressions` | 4 | 2 | 7090225a2 health: what regressed is a query — each stored measure's newest value; 6c071ada1 definition regressions rank by the time they add: the comparison deriv |
| `seon.cluster/ensure-cluster-entity!` | 4 | 2 | 64cb9e80f boot: the process entity's existing boot commit carries its start inst; fb1437312 Revert boot record on the process entity (64cb9e80f): the population t |
| `seon.cluster.boot/stand-cluster-runtime!` | 4 | 2 | 64cb9e80f boot: the process entity's existing boot commit carries its start inst; fb1437312 Revert boot record on the process entity (64cb9e80f): the population t |
| `seon.test.runner/recorded-member-results` | 4 | 2 | 0dd360d1c failed tests count only current evidence and carry their age: a red co; f68dd4086 A red on a branch is the change's only if the same test under the same |
| `seon.error.refusal/post-order` | 4 | 2 | 3071fef19 growth: an error-data walk folds a value it meets again by identity on; 31a53349a refusal-walk-fix: the error-data walk hashes only inside a hash set, s |
| `seon.render.value/core-profile` | 4 | 2 | 131082e0d printer-fix: the core prints with no database (:seon.render.value/unit; 9c88ae5b8 out-fix: the printer's text entries take the db-free unit (render-ai/r |
| `seon.render.value/core-text` | 4 | 2 | 2905b7599 review-batch-d: the core printer is total and its budget fixed; a refu; 131082e0d printer-fix: the core prints with no database (:seon.render.value/unit |
| `seon.render.value/fill-end` | 4 | 2 | ffb788f52 review-printer-opus: the value printer's text stays inside its charge ; 131082e0d printer-fix: the core prints with no database (:seon.render.value/unit |
| `seon.render.transcript/receipt-selector` | 4 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms through fold-so; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form is one no-v |
| `seon.competency-grade-test/"Four` | 4 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa3 against ; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions are green (2 |
| `seon.cluster.source/red-test-verdicts` | 4 | 2 | c53c9306e Revert the night's fences in one batch (fable-replan section 8 Q15, ow; f68dd4086 A red on a branch is the change's only if the same test under the same |

Excluded 15 non-function keys ((ns), (prelude), test-name sets).

### Table B: rework chains, files (4+ touching commits, 2+ fix-subject)

| file | touches | fix-subject | fix sequence (max 6, oldest first) |
|---|---|---|---|
| `resources/seon/tasks/seon.sci.eval.edn` | 90 | 19 | a37d96149 reader recovery: a failure inside a closed collection ends w; 2215ccc7c batch A review, Astra findings: reader regression gains Astr; c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 131082e0d printer-fix: the core prints with no database (:seon.render.; 32ec4d923 Agent-facing messages audit: census of 23 producers measured; b06d986f5 tasks: fanout-5 run findings (3 live deepseek-flash agents o |
| `src/seon/sci/eval.clj` | 100 | 17 | 919be10c5 loaded-commit-id reads the cluster entity's adoption record,; 21ca7c530 docstring-parts ends an Example at the next fixed heading an; 2215ccc7c batch A review, Astra findings: reader regression gains Astr; d3653e987 host-cost: a new branch reuses the store's index nodes (shar; 050f43b80 host-cost: a reset re-arms nothing it already armed (current; d59363530 handle-analysis: a form's result handle is publication's bin |
| `src/seon/turn.clj` | 126 | 14 | 6d2ef7c68 settlement refusal of a form's facts: the refusal rides besi; 18a68b248 forgive-fix: the reply read is linear in the reply; d59363530 handle-analysis: a form's result handle is publication's bin; 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; e9002e761 pass-cost: per-form pass work moves to once per pass — names; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s |
| `resources/seon/tasks/seon.test.edn` | 68 | 12 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; d396a2046 repl-test-edges: regression that a REPL-written deftest reac; 043480710 task seon.test/a-schema-change-has-no-reaching-tests (lane f; 35a5cb284 tasks: fanout-4 run findings (3 live deepseek-flash agents o; da1210491 seon.test/the-reaching-step-selects-every-branch-test-again:; 951cad923 tasks: fanout-6 run findings (3 live deepseek-flash agents o |
| `resources/seon/tasks/seon.turn.edn` | 76 | 11 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; e9002e761 pass-cost: per-form pass work moves to once per pass — names; 32ec4d923 Agent-facing messages audit: census of 23 producers measured; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i; 0f0e77ed8 tasks: fanout-9 findings (root at default c7a7030d8 after re; 18919b663 Law census proofs, red: 6 laws without a proof now have one  |
| `resources/seon/tasks/my.program.edn` | 45 | 11 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 35a5cb284 tasks: fanout-4 run findings (3 live deepseek-flash agents o; b06d986f5 tasks: fanout-5 run findings (3 live deepseek-flash agents o; 951cad923 tasks: fanout-6 run findings (3 live deepseek-flash agents o; 036a0ec13 tasks: fanout-8 findings (3 live deepseek-flash agents at de; 0f0e77ed8 tasks: fanout-9 findings (root at default c7a7030d8 after re |
| `resources/seon/tasks/seon.task.edn` | 39 | 9 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; a04fde197 tasks: fanout-7 findings (3 live deepseek-flash agents on fi; 036a0ec13 tasks: fanout-8 findings (3 live deepseek-flash agents at de; 0f0e77ed8 tasks: fanout-9 findings (root at default c7a7030d8 after re; 18919b663 Law census proofs, red: 6 laws without a proof now have one ; c95848065 seon.task/assign! assigns only on an observed failing proof  |
| `resources/seon/tasks/seon.db.edn` | 47 | 8 | f80a524b2 tasks: a unique rejection's message is the raw Datahike exce; c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; e35e65e1a fanout-3 tasks and proof: typed intents for seon.db/the-miss; a61342a5b Cost entities and a reply's live values rank by self time sp; ff0492e60 Hybrid design for the one path, starting an agent's Flow fro; a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa |
| `test/seon/db_test.clj` | 44 | 8 | 178aa24e0 task-tags (A fix): adoption onto a live store re-derives dig; 9fbe0d3a0 bump-reds: every red after the Datahike bump to dbca76d5 pre; 0717d5a5f fanout-2 proofs: seon.db/the-missing-connection-refusal-has-; 137ff2bff fanout-4: three live deepseek-flash agents' fixes, reviewed ; a61342a5b Cost entities and a reply's live values rank by self time sp; a582e40e3 A test namespace's defs interpret in file order: seon.sci.ev |
| `test/seon/sci/eval_test.clj` | 43 | 7 | a37d96149 reader recovery: a failure inside a closed collection ends w; 808176555 an agent's context binds slurp, spit and file-seq (task seon; a582e40e3 A test namespace's defs interpret in file order: seon.sci.ev; 92a572404 A test member keeps its request's program: seon.sci.eval/pro; a740b1e26 An interpreted test body prints: seon.sci.eval/run-tests bin; 4e1f4717e A copied compiled Var carries its metadata into every contex |
| `src/my/program.clj` | 43 | 7 | 21ca7c530 docstring-parts ends an Example at the next fixed heading an; 33462f71f PB-15b: my.program/breaks reads its gating tests from one re; f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source ren; 77367741d fanout-9b: my.program/nearest ranks every declared name by m; 76525de62 my.program reads take what agents pass: a bare subject for c; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `src/seon/test.clj` | 71 | 6 | da1210491 seon.test/the-reaching-step-selects-every-branch-test-again:; cdce8a37e Expose JVM measurement scope and restore test-body call tota; 92a572404 A test member keeps its request's program: seon.sci.eval/pro; c53c9306e Revert the night's fences in one batch (fable-replan section; f68dd4086 A red on a branch is the change's only if the same test unde; 48523bb20 Execution handles and the test program fallback stop holding |
| `src/seon/db.clj` | 63 | 6 | 2905b7599 review-batch-d: the core printer is total and its budget fix; 178aa24e0 task-tags (A fix): adoption onto a live store re-derives dig; e9002e761 pass-cost: per-form pass work moves to once per pass — names; 137ff2bff fanout-4: three live deepseek-flash agents' fixes, reviewed ; cf4794d43 An unchanged committed contract is admitted at the REPL: the; a61342a5b Cost entities and a reply's live values rank by self time sp |
| `test/seon/turn_test.clj` | 55 | 6 | e79a604ad call preparation: arming joins contracts to the same supplie; 6d2ef7c68 settlement refusal of a form's facts: the refusal rides besi; 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; ae929e364 s2a-fix: delete the orphaned agent-calibration; complete con; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; ab2bbf098 Retire task seon.turn/an-eval-request-on-an-unchanged-progra |
| `docs/seon/plan.md` | 51 | 6 | aa2e97b32 plan §0: chain run 3 moves to comp.text/slug-removes-edge-hy; 748043656 plan §0: chain run 4 green and landed (d3de85d72), first mod; 73a36c1fc Plan pass 2 from the owner's answers (fable-replan §8 Q1–Q16; 43b14d8e9 plan and TASK-CHAIN sanity pass: the merge request is a task; f3c63d57a Runtime follows DB corrected from the design probes: the fro; 798047e3c plan §0: the owner's build sequence of 2026-10-11 evening (r |
| `AGENTS.md` | 46 | 6 | 8f5a3fe22 AGENTS: Opus builds at the REPL, Astra follows each slice pr; 5fb801855 AGENTS.md: the right context is the product; one reference r; c722d8d22 AGENTS.md: NO GUARDS OR FENCES (owner, 2026-10-11). A guard ; 73a36c1fc Plan pass 2 from the owner's answers (fable-replan §8 Q1–Q16; 75f7d5152 AGENTS.md: design round revised (owner 2026-10-12) — thoroug; 64668ecae AGENTS.md (owner 2026-10-12): a performance commit is proven |
| `src/seon/task.clj` | 45 | 6 | 178aa24e0 task-tags (A fix): adoption onto a live store re-derives dig; f478bdee1 task-tags (A fix 2): adoption derives the symbol of every ta; c95848065 seon.task/assign! assigns only on an observed failing proof ; 36890687e One assignment has at most one open proposal, checked at the; c53c9306e Revert the night's fences in one batch (fable-replan section; cc56ee31a Retire the fixed seon.task create! task and fold the open-ta |
| `src/seon/context.clj` | 44 | 6 | 15ef7b9b4 a-handled-message-stays-unread-in-every-later-pass: the curr; edfa957c1 the-system-message-is-the-printed-help-map-not-its-render: m; 062027664 seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green: ; 56d2efa61 A task ends done only when no test its change reaches is red; c53c9306e Revert the night's fences in one batch (fable-replan section; f68dd4086 A red on a branch is the change's only if the same test unde |
| `src/seon/error.clj` | 36 | 6 | 053460631 a fault resolves by a green stored run, or by its producer's; 822dc7021 task seon.error/commit-tx-stored-the-whole-admitted-data-edn; 32bc482b0 context-refine: an error reply (seon.error/reply-value, the ; 137ff2bff fanout-4: three live deepseek-flash agents' fixes, reviewed ; 6e9b9017c seon.error/faults-form shows no read for a render value that; 982a4e1ad fanout-6: two live deepseek-flash agents' fixes in seon.erro |
| `resources/seon/tasks/seon.error.edn` | 28 | 6 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 32ec4d923 Agent-facing messages audit: census of 23 producers measured; 822dc7021 task seon.error/commit-tx-stored-the-whole-admitted-data-edn; a04fde197 tasks: fanout-7 findings (3 live deepseek-flash agents on fi; 990976364 tasks (lane task-writer): six agent-sized tasks in non-host-; 4dc20e21a Parity probe after the fence revert: bench task green in 2 c |
| `test/seon/turn_continue_test.clj` | 28 | 6 | 56d2efa61 A task ends done only when no test its change reaches is red; f540268a1 A task's end reaches root once: the end message's id is the ; ad86afd43 A model pass's redefinition reaches the base's copy of the a; 0794ffc8b A landing into a file holding uncommitted edits waits for th; c53c9306e Revert the night's fences in one batch (fable-replan section; f68dd4086 A red on a branch is the change's only if the same test unde |
| `test/seon/health_test.clj` | 14 | 6 | f2d5d16b0 health: replaced roots ride the report as seon.instrument/re; 7090225a2 health: what regressed is a query — each stored measure's ne; 0d02eaaf7 health: a definition regression is a p50 move over at least ; 2f666b53d reds-012ca: an arity refusal builds (violation passed a nil ; 6c071ada1 definition regressions rank by the time they add: the compar; a8e3bde5b definition-comparisons' input contract requires only what th |
| `resources/seon/tasks/seon.cluster.source.edn` | 44 | 5 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 4ad66021f Task seon.cluster.source/merge-base-survives-collection with; 735c6f519 Every refusal seon.cluster.source/land! answers carries its ; 0794ffc8b A landing into a file holding uncommitted edits waits for th; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `test/seon/cluster/turn_test.clj` | 43 | 5 | a755e5eda Revert c2b4f51f6: the installed program shows only two passe; 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 062027664 seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green: ; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `src/seon/test/runner.clj` | 38 | 5 | bbba30dc4 housekeeping: remove fixed/dangling known-issue tags (e13e5a; 0dd360d1c failed tests count only current evidence and carry their age; da1210491 seon.test/the-reaching-step-selects-every-branch-test-again:; c53c9306e Revert the night's fences in one batch (fable-replan section; f68dd4086 A red on a branch is the change's only if the same test unde |
| `resources/seon/tasks/seon.cluster.agent.edn` | 37 | 5 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; ff0492e60 Hybrid design for the one path, starting an agent's Flow fro; a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar; 87e1476b8 Retire two fixed seon.cluster.agent tasks (wrapped stop inte |
| `test/seon/test_support.clj` | 34 | 5 | 98d836c57 Revert f0d96f147: the :seon.test/rows flag still hands analy; c7559abb1 review batch C (opus + Astra verified): root bindings also c; 6098f0359 test-cost2: fixtures define declarations through the fold (s; 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; 48523bb20 Execution handles and the test program fallback stop holding |
| `test/my/program_test.clj` | 28 | 5 | f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source ren; 808176555 an agent's context binds slurp, spit and file-seq (task seon; 340462543 Real task for chain run 5: my.program/a-search-snippet-keeps; 76525de62 my.program reads take what agents pass: a bare subject for c; b1b94c0d6 Retire task my.program/a-function-called-through-its-callers |
| `test/seon/cluster/merge_test.clj` | 27 | 5 | 735c6f519 Every refusal seon.cluster.source/land! answers carries its ; 36890687e One assignment has at most one open proposal, checked at the; 0794ffc8b A landing into a file holding uncommitted edits waits for th; c53c9306e Revert the night's fences in one batch (fable-replan section; f68dd4086 A red on a branch is the change's only if the same test unde |
| `resources/seon/tasks/seon.turn.batch.edn` | 20 | 5 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 32ec4d923 Agent-facing messages audit: census of 23 producers measured; 7f0458ffd dogfood friction 3: a REPL deftest in a namespace with store; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `src/seon/health.clj` | 18 | 5 | f2d5d16b0 health: replaced roots ride the report as seon.instrument/re; 7090225a2 health: what regressed is a query — each stored measure's ne; 0d02eaaf7 health: a definition regression is a p50 move over at least ; 6c071ada1 definition regressions rank by the time they add: the compar; a8e3bde5b definition-comparisons' input contract requires only what th |
| `src/seon/render/value.clj` | 16 | 5 | 8e3aebc5f The reply of a defn is the Var's declared render: the root g; 2905b7599 review-batch-d: the core printer is total and its budget fix; ffb788f52 review-printer-opus: the value printer's text stays inside i; 131082e0d printer-fix: the core prints with no database (:seon.render.; 9c88ae5b8 out-fix: the printer's text entries take the db-free unit (r |
| `resources/seon/schemas/seon.health.edn` | 11 | 5 | f2d5d16b0 health: replaced roots ride the report as seon.instrument/re; 7090225a2 health: what regressed is a query — each stored measure's ne; 0d02eaaf7 health: a definition regression is a p50 move over at least ; 6c071ada1 definition regressions rank by the time they add: the compar; a8e3bde5b definition-comparisons' input contract requires only what th |
| `src/seon/turn/batch.clj` | 40 | 4 | e9002e761 pass-cost: per-form pass work moves to once per pass — names; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 76daa8fd5 A definition keeps its namespace row (M8, G6): the pass name; 7f0458ffd dogfood friction 3: a REPL deftest in a namespace with store |
| `resources/seon/schemas/seon.task.edn` | 40 | 4 | 56d2efa61 A task ends done only when no test its change reaches is red; c95848065 seon.task/assign! assigns only on an observed failing proof ; 36890687e One assignment has at most one open proposal, checked at the; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `src/seon/cluster/source.clj` | 37 | 4 | 735c6f519 Every refusal seon.cluster.source/land! answers carries its ; 0794ffc8b A landing into a file holding uncommitted edits waits for th; c53c9306e Revert the night's fences in one batch (fable-replan section; f68dd4086 A red on a branch is the change's only if the same test unde |
| `test/seon/error_test.clj` | 28 | 4 | 32bc482b0 context-refine: an error reply (seon.error/reply-value, the ; 6e9b9017c seon.error/faults-form shows no read for a render value that; 990976364 tasks (lane task-writer): six agent-sized tasks in non-host-; 4dc20e21a Parity probe after the fence revert: bench task green in 2 c |
| `resources/seon/tasks/seon.operator.edn` | 28 | 4 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 0256a98b5 Tasks from the fence revert verification: seon.turn/a-landin; a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `test/seon/mcp_entrance_test.clj` | 26 | 4 | de7141e87 mcp-entrance-test: an agent runs its own test on its own bra; 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; d396a2046 repl-test-edges: regression that a REPL-written deftest reac |
| `test/seon/cluster/store_test.clj` | 16 | 4 | b1ad96fb9 konserve 8700ca7: a tiered store's background warm never rep; c7559abb1 review batch C (opus + Astra verified): root bindings also c; 050f43b80 host-cost: a reset re-arms nothing it already armed (current; a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa |
| `src/seon/problems.clj` | 16 | 4 | f2d5d16b0 health: replaced roots ride the report as seon.instrument/re; f184e5927 problems: an open fault's unit names its producer (the signa; 0dd360d1c failed tests count only current evidence and carry their age; 016a46853 errored receipts age out: the finding carries the receipt's  |
| `test/seon/sci/forgive_test.clj` | 14 | 4 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i; 340462543 Real task for chain run 5: my.program/a-search-snippet-keeps |
| `test/seon/ref_test.clj` | 14 | 4 | 2de59d8b5 REF-1 fix: a referenced function's stored arglists read in i; 0717d5a5f fanout-2 proofs: seon.db/the-missing-connection-refusal-has-; 990976364 tasks (lane task-writer): six agent-sized tasks in non-host-; aeaf6f1fc Three real tasks for the next loop round, red: seon.print/a- |
| `resources/seon/tasks/seon.sci.forgive.edn` | 12 | 4 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i; 340462543 Real task for chain run 5: my.program/a-search-snippet-keeps |
| `src/seon/sci/kernel.clj` | 11 | 4 | bbba30dc4 housekeeping: remove fixed/dangling known-issue tags (e13e5a; e9002e761 pass-cost: per-form pass work moves to once per pass — names; f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source ren; 808176555 an agent's context binds slurp, spit and file-seq (task seon |
| `resources/seon/schemas/seon.problems.edn` | 11 | 4 | f184e5927 problems: an open fault's unit names its producer (the signa; 0dd360d1c failed tests count only current evidence and carry their age; 016a46853 errored receipts age out: the finding carries the receipt's ; 191627656 s5a-fix: the phase-failure arm holds its planned forms throu |
| `test/seon/turn/batch_test.clj` | 11 | 4 | 76daa8fd5 A definition keeps its namespace row (M8, G6): the pass name; 7f0458ffd dogfood friction 3: a REPL deftest in a namespace with store; cf4794d43 An unchanged committed contract is admitted at the REPL: the; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `docs/seon/schedule/agent-loop-ledger.md` | 45 | 3 | e9b0be2dd decisions: reconcile the redesign and landed-work reviews in; 0cbc0af32 ledger: rows for every unscheduled finding of the 2026-10-08; 043480710 task seon.test/a-schema-change-has-no-reaching-tests (lane f |
| `src/seon/cluster.clj` | 41 | 3 | 64cb9e80f boot: the process entity's existing boot commit carries its ; fb1437312 Revert boot record on the process entity (64cb9e80f): the po; bbba30dc4 housekeeping: remove fixed/dangling known-issue tags (e13e5a |
| `docs/seon/specs/agent-loop.md` | 29 | 3 | 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 756b73410 agent-loop spec: record the merge rulings (old red stops aut; 062027664 seon.turn/the-turn-loop-stops-on-prose-and-runs-past-green:  |
| `resources/seon/schemas/seon.batch.edn` | 27 | 3 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `resources/seon/schemas/seon.agent.edn` | 27 | 3 | 56d2efa61 A task ends done only when no test its change reaches is red; c53c9306e Revert the night's fences in one batch (fable-replan section; 48523bb20 Execution handles and the test program fallback stop holding |
| `test/seon/context_test.clj` | 27 | 3 | 15ef7b9b4 a-handled-message-stays-unread-in-every-later-pass: the curr; edfa957c1 the-system-message-is-the-printed-help-map-not-its-render: m; 0256a98b5 Tasks from the fence revert verification: seon.turn/a-landin |
| `docs/research/agent-platform/2026-10-08/morning-report.md` | 27 | 3 | e734a9a9d morning report: cache weights fixed, growth found; f782d0ca6 morning report: fan-out 5 fixes, PB-08, CC-3/CC-4; 13fd77d71 morning report: interpreted file test fixed |
| `src/seon/mcp.clj` | 26 | 3 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; cdce8a37e Expose JVM measurement scope and restore test-body call tota; a61342a5b Cost entities and a reply's live values rank by self time sp |
| `src/seon/repl.clj` | 25 | 3 | 5c627afc9 repl reply: :repeated calls shown only beside a :slow findin; 131082e0d printer-fix: the core prints with no database (:seon.render.; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `src/seon/profile.clj` | 24 | 3 | 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; cdce8a37e Expose JVM measurement scope and restore test-body call tota; a61342a5b Cost entities and a reply's live values rank by self time sp |
| `test/seon/render/value_test.clj` | 22 | 3 | 2215ccc7c batch A review, Astra findings: reader regression gains Astr; 131082e0d printer-fix: the core prints with no database (:seon.render.; 9c88ae5b8 out-fix: the printer's text entries take the db-free unit (r |
| `test/seon/request_cost_test.clj` | 22 | 3 | cdce8a37e Expose JVM measurement scope and restore test-body call tota; a61342a5b Cost entities and a reply's live values rank by self time sp; a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa |
| `test/seon/test_test.clj` | 20 | 3 | 98d836c57 Revert f0d96f147: the :seon.test/rows flag still hands analy; 92a572404 A test member keeps its request's program: seon.sci.eval/pro; 4e1f4717e A copied compiled Var carries its metadata into every contex |
| `resources/seon/schemas/seon.source.edn` | 19 | 3 | 735c6f519 Every refusal seon.cluster.source/land! answers carries its ; 0794ffc8b A landing into a file holding uncommitted edits waits for th; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `resources/seon/tasks/seon.repl.edn` | 18 | 3 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 131082e0d printer-fix: the core prints with no database (:seon.render.; 32ec4d923 Agent-facing messages audit: census of 23 producers measured |
| `src/seon/render/transcript.clj` | 18 | 3 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; ae929e364 s2a-fix: delete the orphaned agent-calibration; complete con; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `resources/seon/tasks/seon.render.web.edn` | 17 | 3 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; ae929e364 s2a-fix: delete the orphaned agent-calibration; complete con; 0256a98b5 Tasks from the fence revert verification: seon.turn/a-landin |
| `resources/seon/tasks/seon.render.value.edn` | 17 | 3 | 191133cf8 tasks: seon.render and seon.render.value task files are one ; c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 131082e0d printer-fix: the core prints with no database (:seon.render. |
| `test/seon/profile_test.clj` | 17 | 3 | 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; a61342a5b Cost entities and a reply's live values rank by self time sp; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `src/seon/instrument.clj` | 16 | 3 | 2f666b53d reds-012ca: an arity refusal builds (violation passed a nil ; 050f43b80 host-cost: a reset re-arms nothing it already armed (current; aa15e0ef0 seon.instrument: closest-alternatives never walks a database |
| `resources/seon/tasks/my.test.edn` | 15 | 3 | 32ec4d923 Agent-facing messages audit: census of 23 producers measured; 56d2efa61 A task ends done only when no test its change reaches is red; 0bbe82e49 my.test/check runs its tests again when asked: :my.test/chec |
| `test/seon/flow_test.clj` | 14 | 3 | c7559abb1 review batch C (opus + Astra verified): root bindings also c; 2905b7599 review-batch-d: the core printer is total and its budget fix; 0094f293b flow: a Flow proc thread lives in its projection world throu |
| `src/seon/sci/forgive.clj` | 13 | 3 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `test/seon/instrument_test.clj` | 12 | 3 | 18ac6677a instrument-test: require seon.batch for the world regression; 2f666b53d reds-012ca: an arity refusal builds (violation passed a nil ; 050f43b80 host-cost: a reset re-arms nothing it already armed (current |
| `resources/seon/tasks/seon.instrument.edn` | 12 | 3 | 7794623df tasks: seon.instrument/a-call-missing-an-argument-is-refused; ff0492e60 Hybrid design for the one path, starting an agent's Flow fro; ab91b58ee Retire task seon.instrument/an-arity-refusal-offers-the-func |
| `test/seon/config_test.clj` | 11 | 3 | e9002e761 pass-cost: per-form pass work moves to once per pass — names; 89d6399d9 dial-adopt: the older-row regression names its task; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `test/seon/problems_test.clj` | 11 | 3 | 053460631 a fault resolves by a green stored run, or by its producer's; 0dd360d1c failed tests count only current evidence and carry their age; 016a46853 errored receipts age out: the finding carries the receipt's  |
| `docs/seon/schedule/cards/TASK-CHAIN.md` | 11 | 3 | 73a36c1fc Plan pass 2 from the owner's answers (fable-replan §8 Q1–Q16; 43b14d8e9 plan and TASK-CHAIN sanity pass: the merge request is a task; f3c63d57a Runtime follows DB corrected from the design probes: the fro |
| `test/seon/sci/reader_test.clj` | 10 | 3 | a37d96149 reader recovery: a failure inside a closed collection ends w; 2215ccc7c batch A review, Astra findings: reader regression gains Astr; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s |
| `src/seon/fn/analyzer.clj` | 10 | 3 | e09df7854 Revert b57ba36e0 (content-valid analyzer cache): boot refuse; 073a349a4 Revert the content-valid analyzer cache (astra's b57ba36e0, ; 050f43b80 host-cost: a reset re-arms nothing it already armed (current |
| `resources/seon/tasks/seon.config.edn` | 8 | 3 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar; a379516d2 Restore retained-builder fixture through the canonical diges |
| `resources/seon/tasks/seon.print.edn` | 7 | 3 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 32ec4d923 Agent-facing messages audit: census of 23 producers measured; aeaf6f1fc Three real tasks for the next loop round, red: seon.print/a- |
| `test/my/program_read_test.clj` | 6 | 3 | 990976364 tasks (lane task-writer): six agent-sized tasks in non-host-; 340462543 Real task for chain run 5: my.program/a-search-snippet-keeps; aeaf6f1fc Three real tasks for the next loop round, red: seon.print/a- |
| `test/seon/sci/forgive_replies.edn` | 5 | 3 | 18a68b248 forgive-fix: the reply read is linear in the reply; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `docs/research/agent-platform/landing/nil-query-inputs-2026-09-28.md` | 4 | 3 | e09df7854 Revert b57ba36e0 (content-valid analyzer cache): boot refuse; 073a349a4 Revert the content-valid analyzer cache (astra's b57ba36e0, ; c9fc29434 restore astra's analyzer landing notes and probe evidence th |
| `src/seon/fn.clj` | 41 | 2 | e5cfb80dc fn, schema: a contract's text is seon.id/printed, the one fi; ceb09bab9 A namespace's fixtures are data on its entity, so a world ac |
| `src/seon/batch.clj` | 38 | 2 | 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s; cdce8a37e Expose JVM measurement scope and restore test-body call tota |
| `test/seon/cluster/host_test.clj` | 35 | 2 | d3653e987 host-cost: a new branch reuses the store's index nodes (shar; 050f43b80 host-cost: a reset re-arms nothing it already armed (current |
| `test/seon/test_reaching_test.clj` | 27 | 2 | 98d836c57 Revert f0d96f147: the :seon.test/rows flag still hands analy; da1210491 seon.test/the-reaching-step-selects-every-branch-test-again: |
| `src/seon/schema.clj` | 25 | 2 | e5cfb80dc fn, schema: a contract's text is seon.id/printed, the one fi; 0094f293b flow: a Flow proc thread lives in its projection world throu |
| `src/seon/cluster/boot.clj` | 24 | 2 | 64cb9e80f boot: the process entity's existing boot commit carries its ; fb1437312 Revert boot record on the process entity (64cb9e80f): the po |
| `resources/seon/tasks/seon.flow.edn` | 24 | 2 | 89463b86b post-restart notes: threads are a konserve-in-go burst, boot; ff0492e60 Hybrid design for the one path, starting an agent's Flow fro |
| `test/seon/sci/documentation_test.clj` | 20 | 2 | 21ca7c530 docstring-parts ends an Example at the next fixed heading an; 990976364 tasks (lane task-writer): six agent-sized tasks in non-host- |
| `src/seon/program.cljc` | 19 | 2 | 8f78890f1 replacement-tx: a set attribute with nothing added is no key; 735c6f519 Every refusal seon.cluster.source/land! answers carries its  |
| `resources/seon/tasks/seon.fn.edn` | 19 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 35a5cb284 tasks: fanout-4 run findings (3 live deepseek-flash agents o |
| `test/seon/sci/branch_execution_test.clj` | 18 | 2 | 85b4de483 OOM pid 3360: an is message is evaluated on every assertion;; 30be17208 lazy callers: a branch change binds its reverse closure to i |
| `test/seon/repl_test.clj` | 18 | 2 | 5c627afc9 repl reply: :repeated calls shown only beside a :slow findin; 131082e0d printer-fix: the core prints with no database (:seon.render. |
| `resources/seon/tasks/seon.bootstrap.edn` | 17 | 2 | 32ec4d923 Agent-facing messages audit: census of 23 producers measured; e35e65e1a fanout-3 tasks and proof: typed intents for seon.db/the-miss |
| `test/seon/cluster/agent_test.clj` | 16 | 2 | 8e3aebc5f The reply of a defn is the Var's declared render: the root g; 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc |
| `test/seon/program_test.clj` | 16 | 2 | 8f78890f1 replacement-tx: a set attribute with nothing added is no key; 735c6f519 Every refusal seon.cluster.source/land! answers carries its  |
| `test/seon/render/transcript_test.clj` | 16 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; 131082e0d printer-fix: the core prints with no database (:seon.render. |
| `src/seon/cluster/store.clj` | 16 | 2 | c7559abb1 review batch C (opus + Astra verified): root bindings also c; d3653e987 host-cost: a new branch reuses the store's index nodes (shar |
| `resources/seon/tasks/seon.schema.edn` | 16 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 951cad923 tasks: fanout-6 run findings (3 live deepseek-flash agents o |
| `src/seon/error/refusal.clj` | 16 | 2 | 3071fef19 growth: an error-data walk folds a value it meets again by i; 31a53349a refusal-walk-fix: the error-data walk hashes only inside a h |
| `test/seon/error/attribution_test.clj` | 16 | 2 | f184e5927 problems: an open fault's unit names its producer (the signa; 053460631 a fault resolves by a green stored run, or by its producer's |
| `resources/seon/tasks/seon.cluster.registry.edn` | 16 | 2 | ff0492e60 Hybrid design for the one path, starting an agent's Flow fro; a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa |
| `resources/seon/tasks/seon.batch.edn` | 16 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s |
| `test/seon/repl_differential_test.clj` | 16 | 2 | 8de6239e2 stale-caller (2): own definitions survive a re-acquired base; 30be17208 lazy callers: a branch change binds its reverse closure to i |
| `resources/seon/tasks/my.agent.edn` | 15 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 340462543 Real task for chain run 5: my.program/a-search-snippet-keeps |
| `test/seon/task_ingestion_test.clj` | 15 | 2 | 18919b663 Law census proofs, red: 6 laws without a proof now have one ; c95848065 seon.task/assign! assigns only on an observed failing proof  |
| `docs/research/agent-platform/2026-10-07/root-plan/live-agents-plan-notes.md` | 14 | 2 | 55a0d183f root: budgets on every slice (no multi-second, no multi-MB/G; 75677774e root: plan v4.1 from both third-round reviews (*open* kept u |
| `resources/seon/tasks/seon.ref.edn` | 14 | 2 | 990976364 tasks (lane task-writer): six agent-sized tasks in non-host-; aeaf6f1fc Three real tasks for the next loop round, red: seon.print/a- |
| `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md` | 14 | 2 | f46d4e524 Hybrid done-and-merge revised: narrow-check failures fixed, ; 2b813c4ec Hybrid done-and-merge: second narrow check fixed (satisfied  |
| `test/seon/help_test.clj` | 13 | 2 | a9e2bcf6d help-teaches-a-placeholder-checkout-path: help drops the wri; e35e65e1a fanout-3 tasks and proof: typed intents for seon.db/the-miss |
| `resources/seon/schemas/seon.cluster.eval.edn` | 13 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `resources/seon/schemas/seon.profile.edn` | 13 | 2 | 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; cdce8a37e Expose JVM measurement scope and restore test-body call tota |
| `resources/seon/tasks/seon.render.edn` | 13 | 2 | 191133cf8 tasks: seon.render and seon.render.value task files are one ; e9002e761 pass-cost: per-form pass work moves to once per pass — names |
| `docs/seon/specs/errors.md` | 13 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; 8b3fe405e Errors spec: P3 slice is committed with its regression (ledg |
| `resources/seon/tasks/seon.cluster.host.edn` | 13 | 2 | d3653e987 host-cost: a new branch reuses the store's index nodes (shar; 050f43b80 host-cost: a reset re-arms nothing it already armed (current |
| `resources/seon/tasks/seon.mcp.edn` | 12 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 191627656 s5a-fix: the phase-failure arm holds its planned forms throu |
| `resources/seon/tasks/seon.sci.kernel.edn` | 12 | 2 | bbba30dc4 housekeeping: remove fixed/dangling known-issue tags (e13e5a; c07e86373 persist-tasks: 83 runtime tasks of default move into resourc |
| `test/seon/competency.clj` | 12 | 2 | 56d2efa61 A task ends done only when no test its change reaches is red; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `test/seon/render/web_context_test.clj` | 11 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; 0256a98b5 Tasks from the fence revert verification: seon.turn/a-landin |
| `test/seon/test/selection_test.clj` | 11 | 2 | 6098f0359 test-cost2: fixtures define declarations through the fold (s; da1210491 seon.test/the-reaching-step-selects-every-branch-test-again: |
| `resources/seon/schemas/seon.repl.edn` | 11 | 2 | 131082e0d printer-fix: the core prints with no database (:seon.render.; 4c3be3361 reader-fix (LR-READ, LR-REPAIR): a discard starting a form i |
| `src/seon/call_preparation.clj` | 11 | 2 | e79a604ad call preparation: arming joins contracts to the same supplie; f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source ren |
| `resources/seon/tasks/seon.test-support.edn` | 11 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 6098f0359 test-cost2: fixtures define declarations through the fold (s |
| `resources/seon/tasks/seon.cluster.boot.edn` | 11 | 2 | 050f43b80 host-cost: a reset re-arms nothing it already armed (current; fb46050c8 status-answers-while-a-test-agent-stands-in-the-roster: the  |
| `test/seon/transact_feedback_test.clj` | 10 | 2 | 191627656 s5a-fix: the phase-failure arm holds its planned forms throu; 0717d5a5f fanout-2 proofs: seon.db/the-missing-connection-refusal-has- |
| `resources/seon/schemas/seon.test.edn` | 10 | 2 | da1210491 seon.test/the-reaching-step-selects-every-branch-test-again:; f68dd4086 A red on a branch is the change's only if the same test unde |
| `resources/seon/tasks/seon.cluster.store.edn` | 10 | 2 | c07e86373 persist-tasks: 83 runtime tasks of default move into resourc; 18919b663 Law census proofs, red: 6 laws without a proof now have one  |
| `test/my/test_check_test.clj` | 10 | 2 | 0bbe82e49 my.test/check runs its tests again when asked: :my.test/chec; 4dc20e21a Parity probe after the fence revert: bench task green in 2 c |
| `resources/seon/schemas/my.program.edn` | 9 | 2 | f5eaac1f4 context round 4 (fan-out 8 frictions): my.program/source ren; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `resources/seon/tasks/seon.sci.reader.edn` | 9 | 2 | a37d96149 reader recovery: a failure inside a closed collection ends w; c07e86373 persist-tasks: 83 runtime tasks of default move into resourc |
| `src/seon/sci/arm.clj` | 9 | 2 | 30be17208 lazy callers: a branch change binds its reverse closure to i; ceb09bab9 A namespace's fixtures are data on its entity, so a world ac |
| `src/seon/ref.clj` | 9 | 2 | 2de59d8b5 REF-1 fix: a referenced function's stored arglists read in i; 849ad0a2a fanout-9b: seon.ref/span-candidate answers a name only for a |
| `src/seon/cluster/message.clj` | 8 | 2 | f540268a1 A task's end reaches root once: the end message's id is the ; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `docs/research/agent-platform/2026-10-11/runtime-follows-db.md` | 8 | 2 | e7dbabe9b Runtime follows DB: the resume path and its keys (commit id ; f3c63d57a Runtime follows DB corrected from the design probes: the fro |
| `resources/seon/tasks/seon.print.value.edn` | 7 | 2 | 131082e0d printer-fix: the core prints with no database (:seon.render.; 340462543 Real task for chain run 5: my.program/a-search-snippet-keeps |
| `src/seon/print.cljc` | 7 | 2 | ffb788f52 review-printer-opus: the value printer's text stays inside i; 131082e0d printer-fix: the core prints with no database (:seon.render. |
| `resources/seon/tasks/seon.competency.edn` | 7 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `test/seon/competency_grade_test.clj` | 7 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `test/seon/render/root_pull_test.clj` | 6 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `resources/seon/tasks/seon.ai.edn` | 6 | 2 | b06d986f5 tasks: fanout-5 run findings (3 live deepseek-flash agents o; 951cad923 tasks: fanout-6 run findings (3 live deepseek-flash agents o |
| `script/seon/operator_move_test.clj` | 5 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `test/seon/test/seed_preparation_test.clj` | 5 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `test/seon/flow_world_test.clj` | 5 | 2 | a997a81ef Central round 2026-10-12: the compiled full tier on cd59c9fa; aee027806 Central round 3 on 2319152dc: all 125 round-1 regressions ar |
| `.gitmodules` | 5 | 2 | e09df7854 Revert b57ba36e0 (content-valid analyzer cache): boot refuse; 073a349a4 Revert the content-valid analyzer cache (astra's b57ba36e0,  |
| `test/seon/fn/analyzer_test.clj` | 5 | 2 | e09df7854 Revert b57ba36e0 (content-valid analyzer cache): boot refuse; 073a349a4 Revert the content-valid analyzer cache (astra's b57ba36e0,  |
| `resources/seon/schemas/seon.render.value.edn` | 5 | 2 | 131082e0d printer-fix: the core prints with no database (:seon.render.; 9c88ae5b8 out-fix: the printer's text entries take the db-free unit (r |
| `src/seon/sci/reader.cljc` | 5 | 2 | a37d96149 reader recovery: a failure inside a closed collection ends w; 84a3c5e4d s2b1-code: S2b-1 — the reader rules of spec 4.3 in the one s |
| `docs/research/agent-platform/2026-10-07/task-tags/task-tags-notes.md` | 5 | 2 | 178aa24e0 task-tags (A fix): adoption onto a live store re-derives dig; f478bdee1 task-tags (A fix 2): adoption derives the symbol of every ta |
| `test/seon/pass_probe.clj` | 5 | 2 | 7737b8844 s0-probe: the S0 pass probe (seon.pass-probe) and its produc; e9002e761 pass-cost: per-form pass work moves to once per pass — names |
| `docs/research/agent-platform/2026-10-07/waste/error-frames-notes.md` | 4 | 2 | 56eba1249 2026-10-07 research, reviews, measurements and schedule move; c7559abb1 review batch C (opus + Astra verified): root bindings also c |
| `resources/seon/schemas/seon.git.edn` | 4 | 2 | 0794ffc8b A landing into a file holding uncommitted edits waits for th; c53c9306e Revert the night's fences in one batch (fable-replan section |
| `docs/research/agent-platform/2026-10-11/fable-replan.md` | 4 | 2 | b35b5ef8f Fable replan 2026-10-11, pass 1: what we tried and learned, ; 8766caefc fable-replan §8: owner answers, batch 1 (Q1 concise perf lin |

### Table C: git reverts in the window

| revert | day | reverted | reverted subject | lived | reason in the revert message |
|---|---|---|---|---|---|
| e09df7854 | 2026-09-28 | b57ba36e0 | Validate reached analyzer cache entries by source and jar content | 3 min | Revert b57ba36e0 (content-valid analyzer cache): boot refused twice with 'Source changed during analysis; clj-kondo cache entries changed again' — the check finds clojurescript and tools.reader jar entries stale again right after rebuilding them (operator-star |
| 073a349a4 | 2026-09-28 | b57ba36e0 | Validate reached analyzer cache entries by source and jar content | 4 min | Revert the content-valid analyzer cache (astra's b57ba36e0, whose commit e09df7854 carries a wrong message: root's failed revert amended it): boot refused twice with 'Source changed during analysis; clj-kondo cache entries changed again' — the check finds the  |
| fb1437312 | 2026-09-28 | 64cb9e80f | boot: the process entity's existing boot commit carries its start instant, published sourc | 3 min | Revert boot record on the process entity (64cb9e80f): the population transaction wrote :seon.db.process/layers before the attribute was installed, so HEAD refused to boot. Root committed it unproven; it returns as a patch to be proven by adoption on a branch o |
| a755e5eda | 2026-09-28 | c2b4f51f6 | turn-test: an unreadable reply's third pass opens the next run (close-folded-call 8b44bdd2 | 23 s | Revert c2b4f51f6: the installed program shows only two passes, not [:open :call :open]; the unreadable-reply item is undiagnosed |
| 98d836c57 | 2026-09-28 | f0d96f147 | settlement-row tests: test-support/evaluate with :seon.test/rows runs the derive stage (no | 3 min | Revert f0d96f147: the :seon.test/rows flag still hands analyze-forms a nil row (seon.issue-settlement-test red unchanged after restart); the row-reading tests move to the run-fold rewrite (docs/seon/specs/run.md) instead of a test-helper mode flag |
| c53c9306e | 2026-10-11 | c722d8d22 | AGENTS.md: NO GUARDS OR FENCES (owner, 2026-10-11). A guard or fence is always the wrong s | 50 min | Revert the night's fences in one batch (fable-replan section 8 Q15, owner answer 2026-10-11; AGENTS.md NO GUARDS OR FENCES, c722d8d22) Reverted, generated with git revert in a throwaway clone and applied to claimed files: 5261c17eb (accept fences the target pr |
| c2284b109 | 2026-10-12 | f18ab95e3 | Cache repeated datom attributes in the Fressian stream | 75 min | Revert f18ab95e3: Cache repeated datom attributes in the Fressian stream Point reference-code/datahike back at c9e57c6f and restore the datahike-fork-test datom serialization test. The fork change (writeObject with the cache flag on the datom attribute) measur |

Mislabeled (subject starts with Revert but reverts nothing): d9715071e `Revert verification baseline: the compiled full tier before the fence ` (verification report); 1aad413c1 `Revert verification: the compiled full tier after the fence revert (c5` (verification report)

### Table D: churn (top-level defs, whole window, not sampled)

Raw: `CHURN names_with_add_in_window=3767 add_then_delete_in_window=553 add_delete_add=18 last_event_delete=537`

Names with a top-level add event in the window: 3,767. Add followed by removal inside the window: 553. Add, remove, add again: 18. Last window event is removal (absent at window end): 537. Inferred: most of the 537 are moves and refactors, not abandoned work; not separated here.

### Table E: bump commits (touch deps.edn or a reference-code gitlink), per day

| day | bumps | total |
|---|---|---|
| 2026-09-28 | 10 | |
| 2026-10-06 | 1 | |
| 2026-10-07 | 2 | |
| 2026-10-08 | 9 | |
| 2026-10-09 | 5 | |
| 2026-10-10 | 5 | |
| 2026-10-11 | 2 | |
| 2026-10-12 | 6 | |
| total | 40 | |

### Table F: signals per day (case-insensitive, subject plus body)

| day | regression | red on HEAD | RESET NEEDED | pre-existing | flaky | workaround | no demonstrated |
|---|---|---|---|---|---|---|---|
| 2026-09-28 | 53 | 1 | 0 | 3 | 0 | 0 | 0 |
| 2026-10-06 | 8 | 0 | 0 | 0 | 0 | 0 | 0 |
| 2026-10-07 | 34 | 13 | 1 | 0 | 0 | 0 | 0 |
| 2026-10-08 | 17 | 5 | 2 | 3 | 0 | 0 | 0 |
| 2026-10-09 | 12 | 6 | 0 | 2 | 0 | 0 | 0 |
| 2026-10-10 | 7 | 49 | 1 | 3 | 0 | 0 | 0 |
| 2026-10-11 | 8 | 10 | 2 | 3 | 0 | 0 | 0 |
| 2026-10-12 | 21 | 23 | 4 | 5 | 1 | 1 | 0 |
| total | 160 | 107 | 10 | 19 | 1 | 1 | 0 |

### Table G: lane throughput, commits per day by Co-Authored-By model line

| day | Claude Opus 5.5 | Claude Sonnet 5.5 | Claude Fable 5.1 | Claude Haiku 5.5 | Claude Haiku 4.5 | (no Claude trailer) | total |
|---|---|---|---|---|---|---|---|
| 2026-09-28 | 269 | 89 | 0 | 0 | 0 | 36 | 394 |
| 2026-10-06 | 7 | 6 | 0 | 0 | 0 | 2 | 15 |
| 2026-10-07 | 161 | 5 | 0 | 0 | 0 | 53 | 219 |
| 2026-10-08 | 368 | 27 | 0 | 0 | 0 | 86 | 481 |
| 2026-10-09 | 141 | 9 | 1 | 0 | 0 | 26 | 177 |
| 2026-10-10 | 98 | 12 | 4 | 0 | 0 | 74 | 188 |
| 2026-10-11 | 61 | 6 | 20 | 0 | 2 | 42 | 131 |
| 2026-10-12 | 69 | 60 | 12 | 24 | 2 | 54 | 221 |
| total | 1174 | 214 | 37 | 24 | 4 | 373 | 1826 |

## Notes

- Window is HEAD's history only (refactor/agent-platform). All refs agree on the 2026-09-29..10-05 gap: zero commits on any branch; 10-06 has 15 commits, 10-12 has 224 on all refs and 221 on HEAD.
- Fix-subject is a regex over the subject: fix, fixed, fixing, refix, again, regression, revert, restore, follow-up. It has false positives ("fixed heading", "regression test", "again" in prose). Subject only, not body.
- Function attribution: each hunk line maps to the nearest column-0 def form in the file at the commit (new side) or its parent (old side). Nested defs are not split; prelude and ns forms are excluded from Table A. Chains are counted over commits that touch the form.
- Churn: an add event is a `+(def` line in a commit with no `-(def` for the same name in that file; a commit with both is a modify. A name counted as deleted has its last window event as removal. Names moved between files appear as add and remove. Full set, no sampling.
- Signals are substring hits on subject plus body, case-insensitive. "regression" also matches "regression test" and proof text, so it overstates.
- Bump commits are commits touching deps.edn or a reference-code gitlink (34 gitlinks at HEAD). Restarts are not recorded in git, so restarts are not measured here.
- Model lanes come from Co-Authored-By trailers only; 373 commits carry no Claude trailer (inferred: human or uncredited agent commits).
- Mislabeled reverts: d9715071e and 1aad413c1 have subjects beginning "Revert verification" and revert nothing; they are verification reports. Counted as zero reverts in Table C and the trend.
- The For root section runs past the 12-line cap of the common rules because the assignment asks for ten chains, the revert lessons and a trend table.
- Reverts: the reverted commit is found by a hash in the revert subject (not by the body's "This reverts commit", which these commits lack).
- Revert lessons are one line each per reverted commit; b57ba36e0 was reverted twice (e09df7854 and its amended 073a349a4).
- Not verified: whether a fix-subject chain was caused by the same defect; that needs reading the bodies (done only for f18ab95e3 and c2284b109).
