---
type: research
status: complete
tags: [research, agent, flow, pod, database]
---

# Parent capability and child lifecycle contract — 2026-07-15

## Decision

The next process-containment implementation strengthens the one `seon.eval`
service with one pod-owned, non-detached, disposable Node child per eval batch.
The pod retains one batch-owner handle and permits at most one immutable parent
task capability to be active in it. The child is a calculator: it may return
bounded candidate data and request an explicitly granted operation, but it
never selects actor, database coordinate, run, turn, eval receipt, transaction
provenance, or publication authority.

This decision consumes the ordinary-startup ownership result from
`fbb8c399`: admission to spawn and publication of the exact owned handle are one
serialized phase, cleanup owns only what that invocation started, and an
inverse is not complete until the child is absent. It does not call the
Babashka owner from the pod or copy its durable process-record mechanism. The
operator owns detached watcher/writer/pod groups; `seon.eval` owns attached,
process-local execution children inside the pod.

The ordinary-startup proof is necessary but not sufficient. It proves SIGINT
unwinds a live recorded group leader. It does not yet prove that a child in the
pod's group is absent after the Node group leader itself dies. Unit 1 must
publish and prove that subtree inverse, or the hardened backend must provide a
measured parent-death guarantee, before a production eval cutover or restart
claim. The retained native-branch interruption row remains open for the same
reason. Source implementation waits on that settled ownership boundary.

## Scope and observation

This audit read the target [[../../../seon/architecture/agent-runtime]], the
active [[../roadmap]], the existing process-death and receipt audits, current
eval/run/turn/recovery/client/agent-host source and tests, the newly integrated
ordinary-startup ownership commit, current Inspect task/scorer/solver code, and
the exact dependency source below. It made no application-source change, ran no
hostile child, and claims no live containment evidence.

The default worktree also contains another lane's uncommitted eval/repl/test
changes. This report did not inspect them as completed behavior and did not
modify them.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source read and contract consequence |
|---|---|---|
| Node.js | installed `v26.4.0`; release commit `2022edf3e32ce28ee08b17f8566243a090dacd95`; V8 `14.6.202.34-node.21` | Exact official tag source was sparse-mirrored at `tmp/reference-node-v26.4.0`: `doc/api/child_process.md`, `worker_threads.md`, `permissions.md`, `cli.md`, `lib/child_process.js`, `lib/internal/child_process.js`, and `lib/internal/worker.js`. `kill()` only sends a signal; `close` follows process exit plus stdio closure; PID reuse makes a delayed naked-PID signal unsafe; AbortSignal uses the same signal path; worker resource limits exclude external data. Retain the `ChildProcess` object, wait for `close`, and use a real OS/container ceiling. |
| ClojureScript self-host | `1.12.145`; official tag `r1.12.145`, commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` and `src/seon/eval.cljs`: compiler/analyzer definitions are process-local. Reconstruct only committed declarations; never replay prior effectful forms. |
| Datahike | maintained commit `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike/src/datahike/db.cljc`, `transaction.cljc`, and current `seon.db`: the parent uses one immutable database value, one complete expected coordinate, and writer-serialized run/receipt CAS assertions. No connection or writer/feed socket enters the child. |
| Malli | selected `0.20.0`; reference commit `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc` and current receipt schemas: every frame is a closed registered data shape. Unknown authority fields are rejected before an operation owner runs. |
| SCI | selected `0.13.53`; reference commit `b4917436550c857a18b8f6a4a8b5b26356acc2c4` | `reference-code/sci/src/sci/interrupt.cljc` and current render/eval boundaries: useful cooperative interruption is not native-JS process containment and is not the hard boundary. |
| Piscina | reference commit `23a6c2e94735216c6978679fe7b8ea0b5666683b`; not selected | `reference-code/piscina/src/worker_pool/index.ts`, `test/abort-task.test.ts`, and `test/test-uncaught-exception-from-handler.test.ts`: remove abort listeners, reject every task on termination, and replace a failed execution resource. Its worker-thread boundary remains insufficient for external memory, so do not add Piscina. |
| Babashka process | Babashka `1.12.212`; `babashka.process` `0.6.25`, commit `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `reference-code/babashka-process/src/babashka/process.cljc` plus `script/seon/dev/process.clj`: shutdown cleanup needs explicit ownership and an awaited inverse. `fbb8c399` proved the shutdown hook cannot depend on babashka.process's already-stopped executor. The pod reuses the law, not this JVM implementation. |
| Inspect AI | reference commit `05322696a0f784ec399ef6abbafd3d2a250ea9cc` | `reference-code/inspect-ai/src/inspect_ai/util/_sandbox/environment.py`, `_eval/task/sandbox.py`, `util/_sandbox/exec_remote.py`, and Docker cleanup: cancellation shields cleanup and a remote process handle has idempotent `kill`, but interrupted Docker sample cleanup may defer to task shutdown and Compose down has a long outer timeout. Inspect is proof orchestration, not Seon's runtime reaper. |
| Seon Inspect | current branch `src-inspect-ai/` | `solver.py`, `planning.py`, `tasks/long_term_planning.py`, and `oracle_scorers.py`: capability scorers already separate pod timeout/core failure from model correctness and derive plan evidence from database facts. A containment task needs a dedicated deterministic oracle because an expected child timeout/death is success, not the existing excluded `solve_timeout` flake. |
| Ordinary pod startup | `fbb8c399`, docs follow-up `040f6432` | `seon.dev.process/with-startup-ownership` serializes shutdown admission with detached spawn and managed-record publication, tracks only newly started ids, and reverses the existing `stop!` inverse. Real SIGINT proof is 31 tests/153 assertions. It does not yet prove a child is reaped when the pod group leader dies. |
| Eval receipts | `defb8014` | `src/seon/eval/internal.cljs`, `test/seon/eval/receipt_test.cljs`, and `src/seon/runtime/recovery.cljs`: `:running` plus one CAS terminal transition and restart interruption exist as pure groundwork. The live eval path still records after computation and does not use the start receipt. |

