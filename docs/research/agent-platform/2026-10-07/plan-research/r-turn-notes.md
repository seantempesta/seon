---
type: research
status: draft
created: 2026-10-07
---
# r-turn: one model turn end to end, wake to next context (research lane, 2026-10-07)

Research only: nothing in `src/` or `test/` changed. Default ran `722f110df` (pid 41301); `git diff 722f110df HEAD -- src
test resources` is empty, so every `file:line` below is HEAD. Owners read end to end: `seon.turn/step` (turn.clj:5789),
`turn` (:5450), `open-turn` (:4282), `call-turn` (:4359), `resume-turn` (:5054), `close-turn` (:5238), `generate-turn`
(:5260), `system-turn` (:2030) with `declared-sources`/`latest-evaluations`/`system-plan`/`generated-read-fault`,
`next-agent-work` (:2833) with the episode and wake derivations (:2579-3005), `open-call`/`close-call`/`plan-call`
(:390/:436/:642), `settle-batch!`/`settle-evaluated!`/`settle!` (:3494/:3601/:3880), `record-attempt!` (:4066),
`evaluate-sources` (:4857); `seon.cluster.agent/mailbox-step`, `turn-step`, `graph-definition`, `submit-source!`
(agent.clj:477/511/563/665); `seon.ai/request-body` (ai.clj:561) and `complete` (:1267). Not read end to end:
`seon.cluster.prompt/prompt`, `seon.batch/evaluate-batch` internals, `seon.problems/form-problem`,
`sci.eval/install-evaluated-rows!` (named from profiles only).

## 0. Broken things first

1. **There is no value seam for a fixture provider reply through a turn.** `:seon.ai/send!` is read only by
   `seon.ai/complete` (ai.clj:1295); `call-turn` builds its request from `provider-targets` (turn.clj:4046) and never
   carries a sender. The turn tests stub the provider with `with-redefs [ai/complete ...]` (turn_loop_test.clj:487-500).
   The loop-pass patch added `(:seon.ai/send! cluster)` to call-turn but it is uncommitted
   (`stopped-wip/loop-pass.patch:751`). I drove the fixture turns through a real local HTTP endpoint instead (§2).
2. **A model turn allocates 3.6-6.2 GB and spends 1.5-2.3 s of Seon work before the provider call**, nearly all in the
   pre-turn system turn re-acquiring the agent's SCI context (`seon.sci.eval/acquire-program!` 1.0-1.3 s). Existing
   tasks: `seon.sci.eval/acquisition-rebuilds-the-whole-program` (4a738d4f7138),
   `seon.turn/a-trivial-agent-turn-takes-eleven-seconds` (f5ec53da8a90).
3. **Root's messages never refill the turn bound, and the deferral is silent.** Root's message is an inside wake, so
   `outside-wake-t` stays 0 and `episode-runs` counts every turn the agent ever took. Agent r-turn-a with
   `max-episode-runs 1` and one spent turn got root's message `ee63e682b520`: `next-agent-work` → nil,
   `outside-wake-t` 0, spent 1, the message sat in `unanswered-wakes`, and nothing was recorded or delivered. Filed
   `seon.turn/a-message-from-root-never-refills-the-turn-bound` (2e96e46b28c9) and
   `seon.turn/a-deferred-wake-is-answered-by-silence` (c2ab215d5482).
4. **The agent's own form errors are mailed to another agent.** lp-add-2's turn `76fb76c15e95` settlement
   (tx 536872927) sent three "Repair problem … from run 76fb76c15e95" assignments from lp-add-2 to `review-final-opus`
   (another agent assigned to `user`). Filed `seon.problems/an-agents-own-form-errors-are-assigned-to-another-agent`
   (5e7b36bfd02d).
5. **A JVM reply mislabels refs pulled from another branch.** `d/pull` on r-turn-a's branch run as agent r-turn showed
   `:seon.config/agent [:seon.cluster.eval/id "732b426d6067"]` and `:seon.turn/agent [:seon.cluster.eval/id …]`: eid
   125951 is agent r-turn-a on the pulled branch but an evaluation on r-turn's own branch. Shown data is silently
   wrong. Filed `seon.repl/a-ref-from-another-branch-prints-with-the-wrong-identity` (ad39d9702970).
