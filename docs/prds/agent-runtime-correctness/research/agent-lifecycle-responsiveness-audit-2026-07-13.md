---
type: research
status: active
tags: [research, agent, database, web]
---

# Agent lifecycle and responsiveness audit — 2026-07-13

## TL;DR

The live pod is healthy at `http://127.0.0.1:7890`, and the current process is
idle rather than pegging a core. The cold application boot observed today took
about 8.3 seconds from `-main boot` to `auto-boot ready`; about 7.4 seconds sit
between the transaction feed becoming live and program replay, but the boot path
has no phase timers, so source indexing is only the leading hypothesis, not a
proven attribution.

The most important correction to the current PRD is that `POST /agents/new` no
longer calls cold startup. It performs one atomic birth transaction and then a
warm runtime resume. Global program replay and global instrumentation are not in
that path. The remaining warm-mint costs are a fresh config-file read and Malli
decode, plus a self-hosted `(ns …)` analysis against the shared compiler state.
Those costs have not yet been measured live after the split.

The highest-confidence remaining blockers are:

1. Every database transaction is decoded and grouped once per listener, and one
   wake listener is installed per hosted agent. The wire adapter also schedules
   one timer per listener per transaction. This is an avoidable
   `O(hosted agents × transactions × transaction datoms)` fan-out.
2. A converged cold boot still commits at least five attributed transactions
   before optional recovery/root/AI/brand writes. `state/reconcile!` always sends
   the complete desired set, even when it is unchanged. Datahike assigns
   transaction metadata and advances `max-tx` even for empty domain input.
3. The cold program snapshot rereads and reparses the same source file once per
   function/test var. It also rescans and reads every skill file despite skills
   not being default context. The old second “ghost pruning” pass is gone, but
   the residual per-var ghost detector is evidence that source acceptance is
   still derived from a stale runtime-var roster instead of one file-grouped
   snapshot.
4. The shared `cljs.js` compiler state has neither a singleflight bootstrap nor
   a serialized mutation queue. Concurrent mints/evals can enter asynchronous
   analysis against the same atom; correctness has not been mechanically proven.
5. Every turn synchronously hashes and writes its full prompt and reply on the
   Node event-loop thread, then commits a blob projection transaction. The
   content-addressed file is idempotent, but the projection always writes a new
   `:my.blob/at` value. This is a direct pay-for-observability latency risk.
6. `/agents/run` polls the database every 1.5 seconds, adding up to 1.5 seconds
   of completion latency. LLM timeouts free the awaiter but do not guarantee
   cancellation of the underlying request. The single ticker can overlap itself
   when a pass exceeds its cadence.
7. The current persistent Datahike index is in the old format without subtree
   counts, so the query planner is using rough heuristics. The grown-store cost
   is unknown until the maintained Datahike path has a supported reindex/migrate
   operation and a before/after benchmark.

The next work should be structural and measurable: phase-time cold boot, make
warm mint a measured transition, replace per-agent listeners with one routed
process subscription, make boot reconciliation compute an exact delta, group
source work by file, serialize compiler mutations, make blob I/O nonblocking and
truly idempotent, and replace endpoint polling with a reactive completion signal.

## Scope and method

This was a read-only audit of the active CLJS pod, its startup supervisor, live
logs/processes, behavioral tests, and the vendored ClojureScript, Malli, and
Datahike sources. No restart, agent mint, database write, ACME operation, or
runtime edit was performed.

The current code and live process take precedence over stale roadmap prose.
Claims below distinguish observation from a hypothesis that still needs timing
or profiling.

## Live baseline

- Pod: PID 51847, started `2026-07-13T03:09:18Z`, HTTP port 7890.
- Wire server: PID 51638. Shadow watch: PID 26551.
- Cold application sequence in `logs/pod.log:11-25`:
  - `-main boot`: `03:09:19.524Z`
  - store attached: `03:09:19.623Z`
  - transaction feed live: `03:09:20.043Z`
  - replay logged: `03:09:27.445Z`
  - 708 functions instrumented: `03:09:27.604Z`
  - HTTP listening: `03:09:27.625Z`
  - ticker and runtime ready: `03:09:27.844Z`
- Current CPU is effectively idle. A three-sample `top` observation showed 0%
  pod CPU, and a three-second stack sample found the main thread waiting in
  `kevent`. The earlier `%CPU` snapshot is not evidence of a live spin.
- Current pod RSS was about 370 MB; the sample reported a historical peak near
  1.1 GB. This audit did not force GC or create load, so it does not claim a leak.
- There were zero open Datastar feeds at the probe. The shared feed listener had
  correctly uninstalled itself.
