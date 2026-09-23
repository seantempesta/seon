---
type: research
status: proposal
created: 2026-09-23
lane: hangs-research (Opus 5.5, read-only)
tags: [research, bounded-execution, flow, datahike, writer, stop, hangs]
---

# Why the hangs happen, and the smallest design that ends them

The owner asked: "why are all these hangs happening? it should crash if it can't reach
whatever it's waiting for right?" Then: find solutions "that don't involve a shitload of
fragile code".

## 0. The answer in one paragraph

Most of today's "hangs" were not waits on something that could never arrive. They were
waits on something that **was arriving, very slowly**, because a **serializer** — the one
Datahike writer, or a `locking` monitor — was doing work proportional to the **whole
program** for every request queued behind it. Two separate defects combine:

1. **Some waits have no deadline.** Seven `<!!`, three `>!!`, one `deref` on every
   system write, and every `locking` monitor wait forever. So when the thing behind them
   is slow, the caller sits silently instead of failing and naming what never arrived.
2. **Serializers do program-proportional work.** A deadline alone would turn each of these
   into a loud failure, and that is required. But a failed wait does not stop the work.
   The writer keeps grinding, and everyone queued behind it still waits.

So the fix has two halves, and both are small. **One wait primitive, used everywhere, with
a lint that forbids the bare forms.** And **no program-proportional work on a serializer**:
the writer and the monitors run only bounded work, and stop becomes a join because Flow
already holds the proc's `Future`.

Line numbers below are for the working tree at HEAD `f3d2adbbe` on 2026-09-23. Other
lanes had uncommitted edits in `db.clj`, `sci/eval.clj`, `cluster/agent.clj` and
`test.clj` at the time, so re-verify a line before editing it. Dependency pins:
core.async `dc35f3e`, Datahike `684d3290` (it moved from `0c01b5fe` during this
research; every Datahike line cited was re-checked at `684d3290`), SCI `fcbd8862`,
clj-kondo `57252e07`, Clojure `b18d3adc`, OpenJDK 26.0.1.

## 1. The observed hangs: verified mechanism and class

Classes used throughout:

- **J — join without a deadline.** The wait has no bound, so a missing or late event
  hangs forever.
- **S — serializer doing program-proportional work.** Writer or monitor. Everything
  queued behind it waits.
- **C — CPU-bound work that ignores the interrupt.** `Thread.interrupt` is a request that
  a thread observes only at a blocking call.