## Current source boundary

The implementation has useful pieces, but no parent-child lifecycle exists:

- `seon.eval/eval` races an in-process Promise. Its own error text correctly
  says a timed-out form continues in the background. A synchronous loop can
  prevent the timer from firing at all.
- `seon.eval/record-eval!` allocates an eval identity only after computation.
  Its transcript-first tee failure fallback may allocate a second identity and
  commit an eval without program facts. That is incompatible with a child whose
  uncommitted analyzer state must be discarded.
- `seon.eval.internal/start-tx-data` and `terminal-tx-data` provide a running
  receipt and one terminal CAS, and recovery interrupts running receipts. They
  are not wired into `eval-batch!`.
- `eval-batch!` returns eval ids in process order, but cardinality-many turn
  membership is not durable order. [[../../../seon/issues/multi-form-eval-order-is-not-durable]]
  must land a contiguous per-turn position on the start receipt before the
  child transition can prove exact once-in-order execution.
- `result/<id>` values, analyzer definitions, unresolved Promises, and output
  capture are process-local to the pod. Opaque handles cannot cross framed IPC.
  Same-batch accepted definitions must remain usable in the one batch child;
  cross-batch survival comes only from committed program facts. A pending
  Promise ends with its batch unless the existing result contract is explicitly
  changed and proven; it is not silently moved into a second registry.
- `seon.db/with-agent` and explicit agent ids are ergonomic scopes, not
  credentials. Agent code can select a foreign ALS value today. Only a
  parent-side handler whose actor comes from its retained capability can close
  [[../../../seon/issues/plan-reopen-cross-agent-authority]].
- `seon.worker-eval` and `src-inspect-ai`'s `_LineServer` are persistent oracle
  subprocesses with different compiler/database contracts. Neither may become
  application eval or its lifecycle owner.
- `seon.agent.runtime` owns per-agent wake/compiler reconstruction, and
  `seon.client/stop-runtime!` owns the pod inverse. A child adapter must be a
  private subordinate of `seon.eval` and register its one close operation with
  those existing owners; it must not create a host registry, daemon, port, or
  database job table.

## One immutable parent task capability

One task capability is minted only after the parent has committed that form's
running receipt. It is a private, closed immutable value captured by the batch
owner's serial child handler; it is never stored or serialized wholesale:

```clojure
{:seon.eval.child/agent-id "..."
 :seon.eval.child/run-id "..."
 :seon.eval.child/turn-id "..."
 :seon.eval.child/eval-id "..."
 :seon.eval.child/position 1
 :seon.eval.child/read-coordinate
 {:seon.db.coordinate/database-id #uuid "..."
  :seon.db.coordinate/branch :db
  :seon.db.coordinate/commit-id #uuid "..."
  :seon.db.coordinate/t 536870912}
 :seon.eval.child/artifact-digest "..."
 :seon.eval.child/backend-digest "..."
 :seon.eval.child/absolute-deadline #inst "..."
 :seon.eval.child/grants #{:seon.eval.child/read
                           :seon.eval.child/transact}}
```

The batch-owner closure additionally contains the exact `ChildProcess` object
and its stdin/stdout/stderr objects plus the stable agent/run/turn/coordinate/
artifact/deadline/grant scope from which each task capability is derived. Those
opaque handles are part of possession of the owner but are not values in a
schema, database, frame, or status API. A random frame correlation id is
routing data only. Knowing it, an eval id, or a PID conveys no authority.

Each capability is immutable and exactly one is in flight. The batch owner
retires it only after its terminal writer acknowledgement or abnormal disposal,
then derives the next capability from the next committed positioned receipt.
One invocation-local close latch, listener set, timer, bounded frame buffer,
and terminal Promise coordinate disposal; they are allowed process-local
artifacts, not authority and not a durable task registry. The database receipt
CAS decides the durable terminal winner. The close latch only prevents a late
frame from reaching the writer while that CAS is pending.

The parent validates every child frame against one closed schema, then supplies
agent, run, turn, eval, position, coordinate, deadline, artifact, grants,
transaction user, and transaction process from the capability. A frame that
contains any of those fields is malformed rather than merged. The child may
send ordinary operation data, such as a Datalog query or transaction payload,
only for an operation in the immutable grant set. The parent calls the existing
`seon.db` or `my.*` owner under capability-derived provenance and acknowledges
only the committed writer response.

## Durable facts versus process-local handles

| Durable database truth | Process-local child-owner artifact |
|---|---|
| Agent, current run pointer, run status/deadline, turn, exact turn read coordinate, and contiguous eval position | `ChildProcess` object identity, stdin/stdout/stderr objects, frame decoder buffer |
| Eval id, source, narration, dispatch namespace, start instant, and `:seon.eval/status :running` committed before dispatch | Child PID as diagnostics only; exact launch argv/environment; artifact/backend descriptors retained in the closure |
| Exactly one terminal eval status plus accepted bounded result/output/error/duration and program tee facts | One close latch, deadline timer, TERM grace timer, once-only listeners, terminal Promise, peak-resource samples |
| Transaction provenance stamped by the parent; complete expected coordinate; run and receipt CAS assertions | Current compiler/analyzer instance and same-batch `result/<id>` handles inside the child |
| Restart recovery anchor and the one atomic run/turn/eval interruption transaction | Nothing reconstructable after pod death; stale handles are discarded, never replayed |

Do not transact child PID, launch token, pipes, listener state, timer state,
backend handle, compiler instance, or a stored lifecycle phase. Important
recovery truth is already the running eval receipt joined through turn and run.
Artifact/backend identities belong in retained test/status evidence and the
batch-owner launch descriptor; copying them onto every eval would create
drifting authority.

## Launch and publication contract

The correctness-first unit is one child per eval batch, with at most one form
in flight. The child reconstructs committed program state once, then preserves
accepted same-batch definitions locally. The parent executes this order:

1. Freeze one current database value and derive the run, turn, position,
   artifact/backend identities, grants, and absolute deadline.
2. Enter one monitor-like launch phase shared with shutdown. Shutdown either
   closes admission before spawn or waits until the exact `ChildProcess` handle
   and every listener are retained by the batch owner.
3. Spawn a non-detached child with bounded pipes, empty/minimal environment,
   exact artifact arguments, Node permissions, and the selected hard backend.
   A spawn error produces no eval receipt because no form was dispatched.
