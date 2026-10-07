# RECORD stage: every write for one evaluation and one turn (lane r-record, 2026-10-07)

Default ran `722f110df` (pid 41301); HEAD `ead9d9a14`. Datahike pin `d9121bbbad` (the `reference-code/datahike`
checkout sits at `6f3b61f6fa`, the dh-fix work; line numbers below are the pin's, read with `git show`).
`bin/seon status`: default `:faulted` (open fault `seon.turn/show-row` → `shown-failure` nil, task `7f5dfb6ad7a2`;
`evaluate-batch` and `acquire-program!` over-bound). Heap 2.9 GB live.

## Method

- Every entrant was driven through its real entrance, `seon.mcp/call`, on a fresh `Thread` (no conveyed
  bindings), from one foreground JVM-mode `eval_clj` of agent `r-record-jvm`. Nesting the call inside the
  outer evaluation's thread is refused by the writer (the outer world's `*conn*` names a foreign branch), so
  the first JVM probe was discarded.
- Inside that same eval, a `datahike.api/listen` callback on the default connection and the agent's branch
  connection recorded each commit's datoms, attributes and `:seon.db.transaction/operation`. It was removed in
  a `finally` before the eval returned. No listener, poller or JVM outlived a call.
- Store bytes: files in `data/store` newer than the start mark (apparent bytes, LZ4), assigned to the commit
  whose callback came within 50 ms after the file's mtime. Totals are exact; the per-commit split is
  approximate when commits are under 50 ms apart.
- Time per stage: the delta of `seon.profile` cells (calls, total ns) for named armed functions.
- Allocation: `ThreadMXBean/getTotalThreadAllocatedBytes` delta. This is whole-JVM allocation, so it includes
  any concurrent work (other lanes were quiet; an idle window wrote 0 files). Per-thread numbers were not
  taken.
- Armed calls: my probe's own `seon.profile/counts` sweep is itself armed and added about 45k calls per
  snapshot. Its `:all-other` totals of 119-434k are therefore inflated. Excluding `seon.profile/*`, one SCI
  `(+ 1 2)` is about 11,000 armed calls end to end.
- Probe text: session scratchpad `r-record/probe2.clj`, `turn2.clj` (not committed; the forms are reproduced
  by the tables below).
- Disposable branches, all retired with `my.agent/retire-branch!` (each answered `:my.agent/retired? true`):
  - `r-record` `:agent-2e85b3ef04cb`;
  - `r-record-turn` `:agent-2e83b3882fc7`;
  - `r-record-jvm` `:agent-55fb434873c3`.

  Two DeepSeek calls were made, about 3.4k prompt tokens in total.

## 1. Per entrant: every transaction

### 1a. MCP `eval_clj` SCI, `(+ 1 2)`: 1 commit

| commit | op stamp | datoms | distinct attrs | files / bytes | ms |
|---|---|---|---|---|---|
| run open + evaluation + settlement | `seon.db/absent-call` (mislabel, task `seon.db/settlement-transactions-are-stamped-as-absent-call`) | 27 | 27 | 25-28 / 145-160 KB | transact! 59.5-82.6 |

- Attributes in that commit:
  - run (8): `seon.turn/{id agent opened-tx closed-tx reply reply-size starting-ns}`, `seon.turn.work/situation :call`;
  - evaluation (16): `seon.cluster.eval/{id run ordinal at source author ns code-evidence read-basis-transaction}`,
    `seon.eval/{shown duration-ms value-class elisions shown-tokens value-tokens}`, `seon.sci.eval/ending-ns`;
  - transaction (3): `db/txInstant`, `seon.db.transaction/{operation agent}`.
- Per-stage time (ms), total 205-358 ms wall (`seon.mcp/call`):

  | stage | ms |
  |---|---|
  | `seon.turn/turn` | 131 |
  | `fork-for-turn` | 6 |
  | `evaluate-sources` | 25 |
  | `derive-form` (inside `evaluate-sources`) | 20.4, with no definition in the form |
  | `settle-batch!` | 96.5 |
  | `fault-window` (inside `settle-batch!`) | 34.3 |
  | `db/transact!` (inside `settle-batch!`; includes `close-folded-call` 6.2 and the writer) | 59.5 |
  | `run-observation` (the reply re-reads the settled run) | 27.4 |
  | `significant-tx` | 0.0 |

