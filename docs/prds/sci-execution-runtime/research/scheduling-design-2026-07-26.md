---
type: research
status: active
tags: [research, runtime]
---

# Scheduling design: workload-tagged execution over the transaction log (2026-07-26)

The owner's directive, condensed: workload (`:io` / `:compute` / `:mixed`)
known per function, like schemas, so the runtime schedules every call on the
right executor; agents run in parallel as flows on one scheduled runtime;
the database — a Datomic-like transaction log with time travel and global
access — replaces channel messaging; smart defaults; turtles all the way
down, now at three levels: N cluster-writer flows, M agent flows, and every
function call inside an agent turn, all on one executor system. This
document designs that system, tests the claim that dropping channels loses
no needed semantics, and prices what it costs.

Verdict up front: **the split holds.** In `core.async.flow` a channel is
both the coordination medium and the scheduling boundary. Seon separates
them — the database is the medium (durable, global, temporal), and the
workload-tagged executor is the scheduling boundary, adopted from
core.async's own dispatch rather than reimplemented. Dropping channels
costs two genuine things (producer backpressure and cheap proc-local
state, §2); everything else has a Seon equivalent that is equal or
stronger. The scheduling half — the part the owner wants pushed down to
function granularity — is kept literally, as core.async's own executors
selected by a derived per-function fact.

Every claim below carries a file:line or symbol actually read. Numbers
carry their conditions. `[UNVERIFIED]` marks the exceptions.

## 1. Dependency ledger

Dependency source read in full or in the cited regions, this session:

| source | what it establishes |
|---|---|
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` (all 341 lines) | the flow contract: `create-flow` config (`:procs`/`:conns`/`:mixed-exec`/`:io-exec`/`:compute-exec`, `:76-106`), admin surface (`:108-161`), the step-fn arities describe/init/transition/transform (`:163-284`), `:workload` semantics (`:189-197`, `:259-281`), default `:mixed` (`:265`), `:compute` transform in a separate thread with `compute-timeout-ms` default 5000 (`:273-281`) |
| `reference-code/core.async/.../flow/impl.clj` (all 320 lines) | how a proc actually runs: `futurize` submits to `disp/executor-for` (`:29-34`); channels default `buf-or-n` 10 (`:101-109`); control chan 10, report/error sliding 100 (`:97-100`); casts sliding 100 (`:124-128`); mult/tap fan-out (`:135-139`); `Resolver.get-exec` = `(or (execs context) (disp/executor-for context))` (`:143-146`); the proc loop threads `[status state count read-ins]` and blocks in `alts!!` with `:priority true` over `[control casts & ins]` (`:269-319`); `:compute` transform runs via `.get` on a future with the timeout (`:256-258`) |
| `reference-code/core.async/.../flow/spi.clj` (all) | procs must be `:paused`/`:running`, must `alts!!` with control priority, should not transmit channel objects (`:26-55`) |
| `reference-code/core.async/.../async.clj:100-230, :330-460, :500-600` | buffer semantics (fixed blocks `:113-117`, dropping drops newest `:119-123`, sliding drops oldest `:125-130`); `chan` (`:138-153`); `alts!`/`alts!!` (`:343-409`); `go` compiles to `(thread-call (^:once fn* [] ...) :io)` when virtual threads are available — no state machine (`:519-533`); **the workload tag definition site**: `thread-call` — `:io` may block but must not do extended computation, `:compute` must not ever block, `:mixed` anything else, default `:mixed` (`:555-575`, tags at `:561-566`) |
| `reference-code/core.async/.../impl/dispatch.clj:63-160` | executor construction: `make-ctp-named` = `Executors/newCachedThreadPool` of named **platform** daemon threads (`:71-73`); `:io` = virtual-thread-per-task when available (`:120-125`); `:compute` and `:mixed` = platform cached pools (`:127-132`); `executor-for` memoized, overridable by the `clojure.core.async.executor-factory` system property (`:134-147`) |
| `reference-code/datahike/src/datahike/writer.cljc:80-309` | the writer **is already a core.async flow-shaped pipeline**: `create-thread` builds a strictly serial processing go-loop threading `db-before → db-after` (`:94-200`, the serial recur on `(:db-after res)` at `:182`) feeding a commit thread that drains the queue as one batch (`:202-268`, batch drain at `:211`), `DEFAULT_COMMIT_WAIT_TIME 0` (`:83`), queue-pressure warnings at >90% (`:103-104`) and a built-in 50 ms throttle at commit-queue >50% (`:173-175`); **`create-writer :self` takes a connection and returns a `map->LocalWriter` with its own queues and threads (`:286-306`) — per-connection, no process-global writer state** |
| `reference-code/datahike/src/datahike/api/specification.cljc:805-849, :1066-1094` | `history`/`since`/`as-of` are stable, referentially transparent, `:supports-remote? true`; **`listen` is `:supports-remote? false`** (`:1075`) and callbacks must not perform synchronous writer operations (`:1077`) |

First-party call sites demonstrating each idiom (symbols preferred):

- the live execution path: `seon.agent.driver/start!`, `scan-body!`,
  `process-message!`, `claim-recoverable-run!`, `resume-run!`,
  `drive-sources!`, `execute-form!`, `plan-tx-data`, `lifecycle-tx-data`
  (`src/seon/agent/driver.clj`); `seon.sci.eval/evaluate`, `open!`
  (`src/seon/sci/eval.clj`); `seon.sci.ctx/base`, `fork`
  (`src/seon/sci/ctx.clj`); `seon.sci.interrupt/start`
  (`src/seon/sci/interrupt.clj`).
- claim algebra: `seon.agent.run.core/claim-plan`, `run-fence`,
  `release-tx-data`, `finish-tx-data`, `expired-lease?`, `lease-wake-at`
  (`src/seon/agent/run/core.cljc`).
- receipts: `seon.eval.receipt/receipt-id` (run, ordinal, epoch),
  `next-ordinal`, `terminal-tx-data` (`src/seon/eval/receipt.cljc`).
- messaging as derivation: `seon.agent.message/waking-inbound?`,
  `hop-live?` (`src/seon/agent/message.cljc:292-317`); the driver's one
  interest, `:seon.db/datom-patterns [{:seon.db/a :seon.agent.message/to}]`
  (`seon.agent.driver/start!`, `driver.clj:884-886`).
- the wire: `seon.db.host/writer-session`, `open-interest-session!`
  (UDS at `host.clj:182`), `leaf`, `database-functions` (`host.clj:970-973`).
- the call graph: `seon.program.edge/analyze-function`, `walk-call`,
  `walk-expression`, `argument-uncertainties`, `canonical-terminal`
  (`src/seon/program/edge.cljc`).
- flow-control precedent: `seon.web.feed/enqueue-latest!`
  (`src/seon/web/feed.clj:22-25`) — `.clear` then `.offer`, a sliding
  buffer of one.
- colocated effect metadata (live precedent for workload axioms):
  `^{:seon.capability/effect :pure}` on `seon.agent.lifecycle/wait` etc.
  (`src/seon/agent/lifecycle.cljc:42-86`), on `seon.db/as-of`/`since`/
  `history` (`src/seon/db.cljc:271-377`), on `my.blob/put!`/`get`/`text`
  (`src/my/blob.cljc:290-431`).
- deleted code read from git for inspiration (owner-authorized, not
  wholesale): `git show 8dc8623ad^:src/seon/host/context.clj` — the old
  capability door's `install!` wrapper registry with per-entry `::effect`
  stamps (region around `:740-815`).

Measured evidence reused with conditions, from
`research/measurements-2026-07-25.md` (M) and
`research/flow-prototype-2026-07-25.md` (F), machine conditions in M §1
(MacBook Pro M5 Max, 18 cores, 128 GB, OpenJDK 26.0.1, Datahike
`caf52685`): form granularity 0-vs-9 (M §8.1, F §1); 22 evals vs 18
permits → 4 queued, 71 ms max wait, zero bounced (F §1); D1 two-JVM
40-of-40 vanish (M §8.3); D5 wake stampede 7.0 → 14.4 → 124.8
commits/useful-run, OOM at n=20 (M §8.5); allocation escapes (M §6.4);
no carrier pinning under `jdk.virtualThreadScheduler.parallelism=1`
(M §7.4, F §1); turn attribution provider 78.5095% / SCI 0.1527%
(M §18.2); saturation knee 65,536 callers → 4,336.40 tx/s on APFS
`jdk.FileForce`, 131,072-transaction fixed workload (M §16.1-16.2);
`sci/fork` 149.454 retained bytes / 321.747 ns against Seon's real base
(M §3.2); wire cost 6-7 writer round-trips per one-write form, four
transit encodes/decodes per unpinned write
(`research/redesign-ledger-2026-07-25.md` R-7,
`research/simplification-design-2026-07-25.md:104-115`).

## 2. What a channel gives that a datom does not

The claim under test: flow conflates the coordination medium with the
scheduling boundary, and Seon can split them without losing semantics.
Seven capabilities a core.async channel provides, each with Seon's
equivalent at a file:line or an honest loss.

| # | channel capability | core.async site | Seon equivalent or loss |
|---|---|---|---|
| 1 | backpressure — a full fixed buffer blocks the producer | `async.clj:113-117`; flow default `buf-or-n` 10 (`impl.clj:101-109`) | **partially lost — the one genuine structural loss.** See below. |
| 2 | rendezvous / synchronous handoff | unbuffered `chan` (`async.clj:138-153`) transfers only when both sides are ready | not needed at agent boundaries: the commit IS the handoff, and consumption exclusivity is the claim CAS (`run.core/claim-plan`), which is *stronger* — a fenced take that survives process death. The one true sync handoff that remains is `Future.get` at the eval boundary (`sci/eval.clj:132`). Cost: a cross-agent hop pays a commit (0.53-45 ms/tx depending on concurrency, F §1) instead of ~µs. |
| 3 | `alts!` — wait on several, take the first | `async.clj:343-409`; the flow proc parks in `alts!!` over `[control casts & ins]` with control priority (`impl.clj:286-292`) | structurally present, differently factored: an idle agent's "read set" is its wake predicate over datom patterns (`waking-inbound?`, `message.cljc:292-307`; the driver's one interest, `driver.clj:884-886`); control-priority = the run fence CAS failing first (`run.core/run-fence`); the lease timer is the `timeout` arm (`arm-lease-wake!`). Loss: agent code cannot block mid-form on N sources — but it structurally *must not* (no suspend/resume of an SCI eval, landmine 4); waiting is `(lifecycle/wait ...)` → open + unclaimed, and the wake conditions are the alt. |
| 4 | per-channel FIFO ordering | channel semantics | **stronger**: the transaction log is a total order over the whole system, and every ordered collection carries an explicit ordinal — plan forms (`:seon.agent.run.form/ordinal`), receipts (`receipt-id` = run+ordinal+epoch). Order is never a property of the collection type (Datahike has exactly two cardinalities); the cost is that order must be explicit, which is also why it survives a crash. |
| 5 | buffer policies (sliding/dropping) as flow control | `async.clj:119-130`; flow report/error/casts use sliding 100 (`impl.clj:97-100`, `:124-128`) | ratified as three cases, not one (ledger 19e): agent messages never drop (a committed datom); unclaimed runs queue in the database with the CLAIM as backpressure (22 evals vs 18 permits → 4 queued, 71 ms max wait, zero bounced); presentation frames are latest-wins — `enqueue-latest!` `.clear`+`.offer` (`feed.clj:22-25`), the sliding-buffer-of-one, plus `:seon.db/no-history?` as its in-database temporal twin. Dropping-newest has no equivalent and no ratified need. |
| 6 | close/drain semantics, completion signalling | closed chan → nil after drain; `pipe` propagates close (`async.clj:599-609`) | **stronger**: completion is a durable fact — `finish-tx-data` closes the run, retracts the process, detaches the agent pointer in one transaction (`run.core:209-221`); drain = `next-ordinal` over terminal receipts until `total` (`receipt.cljc:137-151`), proven across six kill positions plus a double kill (F §1). Downstream "propagation" is unnecessary: consumers derive from facts; there is nothing to notify. |
| 7 | proc-local state threaded between inputs | `transform: (state, input, msg) -> [state' output]` (`flow.clj:240-256`); the loop threads it (`impl.clj:269-319`) | **deliberately absent, and this is the second honest loss.** Between forms the state is the previous step's transaction report `:db-after` (measured FORCED: the identical form answered 0 at the turn's opening basis and 9 at the step's, M §8.1). Between turns it is the database, period. Cost: private in-memory accumulation across inputs is not free — an agent counter costs a commit (and the measured turn already carries 12 transactions). Benefit: the state cannot be lost, which is the entire thesis. |

**Item 1, the honest core.** A full channel blocks its producer, and that
pressure propagates upstream automatically through a flow graph. Seon has
backpressure at the *consumption* boundary — the claim queues on the
semaphore, measured queueing with zero bounced claims — and loop-bounding
at the messaging layer (`hop-live?`, cap 8, `message.cljc:308-317`), but
**nothing slows a producer of facts** until Datahike's own queues fill:
the transaction queue warns at >90% (`writer.cljc:103-104`), the commit
queue throttles 50 ms at >50% (`:173-175`), and past that the measured
outcome is the knee (4,336 tx/s at 65,536 callers) and, far past it,
`OutOfMemoryError` with the 120,000-entry transaction queue above 90%
(M §16). An agent emitting facts in a tight loop is bounded by its
`time-limit` and by writer queue pressure, not by a full buffer parking
it. This is a real difference in kind. The mitigation is designed, not
free: bounded admission at the one door (§3.5) plus the writer's own
queue dials (§7.3). It should be stated in agent-facing docs as the
system's flow-control contract, not discovered.

**The counter-example that completes the argument.** Datahike's writer
uses channels *internally* — a serial processing loop feeding a batching
commit thread, buffers, `poll!` drains, timeouts — and exposes *facts*
at every boundary anyone else sees (`writer.cljc:85-269`). Channels as a
mechanism's process-local pipeline; the log as the system's medium. That
is exactly the split this design proposes, already shipped inside the
dependency the owner praised as "fast as shit."

## 3. The design: workload as a derived function fact

### 3.1 The fact

One new attribute on the existing `:seon.fn` corpus entity:

- `:seon.fn/workload` — `[:enum :io :compute :mixed]`.

The name is the dependency's: `workload` is `thread-call`'s own parameter
(`async.clj:555-566`), and the three values carry core.async's exact
meanings — `:io` may block but must not do extended computation,
`:compute` must not ever block, `:mixed` anything else. No Seon coinage,
no fourth value.

It is stored, not derived-at-read, for the same reason `:malli/schema`
projections are: the scheduler consults it on a hot path, and it changes
only when the function or its call graph changes — recompute on write,
read everywhere.

### 3.2 Axioms at the platform edge — metadata like schemas, not a hand list

The rollup needs ground truth at the leaves: `db/transact!` blocks on the
commit; `web/fetch` blocks on a socket; `clojure.core/+` does not. That
ground truth is declared **as metadata on the leaf's own defn**, exactly
like `:malli/schema`, exactly like the live tree already does for
effects: `^{:seon.capability/effect :read}` sits today on `seon.db/db`
(`db.cljc:353`), `my.blob/text` (`blob.cljc:375`), and the lifecycle
verbs (`lifecycle.cljc:42-86`). Add the sibling key at the same sites:

- `:seon.capability/workload :io` on each blocking capability leaf entry
  (fs, shell, web, blob-fetching, LLM transport, `db/transact!` and the
  blocking db entries);
- nothing on pure functions — absence is data.

Why this is not the banned hand list, and the deleted code shows the
difference: the old door stamped `::effect` per entry in a central
`install!` wrapper registry far from the functions
(`git show 8dc8623ad^:src/seon/host/context.clj`, region `:740-815`) — a
hand-maintained table that drifted the moment a leaf changed. A colocated
metadata key is the function's own contract, owned by the one file that
contains the platform residue making it true, reviewed in the same diff
that changes the behavior, and **falsifiable by measurement**: a leaf
declared `:compute` that parks shows up as lease-relative wall time with
near-zero `:seon.eval/fn-entries` — the recorded diagnostics already
distinguish a spin from a block. Restore the *annotation idea* from the
deleted door; do not restore the registry.

The classification RULE — how every other function gets its workload —
is computed (§3.3). The axioms are the ~6 capability families' leaf
entries plus the provider transport: a surface small enough to audit in
one sitting, structural (each annotation lives beside the syscall it
describes), and already precedented by `:seon.capability/effect`.

### 3.3 The rollup — computed, fail-closed

`workload(f)` is a pure function of the stored call graph
(`:seon.program.edge/calls` + terminal axioms), computed by the same
machinery that computes effects, with these rules in order:

1. **any uncertainty anywhere in f's transitive graph → `:mixed`.**
   Fail closed. `:mixed` is the only value that is safe under both
   misclassification directions (§3.6).
2. **no reachable `:io` axiom, graph closed → `:compute`.** Pure and
   capability-free code, which is also exactly the class admissible as
   contract predicates — the two derivations share the substrate.
3. **reachable `:io` axioms and nothing else** — f's closed graph
   consists of one direct edge to an `:io` leaf (a thin wrapper) —
   **→ `:io`.**
4. **reachable `:io` axioms plus other work → `:mixed`.** The `:io`
   half ("may block") is decidable from the graph; the "must not do
   extended computation" half is not statically decidable for arbitrary
   bodies, so the derivation refuses to promise it. The `time-limit`
   enforces it dynamically, as it already does for everything.

A function whose workload is genuinely unknown — dynamic call, macro
expansion, unresolved symbol — is `:mixed` *with its uncertainty facts
committed alongside*, so the projection can show why, and tightening the
analyzer automatically tightens the classification on the next commit of
the function. No name-based rule anywhere: no namespace prefix, no
literal list, no "seon.agent.* is io".

**The precondition this stands on is not yet true** (§6.1): three
measured discard sites in `seon.program.edge` make higher-order callers
*silent* false negatives — `(map blocking-fn xs)` records only
`clojure.core/map`; a bare returned var and a var inside a collection
literal record nothing at all (`argument-uncertainties`,
`edge.cljc:371-377`; resolved-symbol return at `:413-419`; non-seq
short-circuit at `:421`). Rule 1 cannot save you from a defect that
produces no uncertainty. Those sites close before any workload fact is
trusted.

### 3.4 Where derivation happens — reconciling "parse during eval" with O15

The owner said "parse during eval and know what to schedule where"; O15
rules index-at-compile-time-only, never runtime source indexing. These
are the same mechanism at two provenances, and the owner needs both:

- **First-party functions**: the O15 JVM build indexer
  (`script/seon/dev/`) computes `::edge/calls`, the terminal axioms
  (read from the defn metadata), and the workload rollup, and emits them
  as initialization pages. Compile time, once, loud failure if absent.
- **Agent-authored functions**: the driver's terminal transaction — the
  tee being rebuilt as roadmap row 3(a) — commits `:seon.fn/source`, the
  edge bundle, and the derived `:seon.fn/workload` **in the same
  transaction as the defn's receipt**. That is what "parse during eval"
  actually needs: derivation at *author* time, committed with the
  function, exactly as `:malli/schema` is required at authoring.

What the owner does *not* need, and O15 forbids: parsing at *call* time.
Scheduling reads committed facts; it never looks at source. A function
edited by an agent gets a new workload fact in the same commit as its new
source, so the fact can never be stale relative to the corpus.

### 3.5 Who reads it, and what schedules on it

**Adopt core.async's dispatch instead of reimplementing it.**
`seon.sci.eval` today hand-rolls `Executors/newCachedThreadPool`
(`sci/eval.clj:33-38`) while its namespace docstring claims a "bounded
`:compute` platform thread" — the borrowed word without its dispatch.
`make-ctp-named` is the identical construction (`dispatch.clj:71-73`), so
replace the private pool with `(disp/executor-for :compute)` and keep
Seon's semaphore as the bound (core's pool is unbounded by design). Two
things come free: the `clojure.core.async.executor-factory` system
property (`dispatch.clj:141-143`) gives Seon one place to own
construction of all three executors process-wide; and because `go`
compiles to `thread-call :io` on this JDK (`async.clj:519-533`),
**Datahike's own writer pipeline already runs on `executor-for`'s `:io`
executor** — the database, the driver, and agent evals end up on one
dispatch substrate without any glue.