- A stale operator shell has remained for roughly seven hours because
  `bin/seon tail pod | tail -120` cannot terminate: `bin/seon tail` is an
  intentional `tail -f`. This is not a pod leak. Snapshot callers should use
  `bin/seon logs pod 120`; the CLI should reject or document the pipe trap
  (`bin/seon:1449-1457`).

## Exact lifecycle inventory

### Cold process start

The supervisor starts dependencies, performs a CSS build, launches Node, then
waits for the port file, the in-process ready marker, HTTP success, and three
consecutive observations (`bin/seon:238-299`, `bin/seon:360-380`,
`bin/seon:467-622`). The final stability observations intentionally add about
two seconds after application readiness.

Inside Node, `start-runtime!` performs this transition
(`src/seon/client.cljs:2356-2471`):

1. Ping the writer, ensure the cluster database, connect the local reader,
   establish provenance, transact the complete runtime schema, and start the
   lossless transaction-feed adapter (`src/seon/client.cljs:677-710`).
2. Start bootstrap compiler reconstruction concurrently with the independent
   boot seed.
3. Build the complete core program snapshot and load the optional manifest.
4. Commit entity-schema rows, core seed rows, core-index rows, then reconcile
   routes, skills, and the config singleton (`src/seon/client.cljs:2203-2351`).
5. Close every orphaned open run as `:crashed`.
6. Complete the reserved root entity if needed.
7. Replay database-authored program rows into the compiler.
8. Query and instrument the canonical function snapshot once.
9. Resume every nonterminated agent serially: analyze its home namespace,
   install/replace its wake listener, and advertise it as hosted.
10. Bind HTTP, then sync AI and brand configuration, install the ticker, and
    announce ready.

The HTTP socket is bound before AI/brand sync and before the runtime-ready log
(`src/seon/client.cljs:2456-2465`). The supervisor does not publish readiness
early, but a direct client can reach the socket during that window. Either bind
last or return 503 from all handlers until the runtime-ready state is true.

### Warm mint (`POST /agents/new`)

The handler calls `agent/start!` directly and explicitly promises no seed,
replay, or global instrumentation (`src/seon/web/serve.cljs:314-346`). The path
is:

1. Parse the optional purpose.
2. Check the caller's derived spawn depth when an agent owns the call.
3. Read and validate the manifest, resolve the agent-context defaults, and build
   the complete agent plus home-namespace transaction
   (`src/seon/agent.cljs:413-441`, `src/seon/config.cljs:528-547`,
   `src/seon/config.cljs:1323-1339`).
4. Ask the sole writer to allocate a readable unique identity and atomically
   commit all birth facts (`src/seon/agent.cljs:494-521`).
5. Resume that committed identity: ensure the shared bootstrap state, analyze
   one `(ns …)` form, replace/install its wake listener, and host it
   (`src/seon/agent/runtime.cljs:75-115`, `src/seon/eval.cljs:1531-1589`).
6. Return the ID only after resume succeeds.

Because birth stores `:seon.eval/home-requires`, normal resume reads the database
rather than the manifest (`src/seon/agent/home.cljs:54-92`). The manifest is
still read once per new birth. The current PRD statement that `/agents/new`
re-enters cold startup is stale.

### Cold resume of existing agents

Cold boot enumerates every nonterminated durable agent and resumes each one
serially (`src/seon/client.cljs:2407-2449`). Resume never writes durable state;
it checks the entity, reconstructs the home namespace in the shared compiler,
installs a per-agent wake listener, and records process-local hosting
(`src/seon/agent/runtime.cljs:75-115`).

This gives simple correctness at small scale but makes boot time and steady-state
listener cost proportional to all historical nonterminated agents. The hosting
policy is a design choice requiring an owner answer below.

### Message, run, turn, and eval

1. A message transaction reaches every native listener. Each hosted agent's
   listener resolves its own agent eid, then inspects `:seon.agent.message/to`.
   A matching idle agent schedules `open-run!` and the run loop; a running agent
   schedules lease renewal (`src/seon/agent/loop.cljs:404-503`).
2. The loop writes a heartbeat, freezes one database value, renders context,
   captures the full prompt blob, and allocates/opens a fenced turn.
3. One LLM completion runs under per-attempt timeout and retry policy.
4. The cleaned reply is captured as a blob and immediately linked to the turn,
   then forms are evaluated and each eval is persisted.
5. The turn closes with telemetry; the loop runs derived plan escalation and
   either starts the next turn or closes/waits/pauses/completes the run.