- Allocation 159-232 MB (whole JVM).

### 1b. MCP SCI, contracted `defn` alone: 1 commit

| datoms | attrs | files / bytes | settle-batch! | transact! | install-evaluated-rows! (after commit) | wall | alloc |
|---|---|---|---|---|---|---|---|
| 164-183 (8 retracts) | 69-72 | 116-149 / 604-706 KB | 254.5 | 238.1 (`receipt-settle-batch-call` 50.9, `close-folded-call` 18.4, `program-facts-call` 0.8) | 104.1 | 344-536 ms | 712-733 MB |

- About 100 of the 164 datoms (61%) are `seon.profile.measurement/*` upserts: `at` 27, `calls` 27, `p99` 26,
  `p50` 7, `errors` 7, plus 2 new ids with `sym`/`digest`/`id`.
- The rest are 27 evaluation and run datoms and 36 program datoms: `seon.fn/*` 17, `seon.fn.arity/*` 6,
  `seon.fn.argument/*` 2, `seon.fn.binding/*` 3, `seon.program/{definition-digest analyzed-source-digest}`,
  `seon.schema.admission/source`, `seon.eval/renderer(-fn)`, and 6 `seon.db.vector/*` for the 2 `call-arities`
  and `calls` values.
- A first `(in-ns 'scratch.rrec) (defn f1 …) (f1 1)` cost 418 datoms (69 attrs), 148 files / 699 KB, 1,139 ms
  wall and 2.1 GB.

### 1c. MCP SCI form with the agent's own write: 2 commits

`(seon.db/transact! [{:seon.ns/name 'scratch.rrecw}]) (+ 1 1)`

| # | what | op | datoms | files / bytes |
|---|---|---|---|---|
| 1 | the agent's `transact!` | `seon.db/transact!` | 26 (1 is the agent's) | 23 / 106 KB |
| 2 | settlement | `absent-call` | 198 | 62 / 277 KB |

- Commit 1 also carries the run's open: `*open*` (db.clj:184) is prepended by `stamp-transaction`
  (db.clj:3609), bound at sci/eval.clj:3999-4000. It writes the run row, 2 evaluation intent rows and a
  `seon.db/receipt` stamp. The agent's tx report (its form's value) shows all 26 datoms. New task
  `seon.db/an-agent-transact-reports-the-runs-open-datoms` (`803a8155dcb1`).
- Commit 2 (the settlement) holds:
  - `seon.cluster.eval/executed` 55;
  - one read-evidence row: `seon.cluster.eval/read-evidence`, `datahike.read/{dependency-plan revision}`,
    `seon.db.vector/*`, `seon.db/source-argument-position`;
  - `seon.cluster.eval/repeated-calls`;
  - 112 measurement datoms (57%).
- Today an agent write already commits when its form runs. It is not deferred to the end, and the run's
  record splits across that write and the settlement.

### 1d. MCP `eval_clj` JVM mode, `(+ 1 2)`: 1 commit (the second writer)

| op | datoms | attrs | files / bytes | evaluate-batch | turn.batch/settle | transact! | run-observation |
|---|---|---|---|---|---|---|---|
| `seon.turn/record-evaluated-call` | 18 | 18 | 20-23 / 130-137 KB | 42.5 | 38.8 | 38.7 | 20.9 |

- The 18 datoms: `seon.turn/{id agent opened-tx closed-tx reply-size starting-ns}`,
  `seon.cluster.eval/{id run ordinal at source author ns}`, `seon.eval/{shown duration-ms value-class}`,
  `db/txInstant`, `seon.db.transaction/operation`.
- No `seon.db.transaction/agent` is stamped: `turn.batch/settle` runs after the fold, outside `*world*`
  (batch.clj:699-724; `stamp-transaction` reads `batch/*world*`, db.clj:3603).