The scheduling rules, by consumer:

1. **The eval boundary (agent interpreted code).** Always the `:compute`
   platform pool + one semaphore permit + the one `:interrupt-fn`.
   The workload fact never moves an eval off platform threads, because
   the platform-thread constraint is absolute for two independent
   reasons: JMX allocation returns -1 on a virtual thread, and agent
   code on a platform thread has no carrier-pinning surface (verified:
   `jdk.virtualThreadScheduler.parallelism=1` with 8 wedged evals while
   an unrelated virtual thread completed 5/5 steps in 902 ms, M §7.4).
   Any design that schedules agent code onto virtual threads fails both.
2. **Capability calls inside an eval — where the fact pays.** The one
   guarded dispatcher (roadmap row 1) consults the callee's
   `:seon.fn/workload` at the door:
   - `:compute` → direct call; nothing special.
   - `:io` or `:mixed` → **release the `:compute` permit**, submit the
     leaf body to `(executor-for :io)` (a virtual thread), `.get` with
     the eval's remaining `time-limit` plus slack and `Thread.interrupt`
     on timeout, **reacquire the permit** on return.

   This makes the `:compute` contract honest automatically rather than
   by a special case: "an agent-initiated blocking call releases the
   `:compute` permit while it waits" is not a rule about a list of
   blocking functions — it is the generic wrapper triggered by the
   callee's derived fact. GAP #3 (a wedged host call permanently
   retaining a permit) dissolves into: a wedged leaf call holds a
   virtual thread and the parked platform thread, but **zero permits**;
   compute capacity degrades by zero, thread capacity by one, and the
   thread's reclamation remains what O2 says it is — process
   replacement, resumed from receipts. Note what does *not* move: the
   platform thread itself must wait for the leaf result, because SCI has
   no suspend/resume (landmine 4). The permit is the logical compute
   slot; the thread is just memory.
