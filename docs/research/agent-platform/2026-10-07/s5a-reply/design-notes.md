---
type: notes
status: design (gate 1: awaiting root's dual review)
created: 2026-10-07
lane: s5a-reply
---

# s5a-reply: the reply from the held pass; problem-assignment routing deleted (design, gate 1)

What exists: an MCP `eval_clj` pass settles its run in one transaction, then `seon.mcp/observed` re-reads that run
from the settlement's basis (`seon.cluster.agent/run-observation` → `seon.render.transcript/history-entries` →
`seon.repl/entity-emission` per evaluation, plus `seon.eval.drive/run-receipts`) to print each reply. Separately,
every evaluation of every batch asks `seon.problems/form-problem` whether to mail its red form to a namespace owner.
Approach: the settlement writers hand back the receipts they wrote (data already in hand), the reply is
`seon.repl/response` over those receipts, and the routing step and its delivery are deleted. Spec: agent-loop §4.1
FINISH and the "What the one transaction stops writing" rows for routing and the reread; §5 S5.

Which step of the one path this serves: PRINT/FINISH (the reply is composed from the pass's own held values) and
RECORD (one less per-form read before the settlement). Owner reused: `seon.repl/response` and
`seon.repl/entity-emission` (already total without a database), the settlement's own receipts. What it deletes:
`run-observation`, the per-reply `history-entries` call and its 100 `entity-emission` queries, `form-problem`,
`assignment-value`, `planner-scoped-attempt?`, `problem-id`, two schemas and two `requiring-resolve` delays.

## 1. Probe (default at d59363530, idle, my agent `s5a-probe` on its own branch)

`bin/seon status`: default `:faulted` by one open fault, `seon.turn.world/record-fault` "This write names branch
:agent-1048bc748e6e, which is not the writing cluster's branch" (task 3d2fb1b41dc8, already linked); over-bound
`seon.batch/evaluate-batch` (max 7.3 s) and `seon.mcp/sci-run` (max 5.2 s), both from root's earlier loaded runs.

One MCP SCI pass of n × `(+ 1 1)` through `seon.mcp/call`, bracketed in JVM mode with `seon.profile/begin`/`explain`
and `getTotalThreadAllocatedBytes` (JVM-wide, so the outer JVM eval that holds the bracket is included):

| n | wall ms | JVM-wide MB | `seon.db/q` calls (ms) | `run-observation` ms | `entity-emission` calls (ms) | `form-problem` calls (ms) | `transact-call` ms |
|---|---|---|---|---|---|---|---|
| 1 | 64 | 98 | 42 (44) | 11 | 1 (1) | 1 (1) | 33 |
| 10 | 118 | 196 | 96 (60) | 24 | 10 (16) | 10 (9) | 40 |
| 100 | 652 | 1,173 | 636 (335) | 160 | 100 (131) | 100 (93) | 105 |

`run-observation` alone, re-run on the 100-form run's basis on one thread: 86 ms, 208 MB (`run-receipts` 0 ms, 2 MB).
Root's 3.9 s / 1.34 s / 0.84 s numbers were the same pass under load; the shares agree (reread ~25%, routing ~15%).

Where the queries are (read from source, counts consistent with the table):
- `entity-emission`, one `changed?` query per evaluation (`src/seon/repl.clj` `entity-emission`), plus a namespace
  pull when the ref is not expanded: 100 at n=100.
- `form-problem`: one `(pull ?form [*])` query and `message/trigger` (via `planner-scoped-attempt?`) per form; the
  owner and author queries run only for a red form of a triggered run.
- Not in scope and still there after this slice: `evaluation-terminal-data` runs two queries per form (`last-ordinal`,
  `triggered-agent-form?`) at settlement; the fault-window query (S5c).

Prediction for the change (hypothesis until measured): at n=100, about -230 to -300 `db/q` calls, -220 to -250 ms
and -300 MB or more (the 208 MB reread plus the routing allocation).

## 2. Path (a): the reply from the held pass

### Today, end to end

- SCI: `seon.mcp/sci-run` → `seon.cluster.agent/evaluate-source!` → proc → `seon.turn/turn` → `resume-turn`
  evaluates the batch, `settle-evaluated!` → `settle-batch!` writes one transaction (receipts via
  `receipt-settle-batch-tx`, each run's last receipt carrying `:seon.cluster.eval/faults` from `fault-window`) → the
  turn report (`:seon.turn.loop/turn-report`: outcome, forms-run, basis, refusal) → `evaluate-source!` answers
  `{:seon.turn/id :seon.db/db basis [:seon.turn.loop/refusal]}` → `seon.mcp/observed` → `run-observation` re-reads
  the run (`history-entries` with `::selected-run-id` → `candidate-history` → `history` pulls receipts, then
  `branch-errors` reads the run's `:seon.cluster.eval/faults` and floor-prints them into `:seon.repl/errors`) and
  joins `run-receipts` by ordinal → each text is `(or response shown error "")`.
- JVM: `seon.mcp/jvm-run` → `seon.batch/evaluate-batch` (`turn.batch/jvm-request`) → `turn.batch/settle` commits
  `record-evaluated-call` with `evaluation-record` of each settled entry → the same `observed` reread on
  `:seon.batch/after`.

### Defect the reread causes (found in the probe)

Every JVM-mode reply after an agent's first run drops `:ms`: `entity-emission`'s `changed?` query is true for any run
with `:seon.turn/reply-size` and an earlier run of the same agent, and `record-evaluated-call` always writes
`reply-size`, so the fresh reply is rendered as "changed since your last turn" history. Seen live: my JVM replies show
no `:ms`, my SCI replies do. Building from the held receipt fixes it (BEHAVIOR CHANGE, regression below).

### Design

1. **The settlement writers answer the receipts they wrote.** Each terminal writer already holds them:
   - `settle-batch!` success: the receipts as `with-fault-windows` saved them (faults on the last). Today they are
     built inside the `transaction` closure; the closure returns them beside `:seon.db/tx-data`, and the commit phase
     answers them with the outcome, so the window read stays inside the phase (a refused read is still a refused
     settlement) and a retry answers the retry's receipts.
   - `settle-batch-refusal!`: its `receipts` local (each form's shown text and the refusal as error/diagnostics).
   - `settle!` (single-ordinal failure arm): the receipt map `refusal-terminal-data` builds, when there is an ordinal.
   The turn report gains one optional member `:seon.turn.loop/receipts [:vector :seon.cluster.eval/settle-request]`
   (registered in `seon.turn.loop.edn`), set where `applied`/`settled` attach the basis. `evaluate-source!` passes it
   through in its answer (`:seon.agent/source-submission-result` gains the same optional member).
2. **The reply is `seon.repl/response` over each held receipt.** One function, `seon.render.transcript/held-responses`
   (beside `candidate-history`, which already renders in-memory evaluations through the same converter):
   - per receipt, `entity-emission` of the receipt with `:seon.cluster.eval/id` (`seon.turn/receipt-identity`, for the
     handle), the namespace as `{:seon.ns/name sym}` and no `:seon.db/db`, so no per-form query runs;
   - the faults on a receipt become `:seon.repl/errors` exactly as `branch-errors` does today, by the same code
     extracted to one function over the fault refs (pulls proportional to faults; zero for an ordinary pass), printed
     once by the value printer (`floor-text`);
   - `seon.mcp/observed` keeps its `(or response shown error "")` and failure flag over these.
3. **JVM** holds `(:seon.batch/settled commit)` entries; `turn.batch/evaluation-record` (made public) of each is the
   receipt `record-evaluated-call` wrote; the same `held-responses` prints them. No faults ride JVM records today.
4. **Deleted:** `seon.cluster.agent/run-observation` and its two `requiring-resolve` delays. Kept (other consumers):
   `history-entries` (agent history unit, page, tests), `entity-emission` (history, debug page, ledger),
   `drive/run-receipts` (`seon.eval.drive` and tests).

What the held reply equals: the stored receipt is the held receipt (the writer call stores the map it is given), so
response bytes are identical for SCI except the deliberate `:ms` fix for JVM. Verified by a regression that compares
`held-responses` of the held receipts with `history-entries`' `:seon.render.history/printed-value` on the basis for an
ordinary, an erroring and a faulted SCI pass (one table).

Cost after: zero queries per form for the reply; fault pulls only when faults exist.

### Callers and tests of what changes

| What | Callers / tests | Conversion |
|---|---|---|
| `run-observation` | `seon.mcp/observed`; `evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent`; `mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`, `.../a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle` | `observed` takes held receipts; tests assert `:seon.turn.loop/receipts` of the answer and the MCP reply data. **`test/seon/mcp_entrance_test.clj` is held by lane s0-probe**: converted by patch to its owner, or after its release |
| turn report, `evaluate-source!` answer | `seon.cluster.agent` step/answer readers read named keys only (open map); new member is optional | none |
| `turn.batch/evaluation-record` | private today | made public, same contract |

## 3. Path (b): problem-assignment routing deleted

Today: `resume-turn` calls `@problems-form-problem` for every evaluation of every batch with forms (one pull + one
trigger query each), passes the problem into `evaluation-terminal-data` → `asked-value` → `seon.problems/assignment-value`
→ an automatic assignment message to the namespace's agent (the defect of task 5e7b36bfd02d: an agent's own errors
mailed to another agent), and into `evaluation-facts` as an error fallback.

- `evaluation-facts`' `(:seon.cluster.eval/error form-problem)` fallback: `form-problem` returns nil for an
  interrupted evaluation (`resume-artifact?` is true when interrupted), so its error is always the evaluation's own.
  Removing it is EQUIVALENT.
- `asked-value`: only the completed-disposition reply remains. BEHAVIOR CHANGE: no automatic assignment messages.

Deleted: `seon.problems/form-problem`, `seon.problems/assignment-value`, `seon.turn/planner-scoped-attempt?` (only
caller `form-problem`), `seon.turn/problem-id` (callers `form-problem` and one test assertion), the delays
`problems-form-problem`/`problems-assignment-value`, the `problem` threading in `resume-turn`,
`evaluation-terminal-data`, `asked-value`, `delivery-rows`, `evaluation-facts`; schemas `:seon.problems/form-problem`,
`:seon.problems/form-problem-request` and the optional `:seon.problems/form-problem` members of
`seon.turn.edn`/`seon.turn.loop.edn` request schemas (published and adopted on a branch of the live store before
landing).

Kept: the explicit message protocol (`my.message` `:my.message/assignment`, declination) and `seon.turn/form-settlement`
with its `:routed`/`:owner-fixed`/`:owner-declared-cant` states, which read those explicit messages; `form-owner` and
`resume-artifact?` (used by `form-settlement`). Without automatic routing these states are reached only by an agent
that sends an assignment itself. Whether that protocol is wanted at all is root's call (a further cut, not this slice).

| Test | Fate |
|---|---|
| `problem-routing-test`: `parse-time-owner-wins-and-absence-falls-back-to-the-author`, `an-author-owned-red-form-remains-unsettled-without-self-assignment`, `historical-reds-are-outside-the-live-attempt-chain` | deleted with `form-problem` |
| `problem-routing-test/stored-evaluations-derive-routing-and-settlement` | kept (explicit messages) |
| `resume-artifact-routing-test/resume-artifacts-stay-red-and-are-excluded-from-owner-routing` | its `form-problem` assertion deleted; the `form-settlement` assertion stays |
| `seon.cluster.turn-test/a-red-form-routes-to-its-namespace-owner-and-the-fold-continues` | the routed-message assertion becomes "no assignment message"; the fold assertions stay. **File held by s0-probe**: patch to owner |
| `seon.gen.loop-test` (`:routed` expectation, and its ns docstring) | expects `:unrouted-red`. It uses `with-redefs`; touched only for the expectation |
| `seon.turn-test` `form-settlement` `:unrouted-red` | unchanged |

Regression for (b): one SCI pass by agent A whose red form runs in a namespace whose assigned agent is B writes no
`:seon.message/assignment` message (red on the parent, which routes it). For (a): the `:ms` JVM row and the
held-equals-stored table above.

## 4. Risks

1. **Every terminal path must hand back what it wrote.** A settlement arm that answers no receipts would print an
   empty reply. Arms: batch success, batch refusal, single-ordinal failure (`settle!`), refused-refusal (closes the run
   and records a fault; its receipts are the refusal arm's `receipts`), pre-evaluation phase failure (no ordinal, no
   receipt: the reply is the printed refusal, as today's reread also finds none). A timeout answer has no receipts:
   today it rereads nothing either (`evaluate-source!` answers the timeout).
2. **Turn-report size.** Receipts hold shown text already in memory; the report now references them until the MCP
   reply is printed. No copy. Model-turn reports also carry them; the proc drops the report after `step`.
3. **Something else reading the re-read fields.** `:seon.repl/response` on `run-observation` maps is read only by
   `observed` and the two held tests. The page and agent history keep `history-entries`.
4. **Two held test files** (`mcp_entrance_test.clj`, `cluster/turn_test.clj`) belong to s0-probe: root decides patch or
   wait. `bin/lane commit --test` must name them for the check to reach the change.
5. **Schema retirement** of `:seon.problems/form-problem*` needs adoption on a live-store branch; stored data never
   held these keys as attributes (they are request schemas), so adoption is a registry change only (to verify).

## 5. Findings recorded

- JVM MCP replies drop `:ms` (above): fixed by this slice, regression named.
- `my.program/callers` refuses an omitted `:seon.db/db` instead of supplying it from the world: task
  `my.program/callers-refuses-an-omitted-database-instead-of-supplying-it` (20dcb1c6f17f).
- `my.program/callers` cannot see `seon.turn`'s `requiring-resolve` call to `form-problem` (the load-cycle delays hide
  the edge); this slice deletes that delay. Not filed separately: the delays are the known cycle workaround.
- Default `:faulted` (task 3d2fb1b41dc8, linked by health).

## v2 (after the dual review: `review-s5a-opus-notes.md`, `review-s5a-astra-notes.md`; root's rulings)

(b) as written, plus the dangling references: `resources/seon/tasks/seon.turn.edn` must-read, `seon.db.edn`
evidence, `seon.mcp.edn` must-read, `test/seon/schema/datahike_parity.edn` baseline, `sci/eval.clj` and `mcp.clj`
docstrings, `turn.clj` `receipt-identity` docstring.

(a) revised:
- **One converter.** `seon.render.transcript/candidate-history`'s in-memory arm (`:seon.turn.loop/evaluated-sources`:
  admitted form + evaluation + ordinal) is the one assembler for the page preview and the held reply. It is
  generalized to: take the namespace from the evaluation's `[:seon.ns/name x]` when present (what the writer stores,
  since the terminal assertion overrides the frozen row), else the form's; carry `:seon.cluster.eval/run [:seon.turn/id
  id]` (the receipt's actual run, so the entity satisfies the installed `:seon.eval/entity` contract); and print a
  receipt's `:seon.cluster.eval/faults` through the code extracted from `branch-errors` (one function over error refs,
  same `floor-text` root via `entry-root`). `history-entries` renders in-memory entries with no database, so no
  per-form query runs (EQUIVALENT for the preview: its ids are not stored yet).
- **Source from the admitted form**, as that arm already does, so a `def` keeps its `:note`.
- **Held = saved.** The SCI turn report gains optional `:seon.turn.loop/evaluated-sources`: each gated admitted form
  with the receipt the settlement wrote as its evaluation. Batch success: the fault-windowed receipts the committed
  transaction carried (read inside the commit phase, as today). Batch refusal and `settle!`'s refusal arm: receipts
  only when the writer applied them (`open-run-tx-call` decides at the writer; applied iff the report's `:tx-data`
  asserts this run's `:seon.turn/closed-tx`, which rides the same guarded block), else none. Refused-refusal: no
  basis, answered as the refusal alone (today's behavior, unchanged). Every arm attaches it, including the
  refused-but-recorded arm (`turn.clj` "refused-outcome" branch). `evaluate-source!` passes it through
  (`:seon.agent/source-submission-result` gains the member). Faults are pulled on the settlement's `db-after`.
- **JVM** input is `(:seon.batch/entries batch)` after `evaluate-batch` merged the settled state; one pure
  `seon.turn.batch/entry-evaluation` (split out of `evaluation-record`, which keeps the run id and instant) maps an
  entry to its evaluation for both the recording and the reply, so the reply holds exactly what was recorded.
- The "changed since" marker is not touched (S4 deletes it); the JVM reply shows `:ms` because the held arm renders
  without a database.
- Regression table (data): ordinary, def `:note`, renderer, `in-ns` `:ns`, `set!` print-length, error,
  not-admitted, completed disposition, faulted, refused settlement (held reply equals the stored response on the
  basis), and JVM `:ms`.