- Wall was 84-109 ms on an unchanged program. After the agent's `defn f3` the same call took 1,282 ms and
  3.2 GB, all outside the batch: `jvm-run` re-acquires the agent's SCI context, `acquire-context!` →
  `acquire-program!` (mcp.clj:262), existing over-bound task `4a738d4f7138`.

### 1e. Model turn (DeepSeek `deepseek-flash`, real call): 6 machinery commits plus the wake

Agent `r-record-turn`, overlay `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 1}`, woken by
`seon.cluster.message/inbound-tx` on its branch. Two runs:

| # | commit (op) | run A datoms / bytes | run B datoms / bytes | t (ms, run B) | consumer |
|---|---|---|---|---|---|
| 0 | wake message (`transact!`) | 5 / 66 KB | 5 / 59 KB | 104 | wake listener; not machinery |
| 1 | system-turn refresh run (`transact!`, `system-turn` turn.clj:2030 `:write? true`): 6 generated reads with read evidence | 253 / 310 KB | 202 / 362 KB | 1,430 | the refresh itself (D1) |
| 2 | `open-call` (turn.clj:390): run row and trigger | 9 / — | 9 / — | 1,458 | turn machinery |
| 3 | context capture (`seon.context/capture-tx`, turn.clj:4537): prompt text and 10 contributions | 68 / 92 KB | 104 / 86 KB | 1,666 | transcript/debug view (D1) |
| 4 | attempt row (`record-attempt!` turn.clj:4066): usage, settings-edn, model latency | 20 / 211 KB | 20 / 190 KB | 2,659 | episode count, status |
| 5 | intent (`plan-call` turn.clj:642): reply and result-less evaluation rows | 27 / — | 13 / — | 2,689 | recovery (D2) |
| 6 | settlement (`program-facts-call` / `receipt-settle-batch-call`), closes the run (no separate close commit) | 323 / 688 KB | 14 / 124 KB | 2,756 | — |
| | **total** | 6 machinery commits, 1.36 MB, 5.1 s, 4.9 GB | 6 commits, 0.82 MB, 4.3 s, 4.0 GB | | |

- Run A's reply was 3 forms: `(+ 1 2)`, a contracted `defn add2`, `(add2 3)`. 251 of its 323 settlement datoms
  (78%) were measurement upserts. The rest were program facts (about 36), evaluation terminals (about 30),
  `seon.turn/{closed-tx handled}` and `seon.db.vector/*`.
- Run B's reply was a single `(+ 1 2)`: 12 completion tokens, finish `stop`, while the prompt asked for three
  forms.
- Run B stage times (ms):

  | stage | ms |
  |---|---|
  | `open-turn` / `system-turn` (the refresh) | 1,344 / 1,316 |
  | `call-turn` | 1,296 |
  | `cluster.prompt/prompt` (inside `call-turn`; `acquire-context!` 167) | 175 |
  | `ai/complete` (inside `call-turn`; the provider) | 966 |
  | `record-attempt!` (inside `call-turn`) | 26 |
  | `resume-turn` | 67 |
  | `settle-batch!` (inside `resume-turn`) | 31 |
  | `db/transact!` × 7 | 268 |

  Seon's own work in the pass, excluding the provider, was about 1.4 s, the refresh above all.

### 1f. Test run, `(my.test/run-owned {:seon.ns/name 'scratch.rrec})` in an MCP SCI run: 3 commits on the agent branch

| # | op | datoms | bytes | what |
|---|---|---|---|---|
| 1 | `transact!` (the runner's run open, carrying the MCP run's open via `*open*`) | 57 | 139 KB | `seon.test.run/*` 15 attributes, members, the run row and evaluation rows, `seon.db.transaction/hot` 20 |
| 2 | `seon.test.runner/record-tx` | 61 | 517 KB | `seon.test.member/*`, `seon.test.report/*`, `seon.test.failure/*`, `hot` 20, `repeated` 20 |
| 3 | settlement (`absent-call`) | 2,721 | 452 KB | measurement 1,379 (51%), read evidence 151 rows (about 906 datoms), `executed` 367, `repeated-calls` 57 |