3. **Driver orchestration.** `process-message!` reaches the LLM leaf and
   `transact!`, so it derives `:io` — and it already runs on virtual
   threads (`start-virtual-thread!`, `driver.clj:419-420`), which is
   `thread-call :io` by another name. The derivation *ratifies* the
   current placement instead of changing it; that is what a correct
   classification rule should do to correct code.
4. **Out-of-eval planned work** (future: `my.plan` invocations, schedule
   fires): submit to `executor-for (workload f)`, applying the eval
   boundary (rule 1) whenever f is corpus code — a condition computed
   from provenance, not from a list.

**Bounded admission at the door (the §2 item-1 mitigation).** The same
dispatcher is the one place to meter an agent's fact production: the
per-eval output caps that already exist as config facts extend naturally
to a transactions-per-eval budget enforced where `transact!` enters.
This is design intent, not mechanism invention — the door exists (row
1), the budget is one more config fact beside `time-limit`.

### 3.6 Smart defaults

- **Unknown workload → `:mixed`.** This is core.async's own default —
  `thread-call` (`async.clj:566`) and flow's proc (`impl.clj:245`) both
  default `:mixed` — and it is the only safe direction: misclassifying
  as `:compute` blocks a compute permit on a park (the D9/GAP-3 class);
  misclassifying as `:io` puts extended computation on the virtual-
  thread executor, hogging carriers. `:mixed` costs only scheduling
  efficiency (its pool is a plain platform cached pool,
  `dispatch.clj:132`), never correctness. The measured price of the
  whole question is small — SCI is 0.1527% of a turn, the provider
  78.5095% (M §18.2, one durable measured turn) — so the default should
  optimize for never-wrong, not for maximal packing.
- **The eval boundary is always `:compute` platform** — not a default
  but a constraint (§3.5 rule 1); recorded here because it is the
  "smart default" most tempting to relax and most expensive to get
  wrong.
- **Permit count**: the existing config fact defaulting to
  `availableProcessors` (measured queueing behavior at 22/18 with 71 ms
  max wait). Unchanged.
- **The writer's three unset dials** — `transaction-queue-size`,
  `commit-queue-size`, `commit-wait-time` (`writer.cljc:286-297`):
  Seon sets none of them (`rg 'commit-wait-time|transaction-queue-size'
  src/ config/` → nothing). Recommended defaults: leave
  `commit-wait-time` 0 — batch size already self-tunes upward with
  offered load (four commits absorbed 16,384 transactions, M §16.1) and
  the fsync wall, not batching, is the ceiling; but **set both queue
  sizes as config facts** sized to bound writer memory, because the
  measured OOM at 131,072 callers arrived with the 120,000-entry
  transaction queue above 90% — the queue bound is the guard that turns
  overload into loud rejection instead of heap death. The right numbers
  need the §9 experiment; the *mechanism* (config facts, not constants)
  is settled.

## 4. Turtles all the way down — three levels, one mechanism

The owner's expansion names three levels: N cluster-writer flows, M agent
flows, and every function call inside a turn, all on one scheduled
runtime. The one mechanism, stated once:

> **Work is a function submitted to the executor selected by its
> workload fact; whenever the work is agent-visible, its claim and its
> completion are durable facts (CAS-fenced claim, ordered receipt), so
> any process can resume it and any consumer can query it.**

### 4.1 Level one: N cluster-writer flows in one process — the topology finding

`create-writer :self` is **per-connection**: it takes the connection and
returns a `LocalWriter` holding its own transaction queue, commit queue,
processing thread, and commit thread (`writer.cljc:286-306`). There is no
process-global writer state. Three consequences:

- **N stores can have N `:self` writers in one JVM.** The owner's
  "separate flows for each database writer so clusters run in parallel"
  is structurally available today — each cluster's writer is an
  independent serial pipeline, and all of them ride the same
  `executor-for :io` virtual-thread executor through `go`.
- **Landmine 6 is untouched.** It forbids two write connections on ONE
  store (D1: both JVMs won the same epoch CAS, 40 of 40 parent commits
  vanished, zero errors, M §8.3). N writers on N stores is exactly one
  connection per store, N times. Do not read landmine 6 as forbidding
  multi-cluster hosting.
- **Documentation defect, flagged:** `AGENTS.md:201-202` says "Datahike
  ships only a `:self` writer, so there is exactly one writer process
  per store, which makes one JVM per cluster the structural
  consequence." Both halves are wrong against source: `:datahike-server`
  exists (verified: `(defmethod create-writer :datahike-server ...)`,
  `reference-code/datahike/src/datahike/http/writer.clj:35`), and
  `:self` is one write
  *connection* per store — a process may host many. The invented
  constraint quietly forecloses the owner's design and should be
  corrected by the orchestrator (this lane does not edit `AGENTS.md`).

What multi-cluster hosting costs (be honest before anyone reaches for
it): O9's isolation promise — "one crashing or being reset cannot touch
another" — currently holds at the process boundary, and O1 explicitly
prices an agent OOM as a *cluster* restart. Co-host N clusters in one
JVM and the heap blast radius becomes cross-cluster: one agent's
un-catchable host allocation restarts every co-hosted cluster. The
scheduling design supports both topologies identically; the default
should remain one JVM per cluster for fault isolation, with co-hosting
as an explicit capacity choice whose blast radius is accepted the way O1
accepted it for one cluster.

### 4.2 Level two: M agent flows

Map flow's vocabulary onto what exists, term by term — this is the
non-adoption ratification the roadmap lists as owed:

| flow | Seon | where |
|---|---|---|
| proc | the claimed run being driven | `claim-plan` + `drive-sources!` |
| step-fn `describe` | the fn's committed facts: `:malli/schema`, edges, workload | `:seon.fn` entity |
| step-fn `init` | open-run + open-turn transactions | `open-run-tx-data`, `open-turn!` |
| step-fn `transition` (pause/resume/stop) | lifecycle dispositions as VALUES the driver interprets | `lifecycle-tx-data`, `run.core/release-tx-data`, `finish-tx-data` |
| step-fn `transform` `(state, input, msg) -> [state' output]` | `execute-form!`: (previous step's `:db-after`, form) → value → tx-data. **The shape cannot be the turn** (0-vs-9, M §8.1); it CAN be, and is, the form. The turn is a fold of forms. | `execute-form!`, `drive-sources!` |
| proc state between inputs | the previous step's transaction report `:db-after` | driver fold |
| channels + `:conns` topology | committed datoms + attribute-indexed interest; no topology to configure — every agent reaches every agent because queries do not filter by graph | `listen!` interest, `waking-inbound?` |
| `alts!!` over `[control casts ins]` | wake predicate over datom patterns + lease timer + fence CAS | §2 item 3 |
| control chan priority | the run fence fails the stale holder's next commit | `run-fence` |
| `:report-chan` / `:error-chan` | receipts and fault datoms — durable, queryable, historical | `receipt.cljc`, `seon.error/record!` |
| `pause-proc` / `resume-proc` | release / reclaim the run (CAS + epoch) | `release-tx-data`, `claim-plan` |
| `inject` | commit a fact | `transact!` |
| `ping` (timeout 1000, omits non-responders, `flow.clj:136-140`) | a query over run + receipt facts — which *includes* the wedged process, the one `ping` structurally omits | §4.4 |
| workload option | `:seon.fn/workload`, derived | §3 |
| `:mixed-exec`/`:io-exec`/`:compute-exec` | `executor-for` + the sysprop factory | §3.5 |

Flow needs an admin API because its state is hidden in memory. Putting
the state in the database does not replace that surface — it deletes the
need for one (ledger 19f). Agents run in parallel because each claimed
run is independent work on the shared executors, bounded by the permit
pool — not because each agent owns a thread. An idle agent costs a few
datoms, no thread, no channel, no proc.

### 4.3 Level three: function calls inside a turn

Already designed in §3.5: the door consults the callee's workload fact
and the same executor tags schedule the leaf work; the same receipts
cover it (a capability call happens inside a form and is covered by that
form's receipt). The turtle claim is real because the *agent turn
itself* is just the outermost case: `process-message!` is a first-party
`:io`-derived function running on the `:io` executor, whose inner
`:compute` sections (evals) claim permits, whose inner `:io` sections
(leaf calls) release them. One rule, applied recursively. The database
writer is the same shape one level down: a serial `:io` pipeline whose
inner work is the pure index update. Nothing anywhere schedules by kind
of thing — only by workload fact.

**The one seam, named honestly.** Agent interpreted code carries three
obligations first-party code does not: the `:interrupt-fn`, the platform
thread (for allocation attribution and pinning-freedom), and the permit.
This is a genuine boundary — but it is expressed *in the same metadata
system*: corpus provenance (a `:seon.fn/source` row authored through the
door) is a computed condition that switches the eval boundary on. One
mechanism with one computed guard condition, not two mechanisms. If a
future tier ever needs agent code off platform threads, both platform
reasons must be re-answered; until then the seam is load-bearing and
cheap (SCI is 0.15% of a turn).

### 4.4 "Managing what agents are running and the workloads they run on"

Answered as queries over committed facts, against flow's `ping`:

- *what is running where*: open runs joined to `:seon.agent.run/process`
  and `:seon.agent.run/lease-until`; running receipts
  (`:seon.eval/status :running`) give the exact in-flight ordinal and
  source per process.
- *what is queued*: open + plan-digest + no process — the durable
  backlog, inspectable and historical (`recoverable-run-query` is this
  query minus the history).
- *what is wedged*: a `:running` receipt whose run's lease has expired —
  precisely the process flow's `ping` omits (it returns status only "for
  those procs that reply within timeout-ms", `flow.clj:136-140`).
- *what workloads*: join the receipts' forms to `:seon.fn/workload`.

Facts that must exist for this and do not yet:

1. the terminal receipt drops `:seon.eval/fn-entries`,
   `:seon.eval/allocated-bytes`, and the semaphore wait —
   `terminal-receipt-data` persists only status, result-edn, and
   duration (`driver.clj:156-169`), while the in-memory record has all
   three (`interrupt.cljc:86-96`, `sci/eval.clj:94-133`). Roadmap row
   4(d); without it the spin-versus-blocked diagnostic cannot reach a
   query.
2. `:seon.fn/workload` itself (§3).
3. `:seon.agent.run/process` is a bare `"host-<pid>"` string
   (`driver.clj:821`); the vocabulary table grounds process identity as
   (pid, start-instant) via `script/seon/dev/process.clj` ↔
   `ProcessHandle`. Liveness is lease-based so pid recycling cannot
   steal work, but the management join to a real process record needs
   the grounded identity. Minor; ride along with row 6.

## 5. Hidden state audit — is the loop fully defined by database states?

### 5.1 The four states, verified

All derived from run facts; no FSM, no stored status beyond the run row:

- **idle** — no open run (`:seon.agent/run` absent; `finish-tx-data`
  retracts it in the closing transaction, `run.core:209-221`).
- **claimable, which is also waiting** — open + no
  `:seon.agent.run/process`: the `:wait` disposition maps to
  `release-tx-data` (`lifecycle-tx-data`, `driver.clj:100-101`), which
  retracts the process and leaves the run open. Waiting is not a state
  machine; it is unclaimed-ness.
- **running** — open + process + live lease (`live-process?`,
  `run.core:88-95`).
- **closed** — `:seon.agent.run/status :closed` + reason + instant.

Resume position is derived, not stored: `next-ordinal` = first ordinal
in `(range total)` without a terminal receipt (`receipt.cljc:137-151`) —
the D12 fix, present at HEAD.

### 5.2 The audit table

| process-local holder | site | verdict |
|---|---|---|
| `scanning?` / `scan-requested?` AtomicBooleans | `start!` | **legitimate** — single-slot latest-wins scan coalescing (the D5 fix); pure invocation-local coordination; a lost scan is re-created by the next commit or boot scan |
| `in-flight-message-ids` atom | `start!` / `claim-id!` | **legitimate** — process-local dedup only; the cross-process authority is the open-run CAS (`open-run-tx-data`'s `[:db.fn/cas agent :seon.agent/run nil ref]`); loss of the set loses nothing durable |
| `in-flight-run-ids` atom | `start!` | **legitimate** — same shape; authority is `claim-plan`'s CAS |
| `lease-wakes` atom + `*await-lease!*` sleeping vthreads | `arm-lease-wake!` | **legitimate with a condition** — the timer implements the committed lease instant (event-driven off the lease commit, per the D2 fix); the condition: a fresh process must scan on boot to re-arm, and `start!` does (`scan-body!` before returning). The firing of a wake that finds nothing stale is normal; a *missed* wake with no surviving process to re-arm is impossible only while every boot scans |
| `seon.sci.eval/compute-pool` (hand-rolled cached pool) | `sci/eval.clj:33-38` | **legitimate as process resource, wrong as construction** — replace with `executor-for :compute` (§3.5); the docstring's claim is currently ahead of the code |
| `permits` Semaphore | `sci/eval.clj:40-56` | **legitimate** — the process's compute bound; its observable projection (what is executing) is already derivable from running receipts, and becomes fully so after row 4(d) |
| `seon.sci.ctx/base` delay | `ctx.clj:15-35` | **legitimate** — compiler-state cache (149.454 bytes / 321.747 ns per fork against the real base, M §3.2, is what it buys). Standing condition: base vars hold only functions and immutable values — the fork-leak class is real on an unsafe base and **nothing enforces the invariant** (F §1); it must become a startup assertion when the capability door installs into the base |
| `db.host/writer-session` pool/interest atoms | `host.clj:40-76` | **legitimate** — connection state; dies with the wire (§7) except for web-render |
| `interrupt/time-limit-timer` | `interrupt.clj:36-44` | **legitimate** — process resource |
| `*nano-time*` / `*now*` dynamic vars | `driver.clj:185-191` | **legitimate** — test seams |
| `web.feed/connections` + mailboxes | `feed.clj` | **legitimate** — presentation only, ratified latest-wins |

No holder in the driver must become a fact. But two things *around* the
driver must:

### 5.3 Defect found: the pre-plan crash window strands the run

A run that cannot be resumed from facts alone is the bug this design
exists to prevent, and one exists at HEAD:

- `recoverable-run-query` requires a committed plan:
  `[?run :seon.agent.run/plan-digest _]` (`driver.clj:343-350`).
- `pending-message-query` excludes any message that already caused a
  run: `(not [?run :seon.agent.run/cause ?message])`
  (`driver.clj:332-341`), regardless of that run's state.
- `process-message!` commits the run (with its cause) and the turn
  *before* the model call, and the plan only after the reply parses
  (`driver.clj:722-811`).

So a process killed between `open-run!` and the plan commit — a window
dominated by the provider call, 78.5% of the measured turn — leaves an
open, lease-carrying run **no survivor's scan can ever pick up**: not as
a recoverable run (no plan digest), not as a pending message (the cause
edge exists). The run strands until manual intervention; the agent
appears busy forever (its `:seon.agent/run` pointer is set).

Closing change, one mechanism: make recovery select on *open*, not on
*planned* — `claim-recoverable-run!` for a plan-less run whose lease is
claimable re-drives from the causing message (the run's
`:seon.agent.run/cause` ref reaches it), re-issuing the model call under
the same run and a fresh turn. At-least-once for the provider call is
already the system's honest ceiling (landmine 10). Not fixed here (this
lane changes no source); it gates any claim that the loop is fully
database-defined, and it belongs with roadmap row 6.

Adjacent drift, reported while verified: the driver's wake query accepts
only `:seon.agent.message/origin :human` (`driver.clj:336`), so
agent-to-agent messages currently open no run, while `waking-inbound?` —
the stated one rule — wakes on everything except self and `:core`
(`message.cljc:292-307`). One rule, two behaviors; the completion
message the driver itself commits (`lifecycle-tx-data`,
`driver.clj:92-98`) is also a `:to` datom that re-fires the listener and
is then dropped by the origin filter — bounded to one no-op scan by the
coalescer, but the asymmetry is exactly the drift the predicate's own
comment forbids. Interim narrowing or defect, it should be named in row
6's scope.

### 5.4 The agent as an entity — the owner's claim, checked

"The agent is just attributes and values in an entity with lookups and
refs to other things it cares about" — **true at HEAD**. The complete
surface: `:seon.agent/id` (identity), `purpose` (string), `namespace`
(unique ref to the `:seon.ns` entity — O4's resident addressing),
`parent` (ref), `run` (ref, the fencing pointer), `terminated-at`
(inst), `default-turn-limit` / `default-deadline-ms` (ints),
`schedules` (component refs), `ctx` (component set of block refs,
`ctx.cljc:78-82`). Nothing about an agent is not an attribute or a ref;
state is derived (§5.1); context is refs to blocks.

Two honest residues: (a) most of these registrations live in
`src/seon/agent.cljs:92-118` — a pod file on the row-8 deletion list —
while the JVM needs the schema; `run/core.cljc:7-10` records the
precedent (run attributes were moved for exactly this reason) and the
rest must follow in the same cut. (b) `default-turn-limit` /
`default-deadline-ms` / `schedules` have no JVM driver consumer at HEAD
(`rg` finds no reader outside the pod tree) — pod-era residue to be
re-owned or retracted by row 8, not silently carried. `[UNVERIFIED]`
whether the schedule ticker has a surviving JVM owner planned.

## 6. What must be fixed first — blunt ordering

1. **Sound `::calls` — before any workload fact is trusted.** The three
   discard sites (`edge.cljc:371-377`, `:413-419`, `:421`; issues filed
   as `closed-higher-order-call-targets-are-discarded-as-uncertainty.md`
   and the terminal-effect note). Two of the shapes are *silent*, so no
   default protects against them: `(map blocking-fn xs)` derives
   `:compute` today. Record closed higher-order targets as calls;
   record a resolved bare-symbol reference; descend collection
   literals. This is roadmap row 10's first link, promoted: it now
   gates scheduling correctness, not just accretion.
2. **Non-constant terminal axioms.** `canonical-terminal` defaults every
   unannotated target to `:external` (`edge.cljc:144-156`), making any
   rollup a constant. The workload axiom key (§3.2) and the effects-map
   producer must land together; core purity must be computed (the filed
   issue's acceptance), not hand-listed.
3. **The capability door itself** (roadmap row 1). The dispatch site of
   §3.5 rule 2 must exist before the fact has a reader. The door is
   already the earliest unsettled contract for unrelated reasons; this
   design adds its workload-consultation duty to row 1's spec, it does
   not reorder the spine.
4. **Receipt observability fields** (row 4d) — fn-entries, allocated
   bytes, semaphore wait onto the terminal receipt — so §4.4's
   management queries and the axiom-falsification loop (§3.2) have
   data.
5. **The stranded pre-plan window** (§5.3) — with row 6, because its fix
   touches the same recovery queries.
6. **Then** `:seon.fn/workload` + `executor-for` routing + the permit
   release wrapper. Order within this step: route `seon.sci.eval`
   through `executor-for :compute` first (small, independently
   shippable, closes the "borrowed word without its dispatch" defect
   the roadmap already lists as owed), then the door wrapper, then the
   derived fact.

Independent of all six: the wire merge (§7) can land before or after;
nothing above reads through the UDS path except incidentally. Merge
first is recommended — it deletes the 6-7 round-trips every subsequent
measurement would otherwise include, and §3.5's "one dispatch substrate"
claim only becomes literally true when writer and driver share a
process.

## 7. The wire

**State at HEAD, verified:** the cluster JVM's every database operation
crosses a Unix socket to the separate writer process —
`writer-session` builds a UDS connection pool (`host.clj:40-76`),
`open-interest-session!` opens the interest socket (`host.clj:182`),
`database-functions` is `db/bind-leaf` over that session
(`host.clj:970-973`). O1 — reads as pointers into the immutable value,
writes as function calls, the wire off the agent path — is the TARGET.
The tree is not there.

**What merging `writer` + `host` buys, with numbers:** one unpinned
agent write today is two wire round-trips, four transit encodes and four
decodes; one form containing one write is 6-7 writer round-trips with a
120,000 ms call deadline and a durable idempotency protocol
(`simplification-design-2026-07-25.md:104-115`, mechanism at
`host.clj:597-838` / `writer.clj:1281-2072`). Co-located, a read is a
deref plus index access (0 copies, referentially transparent over the
value) and a write is `d/transact` into the same heap — the entire
encode/decode/pool/idempotency layer leaves the agent path. The measured
turn spends 78.5% in the provider and 0.15% in SCI (M §18.2); the wire
is most of what remains that is not the commit itself, and "everything
already cached is reused" (the owner's phrase) becomes literal: the
writer's in-heap index caches and the reader are the same objects.

**What it costs, priced by O1 itself:** the writer's heap is the agent's
heap. No in-process metric bounds live footprint (landmine 2: 200 MB
allocated in 1 ms with 0 fn entries, `:ok`, under both limits), so an
agent OOM is a cluster restart — downtime, not data loss, because the
lease resumes from receipts (measured: an agent OOM did not take the
writer down, twice, and the very next transact committed, F §1). O1
accepts this explicitly. The merge also collapses D1's unsafe
configuration structurally: with the writer in-process and one
`LocalWriter` per store connection, "two writers on one store" requires
deliberately opening a second process against the same store — which
must refuse to open (row 6), but is no longer the default deployment's
nearest failure mode.

**What survives, and why:** web-render stays a separate process reading
through the replica path — ~3,650 wire lines retained (R-8b,
`web/server.clj` builds its own `writer-session`), because Datahike's
`listen` is `:supports-remote? false` (`specification.cljc:1075`) and
the interest transport is precisely the remote-wake mechanism. The wire
does not die; it retreats to the one boundary where a second process is
architecturally required (nothing agent-controlled runs in web-render).

**The three dials** (§3.6): set `transaction-queue-size` and
`commit-queue-size` as config facts when the merge lands — in-process,
writer queue pressure and agent fact production share one heap, so the
queue bound becomes the memory guard; leave `commit-wait-time` 0
pending the §9 experiment.

## 8. Where this improves on flow, and where flow is better

**Improves on flow (the "improve on Hickey" case, concretely):**

- **Durability of the whole coordination state.** A flow proc's state,
  its in-flight messages, and its position die with the process; `ping`
  cannot even see a wedged proc (`flow.clj:136-140`). Seon's equivalents
  are datoms: six kill positions plus a double kill resumed correctly
  from receipts, SIGKILL inside `d/transact` at 8 points with zero torn
  transactions (F §1). Crash-proof is not a feature of the design; it is
  the medium.
- **Time travel as an operational surface.** `as-of`/`since`/`history`
  (`specification.cljc:805-849`) turn every debugging question flow
  answers with a live probe into a reproducible query over a basis:
  what did this agent see when it decided X (the receipt's basis), what
  changed between these turns (`since`), who ever held this run
  (`history` of the process attribute). In-memory channels have no
  yesterday.
- **The admin surface is free and strictly stronger** (§4.2 table,
  ledger 19f): every flow admin verb maps to a query or a CAS that is
  durable, historical, and available to every process and the UI, not
  one live caller.
- **No topology.** flow requires `:conns` declared up front
  (`flow.clj:90`); Seon's "graph" is whatever queries and interests
  derive — every agent reaches every agent, which is the owner's stated
  current intent, and narrowing later is a query change, not a rewire.
- **One medium for machines and humans.** The same facts drive the
  driver, the web UI, and forensics; flow's channels are invisible to
  everything but the procs holding them.
- **Backpressure where it is queryable.** The unclaimed-run backlog is
  inspectable data with measured claim-side bounding; a full channel is
  an opaque stall.

**Where flow is better (non-empty, honestly):**

- **Automatic producer backpressure.** A bounded channel parks the
  producer and the pressure propagates upstream through the graph with
  zero design work. Seon must engineer the equivalent at the door and
  the writer queues (§2 item 1, §3.6) and cannot express "slow this
  producer" without one of those bounds firing.
- **Cheap proc-local state.** flow's `state` is a map in memory;
  Seon's is a committed fact (12 transactions per measured turn). For
  hot in-memory aggregation — windowed stream statistics, high-rate
  event folding — flow's shape is strictly cheaper, and Seon's honest
  answer for such workloads *inside one eval* is plain Clojure
  reduction, not facts.
- **Hop latency and ceiling.** A channel handoff is sub-microsecond and
  scales with cores; a Seon hop is a commit — 0.53 ms/tx best measured
  concurrent case, 4,336 tx/s at the fsync knee (M §16). Any design
  routing high-frequency fine-grained events through the log hits the
  one-core writer and the disk; that is what `:seon.db/no-history?` +
  coalescing + the presentation mailbox exist to divert.
- **Rendezvous as a primitive.** Exactly-when-both-ready transfer needs
  building (CAS + wake) in Seon; flow has it in the channel.
- **Discipline enforced by construction.** A flow proc *cannot* reach
  shared state except through its channels; Seon's equivalent invariant
  — the SCI base holds only functions and immutable values — is prose
  plus a demo until the §5.2 assertion lands.
- **`compute-timeout-ms` ships.** flow bounds a `:compute` transform's
  `.get` at 5000 ms by default (`flow.clj:260`, `impl.clj:256-258`);
  Seon's equivalent deadline discipline at the door is designed (§3.5)
  but not yet built.

The honest synthesis, which is the owner's own instinct sharpened:
**adopt flow's decomposition — describe/transition/transform discipline,
workload tags, executor dispatch — and replace its medium.** The one
place flow's shape cannot survive contact is the transform-as-turn
(`(state, input) -> [state, output]` cannot express read-your-own-writes;
0-vs-9); at form granularity the same discipline holds and is proven.
Datahike's writer already demonstrates the end state: channels inside
one mechanism, facts at every visible boundary.

## 9. What could not be settled, and the experiment that settles each

1. **The corpus workload distribution under fail-closed derivation.**
   How much code classifies `:compute` vs `:mixed` once the analyzer is
   sound — if almost everything is `:mixed`, the fact buys little
   packing. Experiment: after §6.1-2 land, run the rollup over the
   first-party corpus and count; the number is meaningless before the
   discard sites close, which is why it was not produced today.
2. **The writer queue dials.** Right sizes for
   `transaction-queue-size` / `commit-queue-size`, and whether any
   `commit-wait-time` > 0 ever wins on APFS given self-tuning batches.
   Experiment: the §16 load rig swept over the three dials on a
   throwaway cluster (O9 makes this safe), watching tx/s, p50, queue
   pressure warnings, and RSS.
3. **Derived `:io` beyond thin wrappers.** Whether rule 3 of §3.3 ever
   fires on real corpus code or every real function with a leaf edge is
   `:mixed`. Settled by the same census as (1).
4. **Shared `:io` executor interference.** Datahike's writer go-loops
   and agent leaf calls will share `executor-for :io` after §3.5.
   Virtual threads make starvation unlikely (unbounded per-task) but
   carrier competition under thousands of concurrent leaf calls is
   unmeasured. Experiment: the load rig with a synthetic slow-leaf
   capability at n×1000 concurrent calls, watching writer commit
   latency and `jdk.VirtualThreadPinned` events (zero at the knee
   today, M §16.2).
5. **Lease renewal while queued or mid-eval.** Carried from the
   prototype (F §3): max observed semaphore wait was 71 ms against a
   60 s lease, so renewal-during-wait never fired; a convoy of long
   evals makes a healthy queued run stealable. Experiment: permits 2,
   three evals of length > lease, assert no steal of a queued healthy
   run once renewal ownership lands with row 6.
6. **Multi-cluster co-hosting blast radius.** N `LocalWriter`s in one
   JVM is structurally available (§4.1); what one cluster's OOM does to
   its co-tenants' recovery time is unmeasured. Experiment: two-cluster
   host, OOM one via the retained-allocation attack (M §9), measure the
   other's turn latency through the restart.

No prototype was built this session: every discriminating question
between candidate designs either was already measured (F, M), is settled
by dependency source read directly (per-connection `LocalWriter`,
`executor-for` construction, `go`→`thread-call :io`), or is blocked on
the §6 prerequisites and listed above as the experiment that would
settle it.