4. Reconstruct committed program state without opening a task capability. Then,
   for each form, commit its start receipt with the run CAS, complete expected
   coordinate, and contiguous position. If that transaction loses, dispose the
   child and do not dispatch the form.
5. Derive that form's immutable task capability from the retained batch scope
   and committed receipt, then send one closed task frame. Each accepted
   capability response commits and is acknowledged before the child continues.
   Only one form and one task capability may be active.
6. On an ordinary language/runtime error returned through the valid protocol,
   commit `:error`, acknowledge, and allow the existing non-fail-fast batch to
   start the next form. On process/protocol/publication uncertainty, interrupt
   the current receipt, discard the child, and stop the batch; later forms stay
   absent.
7. After the final committed form, close input and reap the child before the
   batch returns. There is no idle warm slot in the first implementation.

The implementation must strengthen `seon.eval` in place. During cutover there
is one selected backend per task, not a fallback that reruns an uncertain child
form in-process. Once the complete matrix passes, delete in-pod arbitrary
execution from the production path; keep only the same public function surface
and data envelopes.

## Cancellation, kill, and reap contract

The pod is the sole deadline owner. It takes the minimum remaining bound
selected by the run/turn/task policy and places one absolute instant in the
capability. The child may observe it but never renew it. The existing
`race-timeout` remains the one result selector; its timeout callback invokes
the owned close capability. TERM-to-KILL grace is inverse cleanup, not a second
behavioral watchdog.

Every terminal cause follows one serialized path:

1. Close process-local frame admission so no later request can reach a writer.
2. Close or supersede durable run/eval authority when the lifecycle cause
   already owns that transaction; otherwise retain the running receipt until
   the parent-observed terminal decision.
3. End stdin/capability transport and request cooperative cancellation where
   one exists.
4. Send `SIGTERM` through the retained `ChildProcess` object, wait a configured
   short grace for `close`, then send `SIGKILL` through that same still-live
   handle if necessary.
5. Wait for `close`, which Node emits only after exit and stdio closure. Remove
   every listener, clear both timers, destroy remaining pipe references, and
   prove no child/backend resource remains.
6. CAS the still-running eval to the one parent-observed terminal status and
   bounded diagnostic if an earlier lifecycle transaction did not already do
   so. A lost CAS is reread as already terminal, never retried with a new id.
7. Stop the batch after abnormal process/protocol/publication death. Do not
   allocate a receipt for an undispatched entry.

Calling AbortController, observing `child.killed`, receiving `exit`, or seeing a
dead PID is insufficient. Node defines `killed` as signal delivery, documents
PID reuse, and allows stdio to remain open after `exit`; the exact retained
handle plus `close` is the completion proof.

Runtime stop orders child admission close and complete reap before compiler,
projection, replica, or database release. A pod crash cannot execute that
inverse. Unit 1 must therefore prove one of these with the selected backend:

- the external supervisor safely drains every member of the pod-owned process
  group even when its recorded leader is already dead; or
- the child/backend has a measured parent-death mechanism and the operator
  proves the backend resource absent before replacement readiness.

Until that proof exists, a dead pod plus an unobservable child is degraded,
not ready, and no new pod may claim the same task/artifact boundary.

## Crash and restart reconstruction

After an unexpected pod exit, no process-local child capability is
reconstructed. The operator first proves the old execution subtree absent.
Then `seon.runtime.recovery/recover!` reads one frozen database value and, in
its existing root/boot transaction, CAS-fences affected run pointers, closes
open runs as crashed, interrupts running turns, and changes every running eval
receipt to `:interrupted`. An immediate second pass writes nothing.

Committed terminal evals and accepted program facts remain unchanged. A
running receipt proves only that execution began and its terminal
acknowledgement is unknown; recovery never replays it. Entries without start
receipts remain absent. A committed reply blob suppresses an automatic second
provider call, while a provider attempt with no committed acknowledgement is an
externally uncertain outcome and also is not automatically retried.

The next deliberate turn reconstructs analyzer state from committed namespace,
function, schema, test, and require-edge facts. It does not replay scratch
forms, external effects, unresolved Promises, or `result/<id>` values. If the
program corpus cannot reconstruct all behavior needed by a later form, the
cutover has falsified its own prerequisite and must stop rather than retain a
hidden warm-process authority.

