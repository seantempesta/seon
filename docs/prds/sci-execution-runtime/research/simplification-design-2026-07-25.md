---
type: research
status: active
tags: [research, runtime, architecture]
---

Terminology: quoted measurements and source identifiers retain their historical spelling; current prose uses `ctx`, `fork`, `:interrupt-fn`, `interrupt!`, `time-limit`, every `fn` body entrance, `:compute`, `:io`, accretion, and breakage.

# Simplification design: one cluster JVM, one thread, one loop (2026-07-25)

Synthesis of seven source audits, three independent designs, and nine
adversarial critiques, plus live probes run for this document. Written to be
falsified: every load-bearing claim carries a `file:line` or a measurement you
can rerun.

Two claims in the input material are WRONG and are corrected here with
measurements (see [Corrections](#corrections-to-the-input-material)). One
deletion ledger in the input material is inflated by roughly 4,800 lines and is
re-priced below.

## 1. The mental model

> How I would explain the runtime to a new engineer, on one page.

There is one process. It holds the database connection, one shared SCI `ctx`, and
every running agent.

A run is a row in the database with `:seon.agent.run/process` and an epoch. When a run becomes
claimable, the process starts **one virtual thread**, and that thread drives the
run to close: derive the prompt, call the model, evaluate the reply's forms one
at a time, publish. There are no phases and no handoffs, because there is no
second process to hand off to.

An agent's `fork` comes from the shared `ctx` — 2.1 microseconds, 539 bytes
— plus a replay of that agent's own `:seon.fn/source` rows. That is the whole
"start an agent" operation, and it is also the whole "recover an agent"
operation, because a fork does not care whether the previous process died or
never existed.

A form the agent writes runs like ordinary Clojure. It reads the database, it
writes the database, it calls out to the world. Nothing is deferred and nothing
is described-then-performed: `db/transact!` transacts, `fs/write-file` writes.
What makes this recoverable is **not** that the eval is pure. It is that the
driver commits a receipt saying *"form 3 of the remaining 5 is starting, here is
its source and here are the ones after it"* **before** the form runs, and
commits a receipt saying *"this irreversible external call is about to happen"*
before each one. So after a crash a survivor reads the datoms and knows which
form was running, which forms never ran, and which effects are confirmed versus
unknown — and tells the agent that in plain language.

The database is not a runtime. Agent code does not run inside a transaction. The
database is a database, SCI is an interpreter, the driver is a plain
`loop`/`recur`.

Containment is **one** primitive: SCI calls the one `:interrupt-fn` at every
`fn` body entrance (`reference-code/sci/doc/interrupt.md:50`). `time-limit` is
the only execution limit, and expiry calls `interrupt!` uncatchably. SCI evals
run on `:compute` platform threads; blocking calls use `:io` virtual threads.
`:seon.eval/fn-entries` records diagnostic evidence but controls nothing. That
is the entire in-process safety story. It bounds **time**, and nothing else:
an agent that allocates a 10 GB vector kills the process, and no in-process
mechanism can prevent that.

Everything else in the current 11,900-line agent layer is either the cost of
splitting one turn across two processes, or dead code from a superseded loop.

## 2. The owner's questions, answered

### Is SCI controlling the loop? Is it self-contained?

**No, and it should not be.** SCI is an interpreter, not a scheduler. Today
`seon.agent.driver/drive-claim!` (`src/seon/agent/driver.cljc:517`) is already a
plain `loop`/`recur` asking `seon.agent.loop.core/next-step`
(`src/seon/agent/loop/core.cljc:76`) which phase comes next. That shape is
correct. What is wrong is *why* it exists: the pod owns `#{render publish}`
(`src/seon/agent/driver/pod.cljs:46-48`) and the JVM owns `#{eval interaction}`
plus `llm` (`src/seon/agent/driver/host.clj:68-73`), so one turn crosses the
process boundary twice and each crossing is a full claim/release protocol.

SCI's total contribution to the loop is `:interrupt-fn`.

**Self-contained in the way that matters, leaky in three verified ways.**
`sci/fork` is literally `(update ctx :env (fn [env] (atom @env)))`
(`reference-code/sci/src/sci/core.cljc:318-323`), so a fork's namespace map is
its own and `def`/`defn` are private to it. But Var *objects* are shared by
reference, so three channels cross fork boundaries today:

- `defmethod` on a base-defined multimethod;
- `extend-protocol` on a base-defined protocol;
- any atom held in a base var root.

`:sci/built-in` (`src/seon/host/context.clj:522-536`) protects the var **root**,
not the value behind it. The same blanket stamping also breaks
`clojure.core/derive` for every agent, because it stamps
`clojure.core/global-hierarchy` — a capability loss nobody chose.

### Can agents update the database from inside SCI?

**They already do.** `seon.db/transact!` is an ordinary host closure installed as
a SCI var (`src/seon/host/context.clj:657-674`). SCI imposes nothing on it, and
`:interrupt-fn` does not inspect it — capability authorization belongs to the
database function boundary, not interruption.

The problem is cost, not permission. One unpinned agent write today is:

`seon.db/transact!` (≈10 validation/normalization passes) → an implicit head
resolve (**wire round-trip 1**) → transit encode → **process boundary** →
writer → `d/transact!` → response → transit decode → the transaction itself
(**wire round-trip 2**). Four transit encodes, four decodes, a connection pool
with a 120,000 ms call deadline, and a durable idempotency protocol
(`src/seon/db/host.clj:597-838`, `src/seon/db/writer.clj:1281-2072`).

Including receipts and fences, **one agent form containing one write costs
roughly six to seven writer round-trips.** That is the complexity you reacted
to, and essentially all of it is the socket.

### Are database ops just effects?

**Yes at the language level, and that framing is already correct.** But be
precise about one thing that is not true today:

**There is no per-eval transaction scope and no rollback anywhere.** Grep
`src/seon/host/` and `src/seon/db/writer.clj` for rollback: nothing. Each
`db/transact!` commits immediately. Three writes then a throw leaves three
committed transactions and one `:seon.eval/status :error` receipt.

The SEGMENT design tried to change that (accumulate tx-data, commit once) and it
failed on semantics, not on effort — see §3.

### Would embedding everything inside the database help? (SpacetimeDB)

**No.** The honest comparison:

| | SpacetimeDB | Seon |
|---|---|---|
| Bound | `Config::consume_fuel(true)`, meters **every WASM instruction incl. library internals**, traps unconditionally (`crates/core/src/host/wasmtime/mod.rs:94`) | `time-limit` enforced by `:interrupt-fn` at every SCI `fn` body entrance |
| Wall clock | Epoch interruption **logs and resumes** — no wall-clock kill (`wasmtime_module.rs:354-363`) | watchdog thread + `Thread.interrupt`, cooperative |
| Isolation | `begin_mut_tx` takes a **database-global write lock** — Serializable by construction (`datastore.rs:908-940`) | snapshot isolation, write skew possible |
| Interpreted path | V8 budget timer **commented out as undefined behaviour** (`crates/core/src/host/v8/budget.rs:23-41`) | — |

That last row is the whole argument. SpacetimeDB's V8 path is the closest analog
to SCI — a dynamic-language interpreter embedded in the database — and a
well-resourced team has **not** landed budget enforcement for it. Do not copy the
model.

Seon partly compensates for that coverage difference in a way the input critiques missed:
`sci.interrupt/clojure-core` overrides `range reduce into count iterate doall
dorun repeat cycle` plus the regex and `clojure.string` fns, and those **are**
merged into Seon's base (`src/seon/host/context.clj:1405-1406`). Measured (this
document, §Corrections): `(reduce + (map inc (range 5000000)))` records 9,999,999
`fn` body entrances. The common "big lazy computation" case is interruptible.

What is **not** bounded is memory. Measured: an OOM after **4,068 `fn` body
entrances**. A `time-limit` cannot bound allocation.

Datahike specifically forecloses the in-database option twice:

- `:db/fn` is rejected under `:schema-flexibility :write` (absent from
  `implicit-schema-spec`, `reference-code/datahike/src/datahike/schema.cljc:89-170`);
- `:db.fn/call` bodies run on the **one serialized processing go-loop**
  (`reference-code/datahike/src/datahike/writer.cljc:100-188`), so a long agent
  computation there blocks every transaction in the cluster, and calling blocking
  `d/transact` from inside it deadlocks by construction.

**Take three ideas, leave the rest:**

1. **The reducer/procedure split.** Work that can run inside a transaction is
   atomic for free and needs no receipt; work that touches the outside world gets
   exactly one receipt per commit boundary
   (`docs/.../key-architecture.md:311-317`).
2. **Applied vs durable, with observers gated on durable.** SpacetimeDB defaults
   `DEFAULT_CONFIRMED_READS: bool = true` (`crates/client-api/src/lib.rs:30-33`).
   Seon already has the offset (`:t`). Gate the web feed and every external
   effect on it so nothing outside the process observes a fact a crash could
   unmake.
3. **"Globals are undefined behaviour"** verbatim as agent-facing doctrine
   (`docs/.../reducers.md:510-521`), including reasons 5 and 6 (non-transactional
   rollback of globals; replay on a serializability anomaly), which Seon has never
   stated.

One primitive worth taking that only becomes available in-process:
`:db.entity/preds` — a `:db.type/symbol` cardinality-many attribute resolved with
`clojure.core/resolve` at transaction time and asserted against `db-after`
(`reference-code/datahike/src/datahike/db/transaction.cljc:845-853`). That is the
right eventual home for an accreted contract predicate: a write-time invariant
that makes an invalid fact unrepresentable, not a test.

### Can virtual threads keep everything parallel?

**Yes, and it is the fast path — measured, not assumed.**

- JDK 26.0.1 is pinned (`bin/_java-home-resolver:19`), so JEP 491 applies:
  monitors no longer pin. Verified for Clojure's `locking` specifically — 8
  virtual threads each holding a monitor across a 300 ms sleep on 1 carrier
  finished in **306 ms**, not ~2,400 ms.
- Blocking `d/transact` from 200 virtual threads: **0.49 ms/tx** versus **23.77
  ms/tx** serial, because Datahike's commit loop drains its queue with
  `(into [tx] (take-while some?) (repeatedly #(poll! commit-queue)))`
  (`writer.cljc:211`) into one durable commit. **Parallelism IS the batching
  mechanism**; no batching API needs writing.

**The real constraint is not pinning, it is that there is no preemption.** JEP
444: the scheduler never time-shares virtual threads. Measured: 2 CPU-bound
virtual threads on `parallelism=2` starved a latecomer for a full 3 s, while a
platform thread was scheduled in 0 ms. `Thread/yield` and `Thread/sleep 0` do
**not** release the carrier; only a genuine unmount does.

SCI therefore stays on `:compute` platform threads rather than occupying virtual
thread carriers. The run's virtual thread parks while the `:compute` work runs;
blocking provider and capability calls use `:io`.

Two ceilings to state out loud rather than discover:

- Datahike's processing loop is strictly serial, so **write throughput is one
  core** regardless of thread strategy.
- `create-writer` ships only `:self` (`writer.cljc:286`), which is process-local,
  so **one process per store**. (A `:datahike-server` HTTP backend does exist at
  `reference-code/datahike/src/datahike/http/writer.clj:35`, so the topology is
  not permanently locked — Seon's UDS wire is a hand-rolled version of it.)

### Can the shared `ctx` be forked and agent start be near-instant?

**Already true and already built. Do not redesign it — delete around it.**

Measured: `build-base!` ~80 ms warm / ~220 ms cold; `fork-context` **2.1 µs /
539 bytes** (10,000 live forks = 5.1 MB); fork + `ensure-context-ns!` + replay of
100 stored defs = **2.876 ms**.

The felt slowness is somewhere else entirely: **requiring the JVM namespace
closure costs 9,232 ms** — 100× the cost of the base it exists to build — and
`bin/seon status` reports the shared JVM AOT+CDS identity mismatch with all four
expected digests `nil`, falling back to a source launch. Under the standing
velocity ruling this is a production incident for development.

Three real fixes to the fork path:

1. `graduate/rebuild!` creates a fresh fork **and a fresh namespace per corpus
   FUNCTION** at every boot, then discards the context
   (`src/seon/host/graduate.clj:157-171, 247-254`; called at
   `src/seon/host.clj:317`). Make it one fork per *namespace*, replaying in
   transaction order.
2. `stamp-shared-base-vars!` must be computed, not blanket
   (`src/seon/host/context.clj:522-536`).
3. Delete the disk-reading `base-load-plan` path; the live host already passes a
   precomputed plan (`src/seon/host.clj:285-286`).

**Do not build a second cache.** `ensure-context!` already does fork + replay +
cache over one atom, and its own comment says so: *"Restore = fork the shared
base plus replay the agent's corpus defs ... a context is a cache of database
facts"* (`src/seon/host.clj:141-146`; agent path at
`src/seon/agent/driver/host.clj:136-152`).

### Can accretion work?

**The gate is built and correct; the ambition needs re-scoping.**

`trust-gate?` (`src/seon/host/graduate.clj:108-126`) already requires spec-valid,
test-covered, fingerprint match, interpreted-green, compiled-green, and
**identical results across tiers**. `effective-tier` still hardcodes the
retired literal `:nursery`
(`graduate.clj:128-134`) pending R48/P4.

Two honest findings:

1. **R48 was right about the code it killed.** The deleted `compiled-var` bound
   `*ns*`, called `clojure.core/refer` on `clojure.core`, then
   `clojure.core/eval` on agent **source**. In a sealed `create-ns` with no
   `refer`, unqualified `slurp` and the `fn` macro are refused — but
   `clojure.core/slurp`, `System/exit`, `java.io.File.` and `Class/forName` all
   **compile**. Namespace hygiene is not a containment boundary on the JVM.
2. **Compiled bytecode has no `:interrupt-fn`.** "Long, hard computations" plus
   native compilation equals "unkillable, unbounded, in-process".
   The trust gate needs an explicit answer to that before `effective-tier` ever
   stops returning the retired literal `:nursery`.

**Position:** accretion v1 = *sharing*, not *compiling*. A proven function moves
from the agent's `fork` into the shared `ctx` — still SCI, still interruptible
at every `fn` body entrance, and
visible to every agent and no longer replayed per fork. Compilation to bytecode
is a separate, later gate. Reject the corpus-native variant of this (see §3): it
keys every agent's cache on the base's identity, so each promotion invalidates
every agent, and it locks the author out of their own function via the read-only
stamp with no demotion path.

The right containment argument for a future compile tier is **not** a purity
proof — it is `jit.cljs`'s: compile the **analyzed** node graph with resolved
callee **values** placed in a constants array
(`reference-code/sci/src/sci/impl/jit.cljs:372-419`), so no agent symbol ever
appears in generated code, and keep the `:interrupt-fn` call
(`if(INT!==null)INT();`, `jit.cljs:769-777`).

### Can we get a JIT for long computations?

**Not soon, and three cheaper things come first.**

- **There is no AST to compile on the JVM.** On `:clj`, `->Node` expands to a
  bare `reify` that **discards** its `ast` argument at macroexpansion, and
  `attach-ast` is the identity
  (`reference-code/sci/src/sci/impl/types.cljc:181-191, 195-200`). `NodeR`,
  `jit-enabled` and `js-eval-available` are CLJS-only. There is no `jit.clj`.
  A JVM JIT is not a port — it is upstream surgery in the pinned
  `reference-code/sci` fork, changing sci's hottest JVM node representation plus
  ~40 `attach-ast` sites, then an emitter, then class-lifetime management.
- **Measured ceiling, so you can price it:** a sealed-compile prototype ran
  fib(30) at **34.7 ms** with the production `:interrupt-fn`, **70.9 ms**
  interpreted, and **7.0 ms** without interruption. Today the interruption
  call costs **4×** the compiled body. Fix that call first; a compile tier is
  then worth ~9×, not 20×.
- **Do not port the hardest part.** `jit.cljs`'s `s`-register machinery
  (`jit.cljs:228-265, 539-577`) exists because JS has cheap mutable locals and
  expensive nested `try`/`catch`. Clojure is the reverse. Emit a real
  `try`/`catch` per sited call instead. The var-epoch deref cache does not port
  either (`vars.cljc:48-69` is CLJS-only; a JVM var deref is already a volatile
  read).

**Honest ordering by measured leverage:** `:interrupt-fn` closure fix → AppCDS
(9 s/boot) → accretion-as-sharing → *then* revisit a compile tier when a real workload demands
it.

## 3. The position

**Winner: "The claim-native cluster JVM" (claim-native) — one process, one virtual thread
per run, resume derived from committed facts — with its pure-eval step contract
REMOVED, plus grafts from both runners-up.**

### What survives from claim-native

- One process owning the Datahike connection and every running agent.
- One virtual thread per claimed run, **end to end including the SCI eval**.
- Delete the stored `:seon.agent.turn/phase` cursor; derive the resume point from
  committed facts.
- Keep `seon.agent.run.core` (189 lines of correct pure CAS algebra) **verbatim**;
  replace `last-beat-at` + a config read with an absolute
  `:seon.agent.run/lease-until`.
- The `:interrupt-fn` fix and `:compute` execution.

### What is grafted in

- **From in-db:** durable `:seon.effect` pre/post receipts bracketing each
  irreversible external call — but as **two small independent transactions**, not
  bound to a segment commit that can fail. Restricted to
  `:seon.capability/effect :external`, **not** `not= :pure` (see below).
- **From corpus-native:** `:seon.eval/ordinal`, `:seon.eval/source-fingerprint`
  and `:seon.eval/claim-epoch` on the receipt, and `resume-plan` as a **pure
  function** over receipts — but **not** the `seon.image` cache, and **not**
  content addressing.
- **From SpacetimeDB:** applied-vs-durable gating, and globals-are-UB as doctrine.

### What the critiques killed, and which critique killed it

**in-db / SEGMENT — dead. Five independent fatal flaws, two reproduced live by
the critic.**

1. *Speculative entity ids escape into agent code.* `my.kb/remember` returns
   `(get tempids "finding")` (`src/my/kb.cljc:197-199`) — the raw eid from the
   `d/with` report. At commit the tempid re-resolves against an advanced head:
   reproduced as speculative eid 3, real eid 5, and `[:db/add 3 :foo/bar 42]`
   committed onto **another agent's entity**, with no error. Snapshot atomicity
   cannot be retrofitted over an API that hands out eids and reports.
2. *`:db.fn/call` executes twice* — once during `d/with`, once at commit.
   Reproduced: `gen-1/run#1` speculatively, `gen-2/run#2` committed. This
   destroys the design's own answer for `seon.db.id/allocate!`.
3. *SCI has no continuations,* so "suspend at an `:external` terminal and resume"
   is unimplementable. `sci.interrupt/interrupt!` throws and unwinds; there is no
   resume. `rg -l 'continuation|call-cc|resume' reference-code/sci/src/` returns
   only the CLJS `await` macro.
4. *`::db/expected-db` and `cas-assert` break inside a segment* — a speculative
   `:t` never existed in the store. `my.plan` pins `expected-db` on eleven write
   paths (`src/my/plan.cljc:830-834` + call sites), so this is deterministic
   breakage, not a race.
5. *Deleting the pre-commit `:running` receipt* removes the only admission
   evidence, creating an undetectable poison-pill crash loop — worse now that the
   writer shares the process.

There is also a polarity bug worth remembering: `canonical-terminal` defaults an
unannotated symbol to `:external` (`src/seon/program/edge.cljc:148`). That is the
correct fail-safe for *placement* and the wrong polarity for a *segment boundary*.

**corpus-native / seon.image — dead as a mechanism.**

1. *`acquire!` is `ensure-context!` renamed.* The existing owner already does
   fork + replay + cache over one atom (`src/seon/host.clj:138-159`;
   `src/seon/agent/driver/host.clj:136-152`), and the design never names it — so
   the second cache lands without the first being deleted, which is exactly the
   `foo-v2` pattern AGENTS.md forbids.
2. *The cache cannot hit on restart* — it holds **live SCI contexts** in a
   process-local LRU. Content addressing changes nothing about a fresh JVM.
3. *Sharing a materialization is a containment disaster.* `fork-context` binds
   **one** historical `guard/holder` per context
   (`src/seon/host/context.clj:1423-1430`). Two agents on one `ctx` share the
   diagnostic counter and interrupt cell, so agent B's reset changes A's state
   and B's `finally`
   (`install-interrupted! holder nil`, `src/seon/host/guard.cljc:250-252`) clears
   A's deadline predicate. A becomes unbudgeted and uncancellable.
4. *Storing `:seon.fn/pure?` at tee time is unsound* — purity is a fixpoint over
   the transitive call graph (`src/seon/program/plan.cljc:402-440`), so defining
   `b` after `a` calls it leaves `a`'s stored purity stale forever, and that stale
   fact then feeds the accretion gate.
5. *Accretion-as-promotion is a thundering-herd invalidation* — the child key
   hashes the parent key, so one promotion invalidates every agent's layer. And
   `sci/fork` snapshots the namespace map, so a promoted var is simply **absent**
   from already-forked agents: "publishes to every other agent" is false.

**claim-native — one part dies: the pure step-value contract.**

- *Value-returning capabilities cannot become descriptions.* `shell/run` returns
  `{::ok? ::exit ::out ::err}` (`src/seon/agent/shell.cljs:222-253` — that shape
  is in the agent-facing docstring), `web/fetch` returns the body, and 26
  capability vars are annotated `:read`. An agent writing
  `(let [{:keys [exit]} (shell/run ...)] (if (zero? exit) ...))` would receive a
  description with no `exit`, take the wrong branch, and commit a wrong fact with
  no error. With no continuation to resume the eval, the only alternatives are one
  effect per LLM round trip (catastrophic) or admitting synchronous external reads
  (which voids the purity claim).
- *`db/transact!` returning tx-data silently breaks a documented invariant.* The
  corpus explicitly teaches `ALWAYS read it: an eval can succeed yet
  :seon.db/ok? false` (`src/my/kb.cljc:119`, and
  `src/my/canvas.cljc:206`). Returning tx-data makes `::db/ok?` nil — falsey —
  so every agent function that checks it silently takes the wrong branch.
- *`:seon.agent.turn/form-count` is not knowable before the batch.*
  `preflight/repair-read-entry` splices N entries in place of one **mid-loop**
  (`src/seon/host/eval.clj:383-388`) and rewrites the source
  (`eval.clj:424-428`; terminal re-asserts the repaired text at
  `src/seon/host/record.clj:384`). A crash message computed from the blob's
  re-parse would name the wrong forms with false precision.

**Replacement, which is strictly better than form-count:** commit the *remaining
entry vector* (or its hash) alongside each `:running` receipt. The survivor then
reads the exact remaining sources off the last receipt instead of re-deriving them
from a blob whose parse is not the parse that ran.

### The disagreement I resolve against the design authors

Both the in-db and claim-native designs assume the eval must become pure to be
recoverable. **It does not.** Recoverability comes from committing position and
intent *before* the act, which the codebase already half-does
(`src/seon/host/eval.clj:396-412`: "a form whose receipt cannot commit never
runs"). Purity was a means to atomicity, atomicity is unreachable (§5), and the
means costs the entire agent-facing capability surface. Drop it.

## 4. Deletion ledger

Honest accounting. `[BLOCKED]` marks items the input designs listed as deletions
that **cannot** be deleted as claimed.

| What | ~Lines | Replaced by |
|---|---|---|
| Pod agent loop: `agent.cljs` 1343, `run.cljs` 1167, `turn.cljs` 787, `loop.cljs` 702, `driver/pod.cljs` 51, `host/session/leaf.cljs` | ~4,400 | `seon.agent.driver` + eval on one JVM. Takes the 8 `setTimeout`/`setInterval` sites, `with-agent-repl` + 4 call sites, `js/Promise.all` render fan-out, and the 30 s ticker with it. |
| Phase-handoff protocol: `driver.cljc` release/reclaim/eligibility/`run-selector`, `loop/core.cljc` pod-phases/host-phases, the stored phase cursor, the unreachable `:published → :close-turn` arm | ~900 | A plain `loop` + `resume-point` derived from receipts. |
| **Dead superseded loop** (verified zero `src`/`test` callers by ripgrep): `loop/transitions`+`transition`, `renew!`, `beat!`, `close-overdue-runs!`, `close-stale-runs!`, `stale-run-ids`, `turn-limit-reached?`, `deadline-passed?`, `activity-log`, `open-turn!`/`close-turn!` | ~600 | Nothing. Their tests pin the dead path and go in the same commit. `src/seon/agent/AGENTS.md` still documents `transitions` as the loop's core — fix in the same commit. |
| `src/seon/runtime/recovery.cljs` (533) + entity schema + dead `recovery-result!` (`src/seon/client.cljs:526-530`) | ~560 | Leases + derived resume-point. `recover!` has **no production caller** (only `test/seon/runtime/recovery_test.cljs`); boot explicitly declines it (`client.cljs:2338-2340`). Also removes the `:seon.runtime.recovery/_eval` reverse-ref from the hot render path in `ctx.cljc`. |
| `src/seon/db/transport/uds.cljs` 999, `src/seon/db/session.cljs` 770, `src/seon/db/fiber.cljs` 69 | ~1,838 | Dies with the pod. `binding` on a virtual thread replaces the three `AsyncLocalStorage` instances; `seon.host.context/database-context` already implements the leaf correctly. |
| `src/seon/db/executor.clj` | 870 | Reads are pure functions over an immutable value in-process; writes are already serialized by Datahike. Keep Datahike's own `:datahike.resource/max-work` / `max-results` / `max-result-weight` — **thread them into the in-process leaf**, or unbounded queries become a new hole. |
| Durable idempotency recovery in `writer.clj` (`committed-transaction`, `transaction-datoms`, `recovered-temporary-ids`, `recovered-generated-entity-ids`) | ~790 | Nothing on the in-process path — a call either returns or throws. Also stops writing extra receipt datoms on *every* transaction. Keep only if a remote writer backend is adopted. |
| Deadline machinery: 2-thread `ScheduledExecutorService`, `interrupt-lock`, tri-state `worker-phase` atom, `interrupt-fired?`, `arm-deadline!`/`disarm!`, `cancel-active!`'s 2 s walk-away (`src/seon/host.clj:334`, `src/seon/host/invoke.clj:30-44, 263-284`) | ~250 | One `time-limit` observed by `:interrupt-fn`. **Caveat:** blocking host calls require their own observable completion or external-call backstop because `:interrupt-fn` cannot wake a parked socket read. |
| Fixed 10-thread platform eval pool (`src/seon/host.clj:332`) + virtual→platform handoff + per-invocation `driver-session` (7 atoms + monitor, `driver/host.clj:154-170`) | ~190 | The one `:compute` executor with capacity derived from measured allocation and workload; the run's virtual thread parks for its result. |
| Reply parsed twice + prompt round-tripped through a blob and re-split on `ai.core/system-boundary` (`turn/llm.cljc:162-170`) | ~150 | Values stay on one thread. Blob capture survives as observability. |
| In-eval lifecycle transactions (`lifecycle/wait`, `complete`, `pause`, `terminate`) | ~250 | A returned disposition the driver interprets. This is the "leaf-bound lifecycle calls" residue the standing goal already names. |
| `pure-block?` regex portability classifier (`context.clj:1060-1065`) + `load-host-toolkit-bindings!` fixed-point retry loop + `(def await identity)` shim | ~90 | `plan-execution`'s computed placement; toolkit source that no longer carries CLJS `await`. |
| `src/seon/db/registry.clj` refcounting/lifecycle half | ~1,600 of 2,109 | `datahike.connections` (127 lines), which `registry.clj`'s own docstring concedes is the reference-count authority. Name→config stays as a ~50-line pure fn. |
| Selective committed-report interests (`writer.clj:2735-4671`) | ~1,400 of 1,936 | `datahike.committed-report` (350 lines, commit-ordered, bounded, explicit `:gapped`) + the attribute-indexed interest **filter** as a pure projection. |
| **[BLOCKED]** `src/seon/db/transport/uds.cljc` 1703, `src/seon/db/host.clj` 945, protocol envelope ~1,000 | ~3,650 | **Cannot delete while the web-render JVM exists.** `src/seon/web/server.clj:10,12` requires `seon.db.host` and `seon.db.transport.uds`; `:89-167` opens a UDS session for its replica reads; `:269-273` builds a `db.host/writer-session`. Deleting these breaks the web UI at namespace load. Either fold web-render into the cluster JVM heap (worsening the OOM blast radius, §5) or keep one socket endpoint. |
| **[BLOCKED]** `src/seon/db/id.cljc` propose/classify/allocation rounds | ~1,200 claimed | A JVM local-connection allocation path **already exists** (`allocate-jvm-attempt!`, `id.cljc:1478-1499`), so the wire is not what blocks this. Worse, `my.plan/reconcile!` and `publish-generated-program!` compute their **return value** from `::db.id/ids` off the write result (`plan.cljc:1636-1665, 1279-1318`), so removing the round trip is an agent-facing breaking change, not a deletion. |
| **[BLOCKED]** Datahike value/report wire projection (`writer.clj:185-887`) ~700 | — | Same blocker as the wire itself. |

**Honest total:** roughly **12,900 deletable lines** in the unblocked rows,
against a measured base of 49,085 lines
(`src/seon/agent` 23,353 + `src/seon/host` 5,270 + `src/seon/db` 19,095 +
`host.clj`/`db.cljc` 1,367) — about **26%**. Of that, ~5,000 (pod loop, pod
transports, dead loop, recovery) is already owed to the standing great-deletion
goal and is not attributable to this design. **The novel contribution is roughly
7,900 lines**, and it is real.

Additions: ~120 lines of `seon.effect`, five receipt/run attributes, one enum
value. No new namespaces beyond `seon.effect` and `seon.db.local`.

## 5. What cannot be achieved — physics, not effort

1. **No hard in-process kill.** `Thread.stop` is gone on modern JDKs.
   Cancellation is cooperative through `:interrupt-fn` at every `fn` body
   entrance. This is the state
   of the art off WASM, and SpacetimeDB's own V8 memory guard says so out loud:
   *"V8 does not terminate execution immediately when requested so this mechanism
   is unfortunately not fool-proof"* (`crates/core/src/host/v8/mod.rs:1952-1969`).
2. **No memory bound.** Measured: OOM after **4,068 `fn` body entrances**.
   `time-limit` cannot bound allocation because allocation per call is
   unbounded. **This is the one genuine regression from
   co-locating the writer**, and it must be accepted explicitly: an agent OOM
   restarts the whole cluster. Committed data survives (Datahike's commit guard
   leaves the head naming the previous snapshot,
   `writing.cljc:423-449`); what is lost is every other agent's in-flight work
   and the in-memory transaction queues (`writer.cljc:88-91` — there is no
   durable pending transaction).
3. **No suspend/resume of an SCI evaluation.** SCI has no continuations; the only
   escape is an uncatchable throw. Any design requiring "stop mid-form, do
   something, continue" is unimplementable without a CPS interpreter tier.
4. **No retrofitted transaction atomicity for an eval.** Speculative eid
   resolution is a pure function of the db it runs against
   (`transaction.cljc:56, 1287`), so any eid handed to agent code mid-eval is a
   prediction that is wrong whenever anyone else commits first. Snapshot
   atomicity cannot be added underneath an API that returns transaction reports.
5. **No exactly-once external effects.** A crash between the `:requested` commit
   and the effect completing is genuinely ambiguous. Content-addressed blob and
   idempotent fetch are fine; `shell/run` is not. The honest ceiling is *"this may
   or may not have happened, here is what it was"* — which is still infinitely
   better than today, where nothing is recorded at all.
6. **Write throughput is one core.** Datahike's processing go-loop threads
   `db-before → db-after` one transaction at a time
   (`writer.cljc:100-188`). No thread strategy changes it. Virtual threads buy
   concurrency in *waiting* and in *interpretation*, not in transacting.
7. **One writer process per store.** `create-writer` ships only `:self`
   (`writer.cljc:286`) and `datahike.connections/*connections*` is a process-local
   atom (`connections.cljc:3`); konserve's only file locks are per-blob
   (`filestore.clj:223, 369, 431`). Two `:self` writers on one store diverge
   silently. **Corollary: co-location forecloses horizontal cluster JVM scaling**,
   which contradicts `docs/seon/architecture/architecture.md:242-246` ("more
   capacity means more interchangeable cluster JVM") and needs an owner ruling.
8. **Snapshot isolation, not serializability.** Two agents can each read the same
   fact, decide differently, and both commit. SpacetimeDB avoids this only by
   holding a database-global write lock across the whole reducer — which for Seon
   would serialize the cluster behind one LLM call. The escape hatch exists and
   must be taught: `seon.db/cas-assert` (`src/seon/db.cljc:383`) emits a per-datom
   `:db.fn/cas`. Note the measured trap: full-head `::db/expected-db` pinning
   degrades badly under concurrency (measured 19.9 ms/commit at 1 thread → 72.1
   ms/commit and 3M rejected attempts at 64 threads, all `:transaction/stale-basis`
   with no logical conflict), and `my.plan` uses it on eleven paths with **no
   retry**. Per-datom CAS is the fix.
9. **A compiled function has no `:interrupt-fn`.** Native compilation removes
   the only containment primitive. This is why accretion v1 must be sharing, not
   compiling.
10. **Content addressing cannot survive process death for live objects.** A cache
    of live SCI contexts is empty in a fresh JVM regardless of key scheme.

## 6. First moves, ordered — each independently valuable

**1. Fix the `:interrupt-fn` closure.** In the current
`src/seon/host/guard.cljc`, build `::check!` as a closure over the `time-limit`
state and diagnostic counter inside `holder` (`:49-55`) instead of calling
`check-holder!` (`:194-205`), which re-destructures the holder map on every
`fn` body entrance while the arrays it needs are allocated once and never
change identity. Split a `:resource` population out of
`:seon.error/fault` so a budget/deadline/output trip stops being reported as an
agent coding mistake (`policy-error!` hardcodes `:agent` at `guard.cljc:149`; the
message text at `:102-120` is already right).

*Independently valuable:* ~10 lines, no policy change, ships alone, and it
reduces the cost of SCI on `:compute`.

*Honest caveat on the number:* the input measurements **disagree** — 44×, 8.6×
and 2.4× on what those reports historically called the safepoint alone,
depending on whether the platform interrupt predicate was installed and
whether the microbenchmark body was eliminated. The
only in-situ figure (3M real SCI iterations through a real fork) is 71.2 ms →
26.8 ms. **Measure it in situ before quoting a number**, and do not delete any
clock on the strength of a tight-loop benchmark.

**2. Build the AppCDS/AOT archive.** `bin/seon status` already reports the
identity mismatch with all four expected digests `nil`. Requiring the JVM
namespace closure is 9,232 ms against 80 ms to build the base it enables. Wholly
independent lane; worth ~9 seconds on every restart under the standing velocity
ruling.

**3. Delete the verified-dead code and fix the verified-broken arm.** The dead
loop set (zero `src`/`test` callers, confirmed by ripgrep), `seon.runtime.recovery`
(no production caller), the unreachable `:published → :close-turn` arm, and the
crash-resume call at `src/seon/agent/driver/host.clj:700`, which passes **5
arguments** — with `storage-view` in the `run` position — to a **7-argument**
`run-eval-batch!` (`:476-478`). That arm is the one branch implementing
mid-eval crash resume, and it has provably never executed. Fix it by **deleting
the arm**: a turn with zero receipts falls through to the ordinary eval path.
Correct `src/seon/agent/AGENTS.md`, which still documents the dead FSM table as
the loop's core.

**4. Make the crash boundary answerable.** Add `:seon.eval/ordinal`,
`:seon.eval/source-fingerprint`, `:seon.eval/claim-epoch`, and
`:seon.eval/remaining-edn` (the not-yet-run entry vector) to the `:running`
receipt in `src/seon/eval/receipt.cljc`. Add `:seon.agent.run/lease-until` and
renew it on the transaction each phase step already commits — the current
heartbeat is written **only** inside a claim transition and never renewed during
a drive, so with `stale-ms` at 1,200,000 (`config/system.edn:619`) any run driving
longer than 20 minutes is stealable from a healthy cluster JVM. That directly
contradicts the stated goal of long computations. Write `resume-plan` as a pure
function and test it by killing the cluster JVM at each boundary.

**5. Add `seon.effect` receipts for `:external` capabilities only.** The
annotations already exist on the JVM host — `fs/write-file`, `edit-file`,
`replace!`, `insert!`, `shell/run`, `run-bg!`, `job-stop!`, `web/fetch`,
`web/search` are all `::effect :external`
(`src/seon/host/context.clj:745-812`). Two small independent transactions per
call: `:requested` before, `:done`/`:error` after. **Do not** use `not= :pure` —
`db/query`, `db/pull`, `blob/get`, `fs/read-file` are all `:read`, so that
predicate would put two extra transactions around every database read (~2.4 s of
fsync on a form that pulls 50 entities), and `db/transact!` is `:idempotent`,
which would make every write three transactions and regress infinitely.

**6. Add `seon.db.local` as the cluster JVM's in-process leaf.** Return the same
`seon.db.leaf` key map the portable core already consumes
(`src/seon/db.cljc:207-219` — the seam already exists), calling `datahike.api`
directly. **Keep the writer process and the socket** at this step, for
web-render. Thread `:datahike.resource/max-work` / `max-results` /
`max-result-weight` into the leaf so deleting the executor does not open an
unbounded-read hole. Prove it by running one agent turn and confirming zero
sockets opened from the eval path.

*Note the seam this will expose:* `bind-leaf` **binds** rather than reads ambient
(`src/seon/db.cljc:207-219`), and `my.blob`, `seon.agent.web`,
`seon.agent.message`, `seon.agent.lifecycle` and `seon.db.id` captured
`transact!`/`query` closures at registration
(`src/seon/host/context.clj:778-812`). Those capture sites must be re-plumbed to
resolve the leaf per call. Land it as a seam fix, not as a blocker.

**7. Collapse the turn into the cluster JVM.** This is the real gate on deleting the
pod, and it is a **port, not a deletion**: ~1,273 CLJS-only lines of prompt
rendering (`src/seon/agent/ctx/driver.cljs` 604, `ctx/typeahead_steps.cljs` 540,
`ctx/admin.cljs` 129) plus `my.plan/publish-generated-program!` must move to the
JVM, and `src/seon/host/invoke.clj:167-172` currently **refuses** render on the
host by explicit rule. Budget this honestly; everything under `ctx/` other than
those three files is already `.cljc`.

**8. Only then decide the writer merge.** It is worth ~3,650 more lines and every
remaining wire round-trip, and it costs the OOM blast radius (§5.2) and
horizontal cluster JVM scaling (§5.7). Both require an owner ruling and an
architecture.md change. Do not bundle it with move 6.

### Deliberately not doing

- A JVM JIT (§2, last question) — until a measured workload demands it.
- Accumulate-then-commit-once / segments — killed in §3.
- A second materialization cache — `ensure-context!` is the owner.
- Enabling `effective-tier` beyond the retired literal `:nursery` — until the
  missing-`:interrupt-fn` question in compiled code has an explicit answer.

## Corrections to the input material

Two claims in the audits/critiques are wrong, and both are load-bearing.

**A. "Idiomatic Clojure calls `:interrupt-fn` ZERO times."** False for Seon's
actual `ctx`. `sci.interrupt/clojure-core` and `clojure-string` **are** merged at
`src/seon/host/context.clj:1405-1406`, and `sci-range` produces an
interrupt-aware lazy seq, so *any* consumer of it is metered. Measured on JDK
26.0.1 with exactly that configuration:

| Form | `fn` body entrances | ms |
|---|---|---|
| `(loop [i 0] (if (< i 1000000) (recur (inc i)) i))` | 1,000,001 | 27 |
| `(reduce + (map inc (range 5000000)))` | 9,999,999 | 279 |
| `(count (filter even? (range 5000000)))` | 7,500,000 | 212 |
| `(sort (vec (range 3000000)))` | 3,000,000 | 118 |
| `(frequencies (vec (range 2000000)))` | 2,000,000 | 387 |
| `(group-by even? (vec (range 2000000)))` | 2,000,000 | 106 |
| `(clojure.string/join "," (vec (range 1000000)))` | 1,000,000 | 51 |

The critique measured a bare `sci/init` without the overrides. **The real hole is
narrower and different:** a single host call over an *already materialized*
structure is a window with no `fn` body entrance (the `sort` above contributed
0 of its 3,000,000 — they all came from `range`), and its duration is bounded
by the size of data whose construction already invoked `:interrupt-fn`. The
genuinely unbounded holes
are blocking capability calls (which have their own leaf deadlines — e.g.
`my.shell`'s `timeout-ms` + `destroyForcibly`, `shell/leaf.clj:95-119`) and heap.

**B. "fs/shell/web carry no `:seon.capability/effect` annotation."** False on the
JVM host: `src/seon/host/context.clj:745-812` annotates all of them, which is why
first move 5 is cheap.

Reproduce A with:

```
clojure -M:writer:host -e '(require (quote [sci.core :as sci]) (quote [sci.interrupt :as interrupt]))
(let [n (atom 0) ctx (sci/init {:namespaces {(quote clojure.core) interrupt/clojure-core}
                                :interrupt-fn #(swap! n inc)})]
  (sci/eval-string* ctx "(reduce + (map inc (range 5000000)))") (println @n))'

```

The OOM probe (`(mapv (fn [_] (byte-array 1000000)) (range 100000))` under
`-J-Xmx2g`) historically reported `safepoints=4068 out="Java heap space"`.

## Open questions for the owner

1. **Does an agent OOM restarting the cluster cost more than 3,650 lines and
   every wire round-trip is worth?** (§5.2, move 8.) There is no third option
   while Datahike ships only `:self`.
2. **Is horizontal cluster JVM scaling a real near-term requirement?**
   `architecture.md:242-246` says yes; co-location says no. One of them must
   change.
3. **Should the web-render JVM fold into the cluster JVM heap, or keep one socket
   endpoint?** This decides ~3,650 lines and the OOM blast radius together.
4. **Accretion-as-sharing now, compilation later — agreed?** It converts a
   disabled subsystem into an immediate caching win with zero containment change.