One turn means one LLM completion. It does **not** globally mean one eval:

- `:stream` mode keeps only the first complete form, so it permits one
  actionable eval per LLM turn (`src/seon/agent/turn.cljs:393-409`).
- `:batch` evaluates every parsed form in the completion.
- DeepSeek defaults to `:stream`; other providers default to `:batch`, unless
  the manifest overrides it (`src/seon/config.cljs:609-627`). The current pod
  reports openai-compatible mode, so its fresh default is `:batch`.

The turn's minimum durable write surface is substantial: prompt blob projection,
turn-open allocation, reply blob projection, eager reply link, one transaction
per eval, and turn close. Run open/heartbeat/close and optional planner messages
are additional transactions. This is legitimate event history, but it amplifies
the listener fan-out described below.

### Crash recovery, reconnect, and supervision

- Boot closes every open run, including paused runs, as `:crashed`
  (`src/seon/agent/run.cljs:758-800`). It avoids a permanently wedged pointer but
  does not automatically resume interrupted work.
- The wire transaction feed is generation-fenced, reconnects once per loss, and
  replays from its basis watermark (`src/seon/store/wire.cljs:846-900`). No
  duplicate feed loop was observed.
- Timeout and watchdog CAS fences prevent late durable writes to a superseded
  run, but `race-timeout` does not cancel generic underlying work
  (`src/seon/agent/turn.cljs:472-503`).
- The one 30-second ticker runs deadline close, stale-run close, and scheduled
  work in sequence. `setInterval` has no in-flight guard, so a slow pass can
  overlap the next (`src/seon/agent/loop.cljs:692-731`).

## Prioritized blockers and fixes

### P0 — one routed transaction subscription, not one decoder/timer per agent

Datahike stores callbacks in one listener map and invokes all of them for every
transaction (`reference-code/datahike/src/datahike/core.cljc:206-224`,
`reference-code/datahike/src/datahike/writer.cljc:335-358`). Seon's foreign-feed
adapter then creates one `setTimeout(0)` per listener
(`src/seon/store/wire.cljs:567-586`). Every wrapper separately runs `mapv` and
`group-by` over the same transaction (`src/seon/db/internal.cljs:1920-1953`),
despite the comment claiming the rich shape avoids repeated grouping. Every
hosted agent installs one such listener (`src/seon/agent/loop.cljs:621-647`).

Replace this with one process transaction subscription that normalizes the
report once, derives target agent IDs from changed message refs, routes wake work
only to recipients, and fans UI invalidations through the existing dependency
router. Keep subscriptions process-local and derived; do not persist them.

### P0 — serialize shared compiler mutation and singleflight bootstrap

`ensure-bootstrap!` caches only the completed state; two concurrent misses can
both run `init-bootstrap!` (`src/seon/repl.cljs:84-116`). Each fresh init
synchronously reads and transit-decodes every analysis cache
(`src/seon/eval/bootstrap_cache.cljs:27-64`). The current bootstrap directory
contains 46 analysis files totaling about 544,000 estimated tokens.

More importantly, ClojureScript defines compiler state as an atom and loads
analysis caches through `swap!` (`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:129-142`),
while `eval-str` performs asynchronous analysis/evaluation against that shared
state (`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:1138-1203`). Add
one in-flight bootstrap promise and one ordered compiler-operation queue (or
prove a narrower safe partition). Concurrent mints and evals must not mutate the
same analyzer state without a defined order.

### P0 — exact no-op boot reconciliation

The runtime schema is always transacted (`src/seon/client.cljs:630-651`), then
boot unconditionally submits entity schemas, core seed, core index, and the
config reconcile (`src/seon/client.cljs:2266-2324`). `state/reconcile!` computes
only stale entity identities; it sends every desired entity map on every boot
and reports the desired count as “upserted” even when no fact changed
(`src/seon/state.cljs:59-139`).

This is not free in Datahike. Every submitted transaction adds `:db/txInstant`,
flushes transaction metadata, and increments `max-tx`, even with no effective
domain input (`reference-code/datahike/src/datahike/db/transaction.cljc:822-841`,
`reference-code/datahike/src/datahike/db/transaction.cljc:1125-1178`).

Build pure checks and exact transaction data per boot-owned population. Submit
nothing when the desired facts already match. Make config an explicit optional
operation rather than ambient boot work. A normal restart should reconstruct
runtime state without advancing the database basis when no durable fact changed.

### P0 — move full-prompt observability off the event loop

