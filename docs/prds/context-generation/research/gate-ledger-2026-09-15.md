---
type: research
status: active
tags: [research, test, orchestrator]
---

# Gate ledger — 2026-09-15 (orchestrator-maintained)

Rule (owner, 21:05Z–21:50Z): astra lanes do focused fixes and never launch
test JVMs (`bin/_test-slot` orchestrator-only mode, `fd98bc5a5`); the
orchestrator batches gates in dedicated Opus threads on committed HEAD only
(`bin/test --paths README.md -- <namespaces>`), saves results under
`tmp/orchestrator/gate-results/<batch>/<lane>.md`, records them here, and
resumes each lane pointing at exactly its reds. One gate per batch; never
the same namespace twice for the same HEAD.

## Batches

| Batch | HEAD region | Namespaces | Result | Reds handed to |
|---|---|---|---|---|
| 1 | `4c8740cf0`–`1d17650a9` | refusal-grammar (10) + fixtures-events (4) + platform | 207/6191 green; platform 84/567 green; results recorded | none |
| 2 | `f5ca25ba9`+ | p1-ambient-state (19) | ABORTED at 22:12Z by the orchestrator: the gate alone ran 9 pool workers + serial + 6 concurrent confirmation JVMs (load avg 75 on 18 cores, 26 GB compressed); runner capped, re-run as batch 2b. The killed batch-2 gate thread RELAUNCHED its run (uncapped) beside 2b; killed again at 22:45Z. Lesson: a gate thread's instruction must say "if the command is killed, do not rerun; report" | — |
| 2b | `f5ca25ba9`+ | p1-ambient-state (19) | KILLED at 21:44Z by the superseded batch-2 thread (the two threads had swapped runs); partial log through seon.sci.eval-test, no tally | — |
| 2c | `48605a1de` | p1-ambient-state (19), capped at 3 workers, nice 15 | 392/2437: 50 failures, 20 errors in 6 namespaces (sci.eval, render-simplification, schema.datahike, effect, render.value, cluster.mcp); 13 namespaces green; published-base 44 s, tests 748 s; results NOT recorded (live prepl unavailable) | p1-ambient-state: class = fixtures mint bare database values and the fallback now refuses; Datom-where-map in mcp-test; two effect events |
| 33 | `7b3a9ecc8` | FRESH STORE (reset: 72 GB → 107 MB; default pid 27828): platform (recording) then seon.issue-test (indexer `75996a9e6`…`da0309344`) seon.test-runner-test seon.test.runner-test seon.test-support-test (reach-closure `bbbfafaf1`, `1086a7b80`) | A: PLATFORM RED 86/579, 13 F all in seon.cluster.source-test/latest-test-evidence-survives-rebuilding-from-an-older-base — `commit-results!` refused at the writer: `:seon.test.run/unavailable The completion lacks its tested database reach membership` (reach-closure's seam); gate recording also rejected; root run.5goq1Y. BLOCKER for the platform tier; fixed by reach-closure `8199364a2` (recording total: `:seon.test/reach-unknown`). B: 66/514, 3 F — seon.issue-test 2 tests (`:seon.issue/unresolved` vector vs set; steward's indexer); runner/support namespaces GREEN; root run.6P5SxE | steward |
| 37 | `e25f0f360` | steward's issue-index-publication-cost `6ed16de1a` (delta-only index-tx; 1,276 → 358 ms): seon.issue-test seon.cluster.source-test | 21/221, 0 F 0 E — both GREEN (source-test's latest-test-evidence… passes under named selection); recording failed only because default was restarting; root run.sK2dTh swept | steward |
| 39 | `3c446a558` | transcript second pass (`c19e826fa`…`583dab7c9`) + config-apply-cost `5e5aa6293`: seon.render.transcript-test seon.render.web-debug-test seon.config-test seon.reconcile-test | 56/568, 1 F: web-debug, config, reconcile GREEN; transcript-test 13 → 1 — every-generated-history-is-ordered-and-total (generative: two same-instant inbound messages order, seed 2026073104); root run.q75EGY | steward |
| 44 | `b77c553e4` | recording wrapper carries the cluster's cause (`b77c553e4`): platform (recording — first gate that can NAME the rejection) then seon.dev.fresh-operator-test seon.test-runner-test | running | orchestrator |
| 43 | HEAD | steward's effect-facts (`0e15593aa` `774b4da39`) + issue-generator (`88b04b970` `8c01f7420` `e2117dd73`): seon.effect-test seon.edit-test seon.fn-test seon.issue-generate-test seon.issue-test | `e3bfa76d1`: 70/482, 17 F 4 E — fn-test, issue-test GREEN; effect-test 2 (detached limit fails instead of interrupting; my.fs refuses the fixture temp path in a cold worker), edit-test 4 (my.edit/form! now requires :my.edit/expected-digest; fixtures refused), issue-generate-test 2 (idempotence, reopen); root run.RRQXky | steward |
| 42 | `c010f87fd` | transcript generator fix `f75112dbd`: seon.render.transcript-test | 17/275, 0 F — GREEN (transcript class closed: 13 → 0); recording rejected again | steward |
| 41 | `cfac8275c` | reach-closure's five commits (`d2a0ad636` expiry vs permit, `3c6a6bb8f` failure identities at the writer, `e2eb91fcd` recording rebased across publications, `e8a017620`, `bb2843264`): platform (recording) then seon.test-runner-test seon.test.runner-test seon.test-failure-facts-test seon.cluster.source-test | A: PLATFORM GREEN 86/582 0/0 (source-test :546 passes); recording STILL rejected, value still hidden; root run.tEkmC6. B: 68/485, 0 F 0 E — seon.test-runner-test, seon.test.runner-test, seon.test-failure-facts-test, seon.cluster.source-test ALL GREEN (batch-34 reds closed); recording rejected again | steward |
| 40 | `dafdd7dc8` | custody-stability fix `3c115ff15`: seon.custody-stability-test | 5/26, 0 F — GREEN; recording still `rejected the prepl operation` on a healthy pid 45917 (recorder race / permit class, in reach-closure's resume) | orchestrator |
| 38 | `1f7bce9c1` | evaluation-context loader fix `653d4d4ef`: seon.sci.eval-test seon.custody-stability-test | 73/365, 4 F: seon.sci.eval-test GREEN (fix proven cold); seon.custody-stability-test 2 — cross-cluster-write-isolation (fixture writes lack the now-required :seon.message/to) and indexed-custody-returning-surface… (expected roster drifted vs derived surface); Opus fix lane launched; root run.1NPVXM | orchestrator |
| 36 | HEAD | config-apply-cost `6313d2006`: seon.config-test seon.reconcile-test seon.schema-test seon.db-test seon.cluster.turn-test seon.test-support-test | `7216a688b`: 160/1161, 17 F 5 E. GOOD: generated-model-attempt-traces 105 s (no longer at the 270 s bound); schema, db, test-support GREEN. RED: seon.config-test 2 (converged-apply regression: raw-deref projection nil; hand edit not repaired; default.edn dial set), seon.reconcile-test 4 (hand edit, provenance lost, identity-scope refusal gone, pull slice) — the lane's unverified reconcile semantics; seon.cluster.turn-test 4 (delimiter-repair 14 receipts vs 6 with `(help)` first — opening/system-turn expectation; a-lost-model-call; NEW ns-unmap-retracts… ×4 and qualified-dynamic-ns-unmap…); root run.gIQ9CS. config-apply-cost resumed with a one-fast-run exception (base poisoned) | config-apply-cost; turn-test-reds (deferred) |
| 35 | `68a3f080b` | default restarted (pid 37572, permit leak cleared): platform (recording) then transcript-web-debug (`0986475bd` `b0951e459` `5b4a4e08b`) + seon.repl-test + seon.issue-test (`bc9181842`) | A: PLATFORM 86 tests, 1 F — source-test/latest-test-evidence… :546 (reach-closure's, in its resume); recording rejected (permit leak fix pending); root run.3qt5Dv. B: 50/532, 23 F 3 E — seon.issue-test GREEN, seon.repl-test GREEN; seon.render.transcript-test 7 distinct and web-debug-test 1 still red (13 → 8); root run.V7UPqN | steward |
| 34 | `8199364a2`+ | recording-total fix: platform (recording) then seon.cluster.source-test seon.test-failure-facts-test seon.test-runner-test seon.test.runner-test seon.test-support-test seon.issue-test | A: PLATFORM 86/579, 1 F (was 13): source-test/latest-test-evidence… :546 run-entity equality after rebuild (reach-closure's new run attributes); gate recording still `rejected the prepl operation` (default's JVM was wedged: Datahike roster permit leaked by an interrupted fixture — steward's find, issue `an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm.md`); root run.X3y47H. B: 85/660, 8 F — GREEN test-failure-facts, test.runner, test-support; RED seon.test-runner-test 3 (recording totality regressed under concurrent retraction: conflicting failure-fact upsert; elision/reach-unknown in stored vs returned results), source-test :546, issue-test 2 (fixed `bc9181842`); root run.4avadX | steward (reach-closure resume) |
| 32 | `2843d6ec7` | fresh-operator config-proof read fix `8208754cb` + steward's program/shapes fix `7cfe02790`: seon.dev.fresh-operator-test seon.fn-test | 77/500, 0 F 0 E — both GREEN; NOT recorded: `:seon.test.run/unavailable The completion lacks its tested database reach membership` (new seam from reach-closure-facts `bbbfafaf1`/`1086a7b80`; handed to the steward); root run.HwsG9I | orchestrator; steward |
| 31 | `34c5a9535` | turn-test-reds third slice: seon.cluster.turn-test seon.turn-test | 82/797, 7 F 6 E: seon.turn-test GREEN; seon.cluster.turn-test 3 — delimiter-repair… (bookkeeping 5.6 s vs 300 ms), a-lost-model-call… (provider diagnostic missing), generated-model-attempt-traces… hit the 270 s worker exchange bound (same cost class suspected); root run.6byCYT. Class: 44 → 3. Opus research lane on the bookkeeping cost launched | orchestrator |
| 30 | `56f0a4ca8` | cold-arming fix `eeafb9dba` (98b5f2afe culprit; `direct-references` defaults an omitted predicate map to {}) + retention-sweep `5a10f5dfa` + arming/analyzer + transcript-test + issue-settlement `0c8f90630`: platform (recording) then 11 ns | A: PLATFORM GREEN 86/579 0/0, exit 0, root removed as successful, results RECORDED on default (runs 9a399119df79 / 4cf0c1d016be at 05:32Z) — first recorded gate since the sweep class was found. B: 178/1341, 77 F 8 E — GREEN: schedule, blob, registry, data-shapes, instrument, issue-settlement, issue-test; RED: seon.render.transcript-test 13 tests (renderer-ref class, steward), seon.fn-test 4 (analyzer-facets: `:seon.fn/call-arities` nil in cold workers — the dev-JVM-vs-cold-worker class), seon.render.web-debug-test 1 (known residual), seon.dev.fresh-operator-test/init-owns-current-source… 2 (shipped-decision prepl round-trip false after the dial removal → retention-sweep) | steward; retention-sweep |
| 29 | `bf58f1ab0` | steward's arming-includes-referenced-schemas (`98b5f2afe`, `1c98259ba`) + analyzer-facets (`efaa45a68`): seon.data-shapes-test seon.instrument-test seon.render.web-debug-test seon.fn-test (seon.render.transcript-test queued for the next batch) | ABORTED at worker initialization on committed HEAD: every cold worker dies arming contracts — `seon.schema/compilable-form refused predicate-functions at []: expected a map, got nil` via `seon.instrument/throwing-report` (instrument.clj:414); root run.C1Ahr5. BLOCKER for every cold gate until fixed; handed to the steward session (arming lane's instrument.clj) | steward |
| 28 | HEAD | turn-test-reds landing: Datahike fork `49ea5933` (speculative tx detaches committed query-cache identity) + `c1d7d4695`, 11 batch-23 members repaired, 4 remain (two refused seeds, bookkeeping bound, provider-error prompt visibility); ns: seon.cluster.turn-test seon.sci.eval-test seon.turn-test seon.datahike-fork-test | `258150603`: 150/980, 27 F 6 E in 6 distinct tests: seon.cluster.turn-test 4 (refused-terminal-program… 15, delimiter-repair… 10, a-lost-model-call… 2, generated-model-attempt-traces… 1), seon.turn-test 2 (virtual-turns… 4, settlement-mints… 1); eval-test and fork-test GREEN; root run.sO5c2C; batch 23 had 44 distinct reds → 7. Resumed: `c34b5c166` settlement-mints; base refreshed once (27.5 s); `99828a14d` virtual-turns (246 assertions), `d8b06746b` generated-model-attempt (48 trials), `80d8fbd0f` refused-terminal (33). TWO REMAIN, both real defects: delimiter-repair… (turn bookkeeping 5,646 ms vs the 300 ms bound — issue `turn-bookkeeping-exceeds-recorded-regression-bound.md`) and a-lost-model-call… (provider diagnostic missing from the next prompt). Lane stopped; batch 31 gates the two namespaces | turn-test-reds (stopped) |
| 27 | `7c7395c8a` | platform (recording) then fixture-stores `6d4705498` (steward: non-temporal store writes, executor frame conveyance) + recording pre-read fix `7c7395c8a` — 9 ns | A: PLATFORM GREEN 86/579 0/0; still NOT recorded (`prepl-response-silent` 30 s with all other clients paused → the record send itself is slow; Opus latency lane launched; root run.4hIzdV). B (`b033e0860`): 155/1001, 0 F 1 E — seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle hung to the 270 s worker exchange bound (suspect: 7c7395c8a's prepl reply seam; handed to the latency lane); other 8 ns GREEN incl. registry/store/transact-feedback/runner; root run.o0NcHO | steward; recording-latency (Opus) |
| 26 | `29d077c22`+overlay | recording platform tier on reforked default (pid 53378), then seon.test-runner-test | A: PLATFORM RED 86/578, 1 F 1 E (registry-test non-temporal-collection…, store-test branch-connections-inherit…) — bare run overlaid the steward fixer's uncommitted cluster.clj/test_support.clj; NOT recorded (`prepl-response-silent` 30 s). B: seon.test-runner-test GREEN 42/267 (runner exhaust fix `86b4c8ff4` proven); NOT recorded (`live-prepl-unavailable`) while MCP evals on the same JVM answered in ms — recording's prepl client is the suspect, not load | steward fixer (platform); orchestrator (recording research) |
| 25 | `a14a3101c` | steward session's issue-settlement (7 ns) | 109 tests / 924 assertions, 6 F 1 E: issue-settlement, my.plan, contracts-plan, db, cluster.source GREEN; seon.issue-test 1 test (known, triage); seon.turn-test 2 tests (`settlement-mints-rows-for-unindexed-call-targets`, `virtual-turns-use-the-proc-and-compaction-is-agent-scoped`); results NOT recorded (`live-prepl-unavailable`); root run.r3qnVJ | steward (issue-test), turn-test-reds (turn-test) |
| 24 | `d47ebcc3e` | attempt-and-eval-facts re-run (2 ns) after `52044b4f4` | faults-test GREEN; issue-test 1 test (plan-opening fixture, issue-settlement's) | steward session |
| 23 | `f817acfd0` | turn-test-reds (3 ns) after `2209387e2`, `c01df3773`, `7c097f8f2` | 148/867: 141 failures, 38 errors — cluster.turn-test still 44 tests red cold (unchanged since batch 20), turn-test 2, sci.eval GREEN; the base built (52 s) so the missing-projection refusal did not hit this path; results not recorded (prepl silent) | decision: option 1 for the transaction-cache defect (clear committed cache identity at transaction-function entry in the Datahike fork; deletion projection at the writer); lane resumed |
| 22 | `01539d18a`+ | steward session's write-validation-class (2 ns; `20d30a0bd`) | 18/181 GREEN, exit 0 | write-validation-class CLOSED |
| 21 | `fe9aeb336`+ | platform tier alone (issue-family's fix `fe9aeb336`: issue indexing out of the publication path), then issue-family's 3 namespaces | PLATFORM GREEN 84/569 at `7ccd30496`, NOT RECORDED (prepl-response-silent) — accepted on the batch-21 log as evidence; a recording-only platform run follows the steward session's refork; named 18/162: 1 error (issue-test opening-links-its-issue: of-agent renderer-fn ref — attempt-and-eval-facts' residual), issues-test and source-test GREEN | steward session |
| 20 | `cecfaf428` | steward session's error-graph (12 ns) | 230/1618: 236 failures, 53 errors; six namespaces GREEN (error, problems, cluster, wake, schedule, status); red: cluster.turn-test 48 (pre-existing class, overlaps turn-test-reds), render.transcript-test 15, turn-loop-test 11, turn-test 2, flow 1, faults 1; results not recorded (prepl unavailable) | steward session, with a baseline-first instruction |
| 19 | `e4080bd3f` | HEAD publishes again (`ff48a4110`). Six lanes in one batch: turn-test-reds (2) + steward session's reach-digest (2), program-provenance (3), entity-pairs (1), attempt-and-eval-facts (6), generated-read-identities (4) + platform; all 18 namespaces validated against test/ | named: 355/2452, 82 failures, 23 errors; entity-pairs GREEN; turn-test-reds 19 tests; reach-digest 4; program-provenance 11; attempt-and-eval-facts 4; generated-read-identities 3; PLATFORM RED 84/568: 13 failures all in seon.cluster.source-test (latest-test-evidence-survives-rebuilding-from-an-older-base ×12, incremental-first-party-publication ×1) — last touch of cluster/source.clj is the steward session's `a7d1e115e` (issue-family); results not recorded (prepl silent 30 s; result store held by another process) | per-lane files and the platform attribution sent to the steward session; turn-test-reds resumed |
| 18b | `806e6e8d8` | turn-test-reds (2 ns), relaunched once | ABORTED in published-base preparation: schema publication refuses `:seon.issue/issue` (`:seon.render/ai` names `seon.issue/render-ai` whose declared input nil does not accept the declaring shape) — introduced by the other session's commit `6a491f0b3` (01:26Z). EVERY cold gate at HEAD is blocked until that schema is fixed | owner / the steward session |
| 18 | `0eba2ae10` | turn-test-reds (2 ns; five slices: a production deletion fix `4b3322b04`, the test entity AI/HTML pair `ff351811b`, 27 repaired tests / 188 assertions, two obsolete tests replaced; 16 tests still unresolved) | running | — |
| 17 | `47dcf6d92` | n7-eval-call-edges re-gate (2 ns) after `171c0c193` (4 tests green on a fresh base) | 89/458: fn-test GREEN; seon.cluster.turn-test still 44 tests red cold (103 blocks) — the lane verified 4 of ~89 tests; BASELINE at pre-wave 4c8740cf0: 59 tests, 56 failures, 44 errors — 43 tests fail in both, 7 baseline-only (fixed since), 1 HEAD-only. seon.cluster.turn-test was deeply red before the wave; not an N7 regression | n7-eval-call-edges CLOSED for its slice (the 1 HEAD-only test handed back); seon.cluster.turn-test opens as its own class lane |
| 16 | `8bf2dceaf` | p1-ambient-state re-gate (5 ns) after `253206238`, `0edd57230` (supplied test bounds honored; one fresh-store fixture arity; render bounds caller-owned; SCI test state scoped) | 115/663 GREEN cold, no worker-global mutations; 114 s | p1-ambient-state CLOSED for its read/admission scope |
| 15 | `2a4e43d25` | n1-render-substitution (2 ns) | 54/361: render.value GREEN cold; 1 failure = distance-spends-only-real-ref-hops-and-caps-win, already in P1's batch 12b reds | n1-render-substitution CLOSED for its slice; the distance test stays with P1 |
| 14 | `6e4fe9511` | n7-eval-call-edges (2 ns) | 89/365: 59 failures, 44 errors — seon.cluster.turn-test 51 tests red cold (~30 contract refusals at instrument.clj:413, 8 schema refusals at edn.clj:46) after the settlement change; fn-test 1 | n7-eval-call-edges: reproduce cold, fix at the settlement seam |
| 13 | `bebdfb39e` | n1-mcp-bypass (4 ns) | 65/407: seon.mcp-test, render.value, mcp-bridge GREEN cold; 1 failure = the merged artifact test asserting one fresh store now sees 2 (same red as batch 12b, already with P1) | n1-mcp-bypass CLOSED for its slice; the fresh-store count stays with P1 |
| 12b | `b1be50c2a` | P1's 13 real namespaces + seon.fn-test | 340/2448: 11 failures in 6 tests (was 153 blocks); published base reused (0 s); tests 285 s | p1-ambient-state 5 tests (sci.eval 3 incl. two that mutate worker-global state; render-simplification 1; the merged mcp artifact test 1); n7-eval-call-edges 1 (agent-source-reaches-the-evaluator-through-one-visible-path) — handed when the lane stops |
| 12 | `d57a69c3a` | p1-ambient-state re-gate (14 ns + platform) after `72d7dc3a9` (evaluation database context carried; batch-4 fixture contracts repaired; all 52 batch-4 regressions + the platform regression pass in-process) | named gate ABORTED at load 6/14: the request named seon.sci.kernel-test, which does not exist (orchestrator did not validate the request); PLATFORM GREEN again (84/566 at `f402c5d3d`, 125 s); 12b re-runs the 13 real namespaces | — |
| 11 | `0d057a799` | debug-page-cost (1: seon.render.web-context-test) | 4/53 GREEN cold; published base reused (0 s); 34 s | debug-page-cost CLOSED |
| 10 | `deb077923` | slow-tests-merge (1) | 2/59 GREEN cold; 33 s | slow-tests-merge CLOSED |
| 9 | `0cd0ad907` | debug-page-cost (3) + test-runner-waste (1) | 24/330: 1 failure; test-runner-waste GREEN (closed); retained + web-debug GREEN cold; web-context 1 (retained page count 9 vs 10 across an unrelated adoption); tests 53 s | debug-page-cost (`0d057a799`: the tenth render was cluster status, which legitimately depends on the adoption commit; regression now asserts every other renderer is reused → batch 11 pending: seon.render.web-context-test) |
| 8 | `af487bc76` | slow-tests-merge (1): cold-acquired fixture with explicit environment/projection/profile; the raw-EDN failure could not be reproduced in-process, so this gate is the cold-worker confirmation | 2/46: the merged grammar test GREEN cold (raw-EDN class gone, 36 s for the namespace); 1 error: the kept order-schema test now fails invalid-schema :example/order-row (fixture no longer registers it) | slow-tests-merge |
| 7 | `5f80c1871` | test-runner-waste (2) + reaching-tests-tier (2) + debug-page-cost (3) + slow-tests-merge (1) | 79/686: 31 failures, 1 error; reaching-tests-tier GREEN; zero confirmation JVMs; published-base 40 s, tests 99 s | debug-page-cost 22 (fixture render pair / grammar not selected in a fresh worker), slow-tests-merge 8 (same class), test-runner-waste 2 (nested probe runs in a fresh worker). Class: passes in the dev JVM, fails in a cold worker — fixtures relying on ambient dev-JVM state |
| 6 | `6df05c861` | test-runner-waste (2) + reaching-tests-tier (2) | ABORTED in SELECT: the new regrowth check refuses selected tests reaching expensive fixtures without a declared observation — existing tests were never declared; zero confirmation JVMs launched (row 1 works); published-base 39 s | test-runner-waste: declare observations on every legitimately reaching test first |
| 5 | `ec52657de` | slow-tests-merge (6 ns; rows 2–6) | 55/559: 8 failures all in one merged test (refusal-grammar-survives-real-evaluation renders raw EDN, not the grammar); 5 ns green; measured: problems 15 tests/221 s, agent 22/72 s, mcp 11/55 s, config-application 4/27 s, contracts-plan 2/11 s | slow-tests-merge: CLOSED green in batch 10 (`a1fbdb347`, `af487bc76`, `deb077923`) |
| 4 | `8e19dee42` | p1-ambient-state re-gate (9 + platform) after `28e955327` | WORSE: 210/1132: 134 failures, 19 errors (7 ns); PLATFORM RED (1 error: reads-require-their-carried-projection…, test_support.clj:479); published-base 40 s, tests 182 s | p1-ambient-state: platform first; iterate in-process with seon.test/run before any re-gate |
| 3 | `b5b5b0fb3` | startup-and-hook-waste (4) + debug-page-cost (3) + reaching-tests-tier (7, overlapping 3) + platform | 88/597: 13 failures, 2 errors; platform 84/567 green; published-base 44 s, tests 157 s | startup-and-hook-waste GREEN; debug-page-cost: retained_test 8 FAIL + web-debug 1 ERROR; reaching-tests-tier: source-reconciliation 5 FAIL + 1 ERROR |

## Lane → slice → status

| Lane | Landed | Gate | Open residuals |
|---|---|---|---|
| refusal-grammar-2 | `1fd81b2be`, `4c8740cf0` | batch 1 green | class residuals in its note; argument-count refusal omits the count (issue) |
| fixtures-events | `e4f8bbe07`, `1d17650a9` | batch 1 green | P2/P3/N2 members open with residuals |
| p1-ambient-state | `b80f78a7c`, `f5ca25ba9`, `28e955327`, `9c3c3d8d4` | batch 2c red → batch 4 worse (platform red) → in-process repair `72d7dc3a9` → batch 16 GREEN (closed) | adoption/lifecycle members open (recorded options in its note); stale fixture-contract-after-adoption class queued |
| n7-query-classification | `5deb40e4e`, `872fb25d4`; n7-eval-call-edges `f402c5d3d` (analysis returns core + my.* edges) | gated by lane before the rule (82/418, platform 86/542); seon.fn-test pending batch 12b | stored toolkit (cluster.clj, n1 lane holds it), turn.clj persistence landed `924fdbf3a` (batch 14 running); schema-fallback (P1); stored toolkit still open (cluster.clj) |
| bisect-today-reds | `6dc70f30a`, `ee8d54dca` | gated by lane before the rule (103/248, platform 86/542) | none |
| debug-page-cost | `cfb35a22b`, `671108b60`, `c20b83d20` (three kills: retained reuse across carried values; passive directory audit off; shared derivations once) | batch 3 red (retained_test 8, web-debug 1) → fixed `8920d1dfe` (25 + 95 assertions green in-process) → batch 7 red (22) → root cause `27b9f7165`: the cold fixture carried the BOOTSTRAP projection (0 function contracts) while its database held 1,045; the constructor now carries the populated database's projection → batch 9 pending (3 ns) | cold page after adoption 18 s → 1.7–3.4 s (residual acquisition cost in the issue); the 0-contract bootstrap projection is probably the same cause behind slow-tests-merge's raw-EDN refusals (batch 8 will tell) |
| reaching-tests-tier | `e9af61d87`, `b5b5b0fb3` (seon.test/check in the development JVM; hook runs the reaching tests after adoption and withholds escalation on red; bin/test-check) | batch 3: 3 ns green, source-reconciliation red → repaired `558d5614a`, `dec12ea41` (empty check 6.6 s → 1.86 ms; both namespaces green in-process) → batch 7 GREEN | cold development JVMs lack some test dependencies (issue) |
| startup-and-hook-waste | `c395610db`, `db7e653ca`, `f75c85402` (both initializers build one projection, 998/998 armed; hook drains immediately, exactly one successor batch; edit-feedback test updated) | batch 3 GREEN | hook issue narrowed to AGENTS.md wording |

## Live checks (default)

| When | Debug page warm | Fallback warnings / 100 KB log | Note |
|---|---|---|---|
| 21:00Z | 1.81 s | 7 | after restart |
| 21:10Z | 0.70 s | 7 | P1 edits adopted |
| 21:45Z | 0.75 s | 7 | P1 committed; plan attributes the floor to retained-read replay and directory reconstruction |
| 22:05Z | 0.18 s (cold after adoption 18.4 s) | 7 | debug-page-cost kills 1–2 adopted; cold path filed as an issue |
| 22:35Z | 0.14 s (cold after adoption 19.5 s) | 7 | kill 3 adopted; lane in-process 64–76 ms |
| 23:20Z | 0.11–0.16 s (cold after adoption 3.4 s) | — | slice 4 adopted; unrelated adoption re-renders zero evaluations |

## Plans reviewed

| Plan | Decision | Implementation lanes |
|---|---|---|
| slow-surfaces-plan (`65642226b`) | rows 1–3 approved; hook option 1 (no idle delay); rows 4–7 deferred until measured | startup-and-hook-waste (rows 2–3, landed); row 1 (adoption rebuilds the projection) queued behind P1 |
| debug-page-cost-plan (`5755bcd60`) | kills 1–3 approved; option A for the directory audit | debug-page-cost (landed; cold-page slice in flight) |
| n1-total-render-plan (`9167db1a8`, partial: stopped after three malformed probes) | option A approved (close the MCP sorted-map bypass at its owner; early root validation in value/prepare; one class regression); the 20 unverified members go to an Opus verification pass with the corrected probe recipe before any wider scope | n1-mcp-bypass (astra, in flight); N1 member verification (Opus, done: 13 resolved / 7 confirmed / 0 unverifiable — research/n1-member-verification-2026-09-16.md; the class is ALIVE on the agent path: a pulled :seon.fn row renders as a 100-char stale-Var instruction, a config row as English prose, a turn entity as the empty string) → n1-render-substitution landed `563034709` (batch 15 running) |
| test-suite-cost-plan (`78f0d15c0`) | row 1 option 1 (no automatic confirmation; explicit `--confirm`); row 7 publication deferred, lazy checkouts approved; row 8 approved; rows 2–6 approved; regrowth check approved | test-runner-waste (GREEN in batch 9, closed; rows 1, 7-lazy, 8, regrowth landed `ea5861329`…`e0dded0c6`; `e5b206987` carries the connection projection through in-process runs; `5f80c1871` declares 52 existing fixture observations — full selection 1,652 tests, 54 expensive, zero refusals; batch 7); slow-tests-merge (rows 2–6 landed, final `ec52657de`; flow-health test still blocked by a missing projection at seon.program/base-context-injected-symbols — P1 territory) |

## 23:55Z — Codex usage limit

The Codex account hit its usage limit ("try again at Sep 19th, 2026 3:03 AM").
reaching-tests-tier died after landing `558d5614a` (reconciliation fixture
repair; request narrowed to seon.source-reconciliation-test +
seon.test-reaching-test). debug-page-cost died mid-slice with uncommitted
edits in src/seon/render.clj and test/seon/render/retained_test.clj —
preserved, untouched. p1-ambient-state, slow-tests-merge (row 3 landed
`fc90bb972`; row 4 `e3af34340`), test-runner-waste (`ea5861329` landed) will
fail on their next turn. Owner decision pending: credits, or Opus
implementation threads under the same rules.

## Open class noticed by two lanes (00:40Z)

Both n7-eval-call-edges and n1-mcp-bypass hit "the canonical fixture retains
the old function contract after adoption" in the development JVM
(docs/seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md):
the in-memory fixture base caches a projection whose contracts predate the
hot-adopted definitions, so an in-process regression can be blocked by a
stale contract the cold worker never sees. Same family as the projection
carriage work (P1); assign after P1's current slice.

## 01:15Z — a second orchestrator session is live

A separate Claude Code session (the owner's steward-platform dialogue, pid
75117) launched lanes `cold-page-plan` and `hook-publication-race` from the
wave-2 spec templates. This session does not resume, stop, or gate them.
Gate coordination across sessions rests on `tmp/test-slots` (two slots
machine-wide, orchestrator-only mode); each session runs at most one gate
at a time, so the machine sees at most two.

## 01:50Z — default reforked by the other session; status refusal

`default` is now pid 7595 (start 01:36Z), reforked by the steward session.
`runtime_status` refuses: `seon.problems/problems refused return value at
[... :seon.problems/occurrences]: expected an integer, got an integer` —
a zero-occurrence signature against `[:int {:min 1}]`, and the grammar
dropped the constraint. Issue filed
(problems-refuses-its-own-zero-occurrence-signature.md); an Opus agent is
fixing both under the REPL rule (cost rule: Opus for mechanical fixes).

## 02:05Z — gates blocked by the steward session's schema commit

`6a491f0b3` (steward session) declared `:seon.issue/issue` with a render
pair whose contract the publication refuses; `bin/test`'s shared published
base cannot be built at HEAD, so no cold gate can run for anyone. That
session's five lanes also hold uncommitted edits in src/seon/problems.clj,
error.clj, cluster.clj, cluster/source.clj, test.clj, test/runner.clj and
several tests; this session's Opus fix for the problems zero-occurrence
refusal was stopped before writing to avoid clobbering them. Handoff to the
owner: the steward session must fix the seon.issue render-pair contract (or
revert `6a491f0b3`) before any gate here resumes; turn-test-reds' 18b re-gate
and the problems fix are queued behind it.

## 02:20Z — coordination with the steward session

The steward session (seon-61) owns the seon.issue refusal via its
issue-family lane and is repairing it; cold gates here HOLD until its
ledger line says HEAD publishes. Its in-flight lanes: error-graph
(error.clj, cluster.clj commit-fault!, seon.error*.edn, problems.clj
readers), reach-digest (test.clj, test/runner.clj, seon.test.edn),
program-provenance (fn.clj, program.cljc; also fixes the form-span tuple
retraction blocking `init --dev`). Landed from that side today:
`474234fb7` (system turns open again), `ac34ce5a3`/`ff351811b`/`54f9155f1`
(fn/test entity pairs), `3402913f3` (file/span + seon.lint), `17dd75e89`
(usage/renderer facts). Handed to it: the third-error-writer cause behind
the status refusal (fold into error-graph). It reforks default once more
after its schema edits land.


## 02:30Z — HEAD publishes again (steward session)

`ff48a4110` (issue-family) declares the `seon.issue` render pair contracts
the publication accepts; the hook's batch at 02:19:55Z converged on the
tree carrying that fix (commit `6aa9fc8a`, digest `f53a8de4…`), and
`bin/seon init --dev default` exited 0 after `f9a46b0bd` (tuple retraction
with values). Cold gates may resume. Still uncommitted from the steward
session: error-graph (error.clj, cluster.clj commit-fault! region,
problems.clj, seon.error.edn, their tests) and issue-family (issue.clj,
cluster/source.clj, bin/seon, bin/issues-index, script/seon/dev/issues.clj,
seon.agent.edn). Landed today: reach-digest `f2d537187`/`e5de6ebc7`
(warm check 2.65 ms; one changed function 56 ms, three digests recomputed),
program-provenance `3402913f3`/`f9a46b0bd`, entity-pairs, generated-read
fix `474234fb7`, attempt facts `17dd75e89`.

## 05:40Z — default's prepl saturates under in-process regressions

Eight lanes across both sessions ran their in-process regressions inside
default's JVM at once; the hook's publications exited 124 on their bound
and `runtime_status` timed out. The steward session paused four of its
lanes. Same shape as the test-JVM saturation: the development JVM's prepl
is one shared resource; in-process test runs need the same admission as
gates (a slot, or the reaching-tests check running them serially).

## 06:05Z — recording failures explained

The "result-cluster store held by another live process" and
"prepl-response-silent" recording failures in batches 19–20 came from
`f2d537187` (reach-digest): `completion-reach-digests` opened the gate's
shared published-base store under its lifetime flock on every
`commit-results!`. Replaced at HEAD by `1b5c09e15` (canonical private
fixture, no store open); recording should work again from the next gate at
or after that commit. reach-digest's only remaining cold red belongs to
agent-call-edges.

## 06:20Z — batch 20 attribution (steward session's Opus triage, `b45881f32`)

None of batch 20's reds attribute to error-graph. A PLATFORM CLASS since
`26ec13420`: `seon.db/write-map-error` validates a partial entity map
against every entity schema that lists its identity attribute, so fixture
seeds are silently refused and tests assert against an empty database
(issue raw-write-validation-refuses-reverse-refs-and-partial-entity-maps,
blocker; astra lane write-validation-class on db.clj). That explains
seon.cluster.turn-test growing 18 → 48 red between batches 19 and 20; the
turn-test-reds lane was told to classify seed-refused tests as blocked by
that lane and work only the rest. Other attributions: render.faults →
attempt-and-eval-facts; three transcript/turn-loop tests stale (Opus fix
later); turn-test settlement row → agent-call-edges (`76774d044`);
flow-test kill_child reads absence as readiness. Default's own fault
committer transactions are refused on default ("at [0 :seon.error/at]")
after error-graph's schema change: RESET NEEDED; the steward session
reforks after issue-family lands and messages first.

## 07:10Z — HOLD on fresh-base gates

Steward session finding (issue
fixture-base-population-refuses-without-a-carried-projection, `ebc718e7f`):
at committed HEAD, `seon.test-support/create-base` with no published base
refuses `:seon.schema/missing-projection` from
`accrete-schema-population!` (cluster.clj:1347 binds forms but no
projection) — introduced with write-validation-class `20d30a0bd`. Batch 22
was green because it reused a cached base. Gates HOLD until the fix commit
is at HEAD; batch 23 (already running) is allowed to finish and is void if
it refuses at its first with-database. Also: renderer-fn residual fixed
`52044b4f4`; attempt-and-eval-facts re-run queued for the first batch after
the hold.


### 2026-09-16 04:20Z — default refused every publication; second refork

`default` (pid 7595) had `:seon.test/reach-digest` installed as
`:db.unique/identity` from an earlier bridge; the current bridge derives no
uniqueness, and adoption's `declaration-changes` compares only the keys the
new declaration carries, so the drop read as compatible. Every test with a
changed reach digest then carried two identities and `seon.fn/index-tempids`
refused the whole publication ("Program indexing found multiple entity
identities."). Symptoms: hook "Publication did not finish within its declared
bound", `bin/seon init --dev default --changed …` hanging, batch 25 results
not recorded (`live-prepl-unavailable`). Issue:
`docs/seon/issues/adoption-misses-a-dropped-uniqueness-on-an-installed-attribute.md`
(fix handed to the steward session, which holds `src/seon/cluster.clj`).
The steward session is reforking default; the recording-only platform tier
runs after its message. Also landed: `86b4c8ff4` — the runner's confirmation
test wrote `workers/` into the repository root when no test root property was
set; `worker-parent` now refuses that state and `/build/` is ignored.
Machine load at the time was Spotlight (`corespotlightd` 123%) and Backblaze
(`bztransmit` 99%), not our JVMs.

### 2026-09-16 05:00Z — recording refusals: cause found (dc5c7d57f)

Three gates in a row could not record results on a cluster that answered
evaluations in ms. Research verdict
(`gate-recording-refusals-2026-09-16.md`): the recorder never sent its form;
`live-root-value!` gates on a census pre-read whose reply is parsed with
`clojure.edn/read-string`, and the dev JVM's in-process fixture branch
keyword `:seon.test-support.fixture/0` is unreadable EDN → `reachable? false`
→ `live-prepl-unavailable` (batches 25, 26B); the same probe timed out under
the 30 s silence backstop during the platform gate (26A). Refusal reproduces
in 268 ms; the recorder's form answers over the same socket in 13 ms. This is
the pre-read-vs-authority class (AGENTS.md §"No seam may act on a pre-read").
Opus fix lane launched: dissolve the pre-read (send is the authority), mint a
readable fixture keyword, make the parse seam total, one regression.
turn-test-reds lane still live (4 commits, latest `b3266e25b`); its gate runs
when it stops.

### 2026-09-16 05:40Z — the stall class: minute blob sweep under an exclusive permit

Latency lane (commit `4764c233a`, no source edit): the recorder is fine
(`commit-results!` 699 ms); `seon.blob.retention/reclaim!` runs every minute,
takes the store's exclusive sweep permit and walks 380,285 konserve keys
(64.6 s) to find 3,624 blobs; `datahike.api/branch!` waits unbounded, the
reachability gate is closed 96.4% of the time, `registry/branch!`
57–74 s. This explains the recording silence (batches 26A, 27A), the hook
publication timeouts, slow forks/retires, and the fresh-operator-test hang in
batch 27 B (declared-long test slowed 2.4× to the 270 s bound; `7c7395c8a`
refuted as cause). Issue
`blob-retention-sweep-starves-every-roster-writer.md` (blocker). Lanes:
astra `retention-sweep` (spec `tmp/orchestrator/wave2/retention-sweep.spec`:
candidates from blob-write facts, permit only around deletes, event-driven
budget check); Opus research `store-footprint-2026-09-16.md` (why 72 GB /
380k keys; reset vs `gc-storage!`).

### 2026-09-16 06:10Z — retention: option 1 (dissolve) chosen at the design gate

retention-sweep's investigation (`e375a3a97`, `retention-sweep-2026-09-16.md`)
measured inventory 70.3 s and the gate closed 96.6% even with blobs UNDER
budget, and found Datahike's reachability GC already preserves referenced
blobs (`registry.clj:519-548`, weekly `root/maintenance/compact`). Options:
(1) remove automatic byte-budget retention, keep existing GC — 1–2 h,
gives up byte-budget enforcement; (2) keep the policy on root facts with
guarded deletion — 1–2 days across owners; (3) manual-only retention —
30–60 min, manual calls still pause writers. The orchestrator chose (1)
under the dissolution law (owner not present; reversible by one revert) and
resumed the lane. Flagged for the owner.

### 2026-09-16 06:30Z — store footprint: 67 GB unreachable, GC never runs in practice

Research `cfef57241` (`store-footprint-2026-09-16.md`): 71.6 GB / 382,425
files, 93.3% `pss/leaf` copy-on-write index nodes; created 2026-09-08; grew
2.8 → 15.4 → 52.9 GB per day 09-13 → 09-15; ~50 files / ~1.5 MB retained per
transaction on `:cluster-default` at ~6 tx/min, dominated by edit-hook
`:seon.fn.ast/*` upserts and the per-minute maintenance result rows. The only
GC caller (`registry/collect!`) is weekly and the store grew through its
window. Plan: retention-sweep removes the per-minute writer; the steward
session resets default's store at the next refork (disposable-data rule);
GC cadence appended to the existing issue
`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md`.

### 2026-09-16 07:20Z — retention-sweep landed (`5a10f5dfa`, evidence `af0359703`)

Automatic byte-budget blob retention removed (dial, schemas, test, schedule
seed); default's seeded per-minute row retired by transaction; weekly
reachability GC (`registry/collect!`) remains. Live on default: reachability
gate open 3.36% → 100% over 90 s, zero scheduler key walks, in-process 4 tests
/ 21 assertions green, adoption converged. Issue
`blob-retention-sweep-starves-every-roster-writer.md` resolved and archived.
Gate (seon.schedule-test seon.blob-test seon.cluster.registry-test
seon.dev.fresh-operator-test + platform) waits on the cold-arming blocker
(batch 29) and runs on the fresh store after the reset.

### 2026-09-16 08:05Z — fresh-operator config proof: refuted as retention's

retention-sweep verified the batch-30 red in
`init-owns-current-source-and-dormant-cluster-lifecycle` is pre-existing:
both assertions pull the config entity from a raw `@connection`, which
carries no projection state, so `seon.db/pull` returns
`:seon.schema/missing-projection` (0 actual keys vs 77 expected); through
`(seon.db/db connection)` all 76 decision keys match (evidence `147010c50`,
issue `fresh-operator-config-proof-pulls-an-unprojected-database.md`).
Fixed `8208754cb` (two read sites through `seon.db/db`); gated in batch 32 with seon.fn-test on the steward's `7cfe02790` (program/shapes hand list killed).

### 2026-09-16 09:00Z — turn bookkeeping cost: config/apply! rebuilds, not the store

Research `c2972178b` (`turn-bookkeeping-cost-2026-09-16.md`): the six-form
bookkeeping path is ~100 ms warm (whole call-turn window 452 ms: prompt 217,
6× evaluate 134, 4× transact 38, settle 22, analyze once 16, request-profile
15 over 64 calls, issue tests 0) — the archived per-form disease has not
recurred. The 270 s worker bound is the FIXTURE: an empty `with-cluster` costs
~5.0 s (with-database 1 ms), ×48 trials ≈ 240 s. One cause, two halves, in
`seon.config/apply!` (called twice per fixture cluster, 2–3 s each,
already converged): `apply-compiled!` rebuilds `projection-from-database`
(`config.clj:459`, 682–720 ms) where the carried projection is 0 ms — the
§2.1 defect relocated to config — and `reconcile/plan` (`:479`) spends
773–984 ms to return 0 operations. Store size refuted (MemoryStore fixture;
1-datom transact 3 ms); the reset changes none of this. Plan: hand
`apply-compiled!` the carried projection; a converged apply becomes a read on
`:seon.config/applied-manifest-digest` + basis `:t`; seed the fixture
cluster into the canonical base. Flagged: a 13 s / 89k-datom publication
transaction on default; `request-profile` derived 64× per turn. The 5.6 s
first-turn cost in a fresh worker is not yet attributed (needs a cold JVM).

### 2026-09-16 09:40Z — store reset; config-apply-cost lane launched

Steward session ran `bin/seon reset --force`: default pid 27828, forked from
current-src at `f8c00a5be`; store 72 GB → 107 MB. Astra lane
`config-apply-cost` launched (spec `tmp/orchestrator/wave2/config-apply-cost.spec`):
carried projection into `apply-compiled!`, converged apply as a read on the
applied-manifest digest, dissolve the zero-op reconcile plan cost, one
`apply!` per fixture cluster. Steward owns the 13 s publication issue
indexing (`issue-indexing-at-publication-costs-13-seconds.md`) and filed
`request-profile-is-derived-64-times-per-turn.md`.

### 2026-09-16 10:30Z — publications refused by a foreign analyzer error; recording races current-src

Hook log: every current-src publication since 06:23Z refused — static
analysis finds "Unresolved var: support/test-context" at
`test/seon/test_failure_facts_test.clj:120` (reach-closure's new regression
file); the config-apply-cost lane's edits were refused for it at 06:24:21.
Gate recording on the fresh store is rejected with two
`datahike.versioning` "Branch head changed before force-branch!"
(`:stale-branch-head` on `:current-src`) during batch 34 A: the recorder's
scratch fork/retire (`source.clj:300-323`) pre-reads the current-src head
and races the hook's publications — the pre-read-vs-authority class again.
Both handed to the steward's reach-closure lane.

### 2026-09-16 11:20Z — config-apply-cost landed (`6313d2006`, note `8f17a527c`)

Both projection rebuilds in `apply-compiled!` removed (carried projection
reused) and the duplicate fixture apply deleted: an identical-plan apply
612–1,336 ms → 80–86 ms. Digest-only convergence was refuted by the lane
(initialization changes and hand edits remain possible), so the plan still
runs but no longer rebuilds. One class regression added. The lane could not
verify the full fixture target in-process: the restarted default's shared
fixture base had cached a missing-classpath exception (the base-poison
class again, issue `in-process-test-runs-poison-the-shared-fixture-base`).
Gate: batch 36 after batch 35.

### 2026-09-16 11:50Z — base poison on pid 37572: the evaluation context loads test namespaces

Orchestrator's one-shot base refresh (the turn-test-reds form) failed in
25 s: `seon.sci.eval` (`eval.clj:1026`) refused to load
`seon.dev.dependency-cache-test` — cause chain: syntax error at
`dev_cache.clj:1:1` ← `clojure.tools.build.api` not on the classpath. Since
reach-closure made tests program rows, the base SCI context requires every
test namespace too, and `dev-cache` needs a :test-alias-only dependency the
dev JVM lacks (the test loader adds :test paths, not its extra-deps). Every
db-backed in-process run on default is blocked until reach-closure decides:
the evaluation context stops loading test namespaces, or the loader carries
the :test alias's deps. Handed to the steward's reach-closure resume.

### 2026-09-16 12:40Z — base poison root cause (`653d4d4ef`, steward): thread context classloader

Refutes the reach-closure hypothesis. `seon.sci.eval/classpath-locatable?`
used one-arg `io/resource`, i.e. the calling THREAD's context classloader; an
in-process `seon.test/run` binds the test DynamicClassLoader around its body,
so the first base construction inside it saw all 214 test namespaces as
servable and required them until `dev_cache.clj` → tools.build failed. The
fix resolves through the system classloader; regression
`seon.sci.eval-test/process-membership-ignores-a-thread-context-classloader`.
A poisoned delay cannot be un-poisoned in place: default restarted. Queued
for the next batch: seon.sci.eval-test. Follow-on issue filed by the steward
(host-namespace! find-ns branch admits any already-loaded namespace).

### 2026-09-16 13:20Z — config-apply-cost second slice (`5e5aa6293`)

The batch-36 config/reconcile reds were refuted as a semantic regression:
the fixtures' unchecked writes were being REFUSED by the stricter admission
(rows the schema no longer admits) and the tests read the absence as a
changed semantics; the lane fixed the fixture inputs, carried the database
value through `seon.db/db` (the raw-deref class again), and restored the
shipped `:seon.test/check-time-limit-ms` decision missing from
`config/default.edn`. One authorised cold iteration: 27 tests / 128
assertions green. Re-gated in batch 39 with the transcript second pass.

### 2026-09-16 13:50Z — custody-stability reds fixed (`3c115ff15`)

Both were fixture/roster drift: the isolation fixture wrote messages without
the now-required `:seon.message/to` (all writes refused, assertions passed
over an empty database — absence read as health); it now upserts a real
recipient. The custody-returning roster gained two reviewed members
(`seon.cluster.agent/acquire-context!` → ctx,
`seon.db/carry-connection-projection-state!` → connection). The lane kept the
literal expected set as the dated reviewed record whose drift checker is the
test itself (the derived side is already `:seon.fn.arity/output-refs`, so a
query on both sides would be tautological) — accepted under §2.2's
"enforced by a checker" clause. In-process 5/0/0 and 2/0/0. Batch 40.

### 2026-09-16 14:20Z — recording rejected on a healthy JVM: read-only research launched

Every gate since batch 30 ends `persistent results NOT recorded … The cluster
rejected the prepl operation`, including batch 40 on an idle pid 45917; the
wrapper drops the rejection value. Opus read-only lane launched to capture
the exact rejection by sending the recorder's own form to default and to
verify the stale-head race hypothesis (`gate-recording-rejection-2026-09-16.md`).

### 2026-09-16 15:10Z — recording rejection: the wrapper hides the cause (`bfb578efb`)

Research verdict: the operator replaces a prepl `:exception` reply with the
fixed sentence "The cluster rejected the prepl operation."
(`script/seon/fresh_operator.clj:1605`) and the runner keeps only the message
(`runner.clj:2007`), so the cause is unnameable from any log by
construction. The recorder's form itself is GREEN on idle default over the raw
prepl socket (2,010 ms, committed refs); every in-cluster failure returns as a
value, so the `:exception` must come from prepl's own read/emit path
(`src/seon/cluster.clj:478-495`). Falsified: the stale-head race (retried,
diagnostic value) and a concurrent publication. Opus fix lane launched: the
wrapper carries the cluster's cause, the notice prints it, regression
replaces the stale-sentence assertion at `fresh_operator_test.clj:1776`;
then one reproduction through the operator path to name the real cause.

### 2026-09-16 16:30Z — wrapper fix landed (`b77c553e4`); cause still unnamed

The operator's `:exception` branch now raises `:seon.fresh-operator/prepl-exception`
carrying the cluster's `Throwable->map` (cause, via chain, ex-data, first
frame, clipped form, advertisement), and the runner's notice prints kind,
message and data. Two more hypotheses falsified live: the full recorder
through `live-root-value! "."` commits on default in 2,229 ms, and reply
sizes up to 739 KB printed return complete — so neither payload size nor a
failure value explains the sentence; the archived gates that printed it were
green 15–16-test runs. Batch 44 is the first gate whose notice can name the
rejection. Stale-sentence assertion at `fresh_operator_test.clj:1776`
replaced (4 in-process regressions green).
