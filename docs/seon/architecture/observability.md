---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Observability — inspect any agent, any turn

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

Every question about agent behavior — "what exactly did this agent see at turn
N?", "what changed between turn N and N+1?", "why did it do that?" — is answered
by a **query against the database plus the blob archive**. Process logs remain
necessary operational evidence for startup, readiness, transport, and crashes;
they are not the durable forensic truth of an agent turn.
This falls out of the core property: a turn's prompt is a pure render of one
frozen database value. The turn retains a ref to that value's basis transaction,
so the same structured inputs can be queried with `as-of`; the complete database
value remains request-scoped. Observability is not a subsystem bolted on; it is the
derive-everything principle pointed backwards in time.

## The turn record

Every datom already names its Datahike transaction. Normal Seon transactions
add `:seon.db/user` and `:seon.db/process` refs on that transaction, so
“who/through which execution path wrote this datom?” is a join, never a stored
domain back-reference. Turn/eval causality is intentionally not copied onto
arbitrary transactions: ordinary run/turn/eval/message refs record the modeled
facts, but Seon does not claim it can enumerate or replay every external effect
caused inside a turn. What every turn additionally persists—always on, no debug
flag:

- **The rendered transaction** —
  `:seon.agent.turn/rendered-tx` is a native Datahike ref to the basis
  transaction of the request-scoped database value captured before prompt
  rendering. This is the one historical fact the turn's own transaction cannot
  supply because other commits may interleave before the turn is recorded.
  `render-prompt` applies `as-of` at that transaction to reproduce the
  structured context. Rare exact-branch forensics resolve the transaction's
  originating commit from Datahike's retained commit graph at the authority;
  every turn does not duplicate a commit descriptor.
