---
type: research
status: completed
tags: [research, agent, flow, database]
---

# Agent runtime source audit — 2026-07-14

## Scope and verdict

This audit grounds the agent-runtime-correctness chunk in the selected
dependencies, their mirrored source, the maintained Seon mechanisms, focused
tests, and safe read-only probes against the default cluster. It covers model
reply capture, parsing, evaluation, result admission, async instrumentation,
provider retry/cancellation, planning, restart recovery, process containment,
and the existing Inspect surface.

The runtime already has a strong database transaction spine: ordered parsing,
one eval row per attempted form, result publication after commit, one transport
retry owner, restart recovery with CAS fences, and one database-backed plan.
The main correctness work is not a replacement runtime. It is to strengthen
those owners in place. The most important correction is that the current
`reply-blob` is *not* raw ground truth: `ask-and-eval-reply!` deletes alleged
result text before blob capture and parsing. The target must preserve the reply
byte-for-byte and let the parser distinguish executable forms from narration.

No paid model call, database write, hostile allocation, process kill, or ACME
operation was used for this audit.

## Dependency ledger

| Dependency | Selected version or SHA | Mirrored source inspected | Relevant contract and status |
|---|---|---|---|
| ClojureScript | `1.12.145` | `reference-code/clojurescript` at `946d75f3483c0c8e784e6668bff2c71a25619a77` | Self-host analyzer/compiler state and `cljs.js/eval-str`. The checkout's `pom.xml` says `1.12.41` even though later release material is present; exact selected source is not proven. |
| Shadow CLJS | `3.4.10` | exact release commit `2911c9082dac3a571e5895d14a17c46d642d4f92` is present in `reference-code/shadow-cljs`; working head is later `8236315af7426ba505aad6102dea1c4ccb1fe412` | Bootstrap/cache build production. Read the exact release commit for behavior instead of citing later working-tree line numbers. |
| SCI | `0.13.53` | `reference-code/sci` at `b4917436550c857a18b8f6a4a8b5b26356acc2c4` | Interrupt behavior used by bounded canvas evaluation; exact selected tag. It is not the agent self-host evaluator. |
| Malli | `0.20.0` | exact tag object `4c054bd7d042e70d60b83b9f07fb765bc103037f`; checkout head `80138076960e7820523b4cb932c5b5d1936d4e7f` is `0.20.1` | Stock instrumentation validates returned Promises synchronously. Seon's `injecting-fschema` is the existing resolved-value owner for the supported shape. |
| Datahike | fork `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike` at the same SHA | Immutable database values, transactions, CAS fences, history, and the local pod replica. Exact. |
| Konserve | fork `df6818d43ea3363a808cd051c0d68917f1b987a9` | maintained fork referenced by root `deps.edn` | Durable storage beneath Datahike; no new direct runtime dependency is needed. |
| superv.async | fork `3e6ed755f83634c9e9bbb58707f9446420d32ce9` | maintained fork referenced by root `deps.edn` | Datahike supervision dependency, not an agent-loop retry or eval-containment owner. |
| partial-cps | fork `1e119b03ea908ad925b98f9ba0a26371c65441e3` | maintained fork referenced by root `deps.edn` | Datahike async transformation dependency, not the public-function Promise contract owner. |
| OpenAI Node SDK | `6.42.0` | `reference-code/openai-node` at `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472` | Request options accept `signal`; internal fetch setup links abort and timeout cleanup. Adapter retries are already disabled. |
| Anthropic TypeScript SDK | `0.104.2` | `reference-code/anthropic-sdk-typescript` at `fbee0d149ce08532885d766d9b1dc99133181d8e` | Request options accept `signal`; `linkAbort` chains external cancellation and requires cleanup. Adapter retries are already disabled. |
| Node.js | live `v26.4.0` | Node worker/process API plus `reference-code/piscina` at `23a6c2e94735216c6978679fe7b8ea0b5666683b` as a pattern only | Worker termination, task abort, queue bounds, and `resourceLimits`. Piscina is not a selected dependency. Worker heap limits are not OS-process RSS/native/external-memory limits. |
| Inspect AI / evals | checkout `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; evals `97c99f5f6507fc5d1449fe3247f267d591f64350` | `reference-code/inspect-ai`, `reference-code/inspect-evals`, and `src-inspect-ai/` | Existing deterministic long-term planning arms and address-step scorer. Runtime transition and containment scorers remain to be added. |

Before implementation, mirror the exact ClojureScript `1.12.145` source. The
current checkout is useful behavioral reference but does not satisfy the
repository's exact-source rule. Shadow `3.4.10` is already inspectable at its
exact release commit in the existing reference history.

## Read-only live evidence

The default pod reported Node `v26.4.0`, database basis `536870929`, two agents,
and no open run, running turn, or eval at the observation point. Its configured
REPL mode was `:batch`.

One parser probe used the literal reply `(+ 1 2) ⟹ 3`. `parse-forms` returned
one executable form, `(+ 1 2)`, and no executable result claim. In contrast,
`strip-result-claims` returned `(+ 1 2) ` and a strip count of one. The parser
already has enough structure to prevent that narration from becoming an eval;
deleting bytes before durable capture is unnecessary and violates the target.

The instrumentation census found 747 program contracts and 95 structurally
async contracts excluded by `async-unwrappable?`. `coverage-gaps` returned
zero because those 95 contracts are omitted from its denominator. The excluded
set includes core database, lifecycle, run/turn/eval, provider, and `my.plan`
functions, so zero reported gaps is not evidence of full async coverage.

## Current mechanism inventory

### Reply, parse, eval, and results

- `seon.agent.turn/ask-and-eval-reply!` receives `raw-reply`, calls
  `seon.agent.ctx/strip-result-claims`, captures the cleaned text as the reply
  blob, and parses the cleaned text. `:seon.agent.turn/results-stripped` records
  how many claims were removed.
- `seon.repl.internal/parse-forms` emits ordered `:form`, `:read`, and
  `:comment` entries and advances past malformed input without converting
  narration into execution.
- `seon.eval/eval-batch!` processes entries in source order and is not
  fail-fast. A read error gets a failed eval row without executing code; a
  comment/prose entry is non-attempt evidence; each real form is attempted.
- `eval-form-entry!` awaits a native Promise. Its timeout releases the waiter
  but cannot stop synchronous code or the underlying Promise.
- `record-eval!` allocates identities and publishes result bindings only after
  its eval row commits. The result namespace is bounded to 200 slots; structural
  admission has a 4,096-node and 256 KiB shallow-weight budget and stores a
  descriptor for rejected values. These are retention controls, not allocation
  containment.
- The batch has a start fence, but a lifecycle transition during a batch does
  not yet define which later forms are unattempted. Process death must never
  fabricate eval rows for forms that did not start.

### Provider retry and cancellation

- `seon.agent.turn/call-llm!` is the one retry owner. It retries only transient
  transport failures, with four attempts by default, capped exponential jitter,
  a 20-second per-wait cap, and a 60-second total-backoff cap.
- `bounded-llm-attempt!` races the request against a timeout, but does not pass
  cancellation into the provider. A timed-out request may continue consuming
  resources; downstream database CAS fences only prevent stale publication.
- Both selected SDKs accept an external abort signal, and their internal retry
  is disabled. The OpenAI streaming adapter already aborts once its first
  complete form is obtained. The same cancellation path should own outer
  attempt timeout and lifecycle cancellation.

### Async instrumentation

- Malli's stock wrapper validates the immediately returned Promise against the
  output schema. `seon.instrument/injecting-fschema` correctly validates a
  resolved value for a simple fixed-arity `:=>` contract.
- `async-unwrappable?` deliberately skips variadic, multi-arity, `:function`,
  and otherwise non-simple async shapes. `coverage-gaps` also excludes them,
  producing a misleading zero-gap signal despite 95 opt-outs.
- The right owner is the existing Promise-aware instrumentation construction,
  generalized or paired with explicit, measured exceptions. A parallel wrapper
  or loop-level Promise validator would split contract authority.

### Plans

- `my.plan` owns one database schema and transition surface. An active step is
  selected before the oldest ready leaf.
- `done!` receives only an id and can mark a step done without checking its
  declared expectation or linking queryable verification evidence.
- `reopen!` and `active!` receive only an id. They do not receive or verify
  caller authority; `active!` can demote the owning agent's other active work.
- Human inbound messages atomically mint ordinary open plan steps carrying
  `:my.plan/message`. The existing scorer detects some address-step discipline,
  but the runtime does not encode a transition law that prevents a new address
  step from silently displacing authored active work.

### Recovery and containment

- `seon.runtime.recovery/recover!` already performs pod-restart repair from a
  frozen database value in one transaction. CAS fences retract stale run
  pointers, close open runs as crashed, interrupt running turns, and add a
  minimal recovery anchor. It fabricates no eval evidence; notices are derived
  from transaction history and disappear after a later run.
- This recovery owner handles pod lifecycle failure, not an isolated eval
  worker death. A containment boundary must map its receipt/effect evidence onto
  the same run/turn/eval facts without treating every killed task as a pod
  restart.
- `seon.worker-eval` is an existing standalone diffusion-oracle evaluator using
  `vm.runInThisContext` and a JSON-line server. It lacks the pod analyzer state,
  prior agent definitions, instrumentation, and database context. It must not
  become a second application evaluator.
- Piscina demonstrates useful worker termination, abort, queue, cleanup, and
  respawn patterns. It is research material, not a reason to adopt a new pool
  library. A worker thread's isolate heap limit does not prove containment of
  external/native allocation or a process-level crash.

### Inspect

`src-inspect-ai/` already has three long-term planning arms, offline fixtures,
and an address-step-discipline scorer. It does not yet exercise the complete
reply/parse/eval transition matrix, async contract failures, provider abort,
plan evidence/authority, or isolated process death through the maintained
runtime contract.

## Data-oriented transition matrix

| Event | Current committed evidence | Evidence that must not be invented | Target invariant |
|---|---|---|---|
| Provider reply received | Turn reply blob link, after a cleaned blob write | A cleaned string presented as raw provider evidence | Blob bytes equal provider reply bytes; parsing is a derived view. |
| Complete form parsed | Ordered parser entry; later eval row | A result inferred from model narration | Each complete form is attempted once in source order. |
| Read failure | Failed eval row with read error and no execution | Side effects or a successful result | Failure is data and later parseable entries remain eligible. |
| Eval success | Committed eval row and bounded `result/<id>` publication | A result binding before commit | Result exists only for a committed execution. |
| Eval failure | Failed eval row with structured error | A model-authored result claim | Failure cannot throw into or wedge the loop. |
| Async resolution | Awaited result at eval boundary; partial contract validation | Promise identity presented as domain output | Validate the resolved value exactly once through the function contract. |
| Process death mid-batch | No isolated boundary today | Rows for forms that never began | In-flight attempt gets honest interruption evidence; later forms remain unattempted. |
| Provider timeout | Turn failure plus a still-running provider request | Late stale publication | Abort the same attempt, fence late completion, and retain one retry owner. |
| Plan activation/completion | Status and timestamps | Completion inferred from prose | Authority and verification evidence are queryable facts checked atomically. |
| Plan reopen/cross-agent transition | Unconditional id-based mutation | Caller authority inferred from ownership prose | Caller, authority, and expected prior state are transaction inputs/fences. |
| Human address step | Ordinary open step linked to a message | Silent cancellation/displacement of authored work | Address priority is explicit or derived without losing authored active work. |
| Pod restart | One fenced recovery transaction and derived notice | Eval evidence for code that did not run | Recovery is idempotent and the next run derives what remains. |

## Falsifiable gaps

1. **Raw evidence is changed before capture.** A reply containing a reserved
   result glyph does not round-trip byte-identically through `reply-blob`.
2. **Async coverage is incomplete.** Ninety-five of 747 live contracts are
   structurally excluded while the public gap count reports zero.
3. **Attempt timeout does not cancel provider work.** A fake provider that
   observes an abort signal will not receive one from the outer turn timeout.
4. **Completion evidence is absent.** `done!` can complete a step with an
   expectation but no verification fact or linked committed evidence.
5. **Cross-agent authority is absent.** An id holder can reopen or activate
   another agent's step without an explicit authorized transition.
6. **Address displacement is underspecified.** A new inbound address step can
   become the selected work without a durable law preserving/displacing the
   prior active step.
7. **The narration issue prescribed rewriting.** Expanding a sanitizer would
   further destroy raw evidence; structural-looking model text must remain
   model narration and never acquire runtime-event authority.
8. **Hard eval containment is absent.** Result/query budgets cannot terminate a
   synchronous loop or contain arbitrary allocation before pod exhaustion.
9. **Exact ClojureScript source is missing.** Its mirrored checkout does not
   establish the behavior of selected `1.12.145`; exact Shadow `3.4.10` is
   available at the release commit already present in reference history.

## Ordered implementation slices

### 0. Exact-source and transition baseline

Mirror ClojureScript `1.12.145`; read Shadow at the existing exact `3.4.10`
release commit. Record exact source paths for self-host analyzer state,
compile/eval, and cache production. Turn the table above into deterministic
fixtures before changing behavior.

### 1. Raw reply and parse-once transition

Capture the provider reply before any interpretation, parse those exact bytes
once, and carry ordered parser entries into `eval-batch!`. Specify the lifecycle
fence at each attempt so a process death distinguishes the in-flight entry from
entries never started.

Delete after proof: the `strip-result-claims` call in the batch reply path, the
cleaned-reply claim in comments/tests, and new writes of
`:seon.agent.turn/results-stripped`. Preserve historical schema readability if
existing databases contain the attribute; do not migrate by fabricating raw
blobs.

### 2. One complete async contract boundary

Generalize `injecting-fschema` to the supported public async shapes while
preserving arity and `^:async` identity. Make the census include every async
contract and distinguish validated functions from narrowly justified,
source-grounded exceptions.

Delete after proof: `async-unwrappable?` exclusions that the strengthened owner
can handle and warning logic that equates an exclusion with coverage.

### 3. Cancel the one provider attempt

Create one abort controller per `bounded-llm-attempt!`, pass its signal through
the existing provider abstraction into both adapters, and abort on timeout,
lifecycle invalidation, or early stream completion. Keep `call-llm!` as the
only retry owner and keep SDK retries disabled.

Delete after proof: timeout races that release only the caller and any adapter-
local cancellation path superseded by the shared attempt signal.

### 4. Strengthen the one plan transition model

Add schema-owned completion evidence and explicit caller/authority/expected-
state inputs. Make completion, reopening, activation, and address-step priority
atomic database transitions. Prefer refs to existing eval/test/database facts;
add a small evidence entity only when the evidence is not already an addressable
fact. Derive readiness and priority instead of adding a mutable queue, role
enum, parallel ledger, or completion flag.

Delete after proof: unconditional id-only transition arities and tests that
accept completion from status alone.

### 5. Measure, then select one hard eval boundary

Build a disposable experiment against the same eval contract and measure sync
CPU, JavaScript heap, ArrayBuffer/external memory, native/dependency allocation,
worker crash, cancellation latency, analyzer reconstruction, and process
cleanup. Select worker threads only if they satisfy the criteria below;
otherwise use a child process with bounded Node heap and OS-level containment.
Publish receipts and failures through the existing eval/run/turn facts and
fences. Do not reuse the diffusion oracle server as application eval or create
a second database writer.

Delete after proof: in-process arbitrary self-host execution for agent forms
and any duplicated worker evaluator/pool created during the experiment.

### 6. Graduate with deterministic Inspect transitions

Add focused offline tasks/scorers for the transition matrix and run them through
the maintained runtime. Only after those gates pass should small-model or paid
provider trials measure discoverability and behavioral performance.

Delete after proof: bespoke drive scripts or fixtures that bypass the operator,
agent loop, plan API, or eval contract.

## Acceptance gates

### Focused correctness gates

- Parser/eval: focused `seon.repl.internal`, eval batch/repair, Promise, result
  admission, and memory-budget tests prove byte identity, ordered attempts,
  read/eval errors, and no fabricated rows.
- Turn/provider: reply capture, retry, timeout, and fake-adapter tests prove one
  retry owner, signal propagation, abort cleanup, and stale-completion fencing.
- Instrumentation: delta, smoke, resilience, and a full async census prove
  awaited output validation and structured rejection/error values.
- Plans/messages: `my.plan`, plan-internal, and message tests prove evidence,
  authority, CAS/idempotency, address priority, resume, and cross-agent laws.
- Recovery: runtime recovery and run tests retain one fenced transaction,
  truthful interruption, derived notices, and idempotency.
- Run the smallest focused `bin/test-cljs` selections while iterating, then the
  complete relevant pod gate and `bin/seon test operator` at slice boundaries.

### Live default-cluster evidence

- A stubbed reply with result/scaffolding glyphs hashes and reads byte-identical
  from its linked blob while only real forms receive ordered eval rows.
- A representative formerly excluded async function returns a resolved schema
  violation as structured data and the next REPL form runs.
- A fake non-billing provider observes abort on timeout and leaves no live
  request or late publication.
- Plan queries show completion evidence and caller authority; attempted invalid
  reopen/displacement writes no transition.
- After explicit destructive-test coordination, a killed/exhausted eval boundary
  leaves pod and writer healthy, records only the in-flight interruption, and
  permits a later turn without replaying effects.

### Inspect gates

- Keep the existing offline long-term planning fixtures and address-step scorer.
- Add deterministic transition scorers for raw reply identity, ordered eval
  evidence, async failure recovery, plan evidence/authority, provider abort, and
  isolated process death.
- Do not call paid providers to diagnose deterministic runtime failures. Paid
  and small-model matrices begin only after the runtime gates pass.

## Containment decision criteria

| Criterion | Worker thread passes only if | Child process implication |
|---|---|---|
| Synchronous CPU | termination is prompt and the pod event loop remains responsive | Parent owns deadline and kill escalation. |
| JavaScript heap | `resourceLimits` stops the isolate without killing the pod | Use explicit Node heap flags per child. |
| External/native memory | measured ArrayBuffer/native allocation stays within a hard bound | Apply OS/process memory limits; Node heap flags alone are insufficient. |
| Crash isolation | uncaught fatal worker failure cannot terminate/corrupt the pod | Distinct process identity is the stronger boundary. |
| Analyzer/session semantics | compiler state and prior committed definitions reconstruct deterministically within acceptable latency | Child startup must rebuild from committed source/analyzer facts, never replay side effects. |
| Database authority | worker performs no direct durable writes and effects retain receipt/CAS fencing | Keep writer protocol and pod replica authority unchanged. |
| Cleanup/backpressure | ports, listeners, queued tasks, and aborted work are removed and bounded | Parent supervisor must reap children and reject bounded queues. |
| Evidence | one in-flight interruption is recorded; unstarted forms receive no eval rows | Same invariant; process exit status is diagnostic input, not fabricated execution. |

## Uncertainties and required measurements

- Exact selected ClojureScript source must be mirrored before an implementation
  plan depends on analyzer/eval internals. Shadow-sensitive claims must cite
  the exact `3.4.10` release commit already present in reference history.
- It is not yet proven whether Node worker `resourceLimits` contains the
  external/native allocation patterns available through the actual agent
  namespace. That determines worker thread versus child process.
- The smallest plan evidence shape depends on whether all required proof can be
  referenced from existing eval, transaction, blob, and test facts. Do not add
  a generic evidence map before enumerating those facts.
- Mid-batch lifecycle fencing needs a focused experiment to decide whether the
  fence belongs immediately before each form, inside the isolated execution
  receipt, or both.
- Inspect environment package metadata appears to identify a development build;
  pin it when the new scorers require behavior not already covered by the
  checked-in fixtures.