6. **`seon.profile/begin` costs 18,600 armed calls and ~3.5 MB per mark** (2,660 cells; `adder?` +5,320,
   `measured-mark?`/`accumulator?`/`thread-local?`/`histogram?` +2,660 each), against its docstring "calling no other
   armed function". Every window measured with it counts the probe. Filed
   `seon.profile/begin-validates-every-cell-with-armed-predicates` (561d2f7ee288). My per-stage armed-call numbers
   below are corrected by subtracting ~18.6k per mark.
7. **A refused retire half-applies.** `(my.agent/retire-branch! {:seon.store/branch :agent-cfd70b5547f7})` from r-turn
   closed admission on r-turn-a (tx 536872981), then refused ("still has a connection in this process"). r-turn-a's
   own retire then answered MCP with a bare `{:seon.error/message "run transition refused: admission-closed"}` and
   escalated a pr-str'd, clipped error message to root. Filed `my.agent/a-refused-retire-leaves-the-agent-admission-closed`
   (7116117e6aaf). Printed-copy message content is task `c4da2c431c3d`.
8. **The MCP timeout remedy names a parameter the tool does not declare.** eval_clj over 32 s answers "Retry with a
   larger timeout_ms" (script/seon/dev/mcp.clj:186); the bridge reads `"timeout_ms"` (:163) but the tool schema has no
   such input. Filed `seon.dev.mcp/the-timeout-remedy-names-a-parameter-eval-clj-does-not-declare` (8f45091c885c).
9. `runtime_status` at start: `:seon.health/state :faulted`, 17 open faults, 179 errored receipts, 13 over-bound
   warnings (top: `seon.batch/evaluate-step` mean 1.0 s max 43.6 s, task 8e2911b0e1de; `evaluate-batch` mean 2.2 s).
   Not caused by this lane.

## 1. Disposable resources

- Agent `r-turn-a`, branch `:agent-cfd70b5547f7`: four turns (one no-provider, two fixture, one DeepSeek). Admission is
  closed (tx 536872981) but the branch is still linked because its connection is held in the process (finding 7);
  boot's `retire-unclaimed!` unlinks an admission-closed branch. **No reset needed**; the branch goes at the next
  restart.
- Agent `r-turn` (this lane's MCP agent), branch `:agent-21293ca0b763`: JVM-mode probe runs only. Left in place (it
  is the lane's own agent id).
- Five fixture settings were written to r-turn-a's overlay; two (`endpoint`, `no-auth`) were retracted by hand,
  because `settings!` cannot remove a key and `:seon.config.ai/no-auth` is `[:= true]` (refused `false`).
- No server, listener or thread outlives its probe: each probe started a JDK `HttpServer` and a `d/listen` inside one
  foreground eval and stopped both in `finally` (listener roster checked after: only `program-identity`, `route` and
  one uuid listener remain).

## 2. How the turns were traced

One foreground JVM-mode eval per turn (scratchpad `r-turn/probe-fixture.clj`): start an `HttpServer` on 127.0.0.1:0
answering one SSE stream with the fixture reply; set the agent overlay `{:no-provider false :model "fixture-local"
:endpoint <server> :no-auth true :max-episode-runs N}` under the branch's custody (an unregistered model keeps the
overlay endpoint: `resolved-target` only substitutes a provider endpoint when the model has one, ai.clj:356); `d/listen`
on the branch connection; at each commit take `(seon.profile/begin)`, `getTotalThreadAllocatedBytes` and a nanoTime;
send a message from root (`seon.cluster.message/send!`); wait until a closed turn with attempts exists and no turn is
open, plus a 1.5-2 s tail; then per-thread allocation deltas (`getThreadAllocatedBytes`), new store files and bytes
(`data/store` files modified since start), and `seon.profile/explain`.

Fixture reply (both fixture turns):

```
I'll define it and call it.

(defn add-one "Returns x plus one." [x] (inc x))
(add-one 41)
```

Stage times are commit-to-commit; a stage's allocation is the whole JVM over that window (other threads included;
default was otherwise idle). Armed calls are total entry growth minus the ~18.6k each mark adds (finding 6).

## 3. One model turn, stage by stage

The pass structure: wake → **pass A** (`:open`: system turn + open) → self-rewake → **pass B** (`:call`: prompt, capture,
provider, attempt, freeze, fold, settle; close folded into the settlement) → self-rewake → **pass C** (derive work:
idle, or `:open` again to continue).

