---
type: research
status: completed
tags: [research, agent, flow, architecture]
---

# Process-death containment audit — 2026-07-15

## Decision

The smallest boundary that can honestly survive arbitrary ClojureScript CPU,
heap, external-memory, and fatal-runtime failure is a **pod-owned disposable
Node child**, never a worker thread and never a second autonomous agent runtime.
The pod remains the agent-loop, database-capability, durable-receipt, queue,
deadline, and restart owner. A child receives one closed task capability,
executes at most one task at a time, returns bounded data, and has no writer,
feed, database connection, agent identity selector, or important durable state.

Use the same process owner for two non-overlapping task capabilities:

- a provider-attempt child receives one adapter descriptor, one prompt, a
  minimal credential environment, and network access; it returns the exact
  consumed response bytes under backpressure and exits before agent code runs;
- an eval-batch child receives committed reply bytes, the selected agent/run/
  turn capability, the reconstructed program graph, and only the capability
  bridge needed by agent code; it receives no provider credentials or ambient
  network/filesystem access.

This is one execution-service contract with capability-selected child launches,
not two provider/eval services. The existing provider `AbortSignal` and
`seon.eval/race-timeout` remain the cooperative inner bound. Process termination
is the hard outer bound. The existing `seon.worker-eval` diffusion oracle is not
promoted or reused: its standalone namespace, analyzer state, and JSON-line
verdict contract cannot preserve application REPL semantics.

Node worker threads are rejected for the hard boundary before implementation.
Exact Node `v26.4.0` documentation says `resourceLimits` constrain only the JS
engine, exclude external data including `ArrayBuffer`, and can still let a
global out-of-memory abort the entire process. `Worker.terminate()` is useful
cleanup behavior, but it cannot turn a shared process into fault containment.

A direct Node child is necessary but not sufficient for a hard numeric memory
ceiling. `--max-old-space-size` limits only V8 old space. Darwin 25.5.0 rejected
both zsh and bash attempts to set `RLIMIT_AS` (`ulimit -v`) with `EINVAL` during
this audit. Therefore the launch backend must supply an independently measured
OS/container memory limit where native per-process limits are unavailable. A
plain RSS poll is diagnostics and early cancellation, never the claimed hard
ceiling. The contract can graduate on Linux with a per-child cgroup/container;
the macOS development path must use the existing Docker-capable environment or
another measured kernel boundary for hostile-memory proof. It may not relabel a
V8 heap flag as complete memory containment.

## Scope and observation

This audit read the target agent-runtime architecture, the active correctness
roadmap, prior runtime and memory audits, current eval/turn/run/client/recovery
source, the database and plan authority issues, exact dependency source, and
the existing operator/process reference implementations. It changed no source,
ran no ClojureScript suite, made no database write, launched no hostile
allocation, and did not touch ACME.