| # | hang | verified mechanism (file:line) | class |
|---|---|---|---|
| 1 | Stop hung 30 s on an in-flight turn | `flow/stop` only queues `::flow/stop` and closes the error and report channels (`core.async/.../flow/impl.clj:174-183`). The proc runs its stop transition only after the active transform returns (`impl.clj:283-321`). **Measured:** `flow/stop` returned in **0.10 ms** while the transform was still running, and the proc's stop transition ran **304.7 ms** later, when the transform was released (probe P2). The provider wait was fixed in `ad63964fb` (interrupt, `agent.clj:1034-1048`). Joins that remain unbounded: `cluster.clj:3495` `>!!` armer, `:3502` `<!!` quiesced, `:3521` `<!!` cluster-loop completion, `:3525` `<!!` render completion, and `flow.clj:1305,1309` `stop-error-fanout!`. | J |
| 2 | Warm restart hung 302 s in `arm!` | `main` BLOCKED on the `routing` atom's monitor (`agent.clj:958`, then at `:876`). The holder was the armer proc on a **virtual thread**: Flow's `:io` executor is `Thread/startVirtualThread` (`core.async/.../impl/dispatch.clj:81-89`), which is why `jstack` did not show it. The holder ran `acquire-context!` under the monitor. That recorded each acquisition refusal as its own `commit-fault!` transaction, and each one re-derived the whole projection inside the writer (`error.clj:1397` `carried-projection` in `commit-call`). **Measured:** a monitor waiter stays `BLOCKED` after `Thread.interrupt` and acquires only when the holder releases (probe P1). | S (+ J: the monitor has no bound and cannot be interrupted) |
| 3 | `record-acquisition-refusals!` 7–36 s in the writer | Same writer derivation as #2. **Since mitigated in three commits:** `5f2aa93f4` records all refusals in one transaction (`sci/eval.clj:1801-1808`); `55ddec16c`, `0c01b5fe`/`cc1aa4f00` and `1625fb9bc` memoize the projection by what it reads (`db.clj:1393-1452`), and speculative values now carry revision context (`datahike/db.cljc:444`). **Measured now:** warm `carried-projection` on `default` takes **0.29 ms** (probe P4). Nothing yet asserts that in-writer cost is independent of program size. | S |
| 4 | A test request releasing thousands of members in ONE transaction wedged the writer for minutes | The transaction grows with the request, and the writer is serial (`datahike/writer.cljc:118-160`: one go loop, `(apply op-fn old args)` at `:140`). Datahike's queue holds 120,000 entries (`writer.cljc:78`), has no per-transaction cost bound, and cannot cancel an accepted transaction. **Uncommitted in the working tree:** `test.clj:1563` `batch-limit` 64 per admission and release. | S |
| 5 | A turn spends 5–11 s before its provider call; stopping two agents took 14.9 s | The prelude re-derived the projection from rows at each read (`db.clj` `carried-projection` → `schema/load-projection`), as the issue's thread dumps show. The work never blocks, so the stop's interrupt is not seen (`agent.clj:1034-1048`) until the next blocking call. `ac7b61eb4` lets `InterruptedException` through `phase`. The memo commits above remove most of the derivation. The live profile still shows `seon.turn/open-turn` max **13,400 ms**, which is inclusive (below). | C (fed by S) |
| — | Final-report validation wedges a publication (issue `final-report-validation-runs-unbounded-on-the-writer-thread`) | `write-report-validator` (`db.clj:4095`) is installed on every write through `:datahike/validate-report` (`db.clj:~4313`; Datahike `db/transaction.cljc:1224-1295`). It pulls and Malli-validates every affected entity on the writer thread, so the cost is proportional to transaction size × entity size. | S |
| — | Interrupted fixture leaks the roster permit (issue `an-interrupted-fixture-leaks-...`) | `gc_guard/acquire-reachability-permit!` blocks in `<!!` (`datahike/gc_guard.cljc:203`). `seon.test/run` cancels with an interrupt (`Future.cancel(true)`). Interrupting host code that holds a permit is unsafe. | J + the interrupt used as termination |
| — | 30 s write bound fails publication under load | System writes carry **no bound** (`db.clj:4329` `(deref pending)`; ruling 1r comment at `:4306-4311`). Agent writes carry the dial. The interim raised the dial to 600 s. | J + S |

**Live evidence** (MCP `runtime_status`, `default` pid 24492, 2026-09-23T01:4xZ):

- `seon.boot/ready-ms` was **107,102**, against the ten-second law.
- The armed profile's `seon.schema/call-with-projection-state` max was **636,991 ms**,
  x311, 68 threw. This is inclusive: it wraps a whole request, so it is an upper bound
  on something nested inside it, not a single step.
- 65 calls took over one second.

This lane only observed those numbers. Attributing them belongs to the profile owner.

## 2. Inventory of blocking waits in `src/` (scripted)

The inventory is `rg` over `src/**/*.clj*` and took 189 ms. The exact commands are in
§7. Counts are occurrences.