- **`:seon.agent.turn/prompt-blob`** — the assembled prompt verbatim, in the
  blob archive. The as-of re-render is the *structured, queryable* view; the blob
  is the byte ground truth that survives render-code changes (a re-render runs
  TODAY's render fns; the blob is what the model actually saw).
- **`:seon.agent.turn/reply-blob`** — the raw LLM reply. Not derivable from
  anything, so it is stored: a blob ref on the turn (content-addressing makes
  repeated replies free). An errored turn stores WHY instead —
  `:seon.agent.turn/error`, the failure as a bounded data string — so capture
  never depends on turn success.
- **The volatile prompt inputs, as data** — any `result/<id>` values rendered
  into the prompt are recorded on the turn, so nothing the model saw came
  from an unrecorded volatile.
- **`:seon.agent.turn/cause-message`** — the exact inbound human message the
  runtime assigned this turn to answer, when one exists. It is not guessed from
  the run opener because a run can absorb later messages.
- The existing projections: prompt size (tokens at display), `llm-usage` /
  `llm-meta`, the `:seon.eval` component refs, status, retries.
- **Provider attempts as facts** — every retry attempt connects to the turn
  with its ordinal, resolved non-secret
  transport projection, adapter and outer timeout layers, outcome, and present
  response model/fingerprint/request identity. The adapter consumes that one
  resolved value without rereading mutable config. Missing response fields stay
  absent; credentials, headers, and signed parameters never enter evidence.
  Historical projection uses the parent turn's rendered transaction; attempts
  do not copy database identity. Projection re-derives the adapter and stream
  mode at that same historical value. Evidence-size
  policy is read from the config singleton in the final immutable response
  value, once per projection. The process-owned outer attempt bound is retained
  as the exact applied value; admission rejects drift within a run and binds
  cross-run comparison to the admitted process environment. An absent policy
  or rejected optional identity makes formal evidence
  incomplete without changing the provider request, model output, usage, or
  successful outcome; rejected bytes never enter an error value.

**Datom vs blob is decided by size, never by kind.** The DB stores projections
and small values; large text — a prompt, a raw reply, a big eval result —
lives in the blob archive behind a datom ref. The DB is never a text dump.

## The blob archive — `my.blob`

The disk tier of the three-tier storage rule (datoms = projections, blobs =
persistent full content, stash = volatile live values), made a first-class
capability instead of ad-hoc log files:

- **Content-addressed** — a blob's name is its content hash; writes are
  idempotent, identical content dedupes for free. Blobs live under the
  cluster dir, beside the database backend they annotate.
- **A blob ref is data** — the datom carries the hash plus a token estimate
  and a media hint, so queries can filter and budget without touching disk.
- **Functions** — `my.blob/put!`, `my.blob/get`, `my.blob/text` (paged, honest
  totals), on the same never-throw result envelope as every toolkit function.
- **One archive, many producers** — turn capture, oversized eval results, and
  agent-authored artifacts all use the same functions. There is no separate
  "debug capture" file tree; debug persistence IS blob persistence.
- **Discoverable** — database blob projections are queryable by hash, media,
  and token estimate. Literal or semantic content search reuses the protected
  search/embedding capabilities when explicitly requested; `my.blob` does not
  create another index.

Execution-child telemetry follows the same storage law. Bun process handles,
active invocation timers, and demanded live CPU/RSS samples remain transient in
the parent execution host. A loopback operator read returns one demanded
ordinary-data `seon.execution.host/processes` snapshot for root tooling and
Inspect without asking child event loops to cooperate. Healthy sampling does
not write transactions. On
deadline, exit, or explicit forensic capture, one bounded terminal snapshot
becomes queryable recovery datoms and the complete structured report becomes one
`:diagnostic` blob. The blob contains sample history, raw or source-mapped stack
frames, full invocation identity, and complete retained output; the recovery
entity keeps only measurement units and clipped tails needed for ordinary
queries and the root system view.

Stack attribution is evidence, not certainty. A thrown exception supplies an
ordinary JSC stack. A non-responsive child may receive a short on-demand native
sample after the soft threshold; generated frames are mapped through the exact
ClojureScript source map and trimmed to the first agent-authored frame plus its
callers and the owning Seon boundary. Reports retain raw frames and sample share
so optimized, native, or blocked-system-call cases can say that attribution is
incomplete rather than inventing an exact source line. Always-on JSC profiling
is diagnostic-only unless measured overhead proves it suitable for the default.

## Replay, diff, search

Three functions make a turn a first-class object of study:

- **`agent-debug/turn`** — one call returns the whole bundle for agent X, turn N:
  the exact prompt (blob), the structured context (as-of re-render, per-block),
  the reply, the evals with results, usage, and the messages visible at that
  database value. No joins by hand, no filesystem.
- **`agent-debug/turn-diff`** — what changed between two turns: a block-level diff
  of the two rendered contexts plus the datom delta when both commits share the
  required ancestry (`db/since`). Different lineages use two immutable snapshot
  results and report that no linear since-range exists. This is also the
  cache-stability instrument: bytes that should have been frozen but moved show
  up here.
- **`agent-debug/ctx-preview`** — generalized over time: preview any agent's
  context at any complete ordinary database value, not just now. Turn replay
  constructs that request value from the selected database plus the turn's
  rendered transaction. Exact cross-lineage branch work resolves a retained
  commit at the authority rather than guessing one from a transaction number.

Search runs at two ends, one door each, and nothing in between:

- **Literal** — `grep-graph` targets every text-carrying attr: fns, schemas,
  evals, messages, turns, and blob-backed prompts/replies; filterable by
  agent, time range, and attr.
- **Semantic** — the ONE `:seon/embedding` index. Writer boot resolves the
  configured trigger attributes and compose symbols into one immutable
  embedding pipeline; requiring a namespace never mutates it. `search-pull`
  scopes KNN by a datalog `:where`. No second index or separate FTS engine —
  exact regex and semantic KNN cover the spectrum.

## Error recording — fault-tagged datoms + the strict gate

Errors join turn replay as first-class DB objects. `seon.error/record!`
is the catch-site function (the iron rule as a fn: nothing is caught without
becoming data): it classifies `:seon.error/fault` (`:agent` — expected,
the agent's learning signal; `:core` — our bug), stamps the complete ordinary
database value under `:seon.error/db` live at the catch site—the
resolved immutable db is the frozen state the failing code saw, composing directly with
`agent-debug/turn` replay), parses the stack into `:seon.error/frames`
component entities (Datalog-queryable traces), and keeps the full args
of malli contract violations as bounded `:seon.error/args-edn`.
Persistence is fire-and-forget (never throws, never awaits; a bounded
drop-oldest buffer rides out conn-less windows) and one caught failure yields
one deduplicated error record in one recording transaction (propagating
rejections are dedup-tagged).

Two capture layers need zero per-site work: the Malli instrumentation
wrapper's async arms (rejections and resolved-value output violations) and the process net
(`uncaughtException`/`unhandledRejection` → fault `:core`).

Expected-test classification and the error-write recursion fence are
invocation-scoped facts. They follow only the asynchronous work spawned by that
invocation, are never persisted as runtime modes, and cannot suppress another
agent's concurrent fault.

`:seon.config/on-core-error` governs `:core` faults only. Agent faults remain
values in every mode. A failed program publication or readiness transition
records one bounded core fault and fails development admission; a production
render boundary may return the configured bounded fallback after recording the
same occurrence. An outer boundary does not record an error already recorded by
its inner owner, so a derived rerender cannot create an error-invalidation loop.

Persisted messages name the deepest available real cause rather than a generic
wrapper. Root context may derive one concise current fault signal; detailed
frames, arguments, and reproduction data remain on-demand forensic views.

Triage runs through three `seon.agent.debug` functions, three altitudes over
the same datoms:

- **`errors`** — compact recent list, newest first (optional
  `:seon.error/fault` filter + limit): per row the error eid, fault, complete
  ordinary database value,
  deepest-cause short message, top stack frame, and the recording agent.
- **`error`** — one full envelope by eid (message, fault, database value, frames
  table, args-edn, data-edn, stack) plus the JOINS: the recording agent and
  the turn active at that database value (derived from the agent's turn windows and
  ordinary domain refs) — the turn eid composes with `agent-debug/turn`
  inspection.
- **`repro`** — the work-backwards bundle: the LIVE immutable db value resolved
  from the error's ordinary database value (REPL material—render `:t` + abbreviated commit,
  never print the db),
  the failing fn sym + args-edn when the malli envelope captured them, the
  linked turn, a ready-to-eval reproduction expression string built from
  what is actually stored (an honest note when args were not captured —
  nothing fabricated), and the `::fork-hint` — the exact supervisor command
  that boots this error's view as a live cluster (below).