Prompt and reply capture are awaited on every turn
(`src/seon/agent/turn.cljs:153-175`, `src/seon/agent/turn.cljs:355-418`).
`my.blob/put!` computes SHA-256 and uses `existsSync`, `mkdirSync`, and
`writeFileSync` on the main Node thread, then always transacts a projection with
a fresh timestamp (`src/my/blob.cljs:204-245`). Large context therefore blocks
all agents and web feeds while it is hashed/written.

Keep content addressing, but use asynchronous filesystem I/O behind a bounded
singleflight writer keyed by hash. Transact the projection only when absent (or
when immutable facts differ); the first-observed timestamp must not turn an
idempotent content write into a new transaction. Preserve the rule that prompt
capture failure does not stop the turn.

### P1 — one file-grouped accepted program snapshot

`var->fn-row` reads and splits the complete source file once per var
(`src/seon/client.cljs:1207-1245`, `src/seon/client.cljs:1407-1444`), and
`var->test-row` repeats it for tests (`src/seon/client.cljs:1867-1894`).
`index-core!` enumerates the complete roster before diffing
(`src/seon/client.cljs:1752-1814`). Registered schema source is also truncated at
1,000 characters, which prevents the database from being the complete restorable
Malli registry (`src/seon/client.cljs:1816-1865`).

Read and parse each accepted file once, derive namespace/function/schema/test
rows from that immutable snapshot, and diff it against the database before
building a transaction. Remove the residual runtime-var `ghost-var?` path once
the accepted file snapshot owns membership. Store full canonical schema forms.

The old duplicate “rebuild the entire graph again for ghost pruning” pass is
already gone. Do not recreate it. The remaining `ghost-var?` helper
(`src/seon/client.cljs:1255-1266`) is a local omission guard, not a second graph
transaction.

### P1 — remove ambient skill scanning and repeated manifest reads

Boot still scans the skills directory and reads every `SKILL.md` frontmatter
(`src/my/skills.cljs:126-173`) and includes those rows in config reconcile
(`src/seon/client.cljs:2304-2324`). `config/skills-dir` rereads the manifest
internally (`src/seon/config.cljs:787-808`), so boot's explicitly loaded manifest
is not actually the only manifest read. This work remains even though skills are
not a default context section.

Delete the boot skill-corpus path as already planned. If optional config is
applied, load and validate it once, pass the value into every pure resolver, and
transact only its exact desired delta. Warm mint should resolve new-agent
defaults from database config facts, not reread a file.

### P1 — reactive completion and real cancellation accounting

`/agents/run` sleeps 1.5 seconds and re-runs several queries until the target run
is idle (`src/seon/web/serve.cljs:411-550`). Replace the poll with a promise or
subscription keyed by the run identity and completed by the routed transaction
signal. The final result should be computed once from the closing database
snapshot.

Provider SDKs already receive request timeouts, but the generic outer timeout
only abandons the await. Pass an abort signal when supported and maintain a
bounded process-local diagnostic registry for detached work. No durable “still
running” flag is needed; the registry exists to prove that timed-out work does
not accumulate.

### P1 — bound growing derived scans

`turn-index` walks and sorts all historical runs and turns for every new turn
(`src/seon/agent/ctx.cljs:362-379`, `src/seon/agent/turn.cljs:197-204`). Planner
escalation also derives its episode by scanning historical evals on each
productive turn. These are correct derive-don't-store choices, but the current
algorithms can make a long run progressively slower. Measure them on a grown
transcript, use indexed max/count queries or bounded episode inputs, and retain
facts rather than cached projections.

### P1 — migrate old Datahike indices through the maintained Datahike path

The wire log reports `:datahike/estimate-heuristic-fallback`: this store lacks
precomputed subtree counts. Datahike then estimates attribute and value
cardinalities from rough total-datom divisors instead of counted index slices
(`reference-code/datahike/src/datahike/query/estimate.cljc:35-73`,
`reference-code/datahike/src/datahike/query/estimate.cljc:154-162`).

Add or adopt one supported reindex/migration operation in the maintained
Datahike fork and benchmark the current grown store before and after. Do not add
a Seon-side planner fork. Until this is measured, “all queries are performant”
is not established.

### P2 — close smaller supervisor and observability gaps

- Add structured phase duration logs around connect/schema, core snapshot,
  each exact reconcile, compiler bootstrap, replay, instrumentation, per-agent
  resume, and readiness. The current 7.4-second gap cannot be attributed safely.
- Add an in-flight guard to the ticker; either skip/coalesce a tick or schedule
  the next pass after completion.
- Decide whether the supervisor's three readiness observations are worth the
  deliberate extra two seconds once the application has a strong readiness
  state.