| class | form | count | unbounded | sites (unbounded ones in **bold**) |
|---|---|---|---|---|
| J1 completion join | `<!!` | 7 | 7 | **`cluster.clj:3502,3521,3525`**, **`flow.clj:1305,1309`**. `flow.clj:1176,1287` are consumer loops that end when the channel closes; they are correct as lifetime readers, but only as bounded as their source's closing. |
| J1 | `>!!` | 3 | 1 real | **`cluster.clj:3495`** (armer input). `agent.clj:980` puts onto a fresh `(chan 1)` and never blocks. `flow.clj:1288` puts onto the counted-dropping fault channel and never blocks. |
| J1 | `alts!!` | 5 | 0 | All carry a timeout port: `await.clj:91`, `eval/drive.clj:72`, `turn.clj:5275`, `turn.clj:5438`, `agent.clj:1117` (backstop always in `ports`). |
| J2 promise/future deref | `(deref x)` / `@` | 4 | 1 real | **`db.clj:4329`** (every non-agent write; 65 `transact!` call sites funnel here). `artifact.clj:109` `@(promise)` is the main thread's intentional lifetime park. `sci/kernel.clj:644` derefs a `reduced` and `instrument.clj:92` derefs a Var; neither blocks. |
| J2 | bounded `deref` / `.get` / `.join` | 9 | 0 | `flow.clj:882`, `db.clj:4328`, `test/cache.clj:40,191`, `await.clj:140`, `shell/jvm.clj:290,312`, `cluster/process.clj:156`, `test.clj:183`. `test.clj:179` joins without a bound by design: it is the exit watcher (§4, "timeout is not termination"). |
| J3 monitor | `locking` | 21 | 21 (monitors cannot be timed or interrupted) | **Held across seconds of work:** `cluster/agent.clj:825` (contexts: acquisition), **`:958`** (arm: acquisition), **`:1199`** (disarm: turn-completion await), `cluster/store.clj:549` (branch open, I/O), `cluster.clj:917,947` (store open/close), `schema/edn.clj:376,401` (packaged-population parse). **O(1) critical sections:** `profile.clj:136,187`, `store.clj:315,344`, `boot.clj:299,366,469`, `fs/jvm.clj:677`, `agent.clj:522,526,1044`, `test/runner.clj:877`, `cluster.clj:530` (prepl print). |
| J4 sleep | `Thread/sleep` | 2 | 0 | `turn.clj:4610` (finite backoff schedule), `render/web.clj:2735` (coalesce floor). Both are interruptible. |
| S writer work | `:db.fn/call` | 78 | cost unbounded | The transaction function runs inside the writer's loop (`datahike/writer.cljc:140`), plus `write-report-validator` on **every** write (`db.clj:4095`). |
| S listener work | `d/listen` | 5 | cost unbounded | `wake.clj:505`, `schedule.clj:799`, `sci/eval.clj:2419`, `eval/drive.clj:64`, `render/web.clj` (docstring). Callbacks run on the unbounded `:mixed` pool after delivery (`writer.cljc:390-406`), so they do not block the commit loop, but the router queries inside its callback (`wake.clj:517-524`). |
| owner | `seon.await/await!` callers | 6 | — | `effect.clj`, `flow.clj` ×2, `render/web.clj` ×2, `shell/jvm.clj`. |

**Waits inside dependencies:**

- Flow `send-command` `>!!` onto a control channel with a buffer of 10
  (`flow/impl.clj:73`). The flow PRD's N1c owns this.
- Flow `:compute` step `.get` with a 5,000 ms timeout (`impl.clj:259`). **The timeout is
  not termination:** the FutureTask keeps running, and only the step's error is
  reported.
- Datahike `gc_guard` `<!!` at `gc_guard.cljc:203,252`.

**Totals.** 10 unbounded core.async/deref waits, of which 6 are stop joins, 1 is the
system-write deref, 1 is the armer put, and 2 are consumer loops bounded only by their
source closing. Add 8 monitors held across seconds and 13 O(1) monitors. On the cost
side there are 78 + 1 + 5 unbounded writer or listener workloads.

## 3. What the dependencies already provide (file:line)

**core.async / Flow (`dc35f3e`)**

- **Every proc loop is already a `FutureTask`.** `futurize` builds a `FutureTask` and
  `.execute`s it (`flow/impl.clj:29-36`). `spi/start` returns
  `((futurize run {:exec exs}))` (`impl.clj:323`). `create-flow`'s `start-proc` then
  **discards** that value (`impl.clj:151-167`, inside a `doseq`). Retaining it gives an
  exit handle with a timed `.get` for free. No new promise, registry or completion
  channel is needed. **This is the parent-owns-children property.**
- `stop` is a command, not a join. It closes `error-chan` and `report-chan` immediately
  (`impl.clj:174-183`), which drops errors raised by a stop transition (issue
  `flow-stop-transition-errors-are-dropped-on-a-closed-error-channel`).
- `ping` is bounded: `alts!!` against a `timeout` (`impl.clj:75-86`). A proc inside a
  long transform does not answer, because the loop reads control only between messages
  (`impl.clj:289-296`).
- `alts!!` + `timeout` is the bounded channel wait (`async.clj` `timeout`). `thread-call`
  returns a channel that closes on completion (`async.clj:509-529`). `io-thread` runs on
  a virtual thread when one is available (`async.clj:538-550`,
  `impl/dispatch.clj:81-89`). `go` blocks can be collected and never resumed when their
  channels become unreachable (`async.clj:485-503`). That is one more reason no
  lifecycle wait should rest on a `go` block.
