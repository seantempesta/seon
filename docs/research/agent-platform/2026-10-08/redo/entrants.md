---
type: research
status: approved by the owner 2026-10-08, with the transaction census folded in (§3, §5)
date: 2026-10-08
lane: redo-entrants
---

# Every entrant through one pass (redo of S5-07 and S6-01/02)

What exists: one fold, `seon.batch/evaluate-batch`, already runs JVM mode end to end, with its RECORD stage
`seon.turn.batch/settle` (`turn/batch.clj:729`, reached from `mcp.clj:265`). Model and MCP SCI passes run the same
fold with RECORD removed (`(dissoc :seon.batch/settle)`, `turn.clj:5021`) and record through a second owner,
`settle-batch!` (`turn.clj:3554`), with `settle-evaluated!`, `settle-batch-refusal!` and `with-submission`. A test body
does not use the fold at all: `bounded-result` (`test.clj:216`) runs it, `runner/commit-results!` records it, and
an effect-reaching body gets a throwaway agent and an open turn (`attempt-request-context`, `test.clj:184`).
`turn.batch/test-request` (`:970`) and `world/test-agent-world` (`world.clj:70`) exist but have no caller in `src`.
Principal approach: one fold, one RECORD owner (`settle`); an entrant is a request map of stage values (read, eval,
rows, world), never a second settlement. Authority: `review/owner-rulings.md`, CLAUDE.md; designs agreed with
`pass-and-recording.md` (RP), `task-finish.md` (F) and `merge-acceptance.md` (MA). HEAD `053fa1cee`; default runs
`4b4caf142`.

## 1. Measured today (MCP, `seon.pass-probe`, default, agents `s0-*`, `redo-entrants-chk`; all-thread allocation)

| Pass | Wall | Allocated | Attempts / durable commits |
|---|---|---|---|
| MCP SCI ordinary | 47-69 ms | 55-106 MB | 1 / 1 |
| MCP SCI `defn` / the pass after it | 194 / 91 ms | 237 / 118 MB | 1 / 1 |
| MCP JVM ordinary | 31-74 ms | 34-88 MB | 1 / 1 |
| model pass (fixture provider, provider 0.5-10 ms) | 507-886 ms | **720-1,101 MB** | 7 / 7 |
| executed one-test check by identity, warm | 506-1,040 ms | **930-1,682 MB** | 4 / 3 on the agent branch |
| the same check, member reused | 86-110 ms | 115-151 MB | 1 / 1 |
| first executed check in the process | 1,886 ms | **3,216 MB** | 8; 5 on the checkpoint branch |
| fold alone, one test entry, no show or settle | 0.30 ms | 0.36 MB | 0 |

Executed check stages: `seon.test/run` 333-859 ms; `select` 12-605 ms even with `:identities` only;
`clean-request-handle` 152-159 ms; `member-result` 19-28 ms (body 12-20); `commit-results!` 28-35 ms. The four
attempts are the check pass's own recording, `admit-run`, `commit-results!` and `record-exit!`. The gigabyte per
check (async-mixed 482 MB, ForkJoinPool worker 521 MB in one run) is filed as
`seon.test/a-one-test-proof-check-allocates-a-gigabyte`. The attempt context's agent-plus-turn write is
"350-440 ms on a member branch" (`test.clj:196`, not re-measured). Health was `:faulted` before the probes (existing
faults).

## 2. The entrant table (target)