### 3a. Measurements

Warm fixture turn (r-turn-a's 2nd model turn, run 3); cold = its 1st (run 2); DeepSeek = run 4.

| # | stage (commit that ends it) | warm ms | warm alloc | warm armed calls | datoms | cold ms / alloc | DeepSeek ms / alloc |
|---|---|---|---|---|---|---|---|
| 0 | root's `send-call` (the wake) | 44 | 60 MB | ~2k | 37 | 34 / 70 MB | 38 / 54 MB |
| 1 | pass A: `next-agent-work` + `system-turn` → system-turn commit (`seon.db/transact!`) | **1,689** | **2,957 MB** | ~173k | 228 | **2,278 / 3,971 MB** | **1,480 / 3,000 MB** |
| 2 | `open-call` | 29 | 36 MB | ~1.6k | 9 | 34 / 69 MB | 28 / 35 MB |
| 3 | pass B: provider targets, prompt render → context capture (`transact!`) | 227 | 277 MB | ~22k | 110 | 273 / 480 MB | 257 / 318 MB |
| 4 | `ai/complete` → `record-attempt!` (`transact!`) | 32 (stub) | 40 MB | ~2k | 16 | 54 / 46 MB | **1,397** / 46 MB |
| 5 | `planned-sources`, `stage-reply!` → `plan-call` (intent commit) | 33 | 46 MB | ~0.5k | 21 | 26 / 45 MB | 31 / 45 MB |
| 6 | `resume-turn`: fork, fold (`evaluate-sources`), `settle-batch!` → `program-facts-call` (settlement + folded close) | 136 | 151 MB | ~12k | 30 | 414 / 901 MB | 206 / 218 MB |
| 7 | tail: `install-evaluated-rows!`, `episode-exhausted!`, rewake, pass C to idle (no commit) | ≤2,019 window | 77 MB | ~3k | 0 | ≤2,033 / 590 MB | ≤1,553 / 77 MB |
| | **wake commit → settlement** | **2,148** | **3,653 MB (window)** | ~400k (window) | 414 | 3,083 / 6,173 MB | 3,401 / 3,792 MB |
| | **Seon work excluding provider** | ~2,120 | | | | ~3,030 | ~2,030 |
| | store files / bytes written (window) | 197 / 940 KB | | | | 241 / 1.34 MB | 200 / 1.13 MB |

Allocation is concentrated on the pass thread: one `ForkJoinPool` worker allocated 2,820 MB (warm), 3,812 MB (cold),
3,140 MB (DeepSeek) of the window's total.

Profile (warm window, inclusive, `seon.profile/explain`): `turn/turn` 2,183 ms ×2 passes; `open-turn` 1,706;
`system-turn` 1,676; `cluster.agent/acquire-context!` 1,295 → `sci.eval/acquire!` 1,288 → `derive-base-ctx` 1,198 →
`acquire-program!` 1,196; `seon.db/with-declarations` 1,574 ×896; `seon.db/q` 1,389 ×182; `call-turn` 467;
`cluster.prompt/prompt` 192 (`render/acquire-context!` 179, `render.walk/history` 154, `render-call` 165 ×23);
`declared-sources` 172 (`walk/neighborhood` 155); `resume-turn` 176; `evaluate-sources` 135 ×2; `seon.db/pull` 208 ×655;
`carried-projection` 209 ×1,045; `blob/with-publication!` 193 ×4; `seon.db/transact!` 280 ×7. DeepSeek window:
`ai/complete` 1,367 (`stream-fold` 1,037), `acquire-context!` 1,045.

The re-acquisition is not a cold-start effect: it ran on every opening that wrote a system turn (1,907 / 1,295 /
1,045 ms on three consecutive turns). `system-turn` asks `@cluster-agent-acquire-context!` whenever it writes
(turn.clj:2064), and the agent's previous turn changed its program (`add-one`), so the content-keyed base context
is re-derived.

Provider request (fixture run 2, captured by the server): one `user` message, no system message;
`{"model" "fixture-local", "stream" true, "stream_options" {"include_usage" true}, "max_tokens" 65536, "stop"
["user=>"], "thinking" {"type" "disabled"}}`. Body 4,188 chars (prompt 3,765), then 7,576 (run 3). DeepSeek usage:
3,475 prompt tokens, 88 completion, 0 cached.

Prompt growth on a trivial task (stored captures, chars): 3,765 → 6,976 → 9,794. Each capture stores the whole
prompt text again (`:seon.context.capture/prompt`), so stored prompt bytes grow quadratically in the turn count.

What DeepSeek replied (turn `91932e68fa32`): two fenced blocks, the second `(add-one 41)\n;; => 42`, then "Defined
`user/add-one` … and verified it: `(add-one 41)` evaluates to `42`" — it invented the result before seeing it (as a
comment, so nothing flagged it). It also wrote `user/add-one`; the message said "scratch".