- Go blocks and the default `:core-async-dispatch` run on the `:mixed` cached pool
  (`impl/dispatch.clj:106-111`). That is why the Datahike writer shows up as
  `async-mixed-N`.

**Datahike (`684d3290`)**

- **One writer loop per connection.** It is a `go-try` loop over a transaction queue
  (`writer.cljc:118-160`) that runs `(apply op-fn old args)` (`:140`), with a separate
  commit loop (`:219-285`). The queue holds 120,000 entries (`:78`). `-dispatch!`
  returns a `promise-chan` right away (`:46-55`). **An accepted transaction cannot be
  cancelled.** There is no per-transaction deadline or cost hook. **Measured (P3):**
  a two-datom write queued behind an 800 ms transaction function was not delivered at
  200 ms, then committed at 807 ms, after its caller's bounded wait had already
  expired. The write outcome was unknown to the caller and the write happened anyway.
- `transact!` returns a `throwable-promise` (`writer.cljc:390-406`, `tools.cljc:93-107`).
  **Its three-arity `deref` does not return `timeout-val`.** `.get cf timeout` throws
  `TimeoutException`, which is caught and rethrown as `ExceptionInfo`
  (`tools.cljc:104-107`). Measured in P3 (`threw ExceptionInfo cause TimeoutException`
  at 205 ms). `db.clj:4330-4336` already works around this. A one-line fork fix would
  honour `IBlockingDeref` and let that catch go.
- **Derivation outside the writer is exact.** `d/with` (`core.cljc:126`,
  `api/impl.cljc:134`) runs the same expansion, `:db.fn/call` included
  (`db/transaction.cljc:1171`), on a captured value. `committed-value-identity`
  (`db.cljc:385`) and `speculative-cache-context` (`db.cljc:444`) name a value and its
  per-attribute revisions. A basis guard in the writer (Seon's
  `registry/head-guard-tx`) checks that decision cheaply.
- `validate-report` hook: `db/transaction.cljc:1224-1295`. It runs in the writer after
  expansion. Its first line can refuse by size before any expensive work.
- Listeners are notified after delivery, per listener, with the error logged
  (`writer.cljc:379-406`). The flow PRD's N3 owns the failure callback.
- Reachability permits block in `<!!` (`gc_guard.cljc:203`) and are released in
  `finally` (`versioning.cljc:275`).

**SCI (`fcbd8862`)**

- `:interrupt-fn` is called on every interpreted `fn` and `loop` entry (`core.cljc:298`,
  `impl/fns.cljc:40`). `sci.interrupt/interrupt!` throws a signal that sandboxed code
  cannot catch (`interrupt.cljc:32-40`), and `sci.interrupt` supplies interruptible
  core overrides (`interrupt.cljc:1-17`).
- It is a **poll**. It bounds interpreted work, but not host calls or blocking
  realization (issue `a-blocking-realization-is-not-bounded-by-the-interrupt`).

**JVM (OpenJDK 26.0.1, `lib/src.zip`)**

- `Future.get(timeout)` and `Thread.join(Duration)` are bounded and interruptible.
  `Future.cancel(true)` is only `interrupt()`.
- **Monitors (`synchronized` / `locking`) cannot be interrupted or timed.** Measured in
  P1: the waiter was still `BLOCKED` 300 ms after its interrupt. `ReentrantLock.tryLock`
  is bounded (P1: refused at 305 ms).
- `StructuredTaskScope` is **still preview** in JDK 26:
  `@PreviewFeature(feature = STRUCTURED_CONCURRENCY)` on the interface
  (`java/util/concurrent/StructuredTaskScope.java:352`), with
  `Configuration#withTimeout` (`:248`). It needs `--enable-preview`, and its API has
  changed from one preview to the next. `ScopedValue` is final (`@since 25`).
- Virtual threads do not appear in `jstack`. `jcmd <pid> Thread.dump_to_file -format=json`
  includes them. That is the diagnostic hang #2 lacked.

**clojure.core (`b18d3adc`)**

- `promise` is bounded only through `(deref p ms v)` (`core.clj:7327-7331`).
- `deref` of a `Future` takes a timeout.

**clj-kondo (`57252e07`)**

- `:discouraged-var` (`impl/namespace.clj:714`, `doc/linters.md:673-692`) warns on a
  named var, with a message, and can be switched off per namespace through
  `:config-in-ns`.
