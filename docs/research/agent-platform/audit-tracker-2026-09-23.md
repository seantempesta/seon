---
type: research
status: point-in-time trace at HEAD 4ba68d6aa plus the shared working tree (2026-09-23); re-derive before citing a status
created: 2026-09-23
tags: [agent-platform, audit, schedule, tracking]
---

# Audit tracker: what became of every audit finding (2026-09-23)

Lane `audit-tracker`. The owner asked, 2026-09-23: "I want to follow up on all the
audits that I've asked for that we have scheduled or planned work with so nothing
slips through the cracks." This note does not edit src, test or resources.

**Method.** Each audit was read end to end. Each finding's status was traced from
`git log` (subjects and `-S` over the owning paths), the landing notes under
`docs/prds/agent-platform/landing/`, the issue notes under `docs/seon/issues/`, the fix
schedule (`fix-schedule-2026-09-23.md`, cited `#n`), the ownership ledger
(`tmp/orchestrator/file-ownership.md`) and the plan README (§4 row 1.3f). Where a
symbol's survival was the question, `grep` over `src`/`script` settled it. Statuses:

- **LANDED** (commit);
- **IN FLIGHT** (lane and schedule row; uncommitted hunks count here);
- **SCHEDULED** (schedule row, waiting on X);
- **PLAN-ONLY** (a plan row owns it, but no fix-schedule row, lane or issue, so the loop never launches it);
- **ISSUE-ONLY** (an issue note exists, but no schedule row or lane);
- **DEFERRED BY RULING** (cited);
- **REJECTED / FALSIFIED** (evidence cited);
- **KEEP** (the audit itself kept it);
- **ORPHAN** (no schedule row, no issue, no commit, no running lane).