Every row: read → eval → print in the fold; RECORD by `settle` in one transaction through the world's writer on
`world/settlement-connection`; the value is returned whatever RECORD answers (RP-7's second attempt and diagnostic).

| Entrant | Read stage | Eval | Custody: reads and writes | Rows land (one tx) | Origin | Crash before RECORD |
|---|---|---|---|---|---|---|
| model, REPL | `:reply` text, forgiveness then cursor | SCI | the agent's branch, or S while it holds a merge task (MA-5b) | agent branch: call row with attempt facts (usage, latency, block tokens), delivered items, answered-t; evaluations; final program; findings | `:model` | reply lost; the wake stays unanswered and the model is asked again; committed agent writes and effect receipts stay |
| model, DATA | `:data` map, element spans at the cursor (RP-16) | SCI | as above | as above | `:model` | as above |
| MCP SCI | `:code` text | SCI, serialized by the agent's proc | as model | agent branch: call row, evaluations, program | `:outside` | caller gets the transport failure; nothing recorded; durable effects stay; a resend repeats them |
| MCP JVM | `:code`, host `read+string` (`jvm-read-next`) | host, owned per invocation until exit, not serialized | the agent's branch connection for elided `seon.db` arities | agent branch: call row, evaluations; no analysis, no program facts | `:outside` | as MCP SCI; a live host call keeps ownership until exit |
| test member | `:body`: one entry, form = the Var resolved before seeding, source = its stored source | `bounded-result` (SCI `run-test` or host `run-var!`) | the member branch, an in-memory overlay off the request world (`test.clj:1878-1881`), released after actual exit | **none of its own**: its value (the captured result, printed by the value printer) joins the request's one transaction | `:test` | the overlay is gone; nothing was written; the request is re-derived (below) |
| test request | not a pass: `seon.test/run` | members, in order | reads the execution's captured commit; writes the recording connection | recording connection: run, members, results and exit in ONE transaction (EN-6) | — | nothing written; the proof proc re-signals at start (F4); a `check` caller re-asks |

Who makes test requests, and where they record:

| Requester | Execution and recording | Notes |
|---|---|---|
| proof proc (F4) | the agent's acquired context; its branch connection | off the pass; one in flight; a pass landing mid-request leaves a result whose tested digest no longer holds, so the dirty flag re-runs it |
| `my.test/check` in a form | the agent's context; its branch connection | a durable effect of that form, committed before the pass's RECORD; reads the committed program (question 3) |
| root's merge gate (`cluster.clj:2621` `reaching-run`) | S's execution; S's connection | proofs plus root's regressions on the combined program; the rows are test data, not program attributes, so they never trip MA-9's `program-written-since?` (verify in MA-9) |
| `review!` proc (MA-12) | as the merge gate, inside root's pass | that pass is `:outside`: a system evaluation, not a model call |

## 3. What changes

**One RECORD owner.** `settle` survives (RP-7 gives it findings and the second attempt). Its request names the rows
stage, `:seon.turn.batch/record-call` (today `seon.turn/record-evaluated-call`, `turn.clj:1328`), and the call row's
extra facts as one value, `:seon.turn.batch/call-facts` (provider attempt and usage, delivered ids, answered-t),
supplied by the model entrant and absent elsewhere. `evaluate-sources` keeps `settle` instead of removing it, so model
and MCP SCI record exactly as JVM does. Deleted with their last callers: `settle-batch!`, `settle-evaluated!`,
`settle-batch-refusal!`, `with-submission`, `with-fault-windows` (after S5-05), `receipt-settle-batch-tx`.

**The test member is a pass with no rows.** `member-result` builds the member's request with `turn.batch/test-request`:
read stage `:body`, eval = the `bounded-result` call, show = the value printer over the captured result, no settle.
Its pass id is minted before eval (`seon.id/id`); its one evaluation has ordinal 0. Failure values reach the report
through the printer, replacing `render.value/core-text` in `member-result`. `test-agent-world` and its settlement
connection are deleted: nothing records on another branch.

**Effect provenance on the transaction (S6-01; I-Q10 O1 answered; revised by the census fold-in).** The receipt keeps
`:seon.effect/form-ordinal`; its eval request is the `:seon.db.transaction/eval-request` of the transaction that
opened it (`pass-and-recording.md` §2.1), passed explicitly in `open-call`'s request map from `*request-context*`, so no
receipt attribute repeats it. `:seon.effect/run`, the `:seon.effect/eval` ref and `evaluation-eid` (`effect.clj:210`)
are deleted, and the planned `:seon.effect/pass` is never added. A reader holding a receipt reaches its eval request
entity → transaction (EAVT, cheap); boot recovery reads the open receipts (no result, no interruption: few) and
interrupts those whose eval request has no call row. The effect id stays `digest(eval request, ordinal,
effect-ordinal)` (`effect.clj:849`). `*request-context*` carries pass id, ordinal, connection, ctx, counter and cluster, never a row:
the SCI binder (`sci/eval.clj:4180`) passes the pass id; for a test member the fold's eval stage binds the member's.

**No attempt turn (S6-02b).** `attempt-writers`, `attempt-request-context`, the `:seon.test/effect-attempt?` flag
and the per-request `tests-reaching … 'seon.effect/request*` walk (`test.clj:2428`) are deleted. A test body's
effect writes its receipt on the member branch. A background request in a test (`:seon.effect/background?`) refuses
before its receipt opens, `:seon.error/type :seon.effect/background-in-test`: its `:seon.effect/notify` would name an
agent the clean world does not hold, and the branch is released at exit.

**The request in one transaction (EN-6, = F3).** The common path, every body exits inside the bound: after the last
member, ONE `[:db.fn/call record-request …]` writes the run, its members, their results and exits. Writer admission
stays (OD-Q5), so the recording branch sees two commits per executed request. The run's `:seon.test.run/at` and
`selection-tx` and each member's `completed-tx` and `terminated-tx` are not written: the record transaction's
`:db/txInstant` and the transaction itself answer them (census fold-in). Counted honestly, a cold request is today 8
commits on three branches (census §2): 3 on the recording branch, 4 on the checkpoint branch from
`prepare-checkpoint!` (`test.clj:1790`), and the member overlay, which is in memory and writes nothing to the store.
The second census measured a direct one-member request already at 2 durable commits (`tx-census-astra.md`, 300-382
ms), so EN-6's gain is one record transaction for any number of members and no stored instants, not a lower count for
one member. After EN-6 and TX-5 a cold request is 3 commits (admission, record, one checkpoint commit) and a warm one 2,
plus the enclosing eval request's own recording when it runs inside one (`my.test/check`). The writer checks
the reach digests against its head, as admission does today. A body that outlives the bound gets its "held" fact
written at the bound, and its exit fact at actual exit (`record-exit!`). That is the rare path, and it keeps
ownership until exit. Overlap of live work is guarded by the runner's process roster of live bodies, keyed by
`[recording-branch test-sym]` and shown in status; nothing else held a pre-execution admission (question 2).

**The clean world, cached (EN-7, = F2).** The overlay joins the checkpoint key
`[base cluster effective process overlay-digest]` (task `seon.test/a-changed-check-builds-a-world-per-request`). A
request on an unchanged program is one `branch!` plus a base-context hit. A changed program builds its world in one
transaction.

## 4. Cost per operation (targets; each slice reports its probe row)

| Operation | Today | Target |
|---|---|---|
| fold per member (read, eval stage, print) | 0.30 ms / 0.36 MB without print | ≤ 2 ms / ≤ 2 MB with print |
| member body acquire + run + release | 19-28 ms | unchanged (agent work) |
| clean world, unchanged program | 152-159 ms | ≤ 15 ms (branch! + cache hit) |
| request recording | 3 tx, ~60-100 ms | admission + 1 record tx on the recording branch, plus 1 checkpoint commit when the clean world is built (census: cold 8 commits, 545 KB, 1,592 ms) |
| one executed proof, warm | 506-1,040 ms / ~1 GB | < 150 ms / < 20 MB (budget T) |
| model pass | 7 commits, 507-886 ms, 0.7-1.1 GB | 1 commit, < 200 ms, 20-40 MB (C0) |
| MCP SCI / JVM pass | 1 commit, 31-69 ms | unchanged; no regression over 20% or 50 ms |

## 5. Slices (~15 minutes each; proofs assert data through `seon.mcp/call`)

| Id (replaces) | Change | Files | Contract | Proof | Deps |
|---|---|---|---|---|---|
| EN-0 | `seon.pass-probe/run-proof`: per repetition, redefine then check by identity, so every row executes a member | `test/seon/pass_probe.clj` | `[:=> [:cat :string :qualified-symbol pos-int?] [:vector :map]]` | `seon.pass-probe-test` row: every record `::member-executed? true` | — |
| EN-1 (S6-01) | the receipt's eval request is its opening transaction's `:seon.db.transaction/eval-request` (passed by `open-call`'s caller); keep `:seon.effect/form-ordinal`; delete `:seon.effect/run`, `:seon.effect/eval`, `evaluation-eid`; readers `effect.clj:58-115,385-408` and the receipt fixtures (I-Q10 §4); `turn.clj:1897-1905` goes with S4-06's refresh deletion | `src/seon/effect.clj`, `seon.effect.edn`, `test/seon/effect_test.clj`, fixtures | open-request loses `:seon.effect/run` | `seon.effect-test/a-receipt-names-its-eval-request-on-its-transaction`: an MCP eval request runs a stub capability; the receipt has no `:seon.effect/run`, and its opening transaction's `eval-request` is the call row's `:seon.turn/id` | TX-0 |
| EN-2 (S5-07a) | `settle` takes `:seon.turn.batch/call-facts`; `record-evaluated-call` merges them into the call row | `src/seon/turn/batch.clj`, `seon.batch.edn`, test | `settle [request batch] → :seon.batch/commit` unchanged | `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure` JVM row: a refusing writer as the world's `:seon.db/writer`; the value printed; diagnostic `:seon.error/type` present | RP-7 |
| EN-3 (S5-06, ready) | JVM mode holds no SCI acquisition | as ledger | as ledger | as ledger | — |
| EN-4 (S6-02a) | member through the fold: `test-request` `:body` stage, show by the printer, no settle; `test-agent-world` deleted | `src/seon/test.clj`, `src/seon/turn/batch.clj` (`test-request` only), `src/seon/turn/world.clj`, `seon.batch.edn`, `test/seon/test_test.clj` | `member-result` unchanged; `test-request [{:seon.batch/body fn :seon.batch/world w}] → :seon.batch/request` | `seon.test-test/a-member-body-runs-as-one-pass`: an MCP check of a red proof; the recorded report's actual is the printer's value data and the request recorded 0 rows on any member branch | EN-1 |
| EN-5 (S6-02b) | delete attempt writers, the flag and the effect-reach walk; fold binds the member's request context; background refusal | `src/seon/test.clj`, `src/seon/effect.clj`, schemas, `test/seon/test/effect_attempt_test.clj` | `request*` error union gains `:seon.effect/background-in-test` | `seon.test-test/a-test-effect-needs-no-attempt-agent-or-turn` (body pulls its receipt: pass = its member pass, no `attempt-` agent); `…/a-background-effect-in-a-test-is-refused-by-name` | EN-4 |
| EN-6 (F3) | request recorded in one transaction after writer admission (two commits on the recording branch); held fact only past the bound; process roster of live bodies; run `at`/`selection-tx` and member `completed-tx`/`terminated-tx` not written (the transaction answers them) | `src/seon/test.clj`, `src/seon/test/runner.clj`, `seon.test.run.edn`, `seon.test.member.edn` | `record-request [db completion] → tx-data` | `seon.test-test/an-executed-proof-request-commits-twice`: transactions on the recording branch carrying the run id = 2 (admission, record); the checkpoint branch's commits counted apart (TX-5); T row | EN-4, F1, TX-0 |
| EN-7 (F2) | overlay digest in the checkpoint key | `src/seon/test.clj` | unchanged | existing `seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data`; T row: a second proof run on an unchanged program ≤ 15 ms in `clean-request-handle` | F1 |
| EN-8 (S5-07c, d) | `evaluate-sources` keeps `settle`; model passes `call-facts`; delete `settle-batch!`, `settle-evaluated!`, `settle-batch-refusal!`, `with-submission` | `src/seon/turn.clj`, `test/seon/cluster/turn_test.clj` | `evaluate-sources` answers the fold's batch with `:seon.batch/commit` | SCI and model rows of the EN-2 table; `seon.cluster.turn-test/a-model-reply-pass-commits-its-evaluations-once` (marked) | EN-2, RP-10, RP-11, S5-05 |
| EN-9 (S5-07f) | the test row of the table (a refused request recording keeps every member value); a search shows no old owner left | `test/seon/mcp_entrance_test.clj` | — | the EN-2 table, all four rows | EN-6, EN-8 |

**Order.** EN-0, EN-1, EN-3 now (groups T, E, E). Then EN-4 → EN-5. F1 → EN-6 and EN-7. RP-7 → EN-2 → EN-8 → EN-9.
Then **RP-14**, which now needs EN-1 and EN-5 (replacing "S6-01, S6-02b") plus EN-8. It shrinks to deleting
`open-turn`'s commit and the `*open*`/`*receipt*` stamps, because the attempt facts already ride `call-facts`. RP-15a
follows, and **C0** becomes reachable. EN-4 touches `turn/batch.clj` (group B) at `test-request` only, so it runs
between RP rows, not beside them. F4 needs EN-6: the proof proc's request should be one transaction before it runs
after every pass.

## 6. Owner questions (recommendation first)

1. **Where do a member's pass rows go?** Q11 said "on the member's own branch". *Recommend:* none. The branch is an
   in-memory overlay released at exit, so those rows are never read, and each would cost a validated commit. The
   member's value joins the request's one transaction.
2. **Does a request still need a pre-execution admission transaction?** *Recommend:* no, on the common path. The
   overlap it prevents is live bodies, which only this process holds. They go in the runner's roster, and the held
   fact is written only past the bound. Alternative: keep admission, two transactions per request (about +30-60 ms).
3. **`my.test/check` in the same pass as the change.** It reads the committed program, so it tests the previous pass
   (task-finish §0.2). *Recommend:* leave it so. Help teaches that proofs run by themselves after the pass. The
   proc, running after RECORD, is the path that tests the change. Making a check build its world from the live,
   unrecorded overlay is a second world source.
4. **Effect attribute name.** *Recommend* `:seon.effect/pass`, a new name for changed semantics, not a retyped
   `:seon.effect/run`.
5. **A real external effect in a test** (a shell run) keeps its receipt only on the released member branch.
   *Recommend:* accept it. Its result is in the body's value, and a test needing durable effect evidence asserts it
   inside the body.

## Limits

- Design only, plus one task record. All numbers are single runs on the shared default JVM with all-thread
  allocation, at `4b4caf142` (older than PB-01's installed state on HEAD).
- No effect-reaching test was run, so the attempt-context cost is the code comment's figure.
- The probes defined one host Var, `user/sci-recs`, which I unmapped at once. I retired both probe branches
  (`:agent-369cc7f29450`, `:agent-8790044e199e`); the probe's own `s0-*` agents retired their hosts.