- This is configuration, not a hook. It can forbid `clojure.core.async/<!!`, `>!!`,
  `alts!!`, `clojure.core/promise`, `clojure.core/future` and `clojure.core/locking`
  everywhere except the one owner.

**Seon already has the primitive.** `seon.await/await!` (`src/seon/await.clj:110-148`)
races a port against `timeout`, uses timed `Future.get` and bounded `deref`, and returns
an evidence-complete `:seon.error` naming the config fact and the missing event. It is
used at 6 sites. It lacks one thing, as the PRD's N2 already notes (`await.clj:136`): it
starts a fresh clock on every call instead of taking the caller's deadline.

`seon.test/run` (`test.clj:144-207`) already implements the honest "timeout is not
termination" outcome. It joins with a bound. On expiry it reports failure, keeps
custody, and a virtual-thread watcher joins the exit and only then releases
(`test.clj:175-179`).

## 4. Three options

The simplest viable constraint is first. **Option B is the recommendation.**

### Option A — One await owner, linted (the constraint)

- **Change:**
  - `await!` takes one monotonic deadline from the public operation's entry (N2's
    extension).
  - The 6 unbounded stop joins, the armer put and the system-write deref convert to it.
    That is one scripted rewrite of `(async/<!! x)` into
    `(await/await! {… :seon.await/port-operations [x] …})`, plus the handful of sites
    the rule cannot express.
  - Every system write carries its operation's deadline, per ruling 1r.
  - The 8 long-held monitors become `ReentrantLock` with `tryLock(remaining)`, called
    through `await!`.
  - `.clj-kondo/config.edn` adds `:discouraged-var` for `<!!`, `>!!`, `alts!!`,
    `promise`, `future` and `locking`, with `:config-in-ns` off only for `seon.await`
    and the artifact main.
- **Guarantee:** every wait in `src/` fails within its declared bound with a typed error
  naming the operation, the missing event and the config fact. The lint keeps it true
  for new code. Nothing hangs silently.
- **Lines:** about +90 / −60, from the inventory: 8 sites × ~6 lines, 8 lock sites ×
  ~4, await deadline +20, kondo +12. Deleted: the hand-rolled timeout loop in
  `await-turn-completion!` (`agent.clj:1102-1125`, ~25 lines) and the TimeoutException
  catch at `db.clj:4330-4336` (if the Datahike deref is fixed).
- **Mechanisms deleted:** the ad hoc `alts!!` backstop in disarm. Nothing else.
- **Composes with the flow PRD:**
  - It **equals N2**, generalized from stop joins to every wait.
  - It **equals N3's system-write bound**.
  - It **complements N1c**, which still bounds Flow's own control put.
- **Gives up:** speed, which is the whole cause. A slow writer or a monitor held across
  acquisition now fails loudly instead of hanging, but still takes as long. Because a
  timeout is not termination, a caller whose write expired still has an
  **outcome-unknown** transaction queued, and the operation fails where it used to
  succeed slowly. The timed-lock half is itself fragile code: it makes a long critical
  section fail, when the defect is the work inside it.
- **Regression:** a never-settling completion publisher makes `cluster/stop!` return
  `:seon.await/timeout-error` naming `::cluster-loop-exit` within bound + 50 ms. A clean
  lint run shows zero `:discouraged-var` hits outside `seon.await`.

### Option B — A, plus the dependency properties that remove the causes (RECOMMENDED)

Option A's wait primitive and lint, plus three changes that make the waits short, so
bounds fire only on real faults.

**B1. Stop is a join by construction** (Flow already owns the `Future`).

- A fork change in core.async Flow, upstream-compatible:
  - `start-proc` keeps what `spi/start` returns (`impl.clj:155`) in the running state.
  - `run` returns its terminal outcome (`{:pid … :outcome … :cleanup-ex …}`) as the
    future's value instead of `nil`. It catches a stop-transition throw before
    `error-chan` closes.
  - A new `exits` accessor returns `{pid Future}`.
- Seon's stop becomes one sequence under one deadline:
  `flow/stop` → `await!` over each exit `Future` (`await.clj:140` already handles
  futures).
- **Next start cannot overlap.** Custody (the routing entry) is removed only after
  every exit `Future` `.isDone`. `arm!` refuses by name while the previous entry's
  futures are live. This reads a dependency fact; it is not a new flag.