- Total 5.8 s, 5.8 GB, 53,672 armed calls (the reply's own `:calls`); 270 files / 1.1 MB.
- The run failed: "The test world's program overlay (52 entries) was refused: The stored definition digest of
  `[:seon.fn/sym user.sgprobe/fix]` is not the one its definition produces."
  - The cluster head holds entity 105933, written at tx 536872019 by an `absent-call` settlement. The writer now
    recomputes its digest differently (`seon.db/write-digest-error`, db.clj:5239-5287).
  - Every agent branched from default inherits the row, so no agent's test world can be built.
  - New task `seon.test/a-stale-stored-definition-digest-refuses-every-test-world` (`c136c3be1a16`).
- Test members run on their own branches. My listener did not see those commits, but their files are in the
  byte total.
- `seon.turn.batch/test-request` has no production caller. Tests are recorded by `seon.test.runner/record-tx`,
  a third recording writer.

### 1g. Fault and error recording

- `seon.fault/fault!` (fault.clj:359) → `record!` (:138) writes "at most two transactions (origin, then the
  cluster copy)" (fault.clj:166).
- One observed fault (the refused nested JVM run) wrote 2 commits:
  - on the agent branch, `seon.error/commit-call`: 80 datoms, including `seon.db.transaction/hot` 20 and
    `repeated` 19;
  - on default: 39 datoms, including `seon.message/*`;
  - 377 KB together.
- Batch faults are recorded after settlement by the fold's fault stage (batch.clj:615, :725), so they are
  separate commits.
- Repeats are folded into the next settlement (`seon.fault/folded-tx`, turn.clj ~3560).

## 2. Who reads each attribute (rg over `src/`; writers excluded)

| attributes | written by | read by |
|---|---|---|
| `seon.turn/{id agent opened-tx closed-tx starting-ns reply reply-size}`, `seon.turn.work/situation` | both settlement writers, `open-call`, `plan-call` | transcript, repl reply, episode count (`spent-runs` turn.clj:2650), work derivation, status |
| `seon.cluster.eval/{id run ordinal at source ns author comment}`, `seon.eval/{shown duration-ms value-class renderer renderer-fn}`, `seon.sci.eval/ending-ns`, `seon.cluster.eval/{call-count repeated-calls}` | settlement | `seon.repl` (reply map), `seon.render.transcript`, `run-observation` (MCP reply), `previous-ending-namespace` |
| `seon.cluster.eval/{read-evidence executed code-evidence read-basis-transaction}`, `datahike.read/*`, `seon.db.vector/*` and `seon.db/source-argument-position` on read rows, `seon.db/receipt` | turn settlement, `*receipt*` stamp | **only the refresh**: `read-only-evaluation?` (turn.clj:1853), `code-current?` (:1898), `system-plan` (:1910). All are slated by D1 |
| `seon.eval/{elisions shown-tokens value-tokens}` | settlement (`sci.eval` 3552-3595 → turn.clj:173) | **nobody** (no reader outside the producers) |
| `seon.db.transaction/{hot repeated ms calls}` | `stamp-transaction` (profile observation) | **nobody** (task `seon.profile`, line 42 of its EDN) |
| `seon.db.transaction/{operation agent}` | every commit | tests and research tooling only; the operation is wrong for agent-branch settlements |
| `seon.profile.measurement/*` | every settlement (`significant-tx`, measurement.clj:142) | `seon.health` regressions (health.clj:472-483) |
| `seon.context.capture/*`, `seon.context.contribution/*`, `seon.render.block/name` | capture commit | transcript and debug views (D1) |
| `seon.ai.attempt/*`, `seon.ai.usage/*`, `seon.ai.model/last-*`, `seon.turn/attempts` | `record-attempt!` | episode count (`seon.turn/attempts`), status (`usage-edn`), `ai.clj` model stats |
| program facts (`seon.fn/*`, `seon.fn.arity/*`, `seon.program/*`) | `program-facts-call` inside the settlement | acquisition, install, test reach, merge |

