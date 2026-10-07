# I-Q14: disarm, join and backstop proofs, and the deadline path to `stop-graph!`

Notes for S6-07 (Q14 in `split/reconciled-plan.md`). Read at the working tree on `refactor/agent-platform`.
`src/seon/cluster/agent.clj`, `src/seon/cluster.clj` and `test/seon/cluster/agent_test.clj` are unchanged against HEAD;
`src/seon/turn.clj` has uncommitted edits from another lane, but the function lines cited below match
`git show HEAD:src/seon/turn.clj`. No code ran; this is reading plus `rg`.

Plan drift: the plan cites `turn.clj:5635-5824`. At HEAD the owners are `turn-completion-error:5593`,
`await-turn-permit!:5667`, `write-refusal-bound:5717`, `arm-turn-completion-backstop!:5798`, `step:5856`. The
`agent.clj` and `agent_test.clj` lines in the plan hold (test lines are the `deftest` metadata line; the name is two
lines later).

## 1. The deadline path today (caller to `.awaitTermination`)

Two entrances reach `disarm!` and they differ in who owns the deadline.

**A. Cluster stop (carries a deadline).**
`seon.cluster.boot/stop!` (`boot.clj:557`, called by `bin/seon` stop and `eval/drive.clj:386`) calls
`host/release-children!` then `seon.cluster/disarm-agents!` (`cluster.clj:3490`). `my.agent/retire-branch!`
(`my/agent.clj:~190`, reachable from MCP `eval_clj` code) calls `host/retire!` (`host.clj:~200`), which calls
`host/stop!` (`host.clj:172`), which calls `disarm-agents!` on the host.
- `disarm-agents!` reads `:seon.config.agent/turn-completion-backstop-ms` from `config/effective` ONCE and builds
  `bound` with `:seon.await/deadline-nanos = now + backstop-ms` (`cluster.clj:3524-3529`).
- That one deadline is shared by the view stop, armer quiescence, every agent's `disarm!` (passed as
  `:seon.await/deadline-nanos`, `cluster.clj:3558-3561`) and the cluster graph join (`:3568`).
- `disarm!` (`agent.clj:1465`) forwards the key in the request map; `stop-graph!` (`:1508`) destructures it and
  hands `(or deadline-nanos (+ now (* 1e6 timeout-ms)))` to `seon.flow/join-graph!`.

**B. Release of an owned routing (no caller deadline).**
`release-context!` (`agent.clj:927`) calls `stop-owned-graphs!` (`:904`), which calls `disarm!` with only
`{:seon.agent/id :seon.agent/routing}` (`:921`). `arm!` also calls `disarm!` bare when a previous executor has
terminated (`:1269`). Every test teardown (`test_support.clj:1230`, ~40 test sites) is bare too. So `stop-graph!` falls
back to a per-agent timer started inside `stop-graph!`, from the dial in the agent's cluster handle
(`(:seon.config.agent/turn-completion-backstop-ms (:seon.turn.loop/cluster entry))`, set at arm time from
`config/effective` merged with the agent overlay, `agent.clj:1306-1308`).

**What actually waits.** `seon.flow/join-graph!` (`flow.clj:136`) calls `.shutdown` on the graph's executor, then
`await/await!` with a `blocking-deref` over `.awaitTermination` (`await.clj:117-150`: it spends
`remaining-ms deadline`, floor 0). It returns nil on exit or the typed `:seon.await/timeout-error`. The executor is
left shut down, not terminated, on timeout.