`bin/seon status` reported the default cluster fully down: watcher, writer, and
pod were absent. No REPL or live recovery claim is made. That absence makes the
destructive first-slice measurements below mandatory rather than inferred.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and consequence |
|---|---|---|
| Node.js | live `v26.4.0`, release commit `2022edf3e32ce28ee08b17f8566243a090dacd95`, V8 `14.6.202.34-node.21` | Official tag source `doc/api/worker_threads.md`, `child_process.md`, `cli.md`, and `permissions.md`: worker limits exclude external memory; child abort sends a signal but does not prove exit; old-space is not a total RSS limit; `--permission` denies filesystem, network, child process, workers, addons, WASI, FFI, and inspector by default. Node explicitly calls that model a bypassable seat belt for trusted code, not a malicious-code security boundary. Exact source was located at the official tag because no Node mirror exists under `reference-code/`. |
| ClojureScript self-host | selected `1.12.145`; official tag `r1.12.145`, commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs` and current `seon.eval` establish that analyzer state and definitions persist across `cljs.js/eval-str` calls. Child reconstruction must load committed program source, never replay effects. |
| Shadow CLJS | selected `3.4.10`, release commit `d3c04691952aa9ea33f7287ffe9a2b3109c1e510` | The bootstrap/client artifact and analysis cache are immutable child inputs. Artifact identity must match the parent launch descriptor; never select Shadow's latest runtime. |
| SCI | `0.13.53`, commit `b4917436550c857a18b8f6a4a8b5b26356acc2c4` | Pinned for bounded interpreted surfaces. The application evaluator uses `cljs.js`; SCI interruption cannot contain emitted native JS and is not the process boundary. |
| Piscina | reference commit `23a6c2e94735216c6978679fe7b8ea0b5666683b`; not a selected dependency | `worker_pool/index.ts`, `task_queue/index.ts`, abort tests, and crash-reset tests demonstrate one-task abort listener cleanup, termination of a busy worker, task rejection, and replacement. These are patterns only; adopting a pool would add an unnecessary dependency and still fail external-memory isolation. |
| Datahike and Seon database | maintained Datahike `417649383c65e13f15ea41d394fb1ed742477965`; one `seon.db` API and JVM writer | The parent holds the frozen database value and sole writer capability. Child reads/writes cross a typed capability pipe and retain expected coordinate plus run/eval fences. No Datahike resource or feed enters the child. |
| Provider SDKs | OpenAI Node `6.42.0`, Anthropic `0.104.2`; exact source mapped by the provider cancellation audit | One fresh attempt signal remains valid cooperative cancellation. Hard timeout additionally kills and reaps the attempt child; SDK retry stays disabled and `call-llm!` remains the sole retry decision owner. |
| Pod lifecycle | `seon.client/start-runtime!` / `stop-runtime!`, `seon.agent.runtime`, `seon.runtime.recovery` | Child handles are process-local artifacts owned by the existing runtime inverse. Stop closes admission, cancels/kills/reaps children, then releases the replica/database. Recovery derives unfinished receipts from committed facts. |
| Operator | `bin/seon` supervisor and launch descriptor work | The operator must treat execution children as the pod's non-detached subtree, reap them on pod death, and include their artifact/cache identity in readiness. It does not gain another service, port registry, or autonomous lifecycle. |
| Inspect Docker substrate | `src-inspect-ai/src/seon_inspect/swebench_arm.py` and canonical `docker/Dockerfile` | The existing null arm proves `network_mode: none`; the agent arm proves an internal-only network plus model-API relay, immutable `/opt/seon:ro`, and `mem_limit`. It does not yet set a read-only root, non-root user, dropped capabilities, no-new-privileges, or a child-specific PID limit. The image also pins Node `22.23.1`, not the audited live `26.4.0`; it is a source pattern and available substrate, not already-complete eval isolation. |

## Current boundary and the exact seam

`seon.agent.turn/ask-and-eval!` currently calls the provider, then
`ask-and-eval-reply!` persists/parses the reply and calls
`seon.eval/eval-batch!`. `eval-form-entry!` combines six responsibilities in
one pod process: compile/eval, await, analyzer/schema diff, durable eval record,
live-result admission, and accepted-program publication. A synchronous loop or
hostile allocation blocks or kills that process before `race-timeout` can run.

The refactor must split at acknowledgements, not create a second evaluator:

1. The pod opens the existing turn and launches one provider-attempt task.
2. The child streams a bounded exact reply to the pod. The pod stores the reply
   through the one blob path and acknowledges its hash before any eval begins.
   If either side dies before acknowledgement, no durable reply is claimed.
   If the reply is committed but the child dies before the next phase, recovery
   resumes from those exact bytes and does not bill a second provider call.
3. The pod launches one eval-batch task against that committed reply and a
   complete runtime capability. Before dispatching each parsed form, it commits
   a start receipt under the run CAS and expected database coordinate. That row
   means the form really began; no row exists for later undispatched forms.
4. The child evaluates sequentially and returns one bounded candidate outcome.
   The pod commits the result, tee, and projection through the existing writer
   and acknowledges acceptance before the child advances. A rejected/uncertain
   candidate kills the child and stops the batch so process-only analyzer or
   schema state can never outrun database truth.
5. Batch success exits the disposable child. A later turn reconstructs the
   compiler/program graph from committed namespace/function/schema source, not
   by replaying prior eval side effects.

The durable start receipt is a state transition of the existing
`:seon.eval` entity, not a generic job ledger. It needs a running terminality
state plus start coordinate/time; completion moves it once to success, error,
or interrupted. Process exit code, signal, diagnostic-report path, and bounded
stderr are diagnostic fields on the interruption/error evidence, never proof
that another form ran.

## Capability crossing and actor authority

The child cannot carry `db/*conn*`. It receives one unforgeable parent-created
task capability containing agent, run, turn, eval receipt, artifact, and frozen
database coordinate. Every child request is framed and size-bounded, and the
parent derives actor/provenance from that capability rather than trusting a
request field.

This is a dependency correction for `my.plan`. `seon.db/current-agent-id` reads
publicly replaceable AsyncLocalStorage: eval code can nest `seon.db/with-agent`
and select another apparent actor. Plan lifecycle authority therefore cannot
graduate before the parent capability boundary exists. Parent-side plan and
database mutation must stamp the actor and transaction user from the task
capability; the child has no actor-selection operation and no independent
writer. Explicit target ids remain ordinary data checked by the plan authority,
not credentials.

Existing agent-facing semantics cross the child as follows:

- synchronous `seon.db/query`, pull, entity, and program reads use inherited
  pipe descriptors. The child sends a typed request then blocks only its own
  process on the reply; the responsive pod executes it against the task's one
  frozen database value and returns bounded Transit data. Slice 1 must prove
  whether one custom stdio descriptor is duplex on every supported platform;
  otherwise the same framing uses separate inherited request/response pipes;
- asynchronous writes and `my.*` effects use the same framed capability
  protocol, but the pod performs the operation through the existing owner with
  agent/run/turn/eval provenance and CAS fences;
- the parent rejects a request whose task/receipt is no longer current before
  touching the writer. A late child cannot select a newer run or coordinate;
- filesystem, shell, web, blob, canvas, plan, message, and lifecycle calls stay
  behind their one current parent owner. The eval child gets neither raw Node
  ambient capabilities nor an SDK credential environment; and
- reply/result/output messages have independent byte, node, and count limits.
  Structured-clone or Transit success is admission, not authorization.

This bridge is not a second database protocol. It is the local execution
capability adapter that calls `seon.db`; only the existing writer protocol
commits. The immutable database value remains in the pod, so one eval form sees
one coordinate even if the writer advances concurrently.

## Launch permissions and operating-system enforcement

A plain Node child inherits the host filesystem, network, environment, process
namespace, and module loader. Process disposal does not remove those ambient
capabilities. Every direct child therefore starts with Node `v26.4.0`
`--permission`, not an allow-all runtime followed by attempted revocation.

The eval-child launch grants only exact immutable artifact/bootstrap read paths
and the already-open framed capability descriptors. It grants no filesystem
write, network, child-process, worker-thread, native-addon, WASI, FFI, or
inspector permission. Its `env` is a closed allowlist of non-secret runtime
coordinates; provider keys, host tokens, proxy variables, home-directory
credentials, `NODE_OPTIONS`, and ambient configuration are absent. Node states
that existing file descriptors bypass its permission checks; that exception is
intentional only for the parent-created request/response pipes, whose framing,
size, receipt, and operation checks remain parent-owned.

The provider-attempt child never receives agent source or an eval capability.
It gets exact adapter/artifact read paths, no write/child/worker/addon/WASI/FFI/
inspector permission, a minimal selected-provider credential environment, and
network permission. Under the hard container backend that network joins an
internal-only namespace whose sole egress is the selected model API relay,
following the existing Inspect arm. Provider credentials disappear with that
child and are never present when an eval child launches.

Node's permission model is defense in depth, not the hard sandbox. Its exact
documentation warns that malicious code can bypass it, filesystem grants follow
relative symlinks, `node:sqlite` can reach files outside `node:fs` checks,
pre-initialization flags can read files, existing descriptors remain usable,
and `process._debugProcess()` can signal another same-user Node process. Native
addons are denied because they would escape the JS checks; a dependency that
requires one is incompatible until it has a separately measured backend. The
child cannot receive `--allow-child-process` or `--allow-worker`; provider and
eval bundles must fail admission if a selected dependency requires either.

Hard eval enforcement is the platform launch backend described by the same
execution contract. On the existing Docker-capable macOS/Inspect path it uses a
fresh per-task container with:

- `network_mode: none` for eval; an internal-only model-relay network for a
  provider attempt;
- a read-only root filesystem and exact read-only artifact mounts;
- one bounded tmpfs only if the runtime proves it needs scratch space;
- a non-root uid, all Linux capabilities dropped, no-new-privileges, no Docker
  socket/host PID namespace/devices, and a Node-compatible bounded PID count;
- the measured memory/CPU ceiling and bounded stdout/stderr; and
- one mounted parent-owned Unix capability socket directory, containing no
  database/writer/feed socket and removed after the task.

The canonical image currently bundles Node `22.23.1`. Slice 1 must first make
the child artifact's Node identity equal the launch descriptor and read that
exact release's permission behavior. The audited `v26.4.0` network denial and
`--allow-net` flag cannot be projected backward onto the current image. Prefer
updating the child/runtime image to the same selected `v26.4.0`; if packaging
temporarily retains `22.23.1`, Docker's network namespace is the only network
enforcement claimed and unsupported Node flags are omitted. Readiness rejects an
artifact/runtime mismatch rather than silently weakening permissions.

Node uses `--allow-net` inside that container only to reach the mounted Unix
capability socket (and, for provider tasks, the relay); the OS network namespace
is the actual egress enforcement. Slice 1 must verify whether the exact Node
permission check classifies the Unix socket as network on each packaged Node
version. Direct non-container children are allowed as a fast development arm
only after the same denial tests pass, and their Node permission result is never
reported as hostile-code or hard memory isolation.

## Disposal granularity

| Choice | Benefit | Correctness/performance cost | Decision |
|---|---|---|---|
| One child per form | Maximum reset and exact per-form kill attribution | Reloads the bootstrap and committed program before every form; destroys ordinary same-batch definition flow unless every form waits for commit and complete reconstruction; multiplies startup and memory churn; makes pending Promise handles meaningless | Reject as the default. Measure only as a lower-bound isolation experiment. |
| One child per eval batch | One reconstruction per reply; earlier committed forms remain live for later forms; a killed in-flight form stops before later dispatch; next turn reconstructs exact committed definitions | Cold reconstruction every turn may be material; pending process-local results end with the batch unless made explicitly durable/addressable | Smallest correctness-first implementation. |
| One warm, non-multiplexed child per slot | Amortizes reconstruction while retaining one task at a time and kill attribution | Requires replacement/reconciliation after hot reload, uncertain parent acknowledgement, or task failure; more process-local lifecycle state | Allowed only after batch-disposal latency and memory are measured. It is an optimization behind the same owner, not Slice 1. |

Persistent self-host definitions are database program facts, not a reason to
reuse a process. Within one batch, accepted forms remain in that child's state.
Across batches or death, the existing replay/program-publication mechanism
reconstructs accepted namespace, function, schema, and require-edge source.
Process-local `result/<id>` values and unresolved Promises are intentionally
disposable; a result that must survive belongs in the database/blob tier. The
implementation must inventory any definition state still absent from committed
program facts before cutover and either make it durable through the existing tee
or refuse to claim restart equivalence.

## Cancellation, deadline, kill, and restart laws

1. **One deadline owner.** The pod computes the absolute task deadline from the
   run/turn policy. Children receive the deadline as data but never extend it.
2. **Fence before signal.** Lifecycle cancellation first closes or supersedes
   durable authority. Parent capability requests then fail even if cancellation
   delivery is late.
3. **Cooperative first.** Provider cancellation sends the existing fresh abort
   signal. An eval child may acknowledge between forms or while awaiting an
   async capability. Synchronous arbitrary code is never expected to cooperate.
4. **Bounded escalation.** At deadline/cancel, close the capability pipe, send
   `SIGTERM`, wait one configured short grace for the child's `close`, then send
   `SIGKILL` and wait for exit. Merely calling AbortController or `.kill()` is
   not completion. A slot is not reusable until the process is reaped.
5. **Handle identity, not naked PID.** The pod acts on its retained ChildProcess
   handle and one spawn token, removes listeners once, and never sends a delayed
   signal after `close`. PID liveness polling cannot authorize a kill because
   Node documents PID reuse risk.
6. **No descendants.** The eval child has no `child_process` capability. The
   provider child has no reason to spawn. If a selected SDK later requires a
   descendant, the launch must create and reap an isolated process group.
7. **Parent death reaps the subtree.** Children are non-detached and supervised
   as the pod's process subtree. The operator must kill/reap that subtree when
   the pod exits because a synchronous child cannot observe IPC EOF. No child
   owns a standalone pid/port file or survives a pod restart.
8. **One terminal receipt.** Normal result, structured error, timeout,
   cancellation, signal exit, OOM/fatal exit, and parent uncertainty compete
   through one terminal CAS. Exactly one wins; later events only clean handles.
9. **No effect replay.** Restart marks every committed running eval receipt
   interrupted and the owning running turn/run crashed or interrupted through
   the existing recovery transaction. It reconstructs program state from
   committed facts and never re-executes the interrupted form automatically.
10. **Later forms are absent.** A batch child death terminalizes only its
    current started receipt. Entries not dispatched have no eval identity,
    result, or synthetic error.
11. **Provider acknowledgement fences billing retry.** A committed reply blob
    suppresses another provider call for that turn. A provider-child death with
    no committed reply interrupts the turn; recovery does not automatically
    retry an externally uncertain attempt.
12. **Stop is ordered.** Runtime shutdown closes web/admission/wake ownership,
    cancels and reaps every execution child, detaches projection/replica, and
    only then releases the database connection.

## Failure matrix

| Failure point | Durable observation | Forbidden observation | Recovery |
|---|---|---|---|
| Provider child fails before reply acknowledgement | Running/interrupted turn; bounded exit diagnostic; no reply blob claim | Parsed forms, eval rows, automatic billing retry | Close run honestly; later deliberate trigger starts new work. |
| Reply commits, child/pod fails before eval launch | Exact turn reply blob and no eval start | Second provider call or invented eval result | Parse the committed bytes on deliberate resume. |
| Eval child fails during bootstrap/reconstruction | Turn/runtime error and child diagnostic; no form start | Mutation of program projection | Replace child; fix artifact/program fault before retry. |
| Form start commits, child dies before candidate | One running-to-interrupted eval row | Result, tee, result slot, or later-form row | Fence/interrupt receipt; stop batch; later turn sees evidence. |
| Candidate arrives, parent commit rejects | Eval error/interrupted receipt; rejected candidate absent from database | Child continuing with uncommitted analyzer/schema state | Kill child immediately; reconstruct later from committed graph. |
| Commit succeeds, acknowledgement is lost | Committed eval outcome and tee are authority | A duplicate completion/effect on retry | Parent reads terminal receipt; child is discarded/reconstructed. |
| Lifecycle closes run during provider/eval | Run fence changed before child request/write | Late provider/eval publication under a new run | Cooperative cancel then hard kill; one terminal receipt. |
| Child exceeds JS heap | Child fatal exit/report under process/OS ceiling | Pod/writer exit or fabricated structured JS exception | Record interrupted/OOM diagnostic; replace child. |
| Child allocates external/native memory | OS/container kills only child at measured ceiling | Claim that `resourceLimits` or old-space alone contained it | Record kill/RSS evidence; replace child. |
| Parent pod dies with child busy | Operator observes/reaps whole pod subtree | Orphan child, inherited writer/feed, later commit | Existing pod recovery transaction repairs committed receipts. |
| Child floods output or capability calls | Frame/output/queue cap rejects task and closes capability | Unbounded parent buffer or unbounded queued requests | Kill child; compact structured failure. |

## Ordered implementation slices

### Slice 1 — bounded process experiment, no eval cutover

Add the pod-owned child lifecycle behind the future execution-service seam but
do not route production eval through it. Build one repository-owned child
artifact that can load the exact bootstrap/program snapshot supplied by the
parent, run a synthetic pure task, return one bounded receipt, and exit. Use
generated fixtures for synchronous loop, ordinary heap, `ArrayBuffer` external
memory, uncaught exception, explicit exit, output flood, TERM refusal, and
parent disappearance.

The same child must attempt and fail all ambient-capability probes: read a host
file outside the exact artifact allowlist, write beside the artifact, open a TCP
and UDP socket, read a seeded fake provider secret from `process.env`, spawn a
child process, start a worker, load a native addon, enable an inspector, and use
the known `node:sqlite`, symlink, and `process._debugProcess()` caveat shapes.
In the direct arm, the ordinary permission-controlled operations must return
`ERR_ACCESS_DENIED` or the documented addon-specific denial. In the hard Docker
arm, bypass-shaped probes must still fail at the read-only mount, namespace,
uid/capability, seccomp/no-new-privileges, or PID boundary. The framed parent IPC
read and stale write-refusal probes must continue to work under those same
denials.

Measure cold artifact load, bootstrap reconstruction, committed program replay,
first form, batch of forms, normal shutdown, TERM-to-exit, KILL-to-reap, peak
RSS/heap/external memory, descriptor/fd cleanup, and pod event-loop latency.
Run direct Node child and hard-limited platform backend arms. This slice passes
only if pod/writer readiness and a normal parent REPL probe survive every child
death, no orphan remains, and Darwin hostile-memory proof uses an actual hard
backend rather than `ulimit -v` or RSS polling.

Slice 1 also proves the capability protocol with two harmless operations: one
sync read over a parent-held frozen database value and one deliberately refused
write with a stale run/eval capability. It does not expose general `my.*`, does
not add a second writer/feed, and does not move provider or application eval.

### Slice 2 — durable receipts and capability authority

Extend the existing turn/eval schemas with the smallest started/terminal receipt
transition and one parent-owned task capability. Make recovery terminalize
running receipts without replay. Route plan/database actor stamping through the
parent capability so `with-agent` inside eval cannot forge provenance. Prove
CAS/idempotency for start, completion, cancellation, lost acknowledgement, and
restart before any arbitrary form moves.

This slice is a dependency of the plan-transition authority implementation;
the plan owner may implement pure actor/owner/scope decisions earlier, but it
must not claim unforgeable runtime actor enforcement until this boundary lands.

### Slice 3 — provider attempt child

Move one existing adapter attempt behind the child task without moving retry
policy. Preserve one fresh abort signal, SDK retry disabled, exact streamed
reply capture, bounded backpressure, commit acknowledgement, remote job cancel,
and hard kill fallback. Then cover every selected adapter. `call-llm!` continues
to decide whether a new attempt is allowed; crash uncertainty is non-retryable
unless a durable rule proves no external request began.

### Slice 4 — eval batch child

Move the existing parse-once ordered batch behind one child. First proxy frozen
database reads and one no-effect pure form; then inventory and proxy each
existing `seon.db`/`my.*` capability owner. Each form starts durably before
dispatch, commits/acks before the next form, and stops the batch on process or
publication uncertainty. Delete in-pod arbitrary self-host execution only after
the full same-batch definition, namespace, tee, instrumentation, auto-test,
pending-result, and error matrix passes.

### Slice 5 — lifecycle integration and measured optimization

Make start/stop/hot reload/operator readiness own child artifact identity,
admission, subtree reap, and replacement. Compare per-batch disposal with a
warm non-multiplexed slot. Retain warm slots only if measurements justify them
and reconciliation after every accepted program generation, uncertain ack,
reload, cancellation, and crash is exact. Queue and slot counts stay bounded
and derive active work from durable receipts; there is no database job queue or
second runtime registry.

### Slice 6 — destructive graduation and Inspect

Run the full failure matrix through the default operator and the maintained
Inspect runtime. Add deterministic scorers for raw reply acknowledgement,
started/interrupted/absent eval evidence, actor non-forgeability, no replay,
child cleanup, pod/writer health, and subsequent agent progress. Only then run
small-model or paid provider trials.

## Exact acceptance evidence

- Exact dependency identities, child artifact digest, launch backend, memory/
  CPU limits, and parent capability coordinate appear in the retained test log.
- The eval child's launch argv records `--permission` and only exact artifact/
  IPC grants; its environment contains no seeded provider or host secret. Reads,
  writes, TCP/UDP, child spawn, worker creation, addon load, inspector, symlink,
  sqlite, and cross-process-debug probes are denied, while bounded framed IPC to
  the parent succeeds. Container evidence records network mode, mounts, uid,
  dropped capabilities, no-new-privileges, PID/memory/CPU limits, and absence of
  the Docker socket and writer/feed sockets.
- A sync infinite loop exceeds its deadline, the pod web/status and writer ping
  remain responsive during termination, and the child is reaped within the
  configured bound.
- Ordinary JS heap, `ArrayBuffer`, and native/external probes each cross a small
  generated limit; only the child dies, the measured OS/container ceiling is
  visible, and no host-scale OOM payload is used.
- `ps`/fd/port inspection after normal, TERM, KILL, OOM, parent-death, and
  repeated cycles finds no child, listener, pipe, timer, queue entry, or stale
  readiness artifact.
- A committed reply is byte-identical; a crash after its acknowledgement causes
  no second provider call. A crash before acknowledgement produces no reply or
  eval claim and is not automatically retried.
- For a three-form batch killed in form two: form one has one committed result,
  form two has one interrupted started receipt, form three has no eval row, and
  no result slot or tee exists for forms two/three.
- A child calling `seon.db/with-agent` with a foreign id cannot alter the actor,
  `:seon.db/user`, plan owner decision, or run/eval capability observed by the
  parent mutation. The rejected transition advances no database coordinate.
- A database query sees exactly the task's frozen coordinate; a later writer
  commit is visible only to the next turn/task. Every child write request loses
  against a superseded run/eval CAS.
- Accepted definitions work in later forms in the same batch and after a fresh
  child reconstructs from committed facts. Failed/unacknowledged definitions do
  not survive either path.
- Runtime stop and pod crash both reap the child before database release or
  restart readiness. Existing recovery remains idempotent and fabricates no
  undispatched work.
- After every injected failure, a later normal agent turn queries data, stores a
  schema'd fact, reads it back, and completes through the same runtime path.

## Dependencies and non-goals

The launch-descriptor/exact-replica and operator subtree-reap work must settle
before live Slice 1 proof. The plan authority may proceed on pure transition and
schema design, but its actor-security exit depends on Slice 2. Inspect scorers
depend on deterministic receipts, not the reverse.

This unit does not create an autonomous execution daemon, adopt Piscina, move a
writer or feed into a child, restore the diffusion oracle as application eval,
invent a generic workflow ledger, make SCI a security boundary, or promise
microVM/container policy in architecture prose before the measured backend is
selected. The process handle, pipes, timers, and compiler instance are allowed
process-local artifacts; all important authority and recovery truth remains in
database facts.

## Source map

- `src/seon/agent/turn.cljs` — one provider retry owner and reply/eval seam.
- `src/seon/eval.cljs` — current in-pod self-host eval, tee, record,
  publication, and result admission.
- `src/seon/agent/runtime.cljs` — current process-local agent hosting inverse.
- `src/seon/client.cljs` — ordered runtime start/stop and recovery integration.
- `src/seon/runtime/recovery.cljs` — fenced unexpected-exit repair.
- `src/seon/db.cljs` and `src/seon/db/protocol.cljc` — sole application and
  writer database authorities.
- `src/my/plan.cljs` and `src/my/plan/internal.cljs` — transition authority that
  needs a parent-stamped actor.
- `src/seon/worker_eval.cljs` — deliberately separate diffusion oracle, not the
  containment implementation.
- `reference-code/piscina/src/worker_pool/index.ts` and
  `reference-code/piscina/src/task_queue/index.ts` — cleanup/abort patterns only.
- `reference-code/piscina/test/abort-task.test.ts` and
  `reference-code/piscina/test/test-uncaught-exception-from-handler.test.ts` —
  deterministic termination/replacement examples only.
- Node `v26.4.0` official tag source `doc/api/worker_threads.md`,
  `doc/api/child_process.md`, `doc/api/cli.md`, and `doc/api/permissions.md` at
  commit `2022edf3e32ce28ee08b17f8566243a090dacd95`.
- `src-inspect-ai/src/seon_inspect/swebench_arm.py` — existing Docker
  network-none, internal relay, read-only artifact mount, and memory-limit
  patterns plus the controls still missing for a hard eval child.