The as-of db is read-only; when the fix needs a WRITABLE view—re-running
safe code, patching data, letting a forensic agent act—the fourth step is
**fork**: the operator creates a Datahike copy-on-write branch at the retained
commit, then starts a non-autonomous forensic runtime for the new database name. It
does not copy Konserve or define another versioning model. Entity/transaction
ids through the fork point are identical, but database values always retain
`:db-name` and `:datahike/commit-id` because later transaction ids can
diverge/reuse after a reset.

No bare-`:t` convenience selector exists; the complete database value names a
temporal cut inside one retained immutable containing commit. The debug pod starts
non-autonomously: opening history installs no ticker, wake trigger, or agent
host and never resumes agents, schedules, providers, or external-effect workers.

The database-value semantics are precise: it is captured at the catch site—the db
value the failing code saw—while the error datom itself commits later, so the
error datom does not exist inside its own historical view. Branch-local blob
overlays, promotion, garbage collection, and remote retention are related
designs owned by the database-lifecycle-recovery and blob-lifecycle PRDs. The
stable forensic flow is fault list → error detail → reproduction bundle →
non-autonomous historical runtime; operator command names are not part of
the data model.

Execution-child recovery retains two database values rather than inventing a
rollback point: the value pinned for the interrupted invocation and the current
value at which recovery terminalizes it. Their basis transactions and commit IDs
show exactly what the child saw and which transactions committed before it
disappeared. The process exit record retains the admitted artifact and source
identity, PID, signal or exit code, deadline classification, bounded stdout and
stderr tails, and available resource usage. The next-turn recovery render states
that a replacement loaded the current database program and that transient
Promises, handles, and `result/<id>` values were lost. It never claims committed
effects were undone or silently retries an interrupted effect.

## The forensic agent

Debugging an agent is done **by another agent given the exact db the target
saw**, not by a human reading logs:

- Mint a forensic agent in an **ephemeral writable branch** rooted at the
  target's resolved commit, so it
  sees exactly what the target saw at that moment.
- Seed its ctx with the target's **reconstructed context blocks** plus one
  extra **debug-brief block**: the behavior in question and the ask —
  "identify why the agent did this; answer in clear markdown."
- It **evals code to investigate** — its transactions land in its OWN
  cluster's db, advancing that copy realistically, never touching the
  target's cluster.
- **Per-agent LLM config** selects a cheap reasoning model with thinking ON
  for these runs, so forensic passes are routine, not precious.

This is a composition of existing mechanisms—pod isolation, Datahike branch
roots, seed-copy ctx override via `install!`, as-of reconstruction,
per-agent provider routing — not a new runtime. A forensic pass is cheap
enough to run on every puzzling drive.

## Cluster lifecycle and the composition door

Isolation is the CLUSTER: one shared db + its agents, one Bun pod per
cluster, all databases hosted by the one JVM database server (the registry). From
inside a cluster there is ONE conn and ONE database — agents never know
other clusters exist. Database enumeration, fork, release, and deletion are
typed root/supervisor operations in `seon.db.registry`, never agent protocol
operations. The supervisor owns the lifecycle:

- creation establishes one registered database name and pod;
- fork requires a complete retained database value and creates a writable Datahike
  branch without physically copying the database;
- a historical or forensic runtime starts non-autonomously; and
- destroy quiesces users of the target and removes only resources owned by that
  database name. A branch cannot delete source-database content.

`POST /agents/run` is the one-shot composition door on every pod, built
purely from the agent primitives: start-or-reuse an agent in the pod's own
cluster (optional `agent_id` — durable database, so the same agent can be
driven again across a pod restart), deliver the input through the real wake
path, await the derived `:idle` of the run it woke, return the truthful
reply plus termination metadata (turns/evals scoped to this request's
window, closed-reason, timed-out), an ordered `eval_evidence` projection of
that same window's eval ids, times, sources, success facts, and present
narration and result data, plus the ordered bounded provider-attempt facts
connected to those turns.
The pure `seon.ai/resolved-config` reconstructs configuration intent at each
attempt database value; stored attempt facts preserve non-derivable response
identity and outcome. A response-time final database value is never mislabeled as
call evidence. Inspect AI copies the projection unchanged and rejects missing
or drifting transport evidence before capability scoring. Inspect AI drives
per-sample ephemeral clusters by port through this same production boundary;
there is no in-process evaluator lifecycle. The answer key never enters the pod
— scoring stays host-side. Benchmark vocabulary is harness-side only.

An explicit `timeout_ms` is a caller-selected experiment bound. When it is
absent, the door derives its wait duration from the same frozen database run
policy and optional agent override that `open-run!` uses. The HTTP boundary
never introduces a shorter literal deadline that can terminate an otherwise
healthy database-owned run. Its response projects the effective duration and
whether its source was the request or database; Inspect retains both in the
native sample metadata. These are derived evidence, not another config value.

The eval projection is derived from the same final immutable database value
and exact turn-entity set used for the response counts. General eval results,
printed output, exception stacks, and unrelated source dumps never enter the
door. The bounded result projection comes from the eval row recorded in the
same database authority as the turn; it does not preserve a second execution
trace or database-operation log. Inspect consumes this production response
directly and does not issue arbitrary forms through the writer REPL to
reconstruct eval rows.

Standard Inspect tasks measure the selected model and scorer. Pod-backed Inspect
tasks measure Seon's production agent/runtime behavior through this door; the
two claims are reported separately and no duplicate evaluator is created.
Every admitted live run retains both its opening and closing source admission
plus the operator-owned target identity in the native `.eval`. A formal local-
model run also retains a closed model-server identity: the exact request
endpoint, stable process start, serving-module and package identity, absolute
revision-pinned model path, canonical weights-manifest digest, quantization,
and expected response identity. Every provider attempt joins to that exact
endpoint and model path; a successful attempt also joins its response model
and server fingerprint. The model-server snapshot is observed before task
construction and again after the terminal log is published. Remotely managed
weights and mutable local tags remain diagnostic until their serving boundary
can prove the loaded content digest.

The admitted opening identities are one immutable value at the run boundary.
Inspect retains them at eval scope and exact-merges them into every sample's
state before task-owned setup runs, so setup, solver admission, and scoring see
the same maps. A byte-equal value already present on a sample is accepted; a
contradiction rejects the sample before task setup can produce side effects.
Eval metadata alone never stands in for sample-state evidence.

All closing observations are written before evidence finalization. A changed
source, target, or model-server identity makes the run rejected infrastructure
evidence while preserving the terminal bytes; it never becomes a capability
score. The target identity includes one canonical digest of the operator
closure and runtime that started the processes, so a checkout that later
converges cannot make transient launch bytes appear reproducible.
An incorrect scored sample receives one explicit human-reviewed failure label
from the frozen experiment taxonomy on that existing score. The edit preserves
the scorer's value, explanation, metadata, and aggregate metrics, and Inspect's
native score history records who made the classification and why. Passing
scores carry no manufactured failure label, infrastructure failures remain
unscored, and no heuristic classifier becomes another evidence authority.
Long-term planning is a pod-backed Inspect task: one ephemeral cluster spans
multiple interactions and a pod restart, then host-side scoring reads the
resulting plan and eval facts. Offline good/bad fixtures exercise the same
scorer without claiming to measure the pod.

Every turn uses the one complete ordinary database value plus prompt/reply blob
refs. `seon.agent.debug/turn` and `turn-diff` reconstruct and compare turns from
those facts. Inspect and debug projections read the same blobs by hash.