**MCP caller's own deadline.** `eval_clj` SCI mode awaits the run reply in `evaluate-source!` (`agent.clj:~790`) under
the same dial (`:seon.config.agent/turn-completion-backstop-ms`, positive int required). That is a different wait
(the run's reply), not a disarm wait. Nothing on the MCP path calls `disarm!` directly; MCP reaches it only through
`my.agent/retire-branch!` / test/gate `release-context!` evaluated as code. The spec's "caller's lifecycle deadline"
(`docs/seon/specs/agent-loop.md` 4.8) therefore has no MCP-supplied value to forward today: entrance A computes its
own from the dial, entrance B has none.

### Dial consumers (what S6-07 must convert or delete with the backstop)

| Consumer | Site | Role |
|---|---|---|
| `await-turn-permit!` | `turn.clj:5667` | waits for the idle permit on the completion channel; throws `turn-completion-backstop-failure` |
| `arm-turn-completion-backstop!` | `turn.clj:5798` | executor task: fires fault + `stop-request!` when the active pass exceeds the bound; `await-part` extends it per provider/eval part |
| `turn-completion-error` / `turn-completion-backstop-failure` | `turn.clj:5593`, `~5700` | builds the typed failure; also used by `turn-completion-failure!` |
| `step` publish/cancel | `turn.clj:~6040-6090` | cancels the bound and compare-and-sets `turn-backstop-state` |
| `arm-graph!` | `agent.clj:1304-1354` | creates `turn-backstop-state` atom, puts the dial and `:seon.fault/stop-request!` on the handle |
| `turn-completion-failure!`, `cancel-turn-backstop!` | `agent.clj:1402`, `:1422` | disarm-side fault + throw; cancels a bound left armed by an interrupted transform |
| `stop-graph!` | `agent.clj:1508` | the timer fallback (entrance B) and the bound's config evidence |
| `evaluate-source!` | `agent.clj:~790` | MCP run-reply await (separate wait; keeps a dial unless Q14 names a replacement) |
| `disarm-agents!` | `cluster.clj:3509` | the shared deadline (entrance A) |
| `stop-work-launcher!` | `flow.clj:734` | launcher drain + join deadline, also from the dial |
| `flow-workload-attributes` | `flow.clj:621` | lists the dial for the launcher |
| schemas | `seon.config.agent.edn:15`, `seon.await.edn:35`, `seon.flow.edn:67` | dial declaration, await config-attribute enum, flow config key |

Deleting the "backstop/parking/permit" without a replacement for the last four rows leaves them with no bound, so S6-07
must either keep the dial name for the await/launcher owners or give each a different declared deadline. Q14 only
resolves disarm/join.

## 2. The proofs to convert

All in `test/seon/cluster/agent_test.clj` unless noted; each is gated by `with-connection` (a throwaway branch).

| Proof | Line | What it pins | Fate under Q14 |
|---|---|---|---|
| `disarm-interrupts-an-in-flight-turn-and-records-it-interrupted` | 843 (name 845) | Blocks `prompt/prompt` (with-redefs) in an interruptible latch wait; `disarm!` with NO deadline (entrance B) under a 5000 ms dial returns nil well inside the bound; asserts interrupt delivered, no open turn, `recover-tx` closed it, no fault for the wrapped interrupt, route dropped, channel closed. Also runs under another branch's custody. | Stays, converted: pass an explicit `:seon.await/deadline-nanos` instead of the dial; assertions on exit, no open turn, no fault unchanged. Needs the `with-redefs` of `prompt/prompt` replaced by an injected blocked host call (CLAUDE.md bans Var redefinition) |
| `disarm-has-a-declared-loud-turn-completion-backstop` | 923 (name 925) | Blocks `prompt/prompt` uninterruptibly (`acquireUninterruptibly`), dial 100 ms; `disarm!` throws an ex-info whose data names operation `:seon.agent/disarm`, the dial value, agent id, run id; entry stays armed; `arm!` refuses with `:seon.error/expected :seon.agent/graph-exited` while the executor lives; after the host call is released, `awaitTermination`, restart succeeds, second disarm is idempotent. | Convert to the retained actual-exit bound, not timer retirement. The caller deadline replaces the dial value in the failure evidence (the `:seon.config.agent/turn-completion-backstop-ms` assertion goes). It currently asserts `(is (nil? (:seon.fault/recorded ...)))`; check what the typed error carries once `fault/fault!` is dropped |
| `disarm-drops-the-route-before-closing-it` | 793 | `with-redefs [async/close!]` observes the route is already absent when the wake channel closes. Order is load-bearing (`disarm!` docstring, `fenced-route?`). | Must lose the `with-redefs` of `clojure.core.async/close!`. Candidates: assert the same ordering from data a listener on the routing atom records (an `add-watch` on `routing` sees `:armed`/`:channels` dissoc before the channel is closed), or probe `wake/route!`/`fenced-route?` (the observable consequence, test `agent_test.clj:~770-791` already has the fence half) from a thread that races the stop. The pure ordering claim needs a seam; see section 4 |
| `release-joins-every-owned-graph-before-it-closes-the-branch` | 1020 | `release-context!` with dial 100 ms refuses on a stuck graph, still joins the idle one. Entrance B. | Not named by the plan, but it binds on the dial: its 100 ms is the same `provider-backstop-config`. Needs the same conversion; add to S6-07's list |
| `turn-start-has-the-same-declared-loud-completion-backstop` | 1178 | Tests the `await-turn-permit!` failure path (permit never published, 100-ish ms). | Deleted with the permit |
| `a-failed-turn-transform-releases-its-permit-and-retires-its-backstop` | 1215 | `turn-backstop-state` retirement after a thrown step. | Deleted with the state atom |
| `test/seon/turn_test.clj` `a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole` | 2102 | Calls private `arm-turn-completion-backstop!` with a 100 ms bound and asserts the fault evidence and a single stop request. | The plan converts it "to the remaining bound owner"; the thing it tests (a fired bound requests the stop) dies with the bound unless S6-07 keeps one |
| `test/seon/turn_backstop_test.clj` (3 deftests: `cancelled-completion-observer-releases-every-existing-waiter`, `completion-observer-uses-the-admitted-provider-and-evaluation-parts`, `a-thrown-step-cancels-its-completion-backstop`) | 16, 41, 71 | Private `arm-turn-completion-backstop!` calls | Not in the plan's list: delete with the arm function, or convert; add to S6-07's file list |
| `test/seon/loop_proof_test.clj` "a lost turn permit faults at the declared completion allowance" | ~208-243 | Stores dial 100 ms, withholds the permit | Deleted with the permit; also `:seon.config.agent/turn-completion-backstop-ms 600000` writes at 210/230 |
| `test/seon/cluster/turn_test.clj` | 1661 | Constructs a handle with `:seon.agent/turn-backstop-state (atom nil)` | Drop the key |

Other references to the dial that block a delete (setup only): `config_test.clj:871`, `config_application_test.clj:40`,
`effect_test.clj:377,468`, `flow_test.clj:48,163,499,544,558`, `env_test.clj:127`, `flow_launcher_error_join_test.clj:24`,
`context_blocks_fixture.clj:101,236`, `turn_continue_test.clj:38`, `my/agent_test.clj:18,28`, `loop_proof_test.clj:240`,
`agent_test.clj:175,186,216`. Each passes the dial either as an await `config-attribute` (the await owner still needs
one) or as setup the agent reads; all convert in the same commit.

Bare `disarm!` test callers (~40, `agent_call_edges_test`, `program_test:1436`, `rereads_test`, `loop_proof_test`,
`turn_test:722,758`, etc.) rely on the entrance-B fallback. Q14 must decide what a bare call's deadline is, or they all
break at once (section 3).

## 3. Findings from the closure that the plan did not state

1. **Entrance B has no caller.** `stop-owned-graphs!`, `arm!` and tests call `disarm!` with no deadline. "The caller's
   deadline passes explicitly" leaves `:seon.await/deadline-nanos` optional today. If it becomes required, ~45 call
   sites change; if it stays optional, the fallback needs a source other than the dial that is being deleted. Options
   for root (not decided here): (a) require it, supplied by a small helper from the caller's own configured bound
   (the await owner already has `:seon.await/config-attribute` plumbing), (b) keep a single dial under a new name for
   lifecycle only (CLAUDE.md "new names for changed semantics"). This is a Q14 sub-question the recommendation does not answer.
2. **Shared deadline starves later agents.** `disarm-agents!` passes ONE deadline to every agent in sorted order
   (`cluster.clj:3557-3561`); the first stuck agent consumes the whole budget and every later agent's join runs with
   `remaining-ms 0`, so an agent that would exit in 5 ms is reported as timed out. Its `flow/stop` and interrupt are
   still issued before the join, so the flow actually stops; only the report is wrong. Unmeasured; a one-agent probe on
   a SCI branch would give the number.
3. **`disarm-agents!` does not answer a missed agent join, it throws.** Its docstring says "A join that misses its bound
   answers the await owner's timeout value naming the missing exit", and its contract is
   `[:or :nil :seon.await/timeout-error]`. But `stop-graph!` throws `turn-completion-failure!`'s ex-info
   (`agent.clj:1402,1523-1530`) and `disarm-agents!` has no `try` around `cluster.agent/disarm!` (`cluster.clj:3558`).
   So only the view, armer, and cluster-graph joins answer a value; an agent join throws through `boot/stop!` and
   `host/stop!`. Q14's "return a typed error naming the missing exit" changes `disarm!`'s contract
   (`[:=> ... :nil]` today) to a union; `disarm-agents!`, `stop-owned-graphs!`, `arm!` and all callers then handle a
   returned value. Not filed as a task by this lane (notes slice, no default write); candidate name
   `seon.cluster/a-missed-agent-join-throws-instead-of-answering`. Existing related task:
   `seon.cluster/a-missed-quiescence-keeps-the-route-listener` (`resources/seon/tasks/seon.cluster.edn:25`).
4. **Disarm also records a fault** (`turn-completion-failure!` calls `fault/fault!`, `agent.clj:1402-1420`). Under the
   "errors cut" that recording moves to the entrant boundary. S6-07 should not keep a second recorder at disarm.
5. **A stuck turn loses its detector.** Today the active-turn bound (`arm-turn-completion-backstop!`) is the only thing
   that turns a pass blocked in a host call into a fault plus a stop request while the agent is running. Without it a
   stuck pass is discovered only when something calls `disarm!`. The plan accepts this ("the MCP caller's deadline still
   answers 'still running'", agent-loop 4.8); the proof `a-timed-out-pass-keeps-ownership-until-it-exits` covers
   ownership after a deadline, not detection. Flag for root that detection is dropped on purpose.
6. **Executor state after a missed join.** `join-graph!` shuts the executor down before waiting. A retry of `disarm!`
   after the host call exits works because `stop-graph!` re-enters `join-graph!` (a repeat `.shutdown` is a no-op) and
   `arm!` branches on `.isShutdown`/`.isTerminated` (`agent.clj:1257-1273`). That ownership is the "retain resources
   until exit" the plan asks for and it already exists; S6-07 deletes only the timer, not this.
7. **Skill/doc drift to fix with S6-07.** `disarm!`, `stop-graph!`, `disarm-agents!` and `evaluate-source!` docstrings
   name the backstop dial; `agent-loop.md` 4.8 states the same drift ("the deleted backstop" while the dial still
   exists). Docstrings are source, so they convert in the S6-07 commit.

## 4. Seams the converted proofs can use (no `with-redefs`)

- A blocked host call without redefining `prompt/prompt`: the agent's SCI evaluation of a form that blocks on a host
  latch or semaphore passed in through the test's SCI context (the same mechanism `seon.test` uses for host values),
  reached through `outside-trigger!`/`submit-source!`. Interruptible variant: a latch `await`; uninterruptible: a
  semaphore `acquireUninterruptibly`. This is what `a-timed-out-pass-keeps-ownership-until-it-exits` should use.
  Not probed here (notes slice); S6-07 must prove it on a SCI branch before relying on it.
- Route-before-close ordering without redefining `async/close!`: an `add-watch` on the routing atom recording, per
  state, whether the wake channel is `async.impl/closed?`. The final swap (`agent.clj:1523-1535` area) removes the
  entry in one `swap!` and the close follows, so the watch sees "armed gone, channel open" then the later close is
  visible by polling the channel; assert the watch history, not the close call. This is a hypothesis, to be checked
  against the order in `stop-graph!` when converting.

## 5. Design check

Held against the real code: the plan's recommendation (pass the caller's deadline explicitly, return a typed error,
keep resources, no new timer) is already half implemented: `disarm!` and `stop-graph!` accept
`:seon.await/deadline-nanos` and `cluster.clj` passes one. What does not hold as stated: (1) the "caller" of the
deadline exists only on the cluster-stop entrance, not on release or arm; (2) `disarm!` does not return a typed error
today, it throws; (3) four non-disarm consumers of the dial and four test files beyond the plan's list depend on the
same deletion. None of this contradicts the recommendation; they size S6-07 larger than its four listed tests and add
the entrance-B decision to Q14.

RESET NEEDED: no. Default JVM untouched.