### 3b. What each stage reads and writes

| stage | reads | writes |
|---|---|---|
| `mailbox-step` (agent.clj:477) | the wake signal | one payload-free `:seon.agent/episode` (sliding-1) |
| `turn-step` (agent.clj:511) | — | publishes its thread in `:seon.agent/turn-thread` for disarm |
| `step` (turn.clj:5830) | parked state; `await-turn-permit!` (:5600): `agent-dial` → `ai/agent-overlay`, the handle, `config/effective`; `open-for-agent`; blocks on the completion permit | arms the backstop thread (:5731) with an atom of the run id |
| `next-agent-work` (:2833) | `agent-run` (open turn pull); `opening-deferred?` (:2715): `max-episode-runs` q, `wake/declarations-refusal`, `outside-wake-t` (history seeks per listened attribute + `inside-wake?`), `spent-runs` (2 q), failed-attempt q; `unanswered-wakes` (:2936): `latest-answering-turn-t` q, `wake/agent-wake-datoms`, one pull per pending wake; `continuing-reply?` (2 q) | nothing |
| `turn` (:5450) | `ai/agent-overlay` merged into the handle; binds projection state | dispatches on situation; `episode-exhausted!` (:5382) after any non-`:released` outcome (`turns-left`, `max-episode-runs`, `deferred-triggers`) |
| `open-turn` (:4282) → `system-turn` (:2030) | agent namespace; `declared-sources` (:1725): `walk/neighborhood` renders the agent's one-hop units, `unit-read` pulls each; `latest-evaluations` (:1794): every evaluation of the agent with pulls, then read evidence of the kept ones; `system-plan` (:1909): `code-currency` q, `read-only-evaluation?` (2 q per evaluation), `read-evidence-current?`; for :none/:changed reads, `next-id`, `acquire-context!` and one `evaluate-sources` per read; `generated-read-fault`; `db/diff` of shown values | **commit 1**: a closed system turn plus one evaluation per changed read (shown text = `pr-str {:seon.repl/changes diff}`, turn.clj:2188), or evidence refresh datoms; inside a `:db.fn/call` that re-runs `latest-evaluations` at the writer (:2228) |
| `open-turn` cont. | `next-id` (:519): count of the agent's turns | **commit 2** `open-call` (:390): the turn `{id, agent, situation :call, opened-tx, trigger}` and `:seon.agent/runtime {:seon.runtime/trigger}` |
| `call-turn` (:4359) | `provider-targets`: `config/effective`, overlay, `ai/targets` → `model-details`; `opening-db` (as-of the open); `cluster.prompt/prompt` → `render/acquire-context!` → `render.walk/history` → `seon.eval/of-agent` (every evaluation of the agent) | **commit 3** context capture: prompt text, basis, contributions (74-146 datoms) |
| `ai/complete` (ai.clj:1267) | `request-body` (ai.clj:561): model, stream, one user message (`:seon.ai/system` when present — nothing supplies it), wire settings, extra body; credential env var | HTTP POST; streaming snapshots offered to the cluster's stream channel |
| `record-attempt!` (:4066) | settings ref q, blob-threshold q; error recordings | **commit 4**: attempt row (`settings-edn` 3,098 chars of `pr-str`, `usage-edn` `pr-str`), `:seon.turn/attempts`, `ai/model-observation-tx` |
| `freeze!` in call-turn (:4430) | agent namespace; `planned-sources` (:3031, `forgive/segments` in `:reply` mode); `stage-reply!` | **commit 5** `plan-call` (:642): reply text, reply-size, starting-ns, one result-less evaluation per form (author `:agent`) |
| `resume-turn` (:5054) | `fork-for-turn`; `message/trigger`; `fold-evaluations` (pulls the rows commit 5 just wrote); `fold-namespace`; `evaluate-sources` (:4857): overlay, `request-profile`, `evaluate-batch` (read-row → evaluate-row → show-row → publish-row per form); per form `problems/form-problem` | — |
| `settle-evaluated!` → `settle-batch!` (:3494) | `evaluation-terminal-data` per form (dispositions, replies, problem assignments, deliveries); `measurement/significant-tx`; `with-fault-windows` | **commit 6** `program-facts-call`: program facts, terminal facts of each evaluation, deliveries, measurements, folded faults, and `close-folded-call` (:2356) closing the turn and asserting `:seon.turn/handled` |
| after commit 6 | `install-evaluated-rows!` (base ctx); `episode-exhausted!` | rewake own mailbox; armer wake on `:closed`; a fault if install fails |
| pass C | `next-agent-work` again | nothing (idle) |