- Give `bin/seon tail` a loud interactive-only contract or an explicit finite
  option so automation cannot accidentally retain a `tail -f` subprocess.
- Refresh the runtime-reliability `AGENTS.md`: its claims that `/agents/new`
  re-enters cold startup and that instrumentation scans on every mint are now
  false. The roadmap claim that the first-request race is closed is also too
  strong while HTTP binds before final sync/readiness.

## What is already sound

- Warm mint and cold boot are separate code paths.
- Birth is one allocation-plus-facts transaction; resume does not advance the
  database basis.
- Global instrumentation runs once during cold reconstruction. Agent evals use
  exact-symbol incremental instrumentation. Malli's implementation iterates the
  explicit supplied data and replaces only those vars
  (`reference-code/malli/src/malli/instrument.cljs:95-152`).
- The old second ghost-pruning graph pass is absent.
- Wire feed reconnect is watermark-replayed and generation-fenced.
- Web view listeners are lazy and uninstall when the final feed closes.
- Run/turn writes are CAS-fenced so a superseded driver cannot commit new work.
- PID/start-stamp/process-group supervisor checks are materially more robust than
  the old shell lifecycle.

## Mechanical acceptance gates

These gates should become behavioral or benchmark tests; none should assert
specific context wording.

### Warm mint

- Five sequential and ten concurrent mints against a warmed compiler complete
  with unique committed IDs and usable wake listeners.
- Each mint performs exactly one durable birth transaction, zero global seed or
  instrumentation queries, zero source/skill scans, and zero config-file reads.
- Record p50/p95 mint time and set the first budget from that trace. The existing
  roadmap goal of under one second p95 is a reasonable outer gate, not a claim
  about the current code.
- Concurrent setup cannot corrupt analyzer namespaces or lose definitions; the
  compiler queue exposes max depth and wait duration for the benchmark.

### Converged cold restart

- With no changed source/config/schema and no orphaned run, boot does not advance
  the database basis.
- Phase timers account for at least 95% of wall time and name every transaction.
- HTTP either remains unbound or returns 503 until all runtime dependencies are
  ready.
- Boot-time agent resume slope is measured at 1, 100, and 1,000 durable agents.

### Transaction routing

- One unrelated transaction causes one normalization/grouping operation,
  independent of hosted-agent count.
- A message to one agent schedules only that recipient's wake work; no timer is
  created for unrelated agents.
- Feed reconnect replays a missed message exactly once at the routing boundary.

### Turn responsiveness

- Blob writes do not perform synchronous filesystem I/O on the Node main thread.
- Rewriting identical content creates no new file and no new database
  transaction.
- `/agents/run` observes run close without a 1.5-second polling floor and issues
  no repeated Datalog query while waiting.
- A provider timeout aborts the request where supported; detached-work count
  returns to zero after the configured grace period.
- A ticker pass never overlaps another pass.
- Turn overhead excluding provider latency is measured across 1, 50, and 200
  historical turns to detect growth rather than asserting a response string.

### Datahike

- A migrated store reports subtree counts and emits no heuristic-fallback
  warning.
- Representative boot, wake, transcript, and planner queries have before/after
  p50/p95 timings on the grown store.
- Writable fork/restore and read-only `as-of` behavior remain intact after the
  migration.

## Owner questions

1. On cold recovery, should an interrupted run always become idle and wait for a
   new message (current behavior), or should Seon automatically open a **new**
   recovery turn from durable transcript/plan facts? Replaying the interrupted
   eval is unsafe; these are the two safe policies.
2. Should cold boot eagerly host every historical nonterminated agent forever,
   or host root plus an explicit/recent set and lazily host a recipient through
   the one routed transaction subscription? This choice determines the boot and
   steady-state scaling model.
3. Keep DeepSeek's one-form `:stream` policy, use multi-form `:batch`, or make it
   adaptive? Stream structurally prevents fabricated result tails but adds LLM
   round trips; this is a quality/latency policy, not an implementation accident.
4. Once application readiness is atomic, keep the supervisor's three consecutive
   one-second observations, or reduce that stabilization delay? The current wait
   is deliberate and separate from the 8.3-second application boot.

## Test gaps to fill

Existing behavioral tests cover atomic birth, no-write resume, crash-recovery
idempotence, wake behavior, wire replay, and incremental instrumentation. The
missing high-value tests are concurrent warm mint, compiler singleflight/order,
listener fan-out counters, ticker non-overlap, reactive `/agents/run` completion,
converged cold restart with unchanged basis, direct HTTP readiness, grown-store
index migration, long-plan scan growth, and provider cancellation/detached-work
cleanup.