Every ORPHAN, and every PLAN-ONLY or ISSUE-ONLY item with a concrete fix, got an
appended fix-schedule row, marked "added by audit-tracker" (rows #27–#51).

## 1. Dependency-already-does-it audit (26 rows; README §4 1.3f)

**The audit file itself is untracked.** `git status` shows
`docs/research/agent-platform/dependency-already-does-it-audit-2026-09-23.md` as `??`.
README 1.3f and AGENTS.md "No stamps" link to a file that is on no commit.

| row | finding | status | evidence |
|---|---|---|---|
| 1 | projection stamped on the db value | IN FLIGHT / SCHEDULED | memo landed `9b8c5b405`; the writer reads no stamp `ca9c40639`; the cold construction read remains → #11; the remaining stamps (`carry-projection-state`, 4 hits) → #25 |
| 2 | temporal views re-merge metadata | SCHEDULED #25 | landing lane-projection-writer "Audit rows 2 and 9": the merge still carries `:seon.sci.eval/projection-state` (`db.clj:275`); it goes with the sweep |
| 3 | encoded-operation stamp | DEFERRED BY RULING | `852c97497`: the comparator proof failed, so the codec is retained; `mark-encoded` has 5 hits |
| 4 | dependency-revision map | KEEP | the audit kept it; A2 §2(a) |
| 5 | index/replay currency arms | IN FLIGHT | A2 c3 replay diff deleted `f1e55a824`; revision-first reorder uncommitted in `db.clj` (writer-cost, #24c(3)); `index-evidence-current` survives for A2 c1 |
| 6 | `:seon.cluster.eval/read-basis-transaction` | PLAN-ONLY → #40 | 17 hits; 1.3f "B2 with A2 c1, RESET" |
| 7 | packaged-population hand cache | PLAN-ONLY → #41 | `declaration-stamp` 7 hits. **Conflict:** flow audit row 43 says KEEP this lock |
| 8 | environment declaration delay | PLAN-ONLY → #41 | `declared-members` 4 hits; after row 7 |
| 9 | projection staleness by basis-t | SCHEDULED #25 | the landing says the sweep deletes the transport; invalidation census A10 says KEEP, because it is cheap |
| 10 | oversight owning-instance search | LANDED | `da2086452`; review fixes `b44585c0b`, `9c0ecae86` |
| 11 | publication ReentrantLock | LANDED | `a102a8403`; the `:seon.operator.lock/*` schema family is gone (0 hits); review fixes `874918765`, `40c9ebf5e`, `3c55bb0f6`, `4fd4a4128` |
| 12 | branch-open pre-check | KEEP | ruled KEEP (A2 §6.3) |
| 13 | reopen re-reads `:keep-history?` | PLAN-ONLY → #43 | A2 f8 + c12 (lane-a2 plan :182,:191); store.clj unchanged |
| 14 | GC head re-read | PLAN-ONLY → #43 | A2 c9 extension; `branch-reopens?` 2 hits |
| 15 | render walk entity cache | PLAN-ONLY → #42 | `acquire-entity` 2 hits; after row 1 |
| 16 | fabricated transaction report | LANDED | `87c4228f7`, `20f319608`; the publication-lock review verified the conversion |
| 17 | SHA-256 over a gitlink | REJECTED / FALSIFIED | done in `678009fcd`, then reverted by `f8af5d92e`: a 40-char id fails the 64-char `:seon.fn.file/digest`, so every from-zero publication refused (issue gitlink-pins, closed `bd4767a76`). Reopening needs the digest shape widened |
| 18 | gate file digests docstring | PLAN-ONLY → #44 | `test/cache.clj` docstring does not yet name the dirty-tree guarantee git lacks |
| 19 | schema-shape family | PLAN-ONLY → #45 | README 1.4b; `!authored-shapes` 3 hits |
| 20 | ambient projection vars | SCHEDULED #25 | `*projection-state*` 6, `*candidate-forms-overlay*` 9 hits |
| 21–26 | memoize/compiled cache/Malli registry/structural registry/`d/listen`/process bound | KEEP | the audit kept them |

## 2. From-zero boot cost (10 audit rows + phases)

| row | finding | status | evidence |
|---|---|---|---|
| 1 / 6a | per-root rescan in the write validator (~96 s) | LANDED | `6bf3bde78`; the tx-entity follow-up `bb3a0c6c4`; regression `be713ae30` |
| 6b | `owners-of` probes 136 component attributes per entity (~15 s) | ISSUE-ONLY → #37 | the validator landing says it "remains"; the class is `from-zero-boot-takes-minutes.md` |
| 2 | `start` compiles every dependency (17–19 s) | LANDED | `9744c970d` (start reuses the class cache); remaining first-party compile 7.2 s → #14 |
| 3 | reset deletes the store | LANDED | `76b42f90f` (reset = fresh branch); 14.8 s against a 4 s target → #1/#15 |
| 4 | scratch from-zero boot as schema proof | LANDED (ruling) | `b81104985`, `8a43e596a` |
| 5 | kondo over 708 files | KEEP | once per store life |
| 6 | arm loads every `:core` ns, tests included | SCHEDULED #14 | "arm only running namespaces"; see the §10 note on #14's state |
| 7 | schema diff on every branch open | LANDED | `7a9d33da4`; converged reopen 16 ms (`8ce91fbaa`, `3f273fe7c`) |
| 8 | `projection-from-database` rebuilt | SCHEDULED #25 | memo `9b8c5b405` landed; the carry is 1.4 |
| 9 | complete issue index | ISSUE-ONLY | `issue-indexing-at-publication-costs-13-seconds.md` |
| 10 | schema-resource publication diffs every declaration | ORPHAN → #38 | the whole-tree digest was fixed (`ea9a4e3e9`); the full declaration diff was not |
| Q1 | retirement never retracts the ident | LANDED | `1819cdcd3`, `3f273fe7c`, `d58e000fe` (`:db.purge/attribute`) |

## 3. Slow-assumptions audit, tests (10 rows)

| row | finding | status | evidence |
|---|---|---|---|
| 1 | focused test JVM preamble ~38 s | IN FLIGHT #2 | realities-commit-5; `bin/test-fast` deleted, uncommitted |
| 2 | scratch boot as landing proof | LANDED (ruling) | `b81104985` |
| 3 | `bin/_test-slot` two slots | IN FLIGHT #2 | deletion uncommitted (`D bin/_test-slot`) |
| 4 | published base 136–141 s, 15.9 GB | IN FLIGHT #2; sweep SCHEDULED #22 | issue `prepare-head-base-reads-the-live-checkouts-resources.md` |
| 5 | HEAD-load check without the AOT classpath (19.2 → 5.3 s) | ORPHAN → #34 | flow-quick-wins still paid 14–19 s per load |
| 6 | lane probe JVMs without the AOT classpath | ORPHAN → #34 | same |
| 7 | full gate workers | IN FLIGHT #2 | |
| 8 | per-test fixture and arming | IN FLIGHT #2 | folded into row 1 |
| 9 | kondo | KEEP | |
| 10 | RESET NEEDED of default | LANDED | `3f273fe7c`/`8ce91fbaa` (in place), `76b42f90f` (fresh branch) |
| oos | default boots without the AOT cache | LANDED | `9744c970d` |
| oos | `base-ctx` 0.45–1.2 s unmemoized | IN FLIGHT #12 | sci-program-revisions |

## 4. Slow-assumptions audit, runtime (11 rows)

| row | finding | status | evidence |
|---|---|---|---|
| 1 | RESET ritual; stricter-than-Datahike refusals | LANDED | `3f273fe7c` accretive rows (noHistory drop, unique switch) |
| 2 | SCI re-acquired per commit (1.2 s, 3.6 GB) | IN FLIGHT #12 | `sci/eval.clj` uncommitted |
| 3 | read_only MCP writes a tx per windowed result | IN FLIGHT #19 | mcp-and-stop; ruled blob-only |
| 4 | core-declaration reload of 374 ns | LANDED | `2bd568c08`, `da703089b`, `af5eea72f`; residue #17 |
| 5 | page GET re-proves reads; debug page 44 s | IN FLIGHT / SCHEDULED | page-key lane (#24c(3)); #18 waiting |
| 6 | leaf publication whole-repo work | LANDED | `ea9a4e3e9`, `a6fd07c8e` |
| 7 | projection per call | LANDED / SCHEDULED | `9b8c5b405`; transport #25 |
| 8 | kondo cache walk misses changed content | LANDED | `ea9a4e3e9`; issue closed `233615946` |
| 9a | GC never runs on default; no `bin/seon` collect | SCHEDULED #21 | waiting on #1 |
| 9b | Datahike leaf rewrite ~1 MB per 3-datom tx | PLAN-ONLY → #39 | A2 1.3c |
| 10 | unexplained "unchanged alignment" 505 → 4,093 ms | ORPHAN → #49 | recorded anomaly, not re-read |
| 11 | `seon.db/q` does not reuse Datahike's result cache (17 ms vs 0.037 ms) | ORPHAN → #36 | "A2 c1 owns", but no A2 or fix-schedule row names it |

## 5. Swallowed-errors census (417 sites: 181 A / 180 B / 56 C)

| item | status | evidence |
|---|---|---|
| F0 whole cause chain | LANDED | `ed2e1a6b6`; hook `a11f6271d`; `seon.ai/cause-chain` caller `4bc446114` |
| R-DIAG (9) | LANDED | F0 turns them into B (lane-error-cause-chain :102) |
| error-owner swallows `error.clj:762`, `:1061` | LANDED | `ed2e1a6b6` |
| `db.clj` A rows (`:2012` health-on-throw, 8 R-HELPER, `:2794`, `:3865`) | LANDED | `be713ae30` |
| R-PRED (29 C) | LANDED, with leftovers | `4997f3623`, `ab389420b`, `72fa85fc3`, `ea5fde011`; leftovers `db.clj:3752`, `test/accretion.clj:39,:51`, `turn.clj:3092` were sent to their holders (#10) |
| R-HELPER (36), R-MSG (35), R-LOG (15), R-DISCARD (78), R-OFFER/CAUSE/EXDATA/TIMEOUT (12) | SCHEDULED #10 | waiting on the one route (#0) |
| R-UNKNOWN-STR (23 A) | SCHEDULED #0 | not named in #10; final design step 4 converts the survivors |
| B4 runner rows (21 A) | IN FLIGHT #2 | deleted with the commit-5 machinery |
| first-fix sites `boot.clj:443`, `:307`, `repl.clj:165,:461`, `source.clj:269,:280`, `wake.clj:388,:546,:560`, `turn.clj:5393,:5416`, `web.clj:2868` | SCHEDULED #10 | all are R-HELPER/R-DISCARD/R-OFFER sites |
| `db.clj:1145` replay-arm `(catch Throwable _ false)` (invalidation census) | IN FLIGHT | the working-tree `read-evidence-current?` has no catch (writer-cost, uncommitted) |

## 6. Invalidation census + Astra review

| item | status | evidence |
|---|---|---|
| A1 SCI keyed by commit | IN FLIGHT #12 | |
| A2 page key holds `:seon.db/db` | IN FLIGHT | page-key lane (#24c(3)) |
| A3 revision compare after the history scan | IN FLIGHT | uncommitted `db.clj` "Cheapest proof first" (writer-cost) |
| A4 wildcard pulls → `:all` | SCHEDULED #24c(5) | keep `[*]`, measure first (review finding 4) |
| A5 call preparation on basis-t (160 ms; 1.5 s per branch) | SCHEDULED #24c(3) | "call-prep key"; `call_preparation.clj` is free but no lane holds it |
| A6/A9 `program-digest` since-diff (0.3–0.4 s per test request) | ORPHAN → #35 | `test/runner.clj:922` |
| A7 cross-branch/as-of projection miss | IN FLIGHT #24j | writer-cost |
| A8, A10–A13 | KEEP | |
| A14–A16 | LANDED / cited | A14/A15 `ea9a4e3e9`; A16 `7a9d33da4` |
| W1 MCP artifact rows | IN FLIGHT #19 | |
| W2 receipt start | KEEP | harmless under revision keys |
| W3 / C1 receipts assert `:seon.fn/calls` | IN FLIGHT #24c(1) | receipts-no-program-edges lane |
| W4 acquisition refusals, one tx each | IN FLIGHT #17a → #12 | |
| W5 page GET writes an agent row | ORPHAN → #48 | `web.clj:3119` `ensure-namespace-owner!`; violates "read paths never write" (`cd95c73f5`) |
| W6–W9 | KEEP | |
| review 1 dependency-capture blind spots (Datahike) | SCHEDULED #24c(2) | no lane; Datahike fork |
| review 2 digests are not complete keys | SCHEDULED #24c(4) | |
| review 3 stop receipt edges | IN FLIGHT #24c(1) | |
| review 4 wildcard narrowing | SCHEDULED #24c(5) | |
| review 5 one comparator ≠ one cache | SCHEDULED #24c(3)/(4); #36 | |

## 7. Error-route set (four inspiration notes, draft, final design)

The inspiration notes are inputs. Their recommendations were folded into the final
design (`8e21d4bb5`), which is itself schedule #0. The table lists findings with a Seon
owner.

| item | status | evidence |
|---|---|---|
| final design: Flow hook, `seon.fault/fault!`, acknowledged batching, D13 accretion, panic surfacing, clj-kondo `try` hook | SCHEDULED #0 | waits on the owner's option choice (three priced options; no ruling in the plan) and on the cluster.clj/flow.clj holders |
| draft R3 panic handler only prints; R4/R6 twelve dial readers; R5 db transact stores nothing; R7 falls through to nobody | SCHEDULED #0 | |
| message identity `[signature recipient]` fails to re-wake on reopening (`error.clj:1355`) | SCHEDULED #0 | final design "Acknowledgement" |
| P4: ~1.1 s of `commit-call` inside the fault transaction (26 ms outside); the design says fix it FIRST | ORPHAN → #32 | may share #24j's cause (verify) |
| P3: Flow faults carry no `:seon.error/chain` | SCHEDULED #0 | design step 1, "verify F0 evidence round-trip" |
| `exception-summary` drops `ex-data`; the MCP NPE face has no Seon frame | ORPHAN → #31 | draft out-of-scope; flow-quick-wins "ugly output" |
| datahike `raise` without a cause; superv `println`; superv's `{}` ex-data wrap | SCHEDULED #24h | |
| db-down path: no journal/replay | LANDED (ruling) | `f0ec86654`, `b86a4b706` |

## 8. Flow usage audit (D1–D10, 47 sites, 14 steps) + flow-quick-wins landing

| item | status | evidence |
|---|---|---|
| D1 / step 1 launcher error channel | LANDED | `c2140df4e` |
| step 7 schedule timer | LANDED | `25de4dc81` |
| D2–D6, D8–D10 | ISSUE-ONLY (notes filed) | `88b52ae8a`, nine notes; D7 is `orderly-stop-completion-joins-have-no-bound.md` |
| steps 2–4 (var-process wrapper, one drain, `offer!`/`println` → `fault!`) | SCHEDULED #0 | |
| step 5 rows 26–27 (bound the stop waits) | ISSUE-ONLY → #28 | exact change in `flow-quick-wins-2026-09-23.md`; row 25 is in mcp-and-stop's uncommitted `agent.clj` hunk |
| step 14 rows 19–20 (SSE join, worker shutdown) | ISSUE-ONLY → #33 | exact change in the landing; `web.clj` is held by page-key |
| steps 6, 8–13 (router proc, armer, backstop proc, `acquire!` lock, submit paths, model call) | IN FLIGHT | flow-fact-pack lane (fact pack → Fable PRD); issue notes exist |
| §7 stale seon-flow-architecture skill claims | ORPHAN → #27 | `wakes-and-faults.md:19,:137`, `workloads-and-scheduling.md:67,:97` still stale |
| stop-order gap: a launcher fault between `disarm-agents!` and `stop-work-launcher!` hits an unread channel | ORPHAN → #29 | flow-quick-wins proof boundary |
| a launcher fault's evidence measures the whole environment (201 ms, 8.4 MB) | ORPHAN → #30 | flow-quick-wins |
| `commit-fault!` outer catch `(str fault)` swallow (megabytes) | SCHEDULED #10/#0; size aspect → #30 | census R-HELPER `commit-fault!` last-resort shape |
| MCP eval_clj NPE summary with no Seon frame | ORPHAN → #31 | |
| `seon.schedule-test` handler-errors test 6.1–6.9 s over its 5 s bound (old code) | ORPHAN → #50 | relayed only |

## 9. Astra reviews

| review · finding | status | evidence |
|---|---|---|
| oversight 1–3 | LANDED | `b44585c0b` |
| three-way 1–6 | LANDED | `d8734f1e7`; landing `3fbf9dd48` |
| projection-writer P1 as-of future; P1 writer stamp | LANDED | `ca9c40639` |
| projection-writer P2-3 hand dependency list; P2-5 contracts | LANDED | `ca9c40639` (`projection-ranges`) |
| projection-writer P2-4 cold metadata fallback | SCHEDULED #11 | `construction-projection` remains |
| projection-writer P2-6 whole-population per ordered write | ISSUE-ONLY | `class-local-updates-recompute-global-projections.md`; ruled unmemoized |
| projection-writer: no armed pass, no reverse-mutation run | ORPHAN → #51 | landing "Proof and limits" |
| publication-lock P1-1, P1-3, P1-4, P2-5, P2-7 | LANDED | `874918765` (fork `fbd1ad2d`) |
| publication-lock P1-2 | LANDED | `40c9ebf5e`, `3c55bb0f6`, `4fd4a4128` |
| publication-lock P2-6 adoption contest not covered; export copies the live store (detected, not prevented) | ORPHAN → #46 | relayed only in the landing |
| validator §A 1–2 | LANDED | `bb3a0c6c4`; regression `be713ae30` |
| deletion §B P1-1, P1-2, P2-3 | LANDED | `6699de97a`; armed run pending #2 (→ #51) |
| oversight §C 1–2 + catch audit | LANDED | `9c0ecae86`; producer-side pid grammar left → #47 |
| realities commit-4 P1 ×4 | LANDED | `57f02fd5b` |
| realities commit-4 P2-5 host custody, P2-6 resolution catches, P2-7 contracts | IN FLIGHT #8 | realities-commit-5 holds `test.clj`; the chain was kept in `bounded-result` (`57f02fd5b`) |
| invalidation 1–5 | see §6 | |

## 10. Pending audits

- `regression-bisect-2026-09-23.md`: **absent** at trace time (schedule #24i, writer-cost bisect).
- `flow-fact-pack-and-triage-2026-09-23.md`: **absent** (ledger: flow-fact-pack lane running).

**Schedule-state discrepancies for the orchestrator (rows not edited):**

- #14 says "RUNNING resume-in-seconds", but the ledger says that lane released `agent.clj`. Its residue (first-party compile 7.2 s, 26-ns arm 1.2 s, reconcile/plan 634 ms, first projection 516 ms) has no running lane.
- #15 says WAITING, but `76b42f90f` landed reset = fresh branch (14.8 s against 4 s).
- #23 says RUNNING oversight, but it landed `9c0ecae86`.
- #24 says "folded into rows above", but #46/#47/#51 were not folded.

## Summary

### Counts per status per audit

| audit | LANDED | IN FLIGHT | SCHEDULED | PLAN-ONLY | ISSUE-ONLY | DEFERRED | REJECTED | KEEP | ORPHAN |
|---|---|---|---|---|---|---|---|---|---|
| 1 dependency (26 rows) | 3 | 2 | 3 | 8 | 0 | 1 | 1 | 8 | 0 |
| 2 from-zero boot (12) | 6 | 0 | 2 | 0 | 2 | 0 | 0 | 1 | 1 |
| 3 tests (12) | 3 | 6 | 0 | 0 | 0 | 0 | 0 | 1 | 2 |
| 4 runtime (12) | 5 | 3 | 1 | 1 | 0 | 0 | 0 | 0 | 2 |
| 5 swallowed census (10 items) | 5 | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| 6 invalidation + review (20 rows) | 1 | 8 | 6 | 0 | 0 | 0 | 0 | 3 | 2 |
| 7 error-route (8) | 1 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 2 |
| 8 flow + quick wins (13) | 2 | 1 | 2 | 0 | 3 | 0 | 0 | 0 | 5 |
| 9 reviews (15 rows) | 9 | 1 | 1 | 0 | 1 | 0 | 0 | 0 | 3 |

**ORPHAN: 17 findings, in 15 appended rows.** The MCP failure face is one row, #31, for
two findings. **PLAN-ONLY: 9 findings, in 7 rows** (#39–#45). **ISSUE-ONLY with a
concrete fix: 3 findings, in 3 rows** (#28, #33, #37). That makes 25 appended rows,
#27–#51.

### Rows added to the fix schedule (all marked "added by audit-tracker")

| # | P | item | class |
|---|---|---|---|
| 27 | P1 | stale seon-flow-architecture skill claims | ORPHAN |
| 28 | P1 | flow step 5: bound the stop waits (rows 26–27) | ISSUE-ONLY (D7) |
| 29 | P1 | launcher fault in the stop gap hits an unread channel | ORPHAN |
| 30 | P2 | Flow fault evidence measures the whole environment; `(str fault)` fallback | ORPHAN |
| 31 | P2 | MCP failure face drops ex-data / no Seon frame | ORPHAN |
| 32 | P2 | 1.1 s `commit-call` inside the fault tx (gates #0) | ORPHAN |
| 33 | P2 | flow step 14: SSE feed join, worker shutdown | ISSUE-ONLY (D8) |
| 34 | P2 | lane HEAD-load/probe JVMs on the AOT dependency cache | ORPHAN |
| 35 | P2 | `program-digest` since-diff per test request | ORPHAN |
| 36 | P2 | `seon.db/q` bypasses Datahike's result cache | ORPHAN |
| 37 | P2 | `owners-of` probes every component attribute (~15 s from zero) | ISSUE-ONLY |
| 38 | P2 | schema-resource publication diffs every declaration | ORPHAN |
| 39 | P2 | Datahike leaf rewrite ~1 MB per small tx | PLAN-ONLY (A2 1.3c) |
| 40 | P3 | dependency row 6 `read-basis-transaction` | PLAN-ONLY |
| 41 | P3 | dependency rows 7+8 packaged-population cache, env delay (rule the flow-audit KEEP conflict first) | PLAN-ONLY |
| 42 | P3 | dependency row 15 render-walk entity memo | PLAN-ONLY |
| 43 | P3 | dependency rows 13+14 (A2 f8/c12/c9) | PLAN-ONLY |
| 44 | P3 | dependency row 18 gate digest docstring | PLAN-ONLY |
| 45 | P3 | dependency row 19 schema-shape family (1.4b) | PLAN-ONLY |
| 46 | P3 | publication-lock residue: adoption contest test; export not commit-scoped | ORPHAN |
| 47 | P3 | oversight residue: proc-id grammar at the Flow producer | ORPHAN |
| 48 | P3 | page GET writes an agent row (W5) | ORPHAN |
| 49 | P3 | unexplained unchanged-alignment 505 → 4,093 ms | ORPHAN |
| 50 | P3 | schedule-test handler test over its 5 s bound | ORPHAN |
| 51 | P3 | verification debt: follow-ups landed without an armed or reverse-mutation run | ORPHAN |

### Findings relayed only in messages or landing notes (never a row or an issue before this trace)

- flow-quick-wins: the stop-order gap, the 8.4 MB launcher-fault evidence, the `(str fault)` fallback, the MCP NPE face, and the schedule-test bound overrun (#29, #30, #31, #50);
- publication-lock landing: the adoption contest regression and the live-store export (#46);
- oversight landing: the producer-side pid grammar (#47);
- validator-single-pass landing: `owners-of` 6b (#37);
- projection-writer and deletion-caller-edge landings: regressions never run armed (#51);
- one-error-route draft: P4 (#32) and `exception-summary` (#31);
- the dependency audit file is uncommitted (above; not a schedule row).

### Top 10 open items by priority

1. **#0 P0** one error route. Waiting on the owner's option choice (no ruling recorded) and the flow.clj/cluster.clj holders. #32 (1.1 s commit-call) gates it.
2. **#24i/#24j P0** 16.5 s config transaction regression and the cross-connection projection miss (writer-cost). The bisect note is not yet written.
3. **#2 P0** 1.3d commit 5 (realities-commit-5). It unblocks every armed verification (#51) and #22, #35.
4. **#1 P0** rejoin of default. Blocked by the stop hang (mcp-and-stop); #28 is the same class.
5. **#27 P1** stale flow skill (a high-priority defect by AGENTS.md).
6. **#28 P1** bound the stop waits (exact change ready; `cluster.clj` is free now).
7. **#29 P1** launcher fault lost in the stop gap.
8. **#10 P1** census conversions (R-HELPER/R-MSG/R-LOG/R-DISCARD) after #0.
9. **#11 P1** the last cold construction-projection read.
10. **#12 P2** SCI program revisions (1.2 s, 3.6 GB per data-only commit), with #24c(1) receipts.

## Timings

All reads were shell `cat`/`grep`/`git log` calls, each under one second. The whole
trace took about 6 minutes of wall time before this note was written. No JVM, REPL or
test ran. None of the trace's work is proportional to the program.