## 4. Where the 5-6 commits of a model turn come from

Observed on every model turn traced (lp-add-2 ×2, r-turn-a ×3), in this order:

| # | operation stamp | owner | why it is separate today |
|---|---|---|---|
| 1 | `seon.db/transact!` | `system-turn` (turn.clj:2219) in pass A | the generated opening and every changed retained read are re-run and stored as a closed system turn before the turn opens; a new message always changes the inbox read, so every woken turn writes it |
| 2 | `seon.turn/open-call` | `open-turn` (:4326) | "open first, model second: the busy fence has to exist before the expensive part"; the open's `:t` is what answers wakes |
| 3 | `seon.db/transact!` | `call-turn` capture (:4535) | "capture before the provider" (ruling 4, 2026-07-28): exact prompt bytes durable before the unobservable call |
| 4 | `seon.db/transact!` | `record-attempt!` (:4203) | one attempt row per paid call, durable before the reply is used |
| 5 | `seon.turn/plan-call` | `freeze!` (:4473) | "ONE INTENT COMMIT": reply and result-less rows durable before any form runs |
| 6 | `seon.turn.batch/program-facts-call` | `settle-batch!` (:3569) | the settlement: values, program facts, deliveries, close |
| (+1) | `seon.error/commit-call` | the instrument's error recording | only when a Seon fault fires mid-fold: lp-add-2 turn 1 (tx 536872926, the `show-row` → `shown-failure` nil contract fault, task a97a188decbe / 7f5dfb6ad7a2) |

So a clean model turn is **6 commits counting the system turn** (5 without it); the plan's 6 for turn
`76fb76c15e95` was 5 ordinary commits plus a fault, and left out the system turn (7 in total, tx 536872921-927).
**There is no close commit**: the close is folded into commit 6 (`close-folded-call`); `close-turn` runs only for a
`:close` situation (a frozen run with no form left). A no-provider turn is 4 commits (system turn, open, `plan-call`
of `""`, separate `close-call`: r-turn-a tx 950-953, 410 ms). An MCP/virtual submission is ONE commit: the open rides
the settlement (`submission-open-tx` :777 + `with-submission` :3464), e.g. r-turn-a's `(+ 1 2)` = tx 536872948.

## 5. Facts the draft plan gets wrong or leaves out

1. §2 "6 commits: open-call, plan-call, 2 × transact!, program-facts-call, error/commit-call": the two `transact!` are
   the context capture and the attempt; `error/commit-call` was a fault, not turn machinery; the system turn is a 7th.
   Clean turn: 6 including the system turn.
2. §2 step 4 "close-turn: a close commit": no close commit on the model path; close is folded into the settlement.
   Task `seon.turn/close-turn-is-a-third-commit` (60cb4007ada6) is stale for model turns.
3. §5 S0 "fixture provider reply (`:seon.ai/send!` as a value)": no such seam exists in the turn today (finding 1).
4. §2 "2.9 s" is mostly not the provider: on a warm stub turn Seon's own work is ~2.1 s and 3.65 GB, ~80% in the
   system turn's context re-acquisition. Against §4b's budgets (< 200 ms, < 5 MB, 1 commit, < 50 KB store) today is
   ~10×, ~700×, 6× and ~19× over.