**B2. Serializers run only bounded work.**

- (i) **The armer proc is the serializer; the monitors go.** `arm!` and `disarm!` become
  messages to `:seon.agent/armer`. Direct installers inject and `await!` a reply. The
  `routing` and `contexts` monitors (`agent.clj:825,958,1199`) are deleted, as the
  issue `agent-arm-and-disarm-hold-one-monitor-across-seconds-of-work` already asks
  (audit rows 16-17). A proc answers ping and stop between messages; a monitor waiter
  cannot even be interrupted (P1).
- (ii) **Admission bounds transaction size.**
  - `seon.db/transact!` refuses an input over a declared datom/operation bound before
    dispatch.
  - The `validate-report` callback's first form refuses an expanded report over the
    same bound (`count` on `:tx-data`) before any whole-entity validation.
  - Producers batch. Test release does this already (`batch-limit` 64, working tree).
    Publication already has its own lifecycle bound.
  - This makes the writer's work proportional to a bounded transaction, never to the
    request or the program.
- (iii) **In-writer derivation is a memo hit, asserted.** This landed in `55ddec16c`,
  `0c01b5fe` and `1625fb9bc`. What is owed is the regression, below.

**B3. The honest bounded outcome when host work will not stop.** This is Option A's
expiry, completed.

1. **Record the fault** with the live thread's stack. The agent already holds its turn
   thread (`agent.clj:1044`); `Thread.getStackTrace` gives the stack.
2. **Keep custody.** The branch connection, the routing entry and any held permit stay
   held.
3. **Refuse restart of that agent** until its exit `Future` completes.
4. **Watch the exit.** One virtual-thread watcher joins that `Future` and releases on
   exit, the pattern `test.clj:175-179` already uses.
5. **A wedged writer is "db down".** If the expired wait is the writer's, the fault
   commit would queue behind the same wedged transaction. AGENTS rules this
   "database down": panic in both modes, with evidence on stderr and in the REPL. There
   is no fallback store.
6. **Never cancel host work.** No `Future.cancel(true)` as termination on host code that
   may hold a Datahike permit: that is exactly the roster-permit leak.

**Summary of Option B:**

- **Guarantee:**
  - Everything in Option A.
  - A stop is a join by construction.
  - A restart cannot overlap a live exit.
  - The writer and every surviving monitor do work bounded at admission, so a bound
    firing means a real fault, not load.
