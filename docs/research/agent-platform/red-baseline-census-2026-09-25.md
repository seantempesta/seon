---
type: research
status: complete
lane: census-red-baseline-2
created: 2026-09-25
---

# Default's red baseline, second census (2026-09-25 00:16Z)

This is a read-only repeat of the first census (red-baseline-census-2026-09-24.md, 341a94f23), using the same method.
No test ran and no JVM started. Default is pid 57919, booted 2026-09-24T23:30:19Z (17:30 local), health `faulted`.
The data was read through MCP `eval_clj` in JVM mode against `(seon.cluster.boot/connection "default")`. The read was
`#'seon.problems/failed-tests` (problems.clj:378). It now counts only current `:seon.test/sym` rows, so the first census's
34 deleted-test reds are gone. It found 671 entries (537 red, 134 typed unknown) in 466 ms. Each red's text comes from
`seon.test/failure-message` (test.clj:55), clipped to 1,500 characters. For each unknown, the stop cause was pulled
from `:seon.test.run/stopped`.

Classification used a scratch classifier kept outside the repo: an ordered substring list, then a hand read of all 78
leftovers. Line numbers are the `deftest` line. Run times are local (-06:00), the same zone as `git log`. History was
one query over every completed member of the 671 symbols (6,260 rows, 91 ms). It answers whether each test was green at
any point on the 24th.

## The headline: the recorded baseline predates today's fixes

| | count |
|---|---:|
| reds + unknowns (`problems`: 537 failed + 134 unknown) | 671 |
| latest result recorded **before this JVM booted** (17:30) | 663 |
| latest result recorded before the S2a clean world (fe5dcc072, 16:24) | 655 |
| recorded on the current program (18:12–18:13 runs) | 8 |
| **stale: a fix for its class landed after its latest result (rerun only)** | **473** |
| live or unproven (needs a code, test or harness change, or a probe) | 198 |

The first census found 591 current reds, 195 of them "not started" errors. Today's 671 is higher because five requests
between 15:04 and 15:23 stopped (on a deadline, an unfinished member or a refused recording) and left 134 typed
unknowns. That typed unknown is the class 1 fix (e33009381, cb632bd81) working as designed. The baseline was never
rerun after the afternoon's fixes, so it measures the tree as it stood at 15:00, not HEAD.

## Ranked classes (671)