## Failure matrix

| Event | Required durable result | Required process result | Forbidden result |
|---|---|---|---|
| Shutdown before spawn admission | No start receipt or child | No spawn | A child not present in the owner closure |
| Shutdown after child spawn, before a start receipt | No start receipt | Published child is reaped | Synthetic eval evidence |
| Shutdown after start receipt, before dispatch | Receipt becomes interrupted | Published child is reaped | Running receipt left indefinitely |
| Shutdown races handle publication | One of the two serialized phases wins | Exact published handle is reaped before shutdown returns | Naked PID cleanup or orphan |
| Ordinary child result | One terminal `:done`/`:error` plus accepted facts | Child acknowledged only after commit; final child reaped | Result/tee without receipt CAS |
| Candidate rejected by schema, fence, coordinate, or publication | Current receipt interrupted/error as parent decides; candidate absent | Child killed and reaped | Child continues from uncommitted analyzer state |
| Deadline or cancellation | One terminal interrupted receipt | TERM, bounded KILL when needed, awaited `close` | Timeout value while child continues |
| Child OOM, explicit exit, uncaught fatal error, or output flood | One interrupted receipt with bounded parent diagnostic | Hard boundary contains and reaps only the child | Pod/writer death or synthetic later evals |
| Commit succeeds, acknowledgement is lost | Existing terminal receipt and effects remain the authority | Child discarded and reaped | Candidate replay or second eval id |
| Pod dies with child active | Running receipt repaired exactly once after old subtree absence | No old child/backend resource before new readiness | Reconstructed handle or automatic effect replay |
| Three-form batch dies in form two | Form 1 terminal, form 2 interrupted at position 2, no form-3 row | Child absent | Form 3 result/error or tee |
| Child requests foreign actor/coordinate/run/eval | No writer call and no coordinate advance | Frame refused; child disposed on protocol violation | B-attributed provenance or child-selected authority |

## Inspect and deterministic proof contract

Inspect supplies orchestration and scoring after deterministic runtime gates;
it does not own child disposal. Add one generated containment task and one pure
oracle over retained runtime evidence. The task should drive these arms through
the normal pod service: ordinary completion, sync loop, never-settling Promise,
ordinary heap, `ArrayBuffer`, native/external allocation, TERM refusal, output
flood, foreign authority, lost acknowledgement, three-form death, pod death,
and a subsequent normal database task.

The scorer must consume exact database facts, process/backend observations, pod
and writer health probes, and the following-turn result. It must not inspect
the model's narration. Expected child death is a correct containment outcome;
an Inspect transport timeout, cluster boot failure, missing evidence, or scorer
oracle failure is infrastructure and publishes no capability number. Existing
`solver.require_scorable_pod_state` and `scorecard` flake taxonomy remain valid
for ordinary model tasks but cannot classify the deliberately killed child as
`solve_timeout` and exclude the containment assertion.

Inspect's `SandboxEnvironment.exec_remote` provides useful cancellation/kill
patterns and Docker supplies a hard-test substrate. Its deferred interrupted
sample cleanup and global task shutdown are not evidence that Seon's child was
reaped promptly. The retained Seon log must independently prove the task's
`close`, pipe/listener/timer cleanup, backend absence, and pod/writer health
before Inspect scores it.

## Exact implementation boundary

Implementation may start only after unit 1 publishes a stable child launch/
backend descriptor and proves old pod subtree absence across ordinary stop,
SIGINT, dead group leader, and retained native-branch interruption. The first
source slice then has one owner and this exact boundary:

1. Add a private child lifecycle adapter under `src/seon/eval/` that owns closed
   frame schemas, spawn/handle publication, one bounded decoder, deadline close,
   TERM/KILL/`close`, and deterministic disposal. It is not a public evaluator.
2. Extend the existing eval start receipt with contiguous per-turn position and
   complete run/coordinate fences; integrate the already-landed status/CAS
   builders into the current `eval-batch!` owner before moving computation.
3. Build a non-production synthetic child artifact/fixture and direct plus hard
   backend tests. Do not route application eval yet.
