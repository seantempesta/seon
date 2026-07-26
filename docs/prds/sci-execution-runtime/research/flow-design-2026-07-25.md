---
type: research
status: active
tags: [research, runtime]
---

# seon.flow — an agent is a process whose state is its database entity

## Status of this document

This is a **rewrite in place**, 2026-07-25 evening. The prior version of this
file contained three sentences that the prototype falsified against running
code. They are named here so nobody re-derives them:

1. **The turn-level transform signature**
   `(database value, agent, message) -> [tx-data, messages, effects]` — dead.
   It was a shape *ported* from `core.async.flow` and cannot express
   read-your-own-writes. Measured: the identical form answered **0** against
   the turn's opening basis and **9** against the step's basis
   (`flow-prototype-2026-07-25.md:44-56`). Form granularity is **forced, not
   chosen**.
2. **"No ticker, no polling"** — false as written. Datahike's `listen`
   callback fires "on each transact" only
   (`reference-code/datahike/src/datahike/api/specification.cljc:1076`), so a
   lease going stale — which is not a commit — can never be delivered by the
   committed-transaction feed, and the moment it matters is exactly when the
   feed goes silent. Measured: a stranded run stayed stranded four lease
   periods until an unrelated commit arrived (D2).
3. **The deletion arithmetic** "roughly 250 lines … replaces ~10,000+" —
   overclaimed on both sides. Measured: **450-550 projected against ~6,994
   replaced**, a 13-15x reduction, and two of the large "replaced" files are a
   **port**, not a deletion.

Every defect D1-D16 from `flow-prototype-2026-07-25.md` that changes the design
is folded into the design below, not appended as errata. The defect number is
cited at the sentence it changed.

Companions: `flow-prototype-2026-07-25.md` (the measurements and the defect
ledger), `redesign-ledger-2026-07-25.md` (R-1..R-18, the falsifiers a port
fails), `implementation-plan-2026-07-25.md` (waves; its numbers predate the
evening measurements), `wtf-review-2026-07-24.md` (the HEAD trace).

## 1. The sentence

`core.async.flow` gives a process a `transform` and the framework does all I/O,
threading and lifecycle. Seon is that, with **the state in Datahike instead of
memory** — and at **form** granularity, not turn granularity:

```
flow  : (state,             in-id, msg)         -> [state', {out-id [msgs]}]
seon  : (basis database value, agent id, step)  -> tx-data
```

where the **basis** of step *n+1* is the `:db-after` of step *n*'s transaction
report. An agent is a process. Its database entity is its state. Messages are
the channels. **One turn is a fold of steps, each its own transform.**
"Stateless agent" is the precise statement that each transform is pure: all
state arrives as an argument and leaves in the return value.

Read-your-own-writes then costs **zero extra round trips** — Datahike's own
transaction report carries `:db-after`. Receipts record `:seon.eval/basis-t`
per step and the chain is strictly increasing across a turn
(`flow-prototype-2026-07-25.md:484-492`).

**The one place Seon must exceed flow.** A flow transform is fast, pure and
cheap — lose one and you retry it. A Seon step calls a model that costs money
and performs irreversible effects. It **cannot** be retried silently. That is
why receipts exist: they give durable position *inside* a turn, which flow has
no need for and no concept of.

So: **an agent is a flow process at the outer level; receipts give position at
the inner level.**

Corollary that falls out for free: the topology is **derived, not declared**.
Flow makes you declare the graph. Here, who-messages-whom is database data, so
the graph is emergent — which is what you need when agents create other agents
at runtime.

## 2. The strongest argument: the admin surface is free