| # | class | count | stale? | green earlier on 24th | owner / smallest fix |
|---|---|---:|---|---:|---|
| 1 | typed unknown: request stopped (61 member-unfinished, 45 deadline, 28 recording-refused) | 134 | stale | 41 | rerun only |
| 2 | "Not started" recorded as error (old shape) | 102 | stale | 57 | rerun only (cb632bd81, 15:28) |
| 3 | SCI cannot interpret a JVM-macro test helper (`with-server`, `with-connection`) | 54 | stale | 34 | rerun only (55af9e04b, 12:57; F7 a9449b7c3) |
| 4 | bound overrun or event wait under load | 50 | 43 load / 7 live | 9 | rerun on a quiet JVM; 7 live listed below |
| 5 | adoption changed loaded definitions without recording (one run) | 46 | stale | 38 | rerun only (S3b 389d2d265, 7f53d0809) |
| 6 | a changed host-bound declaration refuses on the branch | 40 | stale | 18 | rerun only (S2-overlay 464bca11b, 2f4ea22d9) |
| 7 | test branch inherits default's live faults, messages or agents | 34 | stale | 0 | rerun (S2a fe5dcc072); then confirm |
| 8 | fixture writes hand-built rows the writer refuses | 73 | live | 0 | harness S5 conversion |
| 9 | SCI cannot resolve a non-row name (`future-call`, deftype) | 28 | stale | 22 | rerun; latent SCI resolve gap |
| 10 | "Isolation requires a fresh owned branch" (one run) | 23 | stale | 0 | rerun (S2a; owner ruling 955a9acc6) |
| 11 | test calls src with a retired shape or rule | 23 | partly stale | 0 | update each test |
| 12 | refusal payload member or key is nil | 13 | live | 0 | one probe, then one producer fix |
| 13 | schema, reader and analyzer rule assertions | 10 | live | 0 | per assertion (candidate regressions) |
| 14 | cache or read counters differ | 8 | live | 0 | harness H6 (S10/S11) |
| 15 | render selection ignores fixture renderers | 7 | live | 0 | probe (seon.render selection) |
| 16 | instrument-test run 4 min after 685ffd6e6 | 6 (+1 in #4) | **live** | 0 | **candidate regression**: instrument.clj |
| 17 | worker-global instrumented-Var drift | 6 | stale | 6 | rerun (685ffd6e6, 7f53d0809) |
| 18 | "SCI context did not acquire the tested program" / no SCI Var | 6 | stale | 0 | rerun (2f4ea22d9, 18:10) |
| 19 | one-request harness test, run on current HEAD | 2 | **live** | 0 | **candidate regression**: S2 follow-ups |
| — | singles: store isolation, 2,596-byte report, config manifest, documentation evidence | 6 | live | 0 | below |

"Green earlier" counts tests whose symbol has at least one completed member with 0 fail, 0 error and more than 0 passes
on the 24th. That is the evidence for the rerun estimate at the end of this file.

## Stale classes: rerun only

For each class below, the latest result is older than the commit that removes its cause.

1. **Typed unknowns, 134.** Five runs stopped: fe32de93e4e3 (15:23, member-unfinished, 45), 3fd6afb85e4d
   (15:11, member-unfinished, 16), ad8edb45f624 (15:04, deadline, 30), a64a582e1342 (15:23, deadline, 12),
   a86f048871e8 (15:10, recording-refused, 28) and 19146fa48383 (15:14, deadline, 3). The stop causes predate
   detect-not-lock (7f53d0809, 2bf66450c, 16:26–16:30), the clean world (fe5dcc072) and the panic-stops-graph change
   (fb1980ee3). Examples: `my.plan-test/a-changed-title-is-an-ordinary-fact-update` (test/my/plan_test.clj:215; the
   earlier `with-plan` ns-row residue is gone, and plan_test.clj:22 now uses `support/agent-tx`),
   `seon.call-preparation-test/an-acquired-context-supplies-a-leading-database-to-plan` (call_preparation_test.clj:858),
   `seon.cluster.agent-test/n-agent-parallel-turns-property` (cluster/agent_test.clj:726).
2. **"Not started" errors, 102** (11:25–14:59). This is the old recording shape. Since cb632bd81 (15:28), an unstarted
   member stays a typed unknown and its stop cause is recorded. Examples:
   `seon.ai-test/a-foreign-document-either-yields-text-or-says-why` (ai_test.clj:751),
   `seon.call-preparation-test/two-clusters-supply-their-own-custody` (call_preparation_test.clj:797),
   `seon.db-test/return-map-queries-preserve-ordering-and-limit` (db_test.clj:546).
3. **JVM-macro helper, 54** (12:28–12:36). Of these, 37 are `seon.render.web-test/with-server` and 17 are
   `seon.cluster.agent-test/with-connection`. They were fixed by 55af9e04b (12:57), and host-bound macro status is now
   clj-kondo's (a9449b7c3, plus the fill in 87a7e435b). Examples: db_test.clj:2316, render/web_test.clj:457,
   render/web_test.clj:2210.
4. **Adoption unrecorded, 46.** All come from runs 19146fa48383 and 88f2728edc52 at 15:14:56, the minute S3a
   (d77a9461a) landed. That message was S3a's keep-out. S3b (389d2d265, 15:35) makes an unknown program stop the
   request as unknown, and 7f53d0809 deleted the lock. Examples: adoption_margin_test.clj:9, ai_test.clj:359,
   ai_test.clj:78.
5. **Host-bound declaration changed on the branch, 40** (10:58–15:18). `seon.test.runner/recorded-member-results`
   accounts for 19, `seon.fn/analyze-forms` for 9, `seon.test/admit-run` for 4, `install-caller-host-bindings!` for 4 and
   `seon.db/carried-projection` for 2. Each of these rows differed on default because edits were published but not
   adopted. Since S2-overlay (464bca11b, 17:13), a request runs on the adopted published program plus only its own
   differing rows, and since 2f4ea22d9 (18:10) it acquires no SCI program. Examples: cluster/wake_test.clj:498,
   render_simplification_test.clj:997, reset_edges_test.clj:61.
6. **Inherited live data, 34** (≤15:48). This covers error-test (12), problems-test (9), and 13 more:
   config_test.clj:589 (sees `"default"` beside `"fork"`), db_test.clj:1746 (a `root` message), schedule_test.clj:350,
   turn_test.clj:2120, turn_loop_test.clj:650, wake_test.clj:552 and my/agent_test.clj:38. The cause was
   `support/with-database` forking the live branch. S2a (fe5dcc072, 16:24) branches from the adopted published program
   plus boot's cluster facts. Issue: docs/seon/issues/test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md.
   None of these tests was green on the 24th, so the rerun is the proof. Close the issue only after it.
7. **Unresolved non-row names, 28.** Of these, 15 are `clojure.core/future-call` in `seon.sci.eval-test/deadlined-in`
   (14:59 and 15:26) and 13 are the `DatabaseSchemaIdentity` deftype in `seon.render.walk/root-pull-plan` (15:14). These
   callers were interpreted only because default's rows differed, so S2-overlay removes the trigger. The underlying gap
   is still latent: an interpreted caller cannot reach a non-row host name (a deftype class or a clojure.core Var SCI
   lacks). Examples: bootstrap_test.clj:291, call_preparation_test.clj:519, flow_test.clj:557.
8. **"Isolation requires a fresh owned branch", 23.** All are in `seon.cluster.turn-test` from run 3fd6afb85e4d
   (15:11:57), raised at agent.clj:930. They postdate the custody fix b2fcb5225 (12:44). The cause is an inherited agent
   row inside the member, which the owner ruled out (955a9acc6) and S2a removes. Examples: cluster/turn_test.clj:2620,
   :2334, :449.
9. **Worker-global drift, 6, and "did not acquire the tested program", 6** (≤15:14). A concurrent adoption swapped
   instrumented Vars mid-run. The fixes are one wrapper per adoption (685ffd6e6) and member-side change detection
   (2bf66450c). The "did not acquire" refusals came before 2f4ea22d9. Examples: data_shapes_test.clj:205,
   fn_test.clj:1536, bootstrap_test.clj:428.

## Live or unproven classes

- **#8 Hand-built fixture rows, 73: harness S5.** These fixtures stay red under any world.
  - Agent rows without `:seon.agent/branch`: 27. Examples: my/agent_test.clj:11, contracts_plan_test.clj:77,
    render/web_debug_test.clj:413.
  - Rows missing `:seon.program/definition-digest`: 15. Examples: bootstrap_test.clj:229, render/web_debug_test.clj:485,
    test_failure_facts_test.clj:363.
  - Component validation: 4 (db_test.clj:913, render/root_pull_test.clj:295/343).
  - Fixture or agent-creation refusals: 23. These include the failure-fixture transaction (test_failure_facts_test.clj:48),
    the auto-check admission (test/accretion_test.clj:19), my.note-test (4; test/my/note_test.clj:20) and
    plan_api_test.clj:73.
  - "No surviving test definition" for hand-registered fixture tests: 4 (test_provenance_test.clj:21,
    test_failure_facts_test.clj:295/345).
  - A foreign-branch write in config_test.clj:659 ("two clusters on one JVM").
  - Open items records a possible src owner for the agent-branch rows: creation-tx refuses `:seon.agent/branch` on
    fixture branches (agent.clj:186–196). Verify that before routing all 27 to S5.
- **#4 Bounds, 7 live.** Six are in `seon.test-reaching-test`, run at 18:12 on the current program, and take 5.9–9.1 s
   against a 5 s bound: `an-in-process-check-excludes-a-declared-long-test-and-names-it` at 9,086 ms,
   `fixture-observations-remain-stale` at 7,650 ms, and `agent-admitted-tests-reach-their-tested-function` at 7,547 ms.
   This is the cold reach-derivation cost named in f9de0d928 and 532a364a0. The seventh is fault_test.clj:81
   (1,390 ms against 1,000 ms at 16:32).
  - The other 43 ran between 12:26 and 16:30 under concurrent adoption load:
    - 14 are event waits of 20–48 s, e.g. loop_proof_test.clj:64 at 47.9 s,
      `seon.cluster.agent-arming-test/starting-an-issue-…` at 36.3 s, and run4_install_test.clj:17 at 34.8 s.
    - Two are micro-bounds (db_test.clj:2306, 52 ms against 50 ms).
    - Most of the rest sit between 5.3 and 30 s.
  - Rerun them on a quiet JVM before any per-test work. The profile shows `call-with-projection` at 1,111 s over 10,574
    calls, and 120 profiled calls over one second.
- **#11 Retired shapes, 23.**
  - Update each caller:
    - `seon.turn.loop/phase-failed` is gone from terminals (turn_loop_test.clj:960/1088, cluster/agent_test.clj:1175).
    - mcp_bridge_test.clj:682 defines an uncontracted defn and hits schema-entry `:gate`.
    - my/message_test.clj:132 now expects `seon.cluster.message/delivery` as the operation.
    - Contract refusals come from test callers: `record-attempt!` without `:seon.ai/max-tokens`, `commit-tx`/`recording`
      without `:seon.error/declared-schema`, `seon.error/normalize` without a projection, and turn `open-turn`/`settle!`.
    - Fixture entity schemas lack a partition: 5, e.g. help_trial_test.clj:17 and rereads_panel_test.clj:28.
  - Those older than 041102a76 (15:18) or 3791dcfd2 (15:28) may already be fixed. The fix-retired-shape landing lists 9
    as committed but not run.
- **#12 Nil refusal payload, 13 (≤14:48).** The same producer shape as the first census's class 7. Expected fields
  (`:seon.error/member`, `:seon.config/key`, `:seon.error/offending`) come back nil in config_test.clj:213/477/545/383/714,
  db_test.clj:184/2089, fn_test.clj:2847, schema_test.clj:446/959/1481, shell/jvm_test.clj:175 and mcp_test.clj:112.
  No commit since the first census targets it. The suspects are still bab838388 (error constructor E0a) and the reverted
  ab7e4a643. One probe of `config/default-population`'s refusal data decides between a regression and a retired
  expectation. This is the smallest real unblock.
- **#16 instrument-test, 6 + 1: candidate regression.** Run at 16:30:02, four minutes after 685ffd6e6 ("one wrapper per
  adoption"):
  - instrument_test.clj:1454 has arglists nil.
  - instrument_test.clj:1500/1537 show reporter evidence lost.
  - instrument_test.clj:531/598 fail kernel/contract error validation.
  - The `render-ai` NPE is at "the caller frame is part of the refusal sentence".
  - The +1 is `a-sovereign-sci-fork-acquires-its-own-recorder` (28.7 s, with no interpreted wrapper; also named in open
    items).
  - Owner: instrument.clj. Run `bin/test-check default --ns seon.instrument-test` once on HEAD to separate this from load.
- **#19 one-request-test, 2: candidate regression, run at 18:13 on HEAD.**
  - test/seon/test/one_request_test.clj:202: the held member runs again (`executed-count` 1, expected 0).
  - one_request_test.clj:96: unchanged green members are not answered from the record.
  - Both run after 2b2c2bf39, 8da3dcd16, 266187a0e and 2f4ea22d9 (the incremental-selection changes), so the owner is
    the S2 follow-up lane.
- **#13 Rule assertions, 10:**
  - schema_test.clj:526/1298/1449/1266: an undocumented `:fn` predicate, a new projection-predicate reader, and an
    observation with `:db.unique/identity`.
  - The schema.datahike parity register refusal at 11:11.
  - The sci/reader round-trip, which open items blames on an orphan `;;; Development Helpers` banner in schema.clj.
  - fn-test `:malli.core/invalid-schema`, the db_test.clj:2027 NPE and branch_execution_test.clj:389.
- **#14 Counters, 8:** render/root_pull_test.clj:204/235/340, fn_test.clj:2651/699, cluster/turn_test.clj:3182,
  error_write_timing_test.clj:123 and render_simplification_test.clj:909.
- **#15 Render selection, 7:** render_simplification_test.clj:263/701/733, render/web_debug_test.clj:661,
  render/page_review_test.clj:92, render/entity_pairs_test.clj:90 and reset_edges_test.clj:370. All ran before S2, so
  the fixture render rows may simply be absent from the member world. Rerun first, then probe.
- **Singles:**
  - cluster/store_test.clj:127 found its store already open in pid 99265, an earlier JVM. The destructive test is not
    isolated.
  - adoption_diagnostic_test.clj:57 reports 2,596 bytes against a 1,024-byte limit (open item).
  - config_test.clj:151: the shipped EDN does not match the dial attributes.
  - Two in documentation_test (:129 read-evidence, :273 shown example).

## What one fresh run of the stale set would clear

The stale set is 473 tests. Of those, 216 have green evidence earlier on the 24th, their latest outcome is a stop
artifact or an SCI refusal that no longer applies, and their bodies ran green before. My estimate is that about
**200–230 clear** on a single quiet run of the stale set. That would take `failed + unknown` from 671 to roughly 440–470.

The remaining ~250 have no green evidence today. That includes the 57 "isolation" and inherited-data tests, whose
bodies never reached their assertions. They will turn into real outcomes, and past pattern says many will land in
class #8 (S5) and class #11.

Rerunning the 43 load-bound reds on a quiet JVM would add perhaps 5–15 more, since only 9 were ever green.

Order:
1. One quiet rerun of the stale set: no code, about 215 cleared.
2. Probe #12 and run instrument-test on HEAD (#16): about 20.
3. Route the #19 regression to the S2 follow-up.
4. The #11 test updates: 23, mechanical.
5. Harness S5 for #8: 73, the largest remaining block that needs design work.

Limits:
- Substring classes plus a hand read of text clipped to 1,500 characters. Counts are ±3.
- "Green earlier" ignores branch and published-base filters, so it is an upper bound.
- Stop causes are the recorded `:seon.test.run/stopped` values.
- Reach counts (the first census's 538 reaching `seon.fn`) were not recomputed.
- No red was re-executed.
- Timings: `failed-tests` took 466 ms, the dump 45 ms and the history query 91 ms.