5. The one-transaction mechanism the plan wants already exists for submissions: `step` handles a submission as
   `:resume` and `with-submission` prepends the run's open to the settlement (one commit). `generate-turn` uses the
   same trick for openings. The model path is the only one that commits open, capture, attempt and plan separately.
6. `step` is not only the model driver: MCP SCI evaluations, `virtual-turn!` and fixtures enter the same proc through
   its request port (`turn-step` arity 1, agent.clj:526; `submit-source!` :665). D2/D3 must keep that entrance.
7. `next-id` is not used by submissions (`submit-source!` mints `id/id`, agent.clj:748-755); its callers are
   `open-turn` and `system-turn` only. (map.md listed agent.clj as a caller; that comment is historical.)
8. Context capture contributions ARE installed on default (7 `seon.context.contribution` attributes); every model turn
   writes one (map.md §2e said they were not).
9. Answeredness and the episode count depend on the open being its own earlier commit: `latest-answering-turn-t`
   (:2909), `spent-runs` (:2650) and `close-call`'s covered messages (:444) all test `reply-size` tx `>` open tx. If
   open, plan and settlement become one transaction, the reply arm can never qualify and only the attempt arm answers
   wakes. S4 must re-derive these, not just delete commits.
10. The episode bound the plan keeps ("model calls per wake") does not see root as a waker today (finding 3).
11. `episode-exhausted!` runs after every non-released pass (turn.clj:5512), reading `turns-left` and
    `deferred-triggers`: two more derivations per pass on the model path.

## 6. Constraints the plan must respect

- The agent proc serializes everything for one agent: model turns, MCP SCI runs, virtual turns and openings all go
  through `step` (permit, backstop, park, reply port). Removing the permit/backstop must keep "one pass at a time per
  agent" and the reply-port answer the MCP bridge awaits.
- Boot recovery closes open runs with `recover-tx` (cluster.clj:2967-2972) and disarm closes an interrupted one
  (`record-interruption!` → `turn/recover-tx`, agent.clj:1462-1490). As long as any run can be open across commits,
  those stay; with one commit per turn there is nothing open to recover.
- `seon.repl/frame` prints "turns left: N of M" into every prompt from `turn/episode-runs` (repl.clj:26-77);
  `seon.problems` derives the stalled-agent problem from `episode-runs` and `deferred-triggers` (problems.clj:281-305,
  601-646); the transcript renders the bound (transcript.clj:1140, 2049); bootstrap's opening prose names
  `seon.turn/turns-left` and `my.agent/done` (bootstrap.clj:63, 125-136).
- `continuing-reply?` decides whether a closed reply continues (no disposition → another `:open`); deleting
  `my.agent/done`/dispositions needs another "stop" signal or the cap alone ends every episode.
- The capture-before-call ruling (2026-07-28) and the intent-commit design are owner rulings in docstrings; deleting
  commits 3 and 5 reverses them and needs the owner's explicit ruling recorded in the plan.
- `phase`, `settle-batch-refusal!` and `close-escaped-run!` are what keep a refused or throwing settlement from
  leaving a run open; with no open run the same totality must still hold (the value returns, a fault is recorded).
- The value printer must stay the only printer: `system-turn` stores `pr-str {:seon.repl/changes …}` as shown text
  (turn.clj:2188) and `record-attempt!` stores `pr-str` settings and usage (:4179-4181) — task c4da2c431c3d.

## 7. Slated for deletion in this path (D2, D3, and the D1 parts that run inside a turn), with callers

Production callers only (tests listed by file where many).

