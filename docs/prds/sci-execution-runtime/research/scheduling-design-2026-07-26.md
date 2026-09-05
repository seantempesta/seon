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

Verdict up front: **the split holds, and channels come back on the
scheduling side of it** (owner correction, 2026-07-26). In
`core.async.flow` a channel is both the coordination medium and the
scheduling boundary. Seon separates them — the database is the medium
(durable, global, temporal), and the scheduling boundary is a **bounded
submission channel per workload class feeding one launcher** onto
core.async's own executors. A channel does not have to carry the
communication to give backpressure: as the submission queue, a full
channel parks the submitter and pressure propagates upward for free,
while the work itself stays a claimable fact in the database. This is a
UNIFICATION, not an addition — the tree already bounds submission in
three different expressions (a `Semaphore` for evals, Datahike's own
bounded transaction queue, and nothing at two points that need it, §2)
where one mechanism serves. Dropping channels as the *medium* costs one
genuine thing (cheap proc-local state, §2 item 7); everything else has a
Seon equivalent that is equal or stronger. The scheduling half — the
part the owner wants pushed down to function granularity — is kept
literally: core.async's executors selected by a derived per-function
fact, fed through core.async's channels.

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
  capability request handler's `install!` wrapper registry with per-entry `::effect`
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
The owner's correction sharpened the split's second half: channels stay
OUT of the medium and come BACK as the scheduler's bounded submission
queues, which flips two of the seven verdicts below from loss to kept.
Seven capabilities a core.async channel provides, each with Seon's
equivalent at a file:line or an honest loss.