## 3. The two settlement writers (and a third)

| | `seon.turn/settle-batch!` (turn.clj:3494; total wrapper `settle-evaluated!` :3601) | `seon.turn.batch/settle` (batch.clj:682) |
|---|---|---|
| used by | model turns and MCP SCI runs, via the agent proc → `resume-turn` (:5054) → `evaluate-sources` (:4857, settle stage dissoc'd) | MCP JVM mode only (`jvm-run` mcp.clj:254 → `jvm-request` :887). `code-request`/`turn-request`/`test-request` have no production caller |
| run row | pre-opened (`open-call`, `plan-call`) or carried by `with-submission` (:3464) as `absent-call` | written whole by `record-evaluated-call` (turn.clj:1296) via `record-facts` (:631) |
| evaluation fields | `evaluation-terminal-data` (:3312): disposition, deliveries, `error/values-tx`, read evidence, executed, tokens, elisions, faults window | `evaluation-record` (:611): 9 fields only |
| program facts | `[:db.fn/call program-facts-call …]`, derived inside the writer on its head | `program-facts`, derived before the commit on `head` (empty for JVM: no derive stage) |
| blobs | `blob/with-publication!` of staged reply and shown text | none: `:seon.eval/shown` inline at any size |
| extra | `with-fault-windows` (a query per settlement, 1.6-34 ms), `close-folded-call` (6-18 ms), `seon.fault/folded-tx`, measurement | measurement only |
| refusal | revalidate on head → second commit → `settle-batch-refusal!` (third commit) → `close-escaped-run!` and a fault | rebase → one retry; storage error kept as a value |

- The third recording writer is `seon.test.runner/record-tx`, for test results.
- Single-form and failure paths also commit through `settle!` (:3880, its own refusal arm) and `close-turn`
  (:5238).
- `record-evaluated-call` refuses `::agent-already-running` when the agent has an open run (turn.clj:1337), so
  a JVM-mode run cannot be recorded while that agent's SCI turn is open.

## 4. The ~130 KB per-commit floor (re-measured)

One SCI `(+ 1 2)` commit, 27 datoms over 27 distinct attributes: 27 files, 152 KB.

| object | files | KB | share |
|---|---|---|---|
| commit record (`k/assoc store cid db-to-store`, writing.cljc:539/561) | 1 | 26.7 | 18% |
| branch head (`[branch-key db-to-store]`, writing.cljc:540): the same fused-root value again (`:fuse-index-roots? true`) | 1 | 26.7 | 18% |
| index nodes: about 10 level-1 branches of 3.4-11.8 KB and about 15 leaves of 1.0-3.5 KB | 25 | 99 | 64% |

- The head is now 26.7 KB, up from 21.6 KB on 09-28: the root fan-outs grew.
- Index-node bytes scale with the distinct attributes a commit touches, because aevt keeps each attribute in
  its own region, as the 09-28 analysis found. Datoms add almost nothing: about 1 KB of values.
- The JVM commit touches 18 attributes and writes 130 KB. A defn settlement touches 72 attributes and writes
  700 KB.
- Config: `:branching-factor 128`, `:keep-history? true`, `:crypto-hash? false`.
- Existing tasks:
  - `seon.cluster.store/an-ordinary-commit-writes-26-files-and-136-kb`;
  - `each-commit-serializes-its-root-record-twice`;
  - `a-small-commit-rewrites-thirty-index-nodes`;
  - `each-commit-forces-the-directory-once-per-node`.

## 5. Facts the plan's draft gets wrong

1. **"two settlement writers (`seon.turn` and `turn.batch`)" (§2, D4).**
   - Writer 2 (`turn.batch/settle` + `record-facts`) serves only MCP JVM mode.
   - Tests do not use it. They have their own recorder, `seon.test.runner/record-tx`.
   - The MCP SCI entrant and the model turn share writer 1, through the agent's turn proc.
   - Converting D4 means `jvm-run` and the test recorder, not "MCP, JVM and test entrants".
2. **"A turn whose forms write nothing costs exactly one commit" is already true for MCP SCI runs, but not for
   a model pass.** A pass is the refresh plus `open-call`, capture, attempt, intent and settlement (6). No
   separate close commit was observed: the close is folded into the settlement (`closing-settlement?` :3251,
   `close-folded-call` :2356).
3. **"the agent's own writes commit when its form runs" already holds,** but today the first agent write also
   commits the run's open and evaluation rows (`*open*`), and its tx report shows them (§1c).
4. **Budget "store bytes < 50 KB plus the agent's own data" per pass is unreachable under the current Datahike
   configuration.** Any single commit writes 53 KB of fused-root record twice (2 × 26.7 KB) plus about 4 KB per
   distinct attribute in index nodes: 130-160 KB for a 27-attribute commit. Reaching it needs store changes
   (§4 tasks), not settlement changes.
5. **"`(+ 1 2)` allocates about 150 MB"** is confirmed (136-232 MB). A turn's record is not dominated by the
   evaluation rows: measurement upserts are 51-78% of datoms in every defn, turn or test settlement measured.
   Read evidence and `executed` (refresh-only consumers) are most of the rest.
6. **The record step does not show where a model pass's Seon time goes:** 1.3 of the 1.4 s are the refresh
   system turn (D1), not settlement (31-254 ms).

## 6. Constraints the plan must respect (facts only)

- One durable synchronous transaction per pass must still hold:
  - the run row (today `open-call` or `with-submission`'s `absent-call`, which guards a run already opened by
    an agent write);
  - evaluation rows with ordinal, source, ns, shown and error;
  - program facts derived at the writer on its head (`program-facts-call`, with `changed-since-basis` push-back
    and `revalidate` on refusal);
  - the attempt row (`seon.turn/attempts` is what `spent-runs` counts);
  - the close and `closed-tx`;
  - disposition and deliveries (`evaluation-terminal-data`);
  - folded fault repeats;
  - staged blobs published under `blob/with-publication!` before the commit.
- If the open moves to the end, an agent `transact!` mid-turn has no run or evaluation entity to reference.
  Today `seon.db/receipt` and `*open*` exist to make that reference.
  - The receipt's only reader is `read-only-evaluation?` (refresh).
  - `*open*`/`absent-call` exist so the open is written once by whichever write lands first.
- The episode bound reads `seon.turn/attempts` and `reply-size` asserted after the run's id tx
  (`spent-runs` turn.clj:2650-2673). Writing the reply and the run in one transaction changes that count's
  meaning.
- Faults are separate writers: up to 2 commits each (agent branch plus the cluster copy on default). A
  settlement read of the fault window costs one query per settlement.
- Refusal path:
  - The writer validates the whole final value (`write-digest-error` and others).
  - A refused settlement today takes up to 3 commits (revalidated retry, refusal settlement, escaped-run fault).
- `record-evaluated-call` refuses while the agent has an open run (single run per agent).
- Measurements ride "a commit that happens anyway" by design (measurement.clj:142-166). With one commit per
  pass they still dominate its datoms.
- The JVM entrant's settle runs outside `*world*`, so no agent is stamped. Any one-owner settlement must carry
  the agent explicitly.

## 7. Slated for deletion in this area, with callers

| slated | callers today |
|---|---|
| `seon.turn.batch/settle`, `record-facts`, `evaluation-record`, `rebase` (D4) | `program-request` stage map (turn/batch.clj:826), `jvm-request` (:906), `test-request` (:927); `jvm-run` (mcp.clj:271) via `:seon.turn.batch/record-call #'seon.turn/record-evaluated-call` |
| `code-request`, `turn-request`, `test-request` | no production caller; `test/seon/test_support.clj:347` uses `code-request` (test `run_test.clj:172`) |
| `record-evaluated-call`, `record-evaluated-tx` | `jvm-run`, page "Add to context" path (`record-evaluated-tx` docstring) |
| `open-call`, `plan-call`/`plan-tx`, `close-tx`, `close-folded-call`, `open-run-tx-call`, `settle-batch-refusal!` (D2) | `open-turn` :4282, `call-turn` :4469, `close-turn` :5238, `settle-batch!` :3535-3548, `refusal-terminal-data` |
| read evidence, `executed`, `code-evidence`, `read-basis-transaction`, `seon.db/receipt`, `*receipt*`/`*open*` (D1, refresh-only consumers) | written by `evaluate-sources` :4960-4975, `stamp-transaction` db.clj:3608-3609, sci/eval.clj:3999-4000; read only by `system-plan` / `read-only-evaluation?` / `code-current?` |
| context capture commit (D1) | `call-turn` :4537 |
| `seon.eval/{elisions shown-tokens value-tokens}`, `seon.db.transaction/{hot repeated ms calls}` | written, never read |

## 8. Other findings (each with its task)

- Overlay refusal blocks all test worlds: new `seon.test/a-stale-stored-definition-digest-refuses-every-test-world`
  (`c136c3be1a16`).
- Agent tx report carries machinery datoms: new `seon.db/an-agent-transact-reports-the-runs-open-datoms`
  (`803a8155dcb1`).
- The value printer prints integers as entity references inside a map that has an identity key. Reproduced:
  `[{:conn :branch :datoms 27 :attrs {:seon.turn/id 1 :seon.turn/agent 1 …}}]` printed `:seon.turn/agent` as
  `[:db/ident :seon.db.transaction/operation]` (JVM mode). Existing task
  `seon.render.value/a-count-prints-as-an-entity-reference`.
- Over 1 s outside the agent's work:
  - JVM-mode run after a program change, 1.28 s and 3.2 GB in context reacquisition: task `4a738d4f7138`;
  - the refresh system turn, 1.3 s per pass: D1, `seon.turn/a-trivial-agent-turn-takes-eleven-seconds`;
  - a failing test run, 5.8 s and 5.8 GB: covered by the new overlay task and
    `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`.
- `derive-form` costs 20 ms for a form that defines nothing (`(+ 1 2)`). Recorded here only: under 1 s, no
  task.
- `my.agent/settings!` answers `{:my.agent/settings {}}` after a successful write. The overlay read right
  after shows both keys (live-run defect 6).
- Model run B returned 1 of 3 requested forms (12 completion tokens, finish `stop`). This is consistent with
  the shipped stop `user=>` cutting the reply. UNVERIFIED: the raw completion before the stop is not stored.
- Measurement upserts per settlement: `seon.profile.measurement/a-test-run-upserts-three-hundred-measurement-rows`.
  Stamp mislabel: `seon.db/settlement-transactions-are-stamped-as-absent-call`. Read evidence volume:
  `seon.turn/read-evidence-rows-are-two-fifths-of-the-stored-bytes`.

## 9. Not traced

- UNVERIFIED: per-thread allocation; which index each node file belongs to (sizes only, compared with 09-28
  attribution).
- UNVERIFIED: the commits test members make on their own branches; a successful test run (the overlay refusal
  prevented one).
- UNVERIFIED: a refused settlement's 2-3 commit path (read in source only).
- UNVERIFIED: blob publication with a staged blob (no value crossed the threshold).

## Implications for the plan

- Re-scope D4: writer 2 is the JVM entrant only. The test recorder (`record-tx`) is a separate writer the plan
  does not name.
- The "1 commit per pass" target mostly comes from D1 (refresh and capture) and D2 (open, intent). The
  settlement already includes the close.
- Before "< 50 KB store per pass" can be met, it has to be reconciled with the measured floor of about 130 KB per
  commit (Datahike head duplication and per-attribute nodes).
- The record's datom volume is decided by measurement upserts and refresh-only evidence, not by evaluation
  rows. Both have named consumers, or none, listed in §2.
- The agent-write and open interplay (`*open*`, `seon.db/receipt`) needs a decision when the open moves to the
  end.

No RESET NEEDED. Default was only written through ordinary MCP runs; the three branches are retired.