| item | defined | production callers | tests |
|---|---|---|---|
| D2 `open-turn` | turn.clj:4282 | `turn` `:open` dispatch (:5504) | via `turn` |
| D2 `open-call` / `open-tx` | :390 / :704 | `open-turn` (:4329), `system-run-plan` (:732, submissions), `generated-run-tx` (:861 → bootstrap `seed-tx`) | db_test, turn_work_test, turn_loop_test |
| D2 `next-id` | :519 | `open-turn` (:4315), `system-turn` (:2063) | loop_proof_test, render/web_test, web_debug_test |
| D2 `plan-call` / `plan-tx` | :642 / :495 | `call-turn` freeze (:4469) | call_preparation_test, cluster/turn_test, receipt_set_attributes_test, turn_disposition_test, turn_loop_test, cluster/agent_test, turn_test |
| D2 `close-turn` | :5238 | `turn` `:close` dispatch | via `turn` |
| D2 `close-tx` / `close-call` | :426 / :436 | `close-turn`, `call-turn` failed attempt (:4619), `generate-turn` (:5336), `close-folded-call` (:2370); doc ref eval/drive.clj:33 | data_shapes_test, turn_test, evaluate_sources_test, agent_test, blob_error_test |
| D2 `recover-tx` / `recover-call` | (turn.clj ~1479 / 1488) | `close-escaped-run!` (:5444), boot (cluster.clj:2970), `record-interruption!` (agent.clj:1480) | effect_test, turn_test, agent_test, cluster/turn_test |
| D2 `close-escaped-run!` | :5421 | `turn` catch (:5524) | turn_test, cluster/turn_test |
| D2 situations dispatch, `fold-or-close`, `resume-or-generate`, `next-ordinal`, `close-folded-call` | :5503, :2783, :2798, :2317, :2356 | `next-agent-work`, `settle-batch!` (:3543) | cluster/turn_test |
| D3 `await-turn-permit!` | :5600 | `step` (:5843) | — |
| D3 `arm-turn-completion-backstop!`, `record-turn-backstop-fault!`, `turn-completion-error`, `turn-completion-backstop-failure`, `await-turn-part!` | :5731, :5708, :5526, :5563, :4353 | `step`; `call-turn` (:4397); `evaluate-sources` (:4937); agent.clj:1429-1460 (`turn-completion-failure!`, `cancel-turn-backstop!`, disarm) | turn_backstop_test, turn_test |
| D3 `write-refusal-bound`, `write-refusal-error`, `record-write-refusal-fault!` | :5650, :5669, :5691 | `step` (:5940-5960) | turn_test |
| D3 `agent-dial`, `dial-absent` | :5568, :5586 | `await-turn-permit!`, `write-refusal-bound` | config_test, turn_test |
| D3 `episode-exhausted!` | :5382 | `turn` (:5513) | — |
| D3 `turns-left`, `episode-runs`, `spent-runs`, `max-episode-runs`, `outside-wake-t` | :2706, :2677, :2650, :2688, :2612 | `opening-deferred?`; `seon.repl/frame` (repl.clj:26-77); problems.clj:305; transcript.clj:1140, 2049; bootstrap.clj:63 (prose) | ~20 test files (turn_work_test, loop_proof_test, turn_continue_test, run6_stall_test, config_test, render/value_test, …) |
| D3 `opening-deferred?`, `deferred-triggers` | :2715, :2767 | `next-agent-work`, `episode-exhausted!`; problems.clj:301; doc ref wake.clj:260 | turn_work_test, agent_test, cluster/turn_test |
| D3 `unanswered-wakes`, `unanswered-triggers`, `latest-answering-turn-t` | :2936, :2989, :2909 | `next-agent-work`; problems.clj (unanswered-triggers); bootstrap.clj:136 | turn_work_test, loop_proof_test, schedule_test, no_provider_test, concurrency tests |
| D3 `my.agent/done` disposition | src/my/agent.clj:23; `disposition` (turn.clj:3116), `early-disposition?` (:4638) | `publish-row` (:4753), `evaluation-terminal-data`, `continuing-reply?` (:2810); transcript renders | turn_disposition_test, turn_loop_test |
| D3 `record-interruption!` | agent.clj:1462 | `stop-graph!`/disarm (agent.clj:1563) | — |
| D1 in-turn: `system-turn`, `declared-sources`, `latest-evaluations`, `system-plan`, `generated-read-fault`, `read-only-evaluation?`, `code-currency`, `unit-read`, `generate-turn`, `append-generated-*` | :2030-:2237, :1679-:2028, :5260, :798-:845 | `open-turn`; `generate-turn` (bootstrap's `:generate` run); render/web.clj (debug views); db.clj, error/refusal.clj name it | ~19 test files |
| D1 in-turn: `cluster.prompt/prompt` + context capture | prompt.clj:422; `context-capture-tx` | `call-turn` (:4511, :4537) | prompt_test, turn_loop_test |

## 8. S0 baseline probe (design only)

**What it runs.** One model turn through the real proc, on a disposable branch prepared by the harness: an agent with
an assigned scratch namespace, an overlay `{:no-provider false :max-episode-runs 2}`, and a provider supplied as a
value. Today the only provider path without a source change is the agent overlay `{:model "s0-fixture"
:endpoint "http://127.0.0.1:<port>/v1/chat/completions" :no-auth true}` (an unregistered model keeps the overlay
endpoint) plus a JDK `HttpServer` in the test answering one SSE stream; this lane's probe proves it works on
`722f110df`. A value seam (`:seon.ai/send!` carried from the handle into `call-turn`'s request) needs a src change
and belongs to the slice that adds it. The wake is an outside wake (`message/inbound-tx`, as the loop-pass patch did),
not a root message, so the bound refills (finding 3). The fixture reply is fixed text: one prose line, a `defn`
(exercises program facts) and a call (`(add-one 41)` → 42).

**What it waits for.** What production waits for: the turn proc's Flow report with
`:seon.turn.loop/outcome :closed` (the step's `::flow/report`), bounded at 10 s, then the next pass's
`:seon.turn.loop/idle? true` report.

**What it asserts (behavior, as data).** The turn has one attempt row; its two evaluations exist with values (the
var for `add-one`, `42`); the agent's next context contains both exchanges. No timing or commit count is asserted:
those are recorded.

**What the measurement form records**, per stage boundary (each commit, the provider request, the final idle report):

| key | how |
|---|---|
| `:stage` | the commit's `:seon.db.transaction/operation` (`d/listen` on the branch, or the tx entities between the before/after basis) |
| `:ms` | nanoTime at each listener callback and at the fixture server's request receipt; Seon-only = total − provider response time |
| `:alloc-bytes` | `getTotalThreadAllocatedBytes` deltas, plus `getThreadAllocatedBytes` per thread over the window (the pass runs on a ForkJoinPool worker) |
| `:armed-calls` | `seon.profile/begin` at start, `explain` at end (inclusive growth per armed function); subtract begin's own ~18.6k until 561d2f7ee288 is fixed, or count from `explain` growths only |
| `:datoms` | `(count (:tx-data report))` per commit |
| `:store-bytes` | files and bytes under the test store root modified since start |
| `:prompt-chars` / `:prompt-tokens` | request body's user content length; tokens = chars / `:seon.config.ai/chars-per-token-prior` (3.2) |
| `:commits` | the ordered operation list |

Baseline to expect on today's code (from §3a, warm): 6 commits, ~2.1 s Seon work, ~3.6 GB, ~400k armed calls,
~0.94 MB store. The form is the scratchpad probe made reusable (a function in `test/`, not a def in default's JVM).

## 9. Implications for the plan

- S4's "1 commit per reply" can reuse the existing submission mechanism (`submission-open-tx` + `with-submission`)
  rather than a new writer; but answeredness, `spent-runs` and close coverage must be re-derived (§5.9).
- S1 is the larger win for the budgets: the system turn (commit 1) is ~80% of time and allocation; deleting it removes
  the per-turn context re-acquisition. Re-measure `acquire-program!` after S1 before attributing anything to S4.
- The context capture stores every prompt whole (quadratic growth); D1 removing it is also a storage fix.
- S0 needs a provider seam decision first: an HTTP fixture endpoint works today; a `send!` value is cleaner but is a
  src change.
- The episode bound must count root's messages as wakes (or count calls per wake) before D3 replaces it.
- DeepSeek invented a result inside a comment (`;; => 42`) and claimed verification; S2's reader flag for
  exchange-shaped maps would not catch a comment.

## 10. Verification limits

- One fixture reply shape and one DeepSeek turn; numbers are single runs, not distributions.
- Per-stage allocation is whole-JVM over the window; per-thread totals are reported only for the whole window.
- Stage boundaries are commits; work between commits (e.g. provider vs attempt recording) is split only through
  the profile.
- Store bytes count every file touched in `data/store` during the window, including other branches' writes (default
  was idle) and before this eval's own settlement.
- UNVERIFIED: `seon.problems/form-problem`'s routing rule (finding 4 observed, cause not read); where the JVM reply
  resolves eids to lookup refs (finding 5 observed, owner not located); `install-evaluated-rows!` cost in isolation.