| # | channel capability | core.async site | Seon equivalent or loss |
|---|---|---|---|
| 1 | backpressure — a full fixed buffer blocks the producer | `async.clj:113-117`; flow default `buf-or-n` 10 (`impl.clj:101-109`) | **kept — as the submission queue, not the medium** (owner correction). The bound already exists in three inconsistent expressions today (see below); §3.6 unifies them as one bounded channel per workload class into the launcher. A full class channel parks the submitter; the durable backlog stays in the database. |
| 2 | rendezvous / synchronous handoff | unbuffered `chan` (`async.clj:138-153`) transfers only when both sides are ready | not needed at agent boundaries: the commit IS the handoff, and consumption exclusivity is the claim CAS (`run.core/claim-plan`), which is *stronger* — a fenced take that survives process death. The one true sync handoff that remains is `Future.get` at the eval boundary (`sci/eval.clj:132`). Cost: a cross-agent hop pays a commit (0.53-45 ms/tx depending on concurrency, F §1) instead of ~µs. |
| 3 | `alts!` — wait on several, take the first | `async.clj:343-409`; the flow proc parks in `alts!!` over `[control casts & ins]` with control priority (`impl.clj:286-292`) | **kept, in both halves — no longer a loss.** Scheduling half: the launcher (§3.6) is one loop parked in `alts!!` over the three class channels — take from whichever class has work, optional `:priority`, one loop instead of N blocked threads; this is exactly flow's own proc-loop shape (`impl.clj:292`). Communication half: an idle agent's "read set" is its wake predicate over datom patterns (`waking-inbound?`, `message.cljc:292-307`; the driver's one interest, `driver.clj:884-886`); control-priority = the run fence CAS failing first; the lease timer is the `timeout` arm. Agent code still cannot block mid-form on N sources — structurally must not (no suspend/resume of an SCI eval, landmine 4); waiting is `(lifecycle/wait ...)` → open + unclaimed. |
| 4 | per-channel FIFO ordering | channel semantics | **stronger**: the transaction log is a total order over the whole system, and every ordered collection carries an explicit ordinal — plan forms (`:seon.agent.run.form/ordinal`), receipts (`receipt-id` = run+ordinal+epoch). Order is never a property of the collection type (Datahike has exactly two cardinalities); the cost is that order must be explicit, which is also why it survives a crash. |
| 5 | buffer policies (sliding/dropping) as flow control | `async.clj:119-130`; flow report/error/casts use sliding 100 (`impl.clj:97-100`, `:124-128`) | ratified as three cases, not one (ledger 19e): agent messages never drop (a committed datom); unclaimed runs queue in the database with the CLAIM as backpressure (22 evals vs 18 permits → 4 queued, 71 ms max wait, zero bounced); presentation frames are latest-wins — `enqueue-latest!` `.clear`+`.offer` (`feed.clj:22-25`), the sliding-buffer-of-one, plus `:seon.db/no-history?` as its in-database temporal twin. Dropping-newest has no equivalent and no ratified need. |
| 6 | close/drain semantics, completion signalling | closed chan → nil after drain; `pipe` propagates close (`async.clj:599-609`) | **stronger**: completion is a durable fact — `finish-tx-data` closes the run, retracts the process, detaches the agent pointer in one transaction (`run.core:209-221`); drain = `next-ordinal` over terminal receipts until `total` (`receipt.cljc:137-151`), proven across six kill positions plus a double kill (F §1). Downstream "propagation" is unnecessary: consumers derive from facts; there is nothing to notify. |
| 7 | proc-local state threaded between inputs | `transform: (state, input, msg) -> [state' output]` (`flow.clj:240-256`); the loop threads it (`impl.clj:269-319`) | **deliberately absent — the one remaining honest loss, and the submission-channel correction does not change it.** A submission channel is a queue of independent work items, not a pipeline stage threading state between them, so nothing about it restores flow's `state'`. Between forms the state is the previous step's transaction report `:db-after` (measured FORCED: the identical form answered 0 at the turn's opening basis and 9 at the step's, M §8.1). Between turns it is the database, period. Cost: private in-memory accumulation across inputs is not free — an agent counter costs a commit (and the measured turn already carries 12 transactions). Benefit: the state cannot be lost, which is the entire thesis. |

**Item 1, corrected — the unification finding.** The first version of
this report called producer backpressure "the one genuine structural
loss." The owner's correction stands: the channel does not have to be
the communication medium to give backpressure — it can be the
**submission queue** to the scheduler, with communication staying in the
database. And the tree shows this is a unification, not an addition,
because the submission bound already exists in three different
expressions and is absent at two points that need it:

| submission point | bound today | expression |
|---|---|---|
| SCI eval | YES — `.acquire` on a `Semaphore` sized to `availableProcessors`, released in a `finally` (`sci/eval.clj:49`, `:56`, `:93`, `:130`) | a bounded queue with parking, in disguise — the measured 22-vs-18 result (4 queued, 71 ms max wait, zero bounced) IS a full channel parking its producer, spelled `Semaphore` |
| transactions | YES but unset — Datahike's own `transaction-queue` bounded by `transaction-queue-size` (`writer.cljc:286-306`); warns >90% (`:103-104`), commit queue throttles 50 ms at >50% (`:173-175`) | the dependency's own bounded channel; the `writer-dials` lane is exposing the dials |
| run admission / drives | NO — `start-virtual-thread!` per pending message (`driver.clj:419-420`) fanned out from `scan-body!`; `scanning?` serialises enumeration only, nothing bounds concurrent drives | nothing |
| agent capability calls | NO — nothing in `seon.db.host` or the LLM transport bounds concurrent leaf calls | nothing |

One mechanism serves all four: a bounded submission channel per workload
class (§3.6). That is a one-mechanism defect closed, which is stronger
than the loss claim it replaces. What remains honestly different from
flow: pressure reaches the *submitter* (the scan loop, the driver, the
eval's leaf call), not necessarily the *original author* of the work —
an agent that committed 500 messages completed its turn long ago, and
the backlog sits durable in the database while a full graph of bounded
flow channels would have parked the producing proc itself. That residue
is the design (durable backlog + bounded consumption), it is bounded on
the authoring side by `hop-live?` (cap 8, `message.cljc:308-317`) and by
Datahike's transaction queue parking the author's own `transact!`
mid-eval, and it moves to §8's flow-is-better list in its narrowed form.

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
   misclassification directions (§3.7).
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
replace the private pool with `(disp/executor-for :compute)`; the bound
moves to the launcher's submission channels (§3.6 — core's pool is
unbounded by design, and the current semaphore is deleted, not kept
beside them). Two things come free: the
`clojure.core.async.executor-factory` system property
(`dispatch.clj:141-143`) gives Seon one place to own construction of all
three executors process-wide; and because `go` compiles to
`thread-call :io` on this JDK (`async.clj:519-533`), **Datahike's own
writer pipeline already runs on `executor-for`'s `:io` executor** — the
database, the driver, and agent evals end up on one dispatch substrate
without any glue.

The scheduling rules, by consumer (the reader of the workload fact is
the launcher, §3.6):

1. **The eval boundary (agent interpreted code).** Always the `:compute`
   platform pool + one `:compute` slot from the launcher + the one
   `:interrupt-fn`.
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
   - `:io` or `:mixed` → **release the `:compute` slot first**, then
     submit the leaf body onto the `:io` submission channel (§3.6; the
     launcher runs it on `executor-for :io`, a virtual thread), `.get`
     with the eval's remaining `time-limit` plus slack and
     `Thread.interrupt` on timeout, **reacquire the slot** on return.

   This makes the `:compute` contract honest automatically rather than
   by a special case: "an agent-initiated blocking call releases the
   `:compute` slot while it waits" is not a rule about a list of
   blocking functions — it is the generic wrapper triggered by the
   callee's derived fact. **It is also the same rule as §3.6's
   invariant (b)** — release-before-submit is what makes parking on a
   full `:io` channel legal from inside an eval; one rule serves both
   the wedge case and the backpressure case, which is the collapse that
   makes the channel design compose instead of adding a mechanism.
   GAP #3 (a wedged host call permanently retaining a permit) dissolves
   into: a wedged leaf call holds a virtual thread and the parked
   platform thread, but **zero `:compute` slots**; compute capacity
   degrades by zero, thread capacity by one, and the thread's
   reclamation remains what O2 says it is — process replacement,
   resumed from receipts. Note what does *not* move: the platform
   thread itself must wait for the leaf result, because SCI has no
   suspend/resume (landmine 4). The slot is the logical compute
   capacity; the thread is just memory.
3. **Driver orchestration.** `process-message!` reaches the LLM leaf and
   `transact!`, so it derives `:io` — and it already runs on virtual
   threads (`start-virtual-thread!`, `driver.clj:419-420`), which is
   `thread-call :io` by another name. The derivation *ratifies* the
   placement; what changes is admission: drives go through the `:io`
   submission channel (§3.6) instead of an unbounded
   thread-per-message fan-out.
4. **Out-of-eval planned work** (future: `my.plan` invocations, schedule
   fires): submit to the class channel for `(workload f)`, applying the
   eval boundary (rule 1) whenever f is corpus code — a condition
   computed from provenance, not from a list.

**Fact production remains metered at the writer.** An agent's
`transact!` inside an eval parks on Datahike's own bounded
`transaction-queue` when full (`writer.cljc:286-306`) — the dependency's
channel is the admission bound for facts, exposed as config by the
`writer-dials` lane; the door does not need a second budget for it.

### 3.6 The submission channels and the launcher

The owner's correction, made mechanism. Three fixed-buffer channels —
one per workload class — and one launcher:

- **Submit**: every piece of schedulable work — a form eval, a drive, a
  capability leaf call, a planned invocation — is a map put (`>!!`) onto
  the channel for its class, selected by `:seon.fn/workload`. A full
  channel parks the submitter; that park IS the backpressure, and it is
  core.async's fixed-buffer semantics doing it (`async.clj:113-117`),
  not a Seon mechanism.
- **Launch**: one loop parked in `alts!!` over the three channels
  (`async.clj:380-391`; the same shape as flow's proc loop,
  `impl.clj:292`), taking from whichever class has work — with
  `:priority` available as a policy dial and deliberately not used yet
  (`do-alts` randomizes by default, `async.clj:356-361`, which is the
  fairness we want until measurement says otherwise). The launcher runs
  each taken item on `executor-for` of its class and enforces the
  class's concurrency: `:compute` items start only while live `:compute`
  slots remain.
- **Bounds are config facts, per class**: `queue-depth` (the channel's
  fixed buffer) and `concurrency` (the class's slot count). Today's
  defaults, preserved: `:compute` concurrency = `availableProcessors`
  (the measured 22-vs-18 queueing behavior carries over unchanged —
  the 4 queued submissions live in the channel instead of parked on
  `.acquire`); `:io` concurrency effectively unbounded (virtual
  threads), its queue-depth the meaningful dial; `:mixed` both modest.

**What replaces `seon.sci.eval`'s semaphore: it is deleted, not kept
beside the channel.** The public `open!`/`available`/`permits` surface
and the private pool (`sci/eval.clj:33-56`) go; `evaluate` becomes the
work the launcher runs, not a gate of its own. The semaphore's two jobs
split into the two named config facts above — its queueing job becomes
the channel's buffer (parking preserved, now instrumentable and
`alts!`-able), and its concurrency job becomes the launcher's slot
count. Whether the slot count is *implemented* with a
`java.util.concurrent.Semaphore` inside the launcher is an
implementation detail with exactly one owner; there is no second
admission mechanism anywhere, and nothing outside the launcher can
acquire capacity.

**What a full channel does at each submission point** — and whether
parking is correct there:

| submission point | who parks | correct? |
|---|---|---|
| run admission / drives (`scan-body!` submitting instead of `start-virtual-thread!` per message) | the scan loop | **yes — this is the design working.** Stop enumerating when drives are saturated; the durable backlog is the database, re-enumerated by the next scan. Wake latency under saturation: a commit during the park sets `scan-requested?` (the existing latest-wins coalescer), so enumeration resumes the moment the channel drains — delay is bounded by drain time, and no wake is lost. |
| form evals (the drive loop submitting to `:compute`) | the driver's `:io` virtual thread | yes — the run holds its claim while queued; the lease-renewal-while-queued item (§9.5) becomes load-bearing here and must land with it. |
| capability leaf calls (the door submitting to `:io`) | the eval's platform thread — **only after releasing its `:compute` slot** (invariant b) | yes, given (b); the park is on a thread that holds no compute capacity. |
| `transact!` (Datahike's own `transaction-queue`) | the calling leaf thread | yes — the dependency's own bound, reaching the authoring agent mid-eval; dials exposed by the `writer-dials` lane. |

**Three invariants, stated here once and destined for code** — each
lands as a comment at the channel/launcher creation site, not only in
this document (owner: knowledge lives mostly in code):

- **(a) A channel is a scheduling buffer over durable work, never the
  record of the work.** Channels are process-local; queued submissions
  die with the process. That is acceptable *only* because every
  agent-visible submission is a claimable run or a receipt-covered form
  in the database, and a survivor re-enumerates it (`scan-body!` on
  boot). Therefore **channel depth is never truth**: "what is queued"
  is answered by the database query (§4.4), and any reader treating
  channel depth as state has reinvented hidden state — the exact class
  §5's audit exists to prevent.
- **(b) Parking the submitter must never park a `:compute` thread.**
  Parking the scan loop or a driver vthread is fine — they are `:io`.
  An eval submitting an `:io` leaf call may park **only after the door
  releases its `:compute` slot** (§3.5 rule 2 — the same rule that
  fixes GAP #3; one rule, two consequences). `:compute` must not ever
  block is the dependency's own contract (`async.clj:562`).
- **(c) Two different channels; never conflate them.** The turn-boundary
  take — "what is addressed to me" — is a database query over facts
  (`waking-inbound?`), durable, global, temporal. The submission
  channel is "run this work," process-local and disposable.
  Communication versus scheduling. Routing agent *messages* through a
  core.async channel would reintroduce the medium this design
  deliberately replaced and forfeit durability, global access, and time
  travel; a later "simplification" that merges the two channels is a
  regression, not a cleanup.

### 3.7 Smart defaults

- **Unknown workload → `:mixed`.** This is core.async's own default —
  `thread-call` (`async.clj:566`) and flow's proc (`impl.clj:245`) both
  default `:mixed` — and it is the only safe direction: misclassifying
  as `:compute` blocks a compute slot on a park (the D9/GAP-3 class);
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
- **Per-class bounds**: `:compute` concurrency defaults to
  `availableProcessors` (the measured queueing behavior at 22/18 with
  71 ms max wait carries over into the channel unchanged);
  `queue-depth` per class is a new config fact whose right default
  needs the §9.2 sweep — start modest (flow's own per-channel default
  is 10, `impl.clj:109`) so saturation parks early and visibly rather
  than deep and late.
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
| bounded proc input buffer (`buf-or-n` 10, `impl.clj:101-109`) | the per-class submission channel into the launcher — same primitive, same parking, but over durable work | §3.6 |

Flow needs an admin API because its state is hidden in memory. Putting
the state in the database does not replace that surface — it deletes the
need for one (ledger 19f). Agents run in parallel because each claimed
run is independent work submitted through the class channels onto the
shared executors, bounded by each class's concurrency — not because
each agent owns a thread. An idle agent costs a few datoms, no thread,
no channel entry, no proc.

### 4.3 Level three: function calls inside a turn

Already designed in §3.5-3.6: the door consults the callee's workload
fact, submission goes through the class channels, and the same executor
tags run the leaf work; the same receipts cover it (a capability call
happens inside a form and is covered by that form's receipt). The
turtle claim is real because the *agent turn itself* is just the
outermost case: `process-message!` is a first-party `:io`-derived
function submitted through the `:io` channel, whose inner `:compute`
sections (evals) hold slots, whose inner `:io` sections (leaf calls)
release them before parking. One rule, applied recursively. The
database writer is the same shape one level down: a serial `:io`
pipeline over its own bounded channels whose inner work is the pure
index update. Nothing anywhere schedules by kind of thing — only by
workload fact.

**The one seam, named honestly.** Agent interpreted code carries three
obligations first-party code does not: the `:interrupt-fn`, the platform
thread (for allocation attribution and pinning-freedom), and the
`:compute` slot.
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
  query minus the history). **Never the submission channels' depth**:
  invariant (a), §3.6 — a channel is a process-local scheduling buffer
  and its occupancy is diagnostics at best, truth never.
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
| `permits` Semaphore | `sci/eval.clj:40-56` | **legitimate today, deleted by this design** — it is the `:compute` submission bound spelled as a semaphore; §3.6 replaces its queueing job with the class channel's buffer and its concurrency job with the launcher's slot count, one owner, no public gate. Its observable projection (what is executing) stays derivable from running receipts, fully so after row 4(d) |
| `seon.sci.ctx/base` delay | `ctx.clj:15-35` | **legitimate** — compiler-state cache (149.454 bytes / 321.747 ns per fork against the real base, M §3.2, is what it buys). Standing condition: base vars hold only functions and immutable values — the fork-leak class is real on an unsafe base and **nothing enforces the invariant** (F §1); it must become a startup assertion when the capability request handler installs into the base |
| `db.host/writer-session` pool/interest atoms | `host.clj:40-76` | **legitimate** — connection state; dies with the wire (§7) except for web-render |
| `interrupt/time-limit-timer` | `interrupt.clj:36-44` | **legitimate** — process resource |
| `*nano-time*` / `*now*` dynamic vars | `driver.clj:185-191` | **legitimate** — test seams |
| `web.feed/connections` + mailboxes | `feed.clj` | **legitimate** — presentation only, ratified latest-wins |

No holder in the driver must become a fact. But two things *around* the
driver must:

### 5.3 Two blockers found by this audit, now filed

Both defects this audit surfaced are filed with full evidence; cited
here, not restated:

- **The pre-plan crash window strands the run** —
  `docs/seon/issues/run-is-unrecoverable-before-its-plan-commits.md`.
  A process killed between `open-run!` and the plan commit (a window
  dominated by the provider call, 78.5% of the measured turn) leaves an
  open run no survivor can recover: `recoverable-run-query` requires
  `plan-digest` (`driver.clj:343-350`) and `pending-message-query`
  excludes messages with a run cause regardless of the run's state
  (`driver.clj:332-341`). A run that cannot be resumed from facts alone
  is the exact bug this design exists to prevent; closing direction —
  recovery selects on *open*, and a plan-less claimable run re-drives
  from its `:seon.agent.run/cause` message, at-least-once for the
  provider call being the honest ceiling (landmine 10).
- **Agent-to-agent messages never wake anyone** —
  `docs/seon/issues/agent-messages-never-wake-the-jvm-driver.md`.
  Worse than drift: `:seon.agent.message/origin` is
  `[:enum :human :agent :core]` (`message.cljc:68`), an agent-sent
  message carries `:agent` (derived at `message-transaction`,
  `message.cljc:337`; written literally by `delegate!`,
  `message.cljc:425`), and `pending-message-query` requires `:human`
  (`driver.clj:336`) — so **multi-agent collaboration is
  non-functional at HEAD**, while `waking-inbound?` — the stated one
  rule — would wake on everything except self and `:core`
  (`message.cljc:292-307`). One observed mercy of the narrowing: the
  completion message the driver itself commits (`lifecycle-tx-data`,
  `driver.clj:92-98`) re-fires the listener and is dropped by the
  origin filter, bounded to one no-op scan by the coalescer — the fix
  must keep self-wake excluded (that is `waking-inbound?`'s `from ≠ me`
  clause, plus landmine 8) while admitting `:agent` origins.

Neither is fixed here (this lane changes no source); both gate any
claim that the loop is fully database-defined, and both enter the §6
ordering.

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
3. **The capability request handler itself** (roadmap row 1). The dispatch site of
   §3.5 rule 2 must exist before the fact has a reader. The door is
   already the earliest unsettled contract for unrelated reasons; this
   design adds its workload-consultation duty to row 1's spec, it does
   not reorder the spine.
4. **Receipt observability fields** (row 4d) — fn-entries, allocated
   bytes, semaphore wait onto the terminal receipt — so §4.4's
   management queries and the axiom-falsification loop (§3.2) have
   data.
5. **The two filed driver blockers** (§5.3) — with row 6, because both
   fixes touch the same wake/recovery queries:
   `run-is-unrecoverable-before-its-plan-commits.md` and
   `agent-messages-never-wake-the-jvm-driver.md`. The second gates any
   multi-agent claim at all — no scheduling design matters for agents
   that cannot wake each other.
6. **Then the launcher**: the per-class submission channels (§3.6) with
   `seon.sci.eval`'s semaphore and public `open!`/`available` surface
   deleted in the same change (never both mechanisms live at once), the
   scan fan-out and capability calls routed through them, `executor-for`
   routing, `:seon.fn/workload`, and the slot-release door wrapper.
   Order within this step: route `seon.sci.eval` through
   `executor-for :compute` first (small, independently shippable,
   closes the "borrowed word without its dispatch" defect the roadmap
   already lists as owed), then the channels + launcher replacing the
   semaphore, then the door wrapper, then the derived fact. The three
   invariants of §3.6 land as comments at the channel creation site in
   this step.

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

**The three dials** (§3.7; the `writer-dials` lane is exposing them):
set `transaction-queue-size` and `commit-queue-size` as config facts
when the merge lands — in-process, writer queue pressure and agent fact
production share one heap, so the queue bound becomes the memory guard,
and Datahike's bounded transaction queue is the third expression of the
one submission-bound pattern (§2 item 1); leave `commit-wait-time` 0
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
- **Backpressure where it is queryable, now with flow's own primitive
  doing the parking.** The unclaimed-run backlog is inspectable durable
  data, and the submission bound is a real bounded channel (§3.6) —
  Seon gets flow's parking semantics at every submission point *and* a
  backlog you can query; flow's full channel is an opaque stall.

**Where flow is better (non-empty, honestly):**

- **Graph-propagated backpressure to the original producer.** With
  submission channels Seon has parking backpressure at every point
  where work is *run* (§3.6), but pressure reaches the submitter — the
  scan loop, the driver, the eval's leaf call — not necessarily the
  *author* of the work: an agent that committed 500 messages finished
  its turn long ago, and the durable backlog absorbs what a chain of
  bounded flow channels would have pushed back to the producing proc
  itself. The authoring side is bounded by `hop-live?` and by
  Datahike's transaction queue parking `transact!` mid-eval, but the
  end-to-end producer stall flow gives across a whole graph is
  genuinely not reproduced — by design (the durable backlog is the
  feature), and stated so nobody discovers it.
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
2. **The queue dials — writer and submission channels together.** Right
   sizes for `transaction-queue-size` / `commit-queue-size`, whether
   any `commit-wait-time` > 0 ever wins on APFS given self-tuning
   batches, and the per-class `queue-depth` / `concurrency` defaults
   for the §3.6 channels — including the scan loop's park latency under
   drive saturation (how stale enumeration gets before the channel
   drains). Experiment: the §16 load rig swept over all five dials on a
   throwaway cluster (O9 makes this safe), watching tx/s, p50, park
   time at each submission point, queue pressure warnings, and RSS.
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
   prototype (F §3): max observed submission wait was 71 ms against a
   60 s lease, so renewal-during-wait never fired; a convoy of long
   evals makes a healthy queued run stealable — and §3.6 makes queued
   time a first-class state (a submission parked in the `:compute`
   channel), so renewal ownership must cover it. Experiment: `:compute`
   concurrency 2, three evals of length > lease, assert no steal of a
   queued healthy run once renewal lands with row 6.
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