`core.async.flow` ships a full admin API, verified at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`:
`start` with `:report-chan` (:112) and `:error-chan` (:116), `stop` (:123),
`pause` (:128), `resume` (:132), `ping` (:136-140), `pause-proc` (:142),
`resume-proc` (:146), `ping-proc` (:150), `inject` (:155).

Every one maps to something Seon already has, and every mapping is **strictly
stronger**, because a query is durable, historical and available to anyone
while a ping is a live probe answering one caller once:

| flow | seon | why stronger |
|---|---|---|
| `ping` / `ping-proc` | a query over run and receipt facts | durable, historical, any reader |
| `pause` / `resume` | release / reclaim the run (claim CAS + epoch) | survives process death |
| `inject` | commit a fact | atomic with everything else in the transaction |
| `:report-chan` | the committed-transaction feed | already the one live-update path |
| `:error-chan` | fault datoms | queryable after the fact, not only at the moment |

Stronger still: `ping` takes `timeout-ms` (default 1000) and returns status
only "for those procs that reply within timeout-ms" (`flow.clj:136-140`). Flow's
own introspection **silently omits the wedged process you most need to see** —
which is exactly the D9 case.

**Flow needs an admin API because its state is hidden in memory. Putting the
state in the database does not replace that surface — it deletes the need for
one.** This is the single strongest argument for the design and it appeared
nowhere before this rewrite.

## 3. What is NOT being built

**No engine.** Seon has one linear sequence of about five steps in one process;
a generic graph runner for that is more mechanism than the thing it runs, and
Seon has already built two phase state machines that turned out dead
(`loop.cljs:39` `transitions`, zero callers; the six-phase cursor's recovery arm
at `driver/host.clj:700`, a 5-arg call to a 7-arg fn, provably never executed —
filed as `docs/seon/issues/settle-eval-replay-arity-mismatch.md`). A third
would be the pattern, not the fix.

Take the *discipline* — pure `transform` steps that do not know they are in a
loop — and skip the framework.

**No compile tier, no JIT, no accretion-as-speed.** Measured: one 7-step turn
ran **~104 ms/step of which SCI eval is 0-5 ms**, across 12 transactions per
turn, before an LLM call that dwarfs everything
(`flow-prototype-2026-07-25.md:129-131`). **SCI is ~5% of a turn.** The commit
path is the cost centre. A JIT, a compiled tier and speed-motivated accretion
all optimize that 5%. Separately, the JVM SCI JIT has **no substrate**: on
`:clj`, `->Node` expands to a bare `reify` that never references its `ast`
argument and `attach-ast` is the identity, with SCI's own comment reading "the
`:clj` reify and `:cljd` branches DISCARD the form at expansion time"
(`reference-code/sci/src/sci/impl/types.cljc:245-247`, `:264-273`, `:281-288`);
`jit.cljs` is the only jit file. Compiling agent code to a native JVM fn would
also **delete the `:interrupt-fn`** and must never happen (owner ruling,
2026-07-25).

The only real performance problem is the ~10 s JVM boot, and it is
**orthogonal to this architecture** — see §11.

**No second cache, no per-agent retained `ctx`** — see §6.

## 4. Flow control is three cases, not one

This is a design decision, not an implementation detail, and it was written
down nowhere before this rewrite.

| what | policy | mechanism | why |
|---|---|---|---|
| agent messages | **never drop** | committed datom; delivery derived by one predicate | losing one loses work |
| unclaimed runs | **queue in the database** | inspectable durable backlog; backpressure is the CLAIM (semaphore-bounded) | a queue you can query is a feature |
| Datastar frames | **latest wins** | sliding-buffer of one per connection | a morph is absolute, so dropping is lossless |

Groundings:

- Messages: `src/seon/agent/message.cljc:286-307`, whose own source comment
  names `waking-inbound?` as "ONE source of truth for this message wakes".
- Runs: the claim CAS / epoch / lease algebra in
  `src/seon/agent/run/core.cljc` (on the ledger's keep-verbatim list).
- Frames: `src/seon/web/feed.clj:22-25` — `enqueue-latest!` does
  `(.clear mailbox)` then `(.offer mailbox value)`.

**Why dropping is safe there and only there.** Datastar's default
`ElementPatchMode` is `outer`, whose own docstring is "Morphs the element into
the existing element"
(`reference-code/datastar-clojure/.../consts.clj:46-48`, default at `:79-82`).
A morph carries the **complete** element, so any frame supersedes every earlier
one and dropping is lossless — the same property as reconnect=repaint and as
"the UI is a pure function of the database value". Datastar **does** have
incremental modes (`append`, `prepend`, `before`, `after`, `:62-77`), for which
dropping would be lossy. **The property belongs to Seon's usage, not to
Datastar**: `(datastar/patch-elements! sse value)` at `feed.clj:40` is the sole
patch call under `src/seon/web/` and `src/seon/ui/`, and it passes no opts.
Reaching for `:append` silently breaks the drop.

**Vocabulary.** What `feed.clj` hand-rolled is core.async's **sliding-buffer of
one** — "oldest elements in buffer will be dropped"
(`reference-code/core.async/.../async.clj:125-129`). It is *not* a
dropping-buffer, which discards the **newest** (`:119-122`). Use the borrowed
word.

**Companion deletion:** `::mailbox-depth` is dead configuration. `.clear`
before every `.offer` on every path makes depth > 1 structurally unreachable,
yet the knob costs `config/system.edn:171`,
`src/seon/config/resolve.cljc:284`/`:1134-1135`/`:2055-2056`, and
`src/seon/web/server.clj:21,33,293`.

## 5. `seon.sci.interrupt`

The one `:interrupt-fn`. Vocabulary from `reference-code/sci/doc/interrupt.md`.

**Banned word: "safepoint."** A JVM safepoint is a different real thing (GC).
SCI's own phrase is **"every `fn` body entrance"** (`interrupt.md:50`). The
hook fires at exactly three sites, all in SCI's fn invocation trampoline: once
per fn body entrance and once per `recur`
(`reference-code/sci/src/sci/impl/fns.cljc:52`, `:77`, `:166`). There is no
other hook.

**`time-limit` is the ONLY limit** (defined `interrupt.md:26`; `:32` is its
usage line). Two consequences:

- **`:seon.eval/fn-entries` is a RECORDED DIAGNOSTIC, not a limit.** There is
  no fn-entry limit (owner ruling, 2026-07-25). The SCI interpreter contains
  **zero counters** — "interpreter-step budget" and "fuel" named a mechanism
  the dependency does not have, which is why the configured default sat at
  100,000,000 (`config/system.edn:84-86`) against an observed P99.9 of 19,999
  (`u1-fuel-calibration-2026-07-23.md:33-42`) and was never calibrated.
- **`:seon.eval/allocated-bytes` is a RECORDED DIAGNOSTIC, not a limit** — see
  D14 below and open ruling O4.

### Cadence (D8, fixed)

```
every fn body entrance     : read one volatile long/boolean; interrupt! on breach
every 1024 fn entries      : getCurrentThreadAllocatedBytes  (diagnostic only)
```

**Why the every-entry read is mandatory, measured.** Under a 1024-entry sample
interval the nested-bigint form
`(let [b (loop [v 2N i 0] (if (< i 18) (recur (* v v) (inc i)) v))] (mod (apply * (repeat 300 b)) 7))`
ran **319 fn entries, 8,867 ms against a 500 ms limit (17.7x), 99,341,268,032
bytes against a 64 MB cap (1,480x), outcome `:ok`** — the agent received its
answer. Reading the time flag as a **volatile on every entry** killed the same
form `:time` at **505 ms with 99 fn entries**. The fix is *proven, not
projected* (reproduced 2026-07-25, JDK 26.0.1; matches
`flow-prototype-2026-07-25.md:284-286`).

### The hole, stated instead of implied

**A single un-overridden host call over data the agent already holds charges
zero fn entries and no cadence can see it.** Measured:
`(alength (byte-array 200000000))` allocated 200,033,752 bytes in 1 ms with
**0 fn entries** and outcome `:ok` under both a 500 ms `time-limit` and a
64 MB cap. This is upstream's own stated ceiling, not a Seon compromise: "For
hard guarantees it is best to run untrusted code in a separate process that can
be killed" (`interrupt.md:85`; the interpreted-only caveat at `:52`).

The design must **say** this. It must not imply a bound it does not have.

### Coverage is better than the standing issue claims (D16, R-9)

`docs/seon/issues/core-hof-forms-bypass-the-guard-safepoint-entirely.md`
headlines `(reduce + (map inc (range 1000000)))` as **0** safepoints. That was
measured against a bare ctx. **The tree merges both interrupt-aware namespaces
into the base** (`src/seon/host/context.clj:1405-1406`), so idiomatic agent
Clojure **is** metered. Measured against the real merged base, 2026-07-25:

| form | fn entries |
|---|---|
| `(reduce + (map inc (range 1e6)))` | 1,999,999 |
| `(count (filter even? (range 1e6)))` | 1,500,000 |
| `(sort (vec (range 300000)))` | 300,000 |
| `(frequencies (range 200000))` | 200,000 |
| `(apply + (repeat 1e6 1))` | 1,000,000 |
| `clojure.string/join` over 100k | 100,000 |
| `clojure.string/replace` over 200k chars | 100,000 |
| `clojure.string/split` over 200k chars | 300,000 |

D16 (`clojure.string` uninstrumented) is **PROTOTYPE-ONLY** —
`src-flow-prototype/src/flow/ctx.clj:20` merges `clojure-core` but not
`clojure-string`. The tree is already correct. Carry D16 forward **only** as an
invariant the new `seon.sci.ctx` must not regress.

### Cost, settled in situ — do not quote 44x

Four figures circulated (44x, 29.857 ns/check, 71.2→26.8 ms, 2.4x). Settled
over **3,000,001 real SCI fn entries** (median of 5, 3 warm runs, JDK 26.0.1):

| shape | total | ns/entry | ratio |
|---|---|---|---|
| no `:interrupt-fn` | 24.6 ms | — | 1.00x |
| agent-fork shape (closure over holder) | 73.4 ms | 16.3 | **2.98x** |
| base ctx-store shape (production) | 116.4 ms | 30.6 | 4.73x |
| target: closed-over long-array + one volatile read | 34.5 ms | 3.3 | 1.40x |

`0.204 ns/step` is below one memory operation — a JIT-eliminated loop; **44x is
a microbenchmark artifact.** The independent `29.857 ns/check` from
`u1-fuel-calibration-2026-07-23.md:64-71` agrees with 30.6.

**The finding nobody had: there are TWO `:interrupt-fn` shapes and every prior
analysis conflated them.** `build-base!` installs a closure doing
`(sci.ctx-store/get-ctx)` plus two keyword lookups per entry
(`src/seon/host/context.clj:1409-1412`); `fork-context` **overwrites**
`:interrupt-fn` on every agent fork with a closure over the holder
(`:1423-1430`). **Agent evals never pay the ctx-store deref**, worth 14.3
ns/entry — 47% of the base overhead. Base-ctx evaluation does. The fix owner is
`src/seon/host/guard.cljc` **and** `context.clj:1409-1412`.

### Uncatchable, and what agent code can name (D15)

`sci/interrupt!` throws an ex-info carrying a private marker that SCI's own
`try` refuses to hand to user catch clauses
(`reference-code/sci/src/sci/interrupt.cljc:32-42`, docstring states the
contract verbatim). Verified: agent code cannot swallow it and cannot even
**name** a class broad enough to try — `Throwable`, `Error`,
`RuntimeException`, `StackOverflowError` and `:default` all fail with "Unable
to resolve classname"; only `Exception` resolves.

D15 fix, in the design: add `Throwable` and `Error` to `:classes` (idiomatic
agent Clojure writes `catch Throwable`), and include `(.getName (class t))`
when `getMessage` is nil — a `StackOverflowError` currently reports "Threw after
26ms." with a raw `nil`.

### Reading source is part of the armed window (D7 — LIVE IN THE TREE)

Parse with **SCI's own reader**, `(sci/parse-string ctx source)`, so the parse
cost sits inside the armed window. D7's *direction* is right; its *stated
reason* was wrong and the wrong reason invites someone to "restore" the flag:
edamame does not lack `#=` — it **recognizes and refuses** it
(`(sci/parse-string ctx "[:x #=(...)]")` throws "EvalReader not allowed when
`*read-eval*` is false").

**The tree's hole is different and it is live.** `clojure.tools.reader` has its
**own** `*read-eval*` — a different var from `clojure.core/*read-eval*` —
defaulting to `true`, with `#=` wired as a dispatch macro (tools.reader 1.5.2
`reader.clj:879-895`, `:816`, `:591-595`). `seon.host.record/read-forms`
(`src/seon/host/record.clj:36-58`, verified to bind only `*ns*` and
`*alias-map*`), `read-host-form` (`:77-87`), `src/seon/host/eval.clj:477-481`
and `src/seon/host/context.clj:1033-1040` all read agent-authored source and
bind nothing. Running the production functions,
`(defn f [] #=(clojure.core/spit "<path>" "...") 1)` **executed `spit` and wrote
the file**, returning `(defn f [] nil 1)`: zero fn entries, no ctx, no
`:classes`, no receipt. `read-forms` is gated on `(= :form kind)`, **not** on
`ok?`, so a form SCI already **rejected** is handed to tools.reader anyway.

A fix that binds only `clojure.core/*read-eval*`, or that merely reorders behind
`ok?`, **does not close it.** Filed:
`docs/seon/issues/tools-reader-evaluates-agent-source-at-read-time.md`.

### Thread kind

**Must run on a platform thread**, for two independent reasons — and note that
only the second is unconditional:

1. **Allocation is unmeasurable on a virtual thread.**
   `getCurrentThreadAllocatedBytes`: platform `[376 -> 2,939,976]` /
   `[0 -> 4,796,600]` / `[376 -> 6,877,912]` at 31.5 ns/call; virtual `[-1 -1]`.
   A watcher thread reading the virtual thread's id also gets `-1`, because
   virtual threads are absent from `getAllThreadIds`. Grounded in this
   machine's JDK: `java.management/sun/management/ThreadImpl.java:346-350`
   (`isThreadAllocatedMemoryEnabled() && !Thread.currentThread().isVirtual()`),
   `:358-368`, and the `getAllThreadIds` absence at `:131-134`.
   **This reason buys only the diagnostic unless an allocation limit is
   authorized — see O4.**
2. **Agent code on a platform thread has no carrier-pinning surface.** Verified
   with `jdk.virtualThreadScheduler.parallelism=1` and 8 cluster JVM wedged
   inside evals: an unrelated virtual thread still completed 5/5 steps in
   902 ms. `Semaphore.acquire` and `Future.get` both unmount.

**The `:interrupt-fn` must be ARMED ON THE COMPUTE THREAD, not by the caller.**
The first prototype armed it on the `:io` caller, reported 183 KB for a run
that allocated ~67 MB, and misattributed a `:memory` kill as `:time`. A real
implementation trap, not a detail.

**The parkNanos fairness argument is VOID and must be deleted wherever it
appears.** `simplification-design-2026-07-25.md:205-208` claims parking is
"free on a virtual thread … ~1.4 ms each on a platform thread" and uses that to
argue the platform pool must go. Measured: raw `LockSupport/parkNanos(1000)` is
**3.6 µs/call platform, 11.9 µs/call virtual** — ~390x cheaper than the claim
and **cheaper on the platform thread**. Over 3,000,001 fn entries at one park
per 65,536 entries (45 parks): virtual +0.7 ms, platform −7.3 ms. Noise on both.

## 6. `seon.sci.ctx`

**ONE CORPUS, UNIVERSAL** (owner ruling, 2026-07-25). There are no separate
concepts for agent-authored code and the rest of the system. Quality is
**attributes on the row**; **advertising is filtered by those attributes**.
What is personal to an agent is its **environment** — its entity, its messages,
its context blocks — **not its code**.

Runtime consequence, and it is the whole of this namespace:

```
ONE shared base ctx per process   (build-base! ≈ 80 ms)
ONE sci/fork per EVAL             (in-flight isolation only)
NO retained per-agent ctx
```

Today's `ensure-context!` does the per-agent thing **and never evicts**. It is
**simultaneously the owner-rejected model and the memory leak**: two
independent implementations fill `:seon.host/contexts`
(`src/seon/agent/driver/host.clj:136-152` and `src/seon/host.clj:138-160`,
duplicated fork+replay+install+reconcile) and `rg 'dissoc.*contexts' src/`
returns nothing. Each retained ctx carries exactly one guard holder
(`context.clj:1423-1430`), so any per-agent concurrency shares the counter and
the interrupt cell — B's `reset!` refills A's budget, B's `finally` clears A's
deadline predicate. **One deletion fixes both.**

`simplification-design-2026-07-25.md:245` currently says the opposite ("Do not
build a second cache — `ensure-context!` is the owner"). That sentence
preserves the leak by citation and must be corrected.

Filed:
`docs/seon/issues/retained-agent-contexts-are-never-evicted-and-share-one-holder.md`.

**Accretion, under this model.** It splits in two (owner ruling):

- Compiling agent code to a native JVM fn **deletes the `:interrupt-fn` and
  must never happen**.
- A proven function becoming available to every agent is a question of **which
  namespace the var is interned in** — ordinary Clojure, nothing to do with
  speed. `simplification-design-2026-07-25.md:395-396` objects that `sci/fork`
  snapshots the namespace map so a promoted var is absent from already-forked
  agents. **That objection holds only in the retained-per-agent-ctx model the
  owner rejected.** With one shared base and a fork per eval, a var interned in
  a base namespace is visible to the next fork.

**Fork cost — do not quote either published figure without re-measuring.**
Measured 182 bytes retained and 0.04 µs at 100,000 live forks — but against an
empty `(sci/init {})`, **not** Seon's ~182-namespace base. The circulating
539 bytes / 2.1 µs has no recorded provenance. Either way 1,000 forks move the
heap ~0.18-0.5 MB, which reads as 0 MB at whole-MB resolution: **forks are
cheap, not free.** `simplification-design:224`'s "10,000 live forks = 5.1 MB"
should read 1.8-5.4 MB pending a measurement against the real base.

**The invariant to enforce with one test: base vars hold only functions and
immutable values.** Forks isolate new `def`s; they do **not** isolate mutation
of an existing shared var (`alter-var-root`, `set!` on a dynamic, an atom in a
var's value, `defmethod` on a base multimethod, `extend-protocol` on a base
protocol). The prototype demonstrated the leak class real on a deliberately
unsafe base and **nothing enforces the invariant today** — it is prose plus one
demo line.

## 7. `seon.sci.eval`

Evaluate forms under the `:interrupt-fn` on a `:compute` thread. Returns a
value or a flat `:seon/error`. **Never throws into the loop.**

Three rules the prototype's version lacked:

1. **Bound the caller** — `.get(deadline + slack)` plus `Thread.interrupt` on
   the compute thread, so host calls that honour interruption unwind (D9). This
   must land **in** `docs/seon/issues/eval-deadline-interrupt-swallowed-by-database-call.md`,
   whose whole point is that a single `Thread.interrupt` delivered once is
   consumed by an in-flight database call and clears the flag forever.
2. **An agent-initiated blocking call RELEASES the `:compute` permit while it
   waits**, and a wedged thread **leaves the pool** so capacity degrades by one
   rather than to zero (D9). Without this rule the design uses core.async's word
   `:compute` against core.async's stated meaning — see §9.
3. **Never re-execute blindly.** The receipt already carries index, `:running`
   and the epoch, so a pure query answers "this step has wedged N cluster JVM"
   and fails the run (D9).

**The tree's version of D9 is different and worse in one way.** There is no
semaphore: there is a **fixed 10-thread platform pool**
(`src/seon/host.clj:61`, `:332-333`) and `cancel-active!`, which waits 2 s on
the `Future` and then **walks away leaving the thread running**
(`src/seon/host/invoke.clj:280-283`). Ten wedged evals exhaust the cluster
**permanently**, with no queue, no permit accounting and no signal. It is
better in one way: no lease-steal-into-the-same-step path, so a wedged eval
cannot be re-fed to the next victim.

**The fixed count dies; the platform thread survives.** Measured, the semaphore
queues and never bounces a claim: 22 concurrent evals against 18 permits
(config fact, default `availableProcessors`) — 4 queued, max wait 71 ms, total
88 ms, every caller proceeded. Same at 8 against 2.

**One more correction the design owes.** `policy-error!` hardcodes
`:seon.error/fault :agent` (`src/seon/host/guard.cljc:150`), so a `time-limit`
or output-cap trip is filed as an agent **coding mistake** rather than a
resource event.

## 8. `seon.flow` — the driver loop

The only impure part:

```
claim (CAS + epoch)
  -> commit the ordered step plan (CAS-gated)
  -> fold: for each remaining index
       acquire basis (= previous step's :db-after)
       start receipt (run, index, epoch)
       eval on :compute
       admit  the returned tx-data
       commit tx-data + terminal receipt + run-fence, in ONE transaction
  -> close
```

### One write connection per store (D1)

Two live JVMs on one file store **both won the same epoch CAS**, and **40 of 40
of the parent's successfully-returned commits vanished** with zero transact
errors, leaving a store that looked pristine on reopen. The `:self` writer
backend is process-local (`reference-code/datahike/src/datahike/writer.cljc:282`
defmulti, `:286` `:self`). Cluster JVM forward writes. **The unsafe configuration
must REFUSE TO OPEN** — D1's failure was silent.

Note the correction: "`create-writer` ships only `:self`" is **false as
written**. The `:datahike-server` HTTP writer backend exists at
`reference-code/datahike/src/datahike/http/writer.clj:35`. The concrete exit
for horizontal cluster JVM is `:datahike-server` for **writes** plus the existing
`seon.db.host` interest transport for **wake** — necessary because Datahike's
own `listen` is declared `:supports-remote? false`
(`api/specification.cljc:1073`). Not new machinery; it is what the pod already
uses. `docs/seon/architecture/architecture.md:242-246` promises interchangeable
cluster JVM and is unsafe as written — see O2.

### The ordered step plan is committed once, CAS-gated (D11)

Commit the whole ordered plan in one transaction at run start. It is the only
**proven** variant: it modelled `preflight/repair-read-entry` splicing (6
emitted entries → **7** executed forms) and answered `total 7` throughout,
where a reply re-parse answers 6. Resume was correct at six kill positions plus
a double kill (converged at epoch 3), with **exactly one re-execution per
crash** (8 evals for 7 steps). SIGKILL **inside** `d/transact` at 8 points over
200-datom transactions produced **zero torn transactions**.

**Its measured race is a mandatory companion fix, not a footnote.** `start-run!`
is check-then-act: 12 trials of two concurrent calls for one run id with two
different replies committed the plan `BBBBAAA` in **3 of 12** — indices 0-3
from reply B, 4-6 from reply A, one coherent-looking plan spliced out of two
model replies. In the real system the race window is **the model call** —
seconds. Gate the insert on a `:db/cas`, or write the plan as one
cardinality-one value, so a second reply replaces it wholesale or not at all.

Rejected alternative: the per-receipt "remaining entry vector" from
`simplification-design`. No race, but it rewrites the tail on every step.

### Receipts carry an explicit ordinal (D3, D12)

Key the receipt by **(run, index, epoch)**. Resume counts **terminal** receipts
only; a `:running` receipt from a foreign epoch is **abandoned, not in flight**;
a step with a terminal receipt at its index is **never re-executable**.
`next-index` is the **first index in `(range total)` with no terminal receipt**
— which is one line and also removes the in-flight special case (D12; a count
used as a position skipped 3 and 4 forever given a hole).

**State the HEAD symptom next to the prototype defect or the cut aims wrong.**
D3's *mechanism* is prototype-only: HEAD's terminal transition **is** CAS-fenced
`:running -> :running` (`src/seon/eval/receipt.cljc:90-100`), so double
*recording* is prevented. HEAD's symptom is the **inverse and equally bad**:
`:seon.eval/id` is a generated `::db.id/compact-value` (`receipt.cljc:48`), not
(run, index), and forms hang off cardinality-many `:seon.agent.turn/evals`. So
"form 3 of 7" is **unanswerable at HEAD**, and a duplicate execution is
**indistinguishable from a legitimate one**.

**Vocabulary reconciliation, mandatory.**
`docs/seon/issues/multi-form-eval-order-is-not-durable.md:59-102` proposes
`:seon.eval/position`. That is the **same fact** as `:seon.eval/index` +
`:seon.eval/total`. **Do not implement both.** The repo already owns three
ordering idioms — `:seon.error.frame/index`, the terminal status datom's
transaction id (`::status-tx`), and `(juxt :seon.eval/at :db/id)` — and a fourth
is the banned parallel mechanism. Mark `:seon.eval/position` superseded in that
issue's triage.

### Every step transaction carries the epoch fence (D4)

`[:db.fn/cas run-ref :seon.agent.run/claim-epoch e e]` plus the agent-pointer
CAS, in **every** phase and work transaction. A stale cluster JVM's next commit
fails and it stops. In the real system every step is a model or capability call
longer than any plausible lease, so an unfenced overrun is the **normal path**,
not an edge case.

**D4 is prototype-only.** `run-fence` already exists and already rides every
work transaction at HEAD (`src/seon/agent/run/core.cljc:38-47`); the
prototype's `drive-run!` simply omitted it. Keep it verbatim.

### The lease is renewed by the claim, not by step boundaries

`beat-tx-data` exists and is correct but is reachable **only** through
`claim-plan`'s `:held` arm — i.e. only when a claim is *re*-attempted
(`src/seon/agent/run/core.cljc:160-170`, `:138-142`). `drive-claim!` re-reads
state between steps and never beats
(`src/seon/agent/driver.cljc:517-594`). With `stale-ms` 1,200,000
(`config/system.edn:619`) a healthy run driving longer than 20 minutes is
stealable. Renewal must be owned by the claim — **while a step is in flight and
while parked on the semaphore** — not by step boundaries.

### Agent-returned tx-data passes an admission gate (D10)

A step returned `{:facts [[:db/add "not-an-eid" :nope 1]]}`. The eval
**succeeded**; the poison detonated in the driver's own `d/transact` and the
exception **escaped the driver entirely** — run left open, receipt stuck at
`:running`, **no fault recorded anywhere**. A survivor retried 3 times and threw
3 times: a permanent poison pill with no attempt counter and no dead-letter
path. Hostile-but-valid facts are worse because nothing complains: one step
wrote 424242 into **another agent's** counter; another retracted its own run's
open flag and the driver carried on because it never re-reads it.

This violates the standing rule that nothing throws into the agent loop. The
three shapes name "durable FACTS the driver commits" — **the driver decides what
is committable; it does not pass through.**

Design: commit the step transaction inside a `try`; on failure commit a
**terminal** receipt carrying the fault, alone — which both records it and kills
the pill, because resume advances past a terminal receipt. Constrain facts to
attributes the agent may write on entities it owns. Carry an attempt count for
dead-lettering.

### Do not store what you can derive (D6)

40 concurrent 1-step runs for one agent: 40 runs, 40 `:ok` receipts,
**counter = 1. Thirty-nine increments lost, nothing anywhere reporting a
problem.** A transform that reads a value from its basis and writes a computed
value is correct only if nothing else writes that attribute between basis and
commit — and wake makes concurrent runs for one agent the normal case.

The repo rule already answers it: **derive projections instead of storing
them.** That counter is exactly `(count receipts)`. Where a stored value is
genuinely required, emit `[:db/cas eid attr seen (inc seen)]` and re-run the
transform against a fresh basis on failure.

Made invisible by set semantics: a cardinality-many string attribute collapsed
10 runs writing the same string into 1 datom, and 704 re-executions into 1 log
line. **Anything meant to prove a step ran once must carry (run, index,
epoch).**

### Message identity is derived from the sending receipt (D13)

Two agents messaging each other in a loop stopped after **3 hops with no
error**: `:message/id` as `(str from "->" to "#" index)` is a unique identity,
so the second message **upserted the first entity** — which already had a run —
and the waking not-join excluded it. Final state: 3 messages, 3 runs, zero open
runs, nothing reported, and a torn read where a completed run's message pointer
targets an entity rewritten under it.

Derive message identity from the sending receipt — **(run, index, epoch)**.
Note the tension the fix must resolve deliberately: a deterministic id is
exactly what keeps delivery idempotent under D3/D4 re-execution, so **D3 and
D13 must be designed together.**

### Wake (D2, D5) — event-driven, with one honest backstop

The prior version of this document pointed at the existing `listen!` → `scan!`
wiring (`driver/host.clj:807-815`) as already done. **It is the defect, not the
solution.**

**D5, the stampede.** `scan!` commits; every commit fires `listen!`; every
`listen!` submits a whole new `scan!`. Measured: commits per useful run
**7.0 → 14.4 → 124.8** at n=2/5/10; lost CAS claims **5 → 157 → 10,343**;
`OutOfMemoryError` at n=20 after 2,555 scans.

**The fix is a PARAMETER of the existing mechanism, not new machinery.**
`seon.db.host/listen!` (`src/seon/db/host.clj:306-329`) already accepts
`::protocol/datom-patterns` (e/a/v/added?, max 64,
`src/seon/db/protocol.cljc:594-601`), and the writer already maintains a
`::by-attribute` interest index (`src/seon/db/writer.clj:2860-2878`,
`:2900-2905`). The cluster JVM passes the **worst available option** —
`:datahike.read/dependency-plan :all` with `(fn [_] (scan!))`
(`src/seon/agent/driver/host.clj:809-814`) — a full open-runs query inline in
the callback for **every cluster commit**. Pass attribute-scoped patterns; add
equality suppression and a single-slot latest-wins pending scan. No
scan-per-commit.

This cluster JVM-side `:all` listener is currently **unfiled**; the nearest issue
scopes itself to the web tier.

**D2, the strand — this is structural.** Datahike's `listen` callback fires "on
each transact" only (`api/specification.cljc:1076`). A lease going stale is
**not** a commit, so the committed-transaction feed **cannot** deliver it, and
the moment it matters is exactly when the feed goes silent. Measured: a
stranded run stayed stranded four lease periods until an unrelated commit
arrived; committing one unrelated entity completed the whole chain instantly.

Design, without reintroducing a poll and in line with the standing rule that a
clock is a loud last-resort backstop whose firing is itself a bug report:

- **Preferred: change the interface so the claim publishes its own liveness.**
  The commit that **sets** the lease is itself an event, so a listener schedules
  a one-shot wake at that instant, cancelled by the run's close commit. That is
  event-driven off a real commit.
- The one-shot firing is a **bug report**, not the primary failure path.

"No ticker, no polling" is deleted from this design.

## 9. Workloads

`:io` / `:compute` / `:mixed` are core.async's own tags. **Citation
correction:** the documented meanings live in `thread-call`'s docstring at
`reference-code/core.async/.../async.clj:561-563`, **not** at
`impl/dispatch.clj:122-134` — that file only *constructs* the executors
(`:127-133`). Under the standing vocabulary rule a borrowed word must cite where
the dependency **defines** it. Verbatim:

> `:io` - may do blocking I/O but must not do extended computation
> `:compute` - must not ever block
> `:mixed` - anything else (default)

- **`:compute`** — the SCI eval. Platform threads, semaphore-bounded.
  Allocation measurable. Load-bearing, not an implementation detail.
- **`:io`** — the loop and all waiting (model calls, capability calls).

**Adopting the word obliges honoring the definition.** Agent SCI code *does*
perform database writes, LLM calls and host calls, and D9 measured one
`(host/block 600000)` draining every permit permanently — exactly the
violation. §7's release rule is what keeps the usage truthful.

We supply our own bounded executor per tag: core.async's own `:compute`
executor is an **unbounded** cached thread pool
(`impl/dispatch.clj:71-73` `make-ctp-named` = `newCachedThreadPool`,
constructed `:127-133`), and we need the bound.

**Correction worth recording:** "core.async's `go` blocks impose async
contagion" was **wrong** for modern core.async on this JDK. `go*` expands to
`(thread-call (^:once fn* [] ~@body) :io)` — a virtual thread, **no
state-machine transform** — whenever virtual threads are available
(`async.clj:519`, `:530`). `^:async` in CLJS is a real transform; `go` on this
JDK is not.

Thread economics, measured JDK 26.0.1: platform 29.4-69.9 µs to create
(re-measured 50.8) and 38-60 KB RSS each (re-measured 47.1 at 2,000 live);
virtual 1.6-5.2 µs and 0.60-5.1 KB at 100,000 live; 100k virtual threads
sleeping 50 ms complete in 250-420 ms.

## 10. Monitoring

No separate metrics system. Every eval emits its record as a receipt:

```clojure
{:seon.eval/index          3
 :seon.eval/total          7
 :seon.eval/basis-t        11482
 :seon.eval/fn-entries     271197184   ; DIAGNOSTIC, not a limit
 :seon.eval/ms             502
 :seon.eval/allocated-bytes 67108864   ; DIAGNOSTIC, not a limit
 :seon.eval/outcome        :time}      ; :ok | :time | :error
```

That is the whole observability surface, and it is what makes the error message
good: **271M fn entries in 500 ms reads as a spin; 12 entries in 500 ms reads as
blocked in a host call.** Same fact, two different messages to the agent.

**The allocation metric measures the wrong quantity (D14), which is why it is a
diagnostic and not a limit.** It measures **cumulative allocation
(throughput)**, not live footprint, so it is **anti-correlated with the heap
risk it appears to bound**:

- **Kills a harmless program:** 20,000 × `(byte-array 1000000)`, all
  immediately garbage, live footprint ~0 — killed `:memory` at 1,023,124,376
  bytes. `(reduce + (range 500000))` killed at **20 ms**.
- **Misses a dangerous one:** retaining 1,000 × 1 MB under `-Xmx512m` — 473 fn
  entries, 473,333,160 bytes, cap never fired, **the JVM OOM'd instead**.
- **Consequence:** at the default cap **every** interpreted runaway is reported
  `:memory` in ~12 ms, so the `:time` branch — including "blocked inside one
  host call", the single most diagnostic string in the design — is **effectively
  unreachable**. Removing the cap as a limit **restores** that message.

Interpreted SCI allocates **~48.1 bytes per fn entry at ~5.2 GB/s**, so a 64 MB
cap is a **work budget of ~1.4M interpreted steps**, not a heap bound. If the
intent is "one agent must not exhaust the heap", **no in-process metric can
express it** — that is the process boundary (`interrupt.md:85`). The three-way
distinction the agent needs is **looping / blocked in a host call / retaining
too much**, and only the middle one is currently expressible.

## 11. Crash semantics

Receipt before the act ("no receipt, no run" — already implemented at
`src/seon/host/eval.clj:398-412`). After a crash a survivor queries: a form
whose index has no terminal receipt has not completed; a `:running` receipt from
a foreign epoch is abandoned. **Resume is a query, not a stored cursor.**

Guarantee, stated honestly: **at-least-once, and measured tight — exactly one
re-execution per crash.** Atomicity is per **step**, not per turn: a sender that
crashes after step 3 has already delivered step 3's messages and **there is no
unsend.** This must be in the agent-facing docs as the semantics.

The agent is told, in plain language: killed for time, runtime state lost,
database state retained.

## 12. Boot is a separate problem with a separate owner

Recorded here only so it is not folded into the architecture. **The JVM is not
slow — Clojure namespace loading is COMPILATION, and one dependency is most of
it.**

Measured JDK 26.0.1, `-Xmx2g`, 358 namespaces: **10,293 ms** from the source
classpath → **3,976 ms** with the AOT jar and `-Xshare:off` → **3,886 ms** with
AOT plus the AppCDS archive. `datahike.api` alone: **6,568 → 755 → 300 ms**.

**AOT contributes 92.7% of the saving; AppCDS 7.3%.** AppCDS caches **class
loading only**; the 6.1 s is **compilation**; **only AOT skips compilation** and
AppCDS then caches the result. It is the **pair**, with AOT carrying almost all
of it. The earlier claim that "AppCDS targets it directly" was an overclaim and
must not be repeated.

**Two caches at two levels, unrelated.** (a) AOT+AppCDS caches **Seon's own**
compiled JVM classes at process boot, and changes only when Seon is rebuilt.
(b) The SCI base ctx caches agent-visible namespaces, built ~80 ms after boot.
**Agent-authored functions can never enter AppCDS** — `effective-tier` returns
`:nursery` unconditionally (`src/seon/host/graduate.clj:128-134`), so every
corpus function executes through SCI as a `:seon.fn/source` row and never
becomes JVM bytecode.

**Two disjoint boot problems.** The 271 s fresh-cluster reset is pod-side
(corpus indexing, build-projection computed twice) and is owned by
`docs/seon/issues/fresh-boot-271s-rederives-build-computed-state.md`.
AOT+AppCDS does **nothing** for it; de-quadratic build-projection does
**nothing** for the JVM boot. Two line items, two owners.

**Measurement-provenance rule for everything in this document:** quote **one**
pair with its flags (10,293 → 3,886 ms, `-Xmx2g`, JDK 26.0.1), never three
numbers as one fact. Every memory figure carries its `-Xmx`.

## 13. The honest arithmetic

Replaces the deleted "roughly 250 lines … ~10,000+".

**Replacement size.** 767 lines built in the prototype; **284 net core**;
**450-550 projected** once the D1-D16 fixes land (every-entry volatile time
flag, permit release plus bounded `.get`, CAS-gated plan insert,
`::datom-patterns` interest, lease-liveness path, `sci/parse-string`, admission
gate, Malli schemas). **Quote 450-550, never 284** — 284 excludes the fixes that
make it correct.

**Replaced size.** **~6,994 lines across 14 files**, each still owing its
falsifier run before the cut.

**Ratio: 13-15x. Not 40x.**

Two subtractions must travel with the ratio or it becomes a lie:

- `src/seon/host/context.clj` (2,181) + `src/seon/agent/ctx.cljc` (1,959) =
  4,140 lines are a **PORT**, not a deletion (R-8c). They move tiers; they do
  not disappear.
- **~3,650 wire lines SURVIVE** for web-render (R-8b). Not in the replaced
  count; never counted as saved.

**Split by caller, not deleted as a unit.** `src/seon/host/graduate.clj` (276):
`trust-gate?` has **zero production callers** (defn at `:108`; the only other
hit is `test/seon/host_graduate_writer_test.clj:102-111`) — dead, delete.
`install-nursery!` and `rebuild!` are **live** (`src/seon/host/eval.clj:321`,
`src/seon/host.clj:319`) — keep. Whole-file deletion breaks the corpus install
path; "keep and repoint `trust-gate?`" preserves dead code. **The live gate
neither position names** is `my.plan.internal/green-tested?`
(`src/my/plan/internal.cljc:844-849`), namespace-granular, feeding
`compile-namespace-dag`'s diff — a one-mechanism violation sitting directly on
the accretion gate's input.

**Deletions that are not free.** `:seon.fn/execution-tier` is the corpus
**membership** where-clause, not merely a tier stamp: `record.clj:150` writes
`:nursery` on every eval-teed fn, first-party rows never carry it, and
`rebuild!`'s recorded-function-query selects on its **presence** as its only
where-clause (`graduate.clj:90-100`). Delete it alone and `rebuild!` installs
**nothing** at the next boot. The grounded replacement is presence-plus-
provenance — `[?fn :seon.fn/source]` minus the boot-process join
`seon.db.program` already uses (`src/seon/db/program.clj:40-45`) — which is the
ONE-CORPUS ruling expressed as a query. **Same commit.**

**Free deletions, verified.** `::mailbox-depth` (§4). The stale nil-digest
AOT reports in `simplification-design` and `implementation-plan` (§12).

**Numbering collision, to fix downstream:** `implementation-plan-2026-07-25.md:43-55`
uses D1..D11 for **decisions** while `flow-prototype-2026-07-25.md` uses D1..D16
for **defects**, in two files a reader opens together. Today "D5" means both
"result symbols named by (pid, start-instant)" and "the wake stampede". The
plan's decisions revert to being its own §2 table rows referred to by **name**;
the D prefix belongs to defects.

## 14. Corrections this session made — recorded so they are not re-derived

These are how the session avoided shipping wrong designs.

- **"There is no function-level call edge" was WRONG.**
  `:seon.program.edge/calls` exists as `[:set :string]`
  (`src/seon/program/edge.cljc:11`) and is written per function (`:543-544`).
  **But the reverse index is NOT sound**, which is load-bearing for the
  accretion gate: three sites discard resolved targets (`:371-377`, `:412-419`,
  `:421`). Measured: `(map thumbnail ids)` records only `clojure.core/map`; a
  bare returned `thumbnail` and `{:render thumbnail}` record **nothing and no
  uncertainty**. Every higher-order caller is a silent false negative. Equally
  disqualifying for the effect half: `canonical-terminal` defaults every
  unannotated target to `:external` (`:143-152`) while the tee passes `{}`
  literally (`src/seon/host/record.clj:452`), so any purity rollup over the
  stored graph is a **constant**.
- **"core.async's `go` blocks impose async contagion" was WRONG** for modern
  core.async on this JDK (§9).
- **The turn-level transform signature was a SHAPE PORTED from
  `core.async.flow`**, caught by review and then **falsified by measurement**
  (0 vs 9). The clearest instance of the standing anti-port rule catching
  itself.
- **200-concurrent was a BENCHMARK SIZE, not a limit.** Curve 30.25 / 6.73 /
  2.04 / 0.73 ms/tx at n=1/10/50/200 (swept curve; a separate dedicated 200-tx
  benchmark reported 0.53 vs 45.09 serial — **name the run with each number**).
  Still improving at 200; **the ceiling was never found**. The mechanism:
  Datahike's writer is a strictly serial processing go-loop threading
  db-before → db-after into a separate commit thread that drains the queue as
  one batch-commit, with `DEFAULT_COMMIT_WAIT_TIME = 0`
  (`reference-code/datahike/src/datahike/writer.cljc:83`, `:100-188`, `:213`,
  `:266`), so batch size **self-tunes upward with offered load**. Seon sets
  neither `:commit-wait-time` nor `:transaction-queue-size` — an unexplored free
  dial on the one measured cost centre.
- **Both proposed fixes for the vector-order bug were killed by evidence.**
  Tuples: Datahike throws above 8 values for a homogeneous tuple
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1006-1012`) and
  element-level datalog against a tuple returns nothing. Component-ness:
  pre-existing component refs written in permutation pulled back in ascending
  entity-id order. **The fix was wrong DECLARATIONS, not a wrong bridge** — and
  it **landed** as commit `5a37489c6`, nine declarations changed to `[:set …]`,
  with **zero** lines of `form->cardinality` or the tuple trigger touched.
  The rule the episode teaches, stated in no architecture document: **Datahike
  has exactly two cardinalities (`schema.cljc:59`), so order is NEVER a property
  of the collection type** — it is a stored ordinal on the child, a recovered
  transaction id, or an explicit sort key.
- **The JVM SCI JIT measurement:** `fib(30)` = **7.0 ms** compiled with no
  check, **34.7 ms** compiled with the production check, **70.9 ms**
  interpreted. **The check costs 4x the compiled body today** — fix the check
  before any compiler.
- **The AOT-vs-AppCDS split** (§12).
- **Six wrong file:line citations** across the active document set, in a corpus
  whose whole premise is that every claim carries a file:line. Corrected in
  place here: the SCI JIT substrate (`types.cljc:245-247`/`:264-273`/`:281-288`,
  not `:181-191`); the workload tags (`async.clj:561-563`, not
  `dispatch.clj:122-134`); `time-limit` (defined `interrupt.md:26`, not `:32`);
  virtual-thread allocation (`ThreadImpl.java:346-350` + `:358-368` +
  `:131-134`, not `:347` alone); `guard.cljc` (the file is **242** lines — the
  sites are `:242` and `:150`, not `:250-252` and `:149`, and there is no `.clj`
  twin); and the visibility attribute is **`:seon.fn/private?`** with the
  trailing question mark (`src/seon/schema.cljc:423`,
  `src/seon/host/record.clj:154`) — writing it without the `?` makes the
  ratified vocabulary row itself an invented name.

## 15. Vocabulary

Owner-ratified 2026-07-25 unless marked. Every name cites where the dependency
or Clojure defines it.

| use | never | defined at |
|---|---|---|
| `:interrupt-fn` | the guarded door, the door | `reference-code/sci/doc/interrupt.md:6` |
| `interrupt!` | guard, stop!, steering-error! | `reference-code/sci/src/sci/interrupt.cljc:32` |
| `time-limit` (the only limit) | fuel, gas, step budget | `reference-code/sci/doc/interrupt.md:26` |
| "every `fn` body entrance" | **safepoint** (banned — a JVM safepoint is GC) | `reference-code/sci/doc/interrupt.md:50` |
| `ctx`, `fork` | warm base | `reference-code/sci/src/sci/core.cljc:318` |
| `:io` / `:compute` / `:mixed` | — | `reference-code/core.async/.../async.clj:561-563` |
| sliding-buffer of one | dropping-buffer, latest-wins queue | `reference-code/core.async/.../async.clj:125-129` |
| `:seon.fn/private?` | — | `src/seon/schema.cljc:423` (from Clojure's `defn-`) |
| namespaces `seon.sci.interrupt` / `seon.sci.ctx` / `seon.sci.eval` | — | owner ratified |

Two entries needing care:

- **"accretion" / "breakage", rule "require no more, provide no less"** —
  believed Rich Hickey, *Spec-ulation* (Clojure/conj 2016). **THE CITATION IS
  UNVERIFIED.** The lane assigned to confirm it failed; no vendored source under
  `reference-code/` contains the talk and no external material was fetched. The
  **words** may be used — they do real work and the vocabulary rule wants
  borrowed language — but **the citation may not**, until confirmed against a
  primary source. Note that
  `docs/seon/issues/output-map-closedness-decides-accretion-legality.md:15-16`
  already asserts it as fact in the committed tree, which defeats this marker
  unless corrected in the same pass.
- **"cluster JVM" is a Seon COINAGE — UNRATIFIED REPLACEMENT PENDING.** Zero files
  in the vendored Datahike checkout contain it, against 25 files under `src/`.
  Proposed: **`:seon.agent.run/process`**, grounded on both sides —
  `script/seon/dev/process.clj` already carries the process record with
  `(pid, start-instant)` and generation, matching JDK `ProcessHandle`. See O3.
  This document uses "cluster JVM" only because the replacement is not ratified.

## 16. What is measured, and what is not

**Measured** (JDK 26.0.1, Clojure 1.12.0, this machine, 2026-07-25) — every
number in this document traces to `flow-prototype-2026-07-25.md` §1 or to a
probe reproduced in the reconciliation pass.

**Not measured, and nothing here should be read as claiming otherwise:**

- **No LLM, no real capability calls.** No fs, web, shell, or agent-authored
  `db` write passed through the `:interrupt-fn`. No `:mixed` workload. No
  streaming. Every turn timing is a **lower bound** by the cost of a model call.
- **Sustained heap exhaustion by retention across many threads was never
  reproduced.** What *was* measured is a **refused oversized allocation**,
  survived twice: transactor survived, 0 errors, 94 further commits, store
  consistent on reopen, one 180 ms latency spike against a 33 ms median;
  `StackOverflowError` contained in 17-26 ms; no permit leaked across ~50
  killed/thrown/OOM'd evals. **The honest sentence is: a single oversized
  allocation is refused and survivable (measured); sustained retention is
  unproven and is bounded by no in-process metric.** Neither "an agent OOM
  restarts the whole cluster" nor "OOM does not take the writer down" may enter
  the spec as a flat sentence.
- **The multi-writer configuration has no clean result.** Every successful
  crash-resume was single-writer or strictly sequential JVMs.
- **Lease renewal while parked on the semaphore is not implemented.**
- **R-8a was not falsified.** `seon.host.instrument`'s global fair
  `ReentrantReadWriteLock` was never in the prototype's path.
- **`resume` is re-queried at every step** — O(steps²) per turn. Correct,
  unoptimised, unmeasured at length.
- **The fork base invariant is unenforced** — prose plus one demo line.
- **Only the `:file` backend was exercised.** No cloud store, no remote writer,
  no replica session, no web-render reader concurrent with cluster JVM.
- **No reset-boundary live proof.** Per the repo's own testing rule, schema,
  acquisition and process behaviour at a cluster reset is a different failure
  class than any fixture or prototype can see.
- **The 450-550 line count is a projection, not a measurement.** Only the
  284-line broken version was built.
- **The sci/fork cost was measured against an empty base**, not Seon's.
- **ReDoS on other JDKs** — the negative result is JDK-26-specific.
- **`src-flow-prototype/` is claimed by NO runner** (`bin/test-cljs`,
  `bin/test-writer`, `bin/seon test operator` all miss it). By the repo's own
  rule a proof invisible to every runner counts as **NOT COVERED**, so D1-D16
  are currently **evidence, not coverage** — see O6.

## 17. Open owner rulings

These block implementation waves and are not the author's to decide.

**O1 — Co-located writer heap blast radius.** Nothing in-process bounds live
memory; a single host allocation is uncatchable; no in-process metric expresses
"this agent must not exhaust the heap". *Recommendation:* accept for now with
the honest sentence in §16 and a named exit (process isolation per
`interrupt.md:85`), rather than implying a bound. Rejecting costs the co-located
topology and reintroduces the child-process machinery the great deletion is
removing (execution children measured at 650 MB idle). Home:
`docs/seon/issues/eval-process-isolation-memory-containment.md` — **update it**
(it is written in the CLJS-pod framing); do not file a second note.

**O2 — Horizontal cluster JVM topology.** `architecture.md:242-246` promises
interchangeable cluster JVM; D1 destroyed 40 of 40 commits silently.
*Recommendation:* change `architecture.md`, make the unsafe configuration refuse
to open, and record the concrete exit (`:datahike-server` for writes +
`seon.db.host` interest transport for wake). Adjacent evidence: four databases
in one writer process measured 1.96x/1.55x/1.55x rather than 4x, so "more writer
processes" does not obviously buy throughput either.

**O3 — Replace the coinage "cluster JVM" with `:seon.agent.run/process`?**
*Recommendation:* ratify. 25 files under `src/`, plus tests and docs — a
mechanical shared-tree rename the top level must do atomically. Ratify in the
**same change** as the result-symbol identity decision, so one word covers
process identity in both places.

**O4 — Is there an allocation LIMIT at all? This decides the thread kind and
exists in no document.** The owner ruled `time-limit` is the only limit and
`fn-entries` is a diagnostic; D14 shows the allocation metric is anti-correlated
with the heap risk. If no allocation **limit** is authorized, the platform
thread rests **solely** on carrier-pinning (§5 reason 2), because the time flag
is a volatile readable on any thread. *Recommendation:* keep allocation as a
recorded diagnostic, delete it as a limit, and still keep the platform thread on
reason 2 — and state loudly that removing the cap **restores** the ":time with
few fn entries = blocked in a host call" message. **Blocks the Wave 2 deletion
list.**

**O5 — Which tuple trigger survives the bridge reconciliation?** The JVM builder
makes tuple ⟺ float inner type alone (`src/seon/db/datahike/schema.clj:163-166`,
`:196-198`); the portable derivation makes it tuple ⟺ the computed
`:db.secondary/only` property (`src/seon/db/internal.cljc:160-176`).
*Recommendation:* keep the portable rule, **delete** the float-inner-type
trigger — it is a hand rule about a value type, the banned pattern. Latent
today (the only registered float vector is secondary-only); silent if left.

**O6 — Where does `src-flow-prototype/` live and which runner claims it?**
*Recommendation:* claim the fatal-defect regressions under `bin/test-writer` and
delete the rest. Minimum surviving set: **D1** multi-writer silent loss, **D2**
lease strand, **D5** wake stampede, **D6** lost updates, **D9** permit drain,
**D10** poison tx-data, **D11** start-run! splice race — **plus the 0-vs-9 basis
measurement**, the load-bearing proof that form granularity is forced, which
today no test claims and which exists only as prose in two documents. Remove the
~40 checked-in store directories regardless.

## 18. Defect map — where each D lives in this design

| D | severity at HEAD | where it changed the design |
|---|---|---|
| D1 multi-writer silent loss | fatal | §8 one write connection; refuse to open |
| D2 lease strand | fatal, structural | §8 wake; "No ticker, no polling" deleted |
| D3 receipt identity | fatal (HEAD symptom inverted) | §8 receipts carry (run, index, epoch) |
| D4 no epoch fence | prototype-only | §8 `run-fence` already at HEAD; keep verbatim |
| D5 wake stampede | fatal | §8 `::datom-patterns` interest — a parameter, not machinery |
| D6 lost updates | fatal | §8 derive, don't store; `:db/cas` where stored |
| D7 read-eval escape | fatal, **LIVE IN THE TREE** | §5 `sci/parse-string` + the tools.reader hole |
| D8 sample interval | fatal | §5 volatile every entry — fix **proven** |
| D9 permit drain | fatal (tree's version worse) | §7 release rule, bounded `.get`, wedged thread leaves pool |
| D10 poison tx-data | fatal | §8 admission gate + terminal fault receipt |
| D11 start-run! splice | serious | §8 CAS-gated plan insert (mandatory companion) |
| D12 count-as-position | minor, latent | §8 first index with no terminal receipt |
| D13 message identity | serious | §8 identity from (run, index, epoch); design with D3 |
| D14 allocation metric | serious | §10 diagnostic, not a limit; O4 |
| D15 catch Throwable | minor | §5 `:classes` + class name when message is nil |
| D16 clojure.string | **prototype-only** | §5 tree already correct; invariant only |