- **Lines** (estimated from the inventory):
  - Option A: about +90 / −60.
  - Fork: about +25 in `flow/impl.clj`.
  - Seon joins: about −80 by deleting the completion channels published from stop
    transitions (`:seon.agent/turn-stopped` and its closes at `agent.clj:1213-1215`,
    `:seon.turn.loop/completion`, `:seon.render.web/completion`, the fan-out
    `completion` and `committer-error-completion`, and the launcher's `proc-stopped`)
    and their stop-transition publishers (`flow.clj:527,1017`, `turn.clj:5485`,
    `render/web.clj:2701`, `agent.clj:1293`).
  - Armer serializer: about +60 / −45.
  - Admission bound: about +25.
  - Net roughly **+200 / −185**.
- **Mechanisms deleted:**
  - 5 hand-published completion channels and their publishers.
  - 3 long-held monitors.
  - The disarm `alts!!` backstop loop.
  - The Datahike timeout catch in `db.clj`.
  - `stop-error-fanout!`'s two joins, which M4 deletes anyway.
  - Eventually the `bin/test` suite liveness watchdog. It is the "fragile" backstop this
    design makes unnecessary; delete it once the class regressions are green.
- **Composes with the flow PRD:**
  - **B1 equals N1b with a smaller fork diff.** The exit fact is the `FutureTask` Flow
    already creates, not a new per-proc promise-chan.
  - **B1 + A equal N2.**
  - **B2(ii) complements N3's system-write bound**, by making it rarely fire.
  - **B2(i) is audit §5 step 8.**
  - N1a, N1c, N1d, N1e and N4 are **complemented, unchanged**.
- **Gives up:**
  - A maintained fork delta in core.async Flow. It is small and upstream-compatible:
    new accessor, `run`'s return value.
  - Producers must batch, so one logical operation can span several transactions.
    That was already true of publication, and each batch is its own atomic unit.
  - The armer-serializer change touches every direct installer.
- **Regression, one per class:**
  - **J (stop):** a proc whose transform blocks on a never-delivered promise.
    `cluster/stop!` returns the typed timeout naming that pid within bound + 50 ms.
    `arm!` of that agent refuses by name. Delivering the promise lets the watcher
    release, and a subsequent arm succeeds.
  - **S (writer):** time a two-datom `commit-fault!` on fixtures with 1× and 10×
    declarations. The ratio must be ≤ 1.5.
  - **S (admission):** a transaction over the bound refuses at admission with the bound
    named. Releasing 1,000 test members issues ⌈1000/64⌉ transactions, each under the
    agent write dial.
  - **S (monitor):** a disarm awaiting a slow turn does not delay another agent's arm
    beyond one armer pass.
  - **C:** a stop during a real prompt acquisition returns within 1 s (issue
    `a-turn-spends-seconds-before-its-provider-call`).

### Option C — The writer applies precomputed datoms only (the maximal design)

- **Change:** `seon.db/transact!` expands every `:db.fn/call` and validates the result
  with `d/with` on the caller's thread, against a captured basis. It sends the writer
  only the resulting datoms, plus one guard that the basis commit is still the head
  (`registry/head-guard-tx` pattern). The `validate-report` hook leaves the writer. The
  78 transaction functions become pure `db → tx-data` functions called outside the
  writer, with no call-site change, because `seon.db` does the expansion.
- **Guarantee:** writer work is O(datoms). No Seon code runs on the writer thread at
  all. Validation outside the writer is exact, because the guard proves the basis did
  not move.
- **Lines:** about +120 / −150 in `seon.db` (the validator installation and its
  in-writer wrapper leave), on top of Option B.
- **Composes:** it supersedes M3-style writer-cost work, and it complements the flow PRD
  N1–N4.
- **Gives up:** read-modify-write atomicity under concurrency. Any write that commits
  between capture and apply makes the guard refuse. Seon's agents, the router and the
  fault route write constantly, so benign races would surface as refusals. The only
  ways to absorb them are a retry loop (rejected by AGENTS) or guarding per attribute
  read. Datahike cannot tell which attributes a transaction function read, so that
  would be a hand-maintained read set. Occurrence dedup (`error.clj` `commit-call`)
  and the head guards rely on in-writer reads of the latest value. Too much risk for
  what B already buys. Revisit only if B2(ii)'s size bound proves insufficient.
- **Regression:** a transaction function whose body sleeps never delays an unrelated
  two-datom write by more than one apply.

## 5. Fragile approaches rejected, and why

1. **Per-site timeouts with local constants.** They invent bounds that drift from the
   operation's obligation. The bound is a declared config fact carried with the
   operation (AGENTS "Values carry their world"; ruling 1r).
2. **Watchdogs and liveness monitors that dump and kill** (the `bin/test` SUITE LIVENESS
   pattern). They report a thread but never name a missing event. They fire on load.
   They are a second mechanism beside the bound.
3. **Retry on timeout or on basis conflict.** AGENTS forbids it. It also doubles the
   outcome-unknown writes.
4. **`Future.cancel(true)`, `Thread.stop`, or interrupt as termination.** An interrupt
   is a request; `Thread.stop` throws `UnsupportedOperationException` on current JDKs.
   Interrupting host code leaked Datahike's roster permit and wedged a JVM.
5. **Raising bounds** (the 600 s write-dial interim). It converts a loud failure back
   into a hang.
6. **A thread or task registry or supervisor beside Flow.** Flow's proc futures and the
   launcher's admitted set already are the registry.
7. **Timing out or cancelling a transaction inside the writer.** Datahike cannot cancel
   an accepted invocation (`writer.cljc:118-160`), and interrupting a go-pool thread
   mid-commit is unsafe.
8. **Adopting `StructuredTaskScope` now.** It is preview in JDK 26
   (`StructuredTaskScope.java:352`), needs `--enable-preview`, and its API is unstable.
   Flow's retained `Future` gives the same parent-owns-children property today.
9. **Timed-lock wrappers as the end state.** They make a long critical section fail
   instead of removing the work inside it. Option A accepts them; Option B deletes the
   monitors.
10. **A batching fault committer or a fallback fault store.** These are deferred or
    rejected by the flow PRD and the database-down ruling.
11. **An async-everything rewrite** (every operation returns a channel). It spreads
    lifecycle ownership instead of giving it to Flow.
12. **Swallowing an expiry as `nil`.** See the out-of-scope finding below. It is the
    "silence read as health" habit.

## 6. Out-of-scope findings

- **`src/seon/cluster/process.clj:159`:** `(catch java.util.concurrent.TimeoutException _ nil)`
  swallows the subprocess-cleanup deadline's expiry. This is a timeout read as health.
  **No issue exists.** The orchestrator should file it; this lane was restricted to one
  document.
- **Datahike `tools.cljc:104-107`:** the three-arity `deref` of `throwable-promise`
  throws `ExceptionInfo(TimeoutException)` instead of returning `timeout-val`, which
  breaks the `IBlockingDeref` contract (measured, P3). A fork fix deletes
  `db.clj:4330-4336`. It is cited in
  `docs/research/agent-platform/error-route-inspiration-datahike-clojure-2026-09-23.md`;
  no issue exists.
- **MCP exception summary:** it refused its own message with "expected a string, got a
  string" (`cluster.clj:411` contract) during probe P3's first attempt. Existing class:
  `docs/seon/issues/mcp-exception-projection-is-opaque-after-the-kind-removal.md`.
- **`default` readiness:** `ready-ms` was 107,102, over the ten-second law. This lane
  did not attribute it; the boot owner should.

## 7. Evidence: probes and commands

**Inventory** (189 ms). Over `src -g '*.clj*'`: `rg -n '<!!|>!!'`, `rg -n 'alts!!'`,
`rg -n '\(deref [^()]+ [0-9a-z-]+ '`, `rg -n '\(deref [^ ()]+\)'`,
`rg -n '\(\.(get|join|await|acquire|take)\s'`, `rg -n '\(locking '`,
`rg -n 'Thread/sleep'`, `rg -c ':db.fn/call'`, `rg -n 'd/listen'`, and
`rg -c '\(db/transact!|\(transact! '` (65).

**Probes.** MCP `eval_clj`, JVM mode, `default`, throwaway namespace
`tmp.hangs-research`, session `hangs-research`. No write to any cluster store; P3 used
a disposable `:memory` Datahike store, created and deleted inside the form.

- **P0 — platform thread census:** 28 platform threads, 12 RUNNABLE / 7 TIMED_WAITING /
  9 WAITING, no Seon frame BLOCKED; 4.5 ms. Virtual threads are not visible to
  `Thread/getAllStackTraces`.
- **P1 — monitor vs lock:**
  - A virtual thread waiting on `(locking mon …)` was still `BLOCKED` 300 ms after
    `.interrupt`, and acquired at 306.6 ms, only when the holder released (interrupt
    flag still set).
  - `ReentrantLock.tryLock(300 ms)` against a held lock returned false at 305.5 ms.
- **P2 — `flow/stop` is not a join:** a one-proc `:io` flow whose transform blocked on
  a promise.
  - `flow/stop` returned true in 0.10 ms.
  - The stop transition had not run 300 ms later.
  - It ran 304.7 ms after stop, once the transform was released.
- **P3 — serial writer:** on a `:memory` store, `transact!` of an 800 ms
  `:db.fn/call`, then a two-datom write.
  - The second write's `(deref p 200 ::timeout)` threw
    `ExceptionInfo(TimeoutException)` at 205 ms.
  - The slow transaction committed at 806.7 ms.
  - The two-datom write committed at 807.2 ms, after its caller's wait had expired.
- **P4 — warm `seon.db/carried-projection`** on `@(seon.cluster.boot/connection "default")`:
  0.29 ms.

## 8. Timings

| operation | ms | over 1 s? |
|---|---|---|
| scripted inventory (`rg` over `src/`) | 189 | no |
| P0 thread census | 4.5 (eval 12) | no |
| P1 + P2 monitor / lock / flow-stop probe | 928 | no. It deliberately contains 300 ms sleeps and 300 ms bounded waits, so its length is the measured waits themselves. |
| P3 serial-writer probe | 815 | no. Proportional to the 800 ms transaction function it measures. |
| P4 warm carried-projection | 0.29 | no |
| `runtime_status` | < 1 s (not separately clocked) | no |
| lane wall time: reading, probing, writing | ~1,000,000 (about 17 min) | yes. This is research reading proportional to the evidence set (14 issues, the flow PRD, 4 dependency seams); it is not a system operation. |