4. Attach one `close-all!` inverse to `seon.agent.runtime`/
   `seon.client/stop-runtime!`: close admission, interrupt/fence, reap every
   owned child, then continue the existing runtime inverse.
5. Prove denial, deadline, TERM refusal, KILL, OOM/external memory, output cap,
   parent death, restart recovery, and subsequent work. Only then proxy one
   frozen read and one refused stale write.
6. Integrate the parent actor/capability handler and the plan authority proof.
   Cut over eval batches only after foreign/late/duplicate/lost-ack gates pass.
7. Delete the old in-process arbitrary execution and tee-drop fallback from the
   production path after same-batch definitions, namespace movement,
   instrumentation, auto-tests, result admission, and non-fail-fast ordinary
   errors pass behind the one service.
8. Add deterministic Inspect scoring, then run small-model/paid trials.

Exact overlapping owners are `src/seon/eval.cljs`, `src/seon/eval/internal.cljs`,
`src/seon/agent/turn.cljs`, `src/seon/agent/runtime.cljs`, `src/seon/client.cljs`,
`src/seon/runtime/recovery.cljs`, `src/my/plan.cljs`,
`src/my/plan/internal.cljs`, `src/seon/launch.cljc`, `script/seon/dev/process.clj`,
and the artifact/Shadow build descriptors. The root must assign them as one
coherent implementation lane rather than parallel edits to the lifecycle.

## Measured exit gates

No fixed latency target is invented before the backend experiment. The first
run records distributions and then freezes reviewed limits in configuration and
tests. Graduation nevertheless has binary evidence requirements:

- The retained artifact records Node/V8 identity, child artifact digest,
  backend digest, exact argv and non-secret environment keys, complete parent
  read coordinate, absolute deadline, CPU/memory/PID/output/frame limits, and
  configured TERM/KILL bounds.
- For normal exit, TERM exit, TERM refusal/KILL, sync loop, Promise hang, JS
  heap, `ArrayBuffer`, external/native allocation, output flood, and parent
  death, measure launch-to-ready, reconstruction-to-first-form, peak resource
  use, timeout-to-TERM, TERM-to-`close`, KILL-to-`close`, total reap, event-loop
  delay, writer ping, web readiness, and subsequent-turn latency.
- Every configured cleanup bound is greater than the measured source-frozen
  p99 plus an explicit reviewed margin, while still strictly inside the owning
  run/operator bound. The retained evidence must name sample count and maximum;
  one anecdotal fast run is not a limit.
- A hard backend kills only the child when each generated hostile allocator
  crosses a small configured ceiling. Pod and writer stay responsive, and
  process/backend/fd inspection finds zero child resources after every cycle.
- The three-form datom proof shows positions 1 and 2, exactly one terminal
  transition each, no position 3, correct parent provenance, and no result or
  tee for the killed form. Duplicate/late/foreign frames advance no coordinate.
- A pod-death test proves the old execution subtree absent before replacement
  readiness, then one recovery transaction interrupts the running receipt; a
  second recovery writes nothing.
- After each injected failure, a later ordinary agent turn registers a schema,
  stores a fact, queries it from a later turn, and completes through the same
  runtime path.
- The complete focused CLJS/writer/operator gates, destructive default-cluster
  restart proof, browser/status responsiveness, and deterministic Inspect
  scorer all pass from one source-frozen artifact before ACME coordination.

## PRD and issue dependencies

- Unit 1/database lifecycle must finish public branch ownership and prove
  dead-leader subtree cleanup. Ordinary startup alone closes only its row.
- [[../../../seon/issues/multi-form-eval-order-is-not-durable]] supplies the
  durable contiguous receipt position; this contract consumes it.
- [[../../../seon/issues/plan-reopen-cross-agent-authority]] consumes the
  parent-stamped actor only after the capability handler exists.
- [[../../../seon/issues/eval-process-isolation-memory-containment]] remains
  open until the hard backend, hostile matrix, and default live proof pass.
- Inspect scorers depend on deterministic receipt/process evidence. Model
  trials never decide runtime correctness.
- Runtime-reliability graduation and independent ACME packaging consume this
  unit only after the default source-frozen proof; they do not provide its
  missing containment evidence.
