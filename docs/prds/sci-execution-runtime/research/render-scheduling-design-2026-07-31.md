---
type: research
status: active
tags: [research, flow, scheduling, render]
---

# Render/context scheduling — global through flow, and the current-usage audit

Discharges plan ruling **2026-07-31 #3** ("SCHEDULING IS GLOBAL, THROUGH FLOW —
no render-local scheduling … the render/context wave's obligation is to PROVE
[flow] is used right", `docs/prds/sci-execution-runtime/plan/README.md:1304-1318`).
Read-only lane: no `src/`, `test/`, or `resources/` file changed.

## Verdict in five sentences

1. Flow's three workload tags are **executor selection only** — no tag splits a
   synchronous call, and `:compute` is the only tag that hops
   (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-263`).
2. Every current Seon proc is correctly pinned `:io` or `:compute` at the one
   construction door (`src/seon/flow.clj:83-127`), and there is **no `:mixed`
   proc anywhere in `src/`** — but the *cluster render proc* does heavy
   derivation on `:io`, which is a contract violation flow does not police
   (`src/seon/render/web.clj:400` + `:445-480`).
3. **The render wave must not tag any render/walk proc `:compute`.** A
   Datahike read can perform blocking storage I/O on a cold index cache
   (`reference-code/datahike/src/datahike/index/persistent_set.cljc:432-444`,
   `k/get … {:sync? true}`), so "the walk is pure db reads ⇒ `:compute`" is
   **false**. Renders belong on `:io` procs with their expensive/untrusted
   segment submitted through the one bounded door.
4. The never-lock-up guarantee holds today for **evals** by construction (one
   turn proc per agent × one blocking `submit!!` per turn ⇒ an agent can hold at
   most **1** of the 18 compute permits), and holds for **delivery** by
   sliding-1 + the parked writer — but does **not** hold for **production**: one
   cluster-global render proc serializes every agent's page behind the slowest
   renderer.
5. The corrected-B gap from `workload-scheduling-truth-2026-07-29.md` §3 is
   **still open**: the launcher's permit is a *lifetime* gate
   (`src/seon/flow.clj:314-318`), so any future blocking leaf inside an eval or
   a render will hold a scarce compute permit across I/O.

## Dependency ledger

| dependency / mechanism | selected source | owners read |
|---|---|---|
| core.async Flow | `1.10.874-alpha3`, vendored (`deps.edn:17-20`) | `reference-code/core.async/.../flow.clj:78-105,194-203,258-289`, `flow/impl.clj:29-36,50-58,145-148,243-323`, `impl/dispatch.clj:71-116` |
| Datahike | vendored fork | `reference-code/datahike/src/datahike/index/persistent_set.cljc:409-444,526-570`, `api/impl.cljc:30-48` |
| SCI | vendored fork | `reference-code/sci/doc/interrupt.md`, `src/sci/interrupt.cljc:25-42` |
| Seon flow door + launcher | tree at `ef8cc6f77` | `src/seon/flow.clj:83-127,129-137,199-360,381-432,481-523` |
| Seon procs | same | `src/seon/cluster.clj:156-180,918-935`, `src/seon/cluster/agent.clj:123-149,166-240,246-270`, `src/seon/render/web.clj:360-480,502-608`, `src/seon/cluster/wake.cljc:163-228` |
| Seon eval door | same | `src/seon/cluster/loop.cljc:289-320`, `src/seon/sci/eval.clj:225-282` |
| prior research | — | `workload-classification-2026-07-28.md`, `workload-scheduling-truth-2026-07-29.md`, `flow-mechanics-2026-07-28.md`, `agent-flow-render-falsification-2026-07-29.md`, `render-pipeline-design-2026-07-29.md` |

---

## 1. Ground truth: what a workload tag mechanically does

`flow/process` resolves one workload once, option beating `:describe`,
defaulting to `:mixed` (`flow/impl.clj:245-247`). The launcher then has exactly
two shapes (`flow/impl.clj:256-263,270-323`):

```clojure
transform (if (= workload :compute)
            #(.get ^Future ((futurize step {:exec (spi/get-exec resolver :compute)}) %1 %2 %3)
                   compute-timeout-ms TimeUnit/MILLISECONDS)
            step)
exs       (spi/get-exec resolver (if (= workload :mixed) :mixed :io))
…
((futurize run {:exec exs}))            ; impl.clj:323 — the proc's whole loop
```

| tag | proc loop runs on | transform runs on | thread kind (defaults) | timeout on the transform |
|---|---|---|---|---|
| `:io` | `:io` executor | **inline in the loop** | virtual thread per task (`dispatch.clj:82-96`) | none |
| `:compute` | `:io` executor | compute executor, `.get` awaited | loop virtual; transform on `:compute` (default = **unbounded cached platform pool**, `dispatch.clj:91-96`) | **`compute-timeout-ms`, default 5000 ms** (`impl.clj:245,258-260`) |
| `:mixed` | `:mixed` executor | inline in the loop | cached platform pool | none |

Four mechanical facts that the design must be built on:

- **Every proc holds a thread for its lifetime.** The loop blocks in
  `alts!!`/`<!!` forever (`impl.clj:271-305`). `:io`/`:compute` leave one parked
  *virtual* thread; `:mixed` occupies one *platform* thread. "The `:mixed`
  scaling cliff" is thread occupation, not Loom pinning — that framing in
  `src/seon/flow.clj:88-90` is correct.
- **`:mixed` is not a splitting scheduler.** Confirmed again from source; the
  owner's original question ("send the io portions via an io channel and park
  the compute side") has no implementation anywhere in flow.
- **`:compute` moves the WHOLE transform.** It cannot identify I/O inside the
  transform. A transform that both reads a database and serializes HTML is one
  indivisible unit to flow.
- **`compute-timeout-ms` is a REPORT, not a kill.** `.get(timeout)` throws
  `TimeoutException`, which the loop catches and pushes to `::flow/error`
  (`impl.clj:301-316`); the submitted `FutureTask` is **never cancelled**
  (`impl.clj:29-36`). A runaway `:compute` transform therefore keeps burning a
  compute-executor thread forever while the proc reports a fault every 5 s and
  continues consuming input. **Nothing in the render design may rely on
  `compute-timeout-ms` to bound an agent-authored renderer.**

### 1.1 Misconception corrections (loud)

**(C1) "The walk is pure database reads, therefore `:compute`." FALSE.**
`render-pipeline-design-2026-07-29.md:175-190` splits "SCI render, Hiccup
admission/serialization, equality comparison, Datastar framing → `:compute`".
The reads that feed those renders are not in that list, and they are not
non-blocking. Datahike's persistent-set index is lazy: a `PersistentSortedSet`
carries an `IStorage` and restores nodes on demand
(`persistent_set.cljc:526-570,573-596`), and `CachedStorage/restore` on a cache
miss calls `(k/get store address nil {:sync? true})` — a synchronous konserve
read, i.e. **file I/O on the calling thread**
(`persistent_set.cljc:432-444`). Any `d/q`/`d/pull` over a file-store database
value may therefore block. A render proc tagged `:compute` would be blocking on
the bounded compute executor, which is exactly the failure the tag exists to
prevent. **Renders are `:mixed`-class work in classification terms and belong on
`:io` procs.**

**(C2) "Ruling #3 says unknown work is `:mixed`, but `var-process` refuses
`:mixed`." Both are right — they name different objects.** Ruling #3's ladder
(`README.md:1307-1311`) classifies **units of work** (a function, an eval, a
renderer invocation). `var-process`'s refusal (`src/seon/flow.clj:106-111`)
governs **procs** — long-lived loops, of which there are one or three per agent.
A proc is never "unknown": its loop is pure channel I/O. The reconciliation the
wave must write down: **procs are `:io` (or `:compute` for a proven-pure
transform); `:mixed`-classified *work* is not a proc tag at all — it is work
that must be submitted through `seon.flow/submit!!` and run on a virtual thread
under the one time-limited door.** Otherwise someone will read ruling #3 and add
a `:mixed` proc, which the door will refuse at construction, and the confusion
will be resolved by weakening the door.

**(C3) "The bounded `:compute` executor runs the evals." FALSE, and this is
good.** `start-work-launcher!` builds `task-executor` =
`Executors/newVirtualThreadPerTaskExecutor` (`src/seon/flow.clj:135-137,416`)
and `execute-work!` submits to *that*, not to Flow's `:compute-exec`
(`src/seon/flow.clj:302-303,214-215`). The root bounded platform pool
(`src/seon/cluster.clj:157-162`) is passed as the launcher graph's
`:compute-exec` and is used only by the tiny `capacity-observer` proc's
transform (`src/seon/flow.clj:393-400`). So recommendation 1 of
`workload-scheduling-truth-2026-07-29.md` §6 **has landed**; the bound is the
launcher's permit count, not the executor's thread count.

**(C4) `root-executors`' `:io` member is a cached PLATFORM pool**
(`src/seon/cluster.clj:162`), not core.async's virtual-thread executor. Only the
work-launcher graph receives it (`src/seon/flow.clj:399-400`). **If the render
wave passes `root-executors` into the cluster or agent `create-flow`
definitions, every proc in those graphs becomes a platform thread** and the
measured "0 new platform threads at 100 agents"
(`agent-flow-render-falsification-2026-07-29.md` §2) evaporates. Cluster and
agent graphs must keep passing only `:procs`/`:conns`
(`src/seon/cluster.clj:918-935`, `src/seon/cluster/agent.clj:258-270`).

---

## 2. Audit: every proc in fresh `src/`, and what it pins today

Exhaustive: `rg 'var-process|create-flow' src/`.

| proc / graph | file:line | tag | loop thread | what the transform actually does | verdict |
|---|---|---|---|---|---|
| agent `::mailbox` | `src/seon/cluster/agent.clj:123-149,259-263` | `:io` | virtual | increments a counter, emits one payload-free signal | **correct** — total and instant |
| agent `::turn` | `src/seon/cluster/agent.clj:166-240,264-269` | `:io` | virtual | settles orphan, `@connection` reads, `cluster.loop/turn` → synchronous HTTP model call, `d/transact`, `Thread/sleep` backoff (`loop.cljc:932-944`), blocking `submit!!` | **correct** — genuinely blocking |
| cluster `:seon.cluster.agent/armer` | `src/seon/cluster.clj:926-929` | `:io` | virtual | derives (agents in facts) − (armed), arms graphs | correct |
| cluster `:seon.render.web/render` | `src/seon/cluster.clj:930-934`, `src/seon/render/web.clj:397-480` | `:io` | virtual | `Thread/sleep` coalesce floor, then `page-of` per watched agent: `d/pull`/`d/q` walks, hiccup build, **HTML serialization**, equality compare | **VIOLATION-BY-CONTRACT** — flow says `:io` "should not do extended computation" (`flow.clj:194-203`). Measured at 0.85–1.0 ms p95 per 250-event page (`render-pipeline-design-2026-07-29.md:237-241`), ×N watched agents, serialized |
| `seon.flow/fault-committer` | `src/seon/flow.clj:555-602` | `:io` | virtual | `d/transact` a fault fact | correct |
| `seon.flow/capacity-observer` | `src/seon/flow.clj:164-186` | `:compute` | loop virtual; transform on the **bounded root platform pool** | pure map reduction over `@active-work` | correct, and the only legitimate `:compute` proc in the tree |
| `seon.flow/work-launcher` | `src/seon/flow.clj:279-360` | `:io` (hand-rolled `ProcLauncher`, not `var-process`) | root cached **platform** `:io` executor (`flow.clj:305`, `cluster.clj:162`) | `alts!!` on control/completion/submission; `.execute` onto the virtual task executor | correct, but note it is the one proc that is **not** a var-process and therefore **not hot-reloadable** |

Not procs, but scheduling-relevant threads:

| owner | file:line | thread | verdict |
|---|---|---|---|
| wake routing listener | `src/seon/cluster/wake.cljc:203-228` | **Datahike's transaction thread** | correct: only `offer!`, one try/catch, never parks or throws. Any work added here bills the writer loop |
| per-tab SSE writer | `src/seon/render/web.clj:569-601` | one explicit `Thread/ofVirtual` per tab | correct for the write/park half — **but** it calls `page-of` for the initial paint (`web.clj:573-577`), i.e. a full derivation per tab open |
| eval submission | `src/seon/cluster/loop.cljc:304-320` | caller's virtual turn thread parks on `deref result` | correct: goes through the one door |
| eval execution | `src/seon/flow.clj:214-268` | virtual thread per task | correct |
| sci deadline timer | `src/seon/sci/eval.clj:231-238` | one daemon `ScheduledThreadPoolExecutor` thread, process-wide | correct: flips a volatile, does no work |

### 2.1 Findings, ranked by consequence

**F1 — Production is cluster-serial (the fairness hole).** One render proc
derives *all* watched agents' pages in one transform (`web.clj:329-358`). An
expensive page for agent A delays agent B's page by A's whole cost, and one
runaway renderer wedges the proc for the entire cluster. This is precisely what
ruling 21's per-agent render proc dissolves; the fix is architectural, not a
tag change.

**F2 — Compute on `:io` (`web.clj:400`).** Acknowledged in
`render-pipeline-design-2026-07-29.md:188-190` as "current-state evidence, not
the target". Per **C1**, the target is *not* `:compute` either. The honest
resolution: keep the proc `:io` and move the expensive, untrusted segment
(agent-authored renderer evaluation, and later bulk serialization) into
`submit!!`, so what stays inline on the proc is bounded first-party work.

**F3 — Per-tab initial derivation (`web.clj:573-577`).** 50 tabs opening ⇒ 50
`page-of` derivations, each on that tab's own virtual thread, all racing the
render proc. Measured cost of the shared alternative: once+mult at 50 tabs is
0.872–1.171 ms p95 versus 31.783–42.479 ms p50 per-tab
(`render-pipeline-design-2026-07-29.md:259-268`). Fix = serve the latest
keyframe (already the design; `:507-508`).

**F4 — Wildcard pull is everywhere.** `d/pull db '[*]` at
`src/seon/render/walk.clj:307,361`, `src/seon/render/agent.clj:220,340`,
`src/seon/render/block.clj:239,511`, `src/seon/render/web.clj:818`. Under ruling
#2's attribute-revision invalidation every one of these yields a dependency plan
of `:all`, i.e. "stale on every commit". The staleness check cannot bound
anything until these name their pull patterns
(`agent-flow-render-falsification-2026-07-29.md` §6.2).

**F5 — The lifetime-permit gap is still open.** `src/seon/flow.clj:314-318`
admits a submission only while `active-count < parallelism` and decrements only
on completion. Today no I/O capability is bound inside SCI
(`workload-scheduling-truth-2026-07-29.md` §4; `src/seon/sci/eval.clj:136-169`),
so the gate is harmless. The moment a renderer or an eval performs a blocking
read — **including a cold Datahike index restore, which is reachable today via
any db read inside submitted work** — a parked eval holds a scarce permit.
Measured penalty of exactly this shape: 425.9 ms (lifetime gate) versus 102.7 ms
(CPU-segment gate) for M=72, C=18, L=100 ms
(`workload-scheduling-truth-2026-07-29.md:410-418`).

**F6 — `submit!!`'s injection wait is unbounded.** `(.get ^Future injection)`
(`src/seon/flow.clj:500`) has no timeout; the `time-limit-ms` deref starts only
after the item enters the fixed queue (`:501`). With 18 running + 10 queued
(`config/default.edn:5,12`) the 29th caller parks with no clock. Cheap on a
virtual thread, but it makes total turn latency unbounded and unobservable.

**F7 — `capacity-observer` is `:compute`, so its transform carries the 5 s
`compute-timeout-ms`** it never documents. Its transform is pure and fast, so
this is benign — but it is the only place in `src/` where that default is live,
and nobody reading `src/seon/flow.clj:164-186` would know it exists.

---

## 3. The render/context wave: per-unit workload table

Every row states tag, executor, and the source or measurement that justifies it.

| unit | where it runs | tag | executor | why |
|---|---|---|---|---|
| **wake delivery** (listener → interest) | Datahike transaction thread | *not a proc* | — | must only `offer!`; the listener may never park or throw (`wake.cljc:163-196`), and it bills Datahike's writer loop |
| **staleness check on wake** (deps × last-seen attribute revisions) | inline in the agent's `::renders` transform | `:io` proc | virtual | O(deps) map lookups, microseconds; hopping it would cost more than it saves. **Acceptance:** p99 < 50 µs per registration set, else it is not a check, it is a render |
| **the walk** (`d/pull`/`d/q` with concrete selectors) | inline in `::renders` | `:io` proc | virtual | **C1**: a cold index node restore is `k/get … :sync? true` (`persistent_set.cljc:432-444`). May block ⇒ never `:compute` |
| **first-party renderer fns** (`requiring-resolve`d vars, `block.clj:891-911`) | inline in `::renders` | `:io` proc | virtual | trusted, bounded, and they read the database (same blocking argument). Keep inline while measured p95 per block stays under the frame budget |
| **agent-authored renderer fns through sci** | `seon.flow/submit!!` from `::renders` | submitted work, classified `:mixed` | virtual thread per task, bounded by launcher permits | untrusted and unbounded. The ONLY bound that works is the sci `:interrupt-fn` + `time-limit` (`src/seon/sci/eval.clj:246-282`); `compute-timeout-ms` reports but does not cancel (§1). Must not run inline, or one loop wedges the agent's whole render proc |
| **HTML serialization** | inline in the *cluster delivery* tier, once per package | `:io` proc | virtual | pure compute (0.666–0.868 ms p95 at 250 events, `render-pipeline-design:237-241`), but colocated with the mult so it serializes **once** for all tabs (50×–55× measured). Tagging the delivery proc `:compute` would put its `mult` puts and channel work on the bounded pool for no gain |
| **package fan-out** (`mult` → taps) | core.async's own mechanism | — | — | 0.047 ms p95 at 50 taps (`render-pipeline-design:265-266`) |
| **SSE delivery writes** | one virtual thread per tab | *not a proc* | `Thread/ofVirtual` (`web.clj:569`) | blocking socket writes; parks on http-kit's exact drain completion (`web.clj:518-522`) |
| **cache lookup** (digest/output memory) | inline wherever the consumer is | — | — | process-local map read; the memory is a single-writer atom on the agent handle (falsification §5.5, open decision 2) |

**The one seam that must NOT be inline:** the agent-authored renderer eval. Every
other unit is bounded first-party work whose cost is measurable and whose
failure mode is a slow page, not a wedged proc.

---

## 4. Fairness: the never-lock-up analysis

### 4.1 What bounds exist today

| resource | bound | source |
|---|---|---|
| concurrent evals (cluster-wide, in fact JVM-wide) | `:seon.config.flow.compute/concurrency` = `availableProcessors` (18 here) | `src/seon/flow.clj:314-318`, `config/default.edn:12` |
| queued evals | fixed buffer `:seon.config.flow.compute/queue-depth` = 10; producers park | `src/seon/flow.clj:392`, `config/default.edn:5` |
| **evals per agent** | **1, structurally** — one `::turn` proc per agent, `submit!!` is blocking (`loop.cljc:304-320`), and the self-rewake is an `offer!` onto its own sliding-1 (`agent.clj:228-232`) | this is the strongest fairness property the system has |
| eval wall time | `:seon.config.eval/time-limit-ms` = 30 000, enforced at every interpreted body entrance | `config/default.edn:39`, `src/seon/sci/eval.clj:246-282` |
| render derivations per cluster | ≤ 1 per `:seon.config.render/coalesce-ms` = 16 ms window | `src/seon/render/web.clj:461-470`, `config/default.edn:114` |
| render scope | only agents with an open tab (`registration`) | `src/seon/render/web.clj:486-500` |
| redundant delivery | byte-equality suppression on the whole page map | `src/seon/render/web.clj:356-358` |
| slow tab | per-tab sliding-1 tap + writer parks on drain | `src/seon/render/web.clj:558,511-522` |
| **agent-authored renderer** | **NONE — the mechanism does not exist yet** | `src/seon/render/block.clj:891-911` resolves first-party vars only |

### 4.2 Worst cases

| scenario | bounded today by | residual risk | what the design must add | falsifier |
|---|---|---|---|---|
| **A. One agent, expensive renderer, re-waking every commit** | 16 ms coalesce floor; equality suppression; tab registration | the pass is **cluster-serial** (F1): A's cost is added to every other agent's page latency; the floor throttles frequency, not cost | per-agent `::renders` proc (ruling 21) ⇒ N virtual threads, cost stays local; attribute-revision staleness ⇒ unchanged blocks are not re-rendered at all | 1 agent with a 200 ms renderer + 20 idle watched agents; measure the idle agents' commit→package p95. Guarantee: **unchanged by A's presence** (today it grows by ~200 ms) |
| **B. Agent-authored renderer loops forever** | **nothing** | if run inline on `::renders`, that agent's page freezes *and*, with today's single cluster proc, so does everyone's. `compute-timeout-ms` would report a fault every 5 s while the thread burns (§1) | route every agent-authored render through `submit!!` with the sci `:interrupt-fn` + a render-specific `time-limit-ms` (shorter than the 30 s eval limit — a renderer that needs 30 s is a bug, not a render) | `(loop [] (recur))` as a block's `:seon.render/html`; assert the block resolves to a bounded error unit within the limit, the proc's `::passes` still advances, and every other agent's page is unaffected. Second falsifier: a renderer blocked in a *host* call — the known interrupt ceiling (`reference-code/sci/doc/interrupt.md`) — must be bounded by permit release, not by the interrupt |
| **C. 100 agents wake on one hot attribute** | one listener, one `offer!` per report, all targets sliding-1 (`wake.cljc:212-223`) | with per-agent procs this becomes **100 concurrent derivations**; each may block on a cold index restore (C1) and each may submit a renderer eval. Virtual threads absorb the parking; the bounded door absorbs the compute | keep the derivation on `:io` (parks are free), keep the *renderer eval* behind the 18-permit door, and make the staleness check the first thing every pass does so 100 wakes cost 100 × O(deps) lookups, not 100 renders | commit one hot attribute 100×; assert total renderer evals ≈ number of genuinely stale blocks (not 100 × blocks), concurrent submissions ≤ 18, and no growth in platform threads |
| **D. Stalled browser tab** | per-tab sliding-1 (never blocks the `mult`) + writer parked on `http-kit.write/drained` (`web.clj:518-522`) | none found. `mult` cannot be held back because a sliding buffer is never full (`impl/buffers.clj`) | nothing; preserve the buffer choice — a fixed per-tab buffer would couple one slow tab to all tabs (`render-pipeline-design:522-527`) | SIGSTOP a tab's reader; assert other tabs' delivery p95 is unchanged and the render proc's `::passes` keeps advancing |
| **E. One agent floods `submit!!`** | structurally impossible for evals (one blocking submit per turn proc) | **becomes possible the moment renders also submit**: an agent with 40 stale blocks could submit 40 render evals | make the render submission **serial per agent** (the `::renders` transform submits one block at a time, or submits a single batched work-fn) so the "1 permit per agent" property extends from evals to renders | 1 agent with 50 stale blocks + 17 other agents each needing one eval; assert every other agent's eval starts within one eval-duration, i.e. no agent holds >1 permit |
| **F. Blocking inside a permitted unit** (F5) | nothing — the permit is a lifetime gate | with C1 this is **live today**: a submitted work-fn that reads a cold database value holds one of 18 permits across file I/O | the corrected-B change: separate bounded *outstanding admission* (`C+Q`) from a `C`-sized *CPU permit* released around blocking leaves | rerun the committed probe shape from `workload-scheduling-truth-2026-07-29.md:410-418` against the real launcher: M=72 submissions each blocking 100 ms, C=18. Today ≈ 400 ms; corrected ≈ 100 ms |

### 4.3 Buffer semantics per seam (transport law)

| seam | buffer | why |
|---|---|---|
| listener → `::interest` (per agent) | `(sliding-buffer 1)` | a wake says only "look"; the pass derives from the newest db value. Already the shape (`agent.clj:262-263`, `cluster.clj:970`) |
| `::mailbox` → `::turn` | `(sliding-buffer 1)` | unchanged; `agent.clj:269` |
| provider fold → render partial | `(sliding-buffer 1)` | newer prefix supersedes; terminal fact supersedes all (`cluster.clj:960`) |
| `::renders` → delivery (package) | `(sliding-buffer 1)` per agent page | the newest package repairs itself via its keyframe |
| package `mult` → each tab | `(sliding-buffer 1)` per tab | a slow tab must never backpressure the mult (D) |
| `::compute-submission` (the door) | **fixed** (`queue-depth` 10) | the one place backpressure is *wanted*: it is how the system says "no more work right now" |
| flow `::flow/error` / `::flow/report` | counted-dropping | observation must never block a producer (`src/seon/flow.clj:525-553`) |
| tab → http-kit | no Seon queue; at most one event in flight | the writer parks on the dependency's own drain completion |

---

## 5. Ranked recommendations — flow and the existing door only, no new mechanism

Each is falsifiable and names an owner file. None introduces a new construct.

**R1 (blocking the wave). Write the proc-vs-work reconciliation into the flow
skill and `src/seon/flow.clj`'s `var-process` docstring.** Ruling #3's `:mixed`
fail-closed applies to *work*; procs stay `:io`/`:compute`. Without this the
next lane will try to build a `:mixed` render proc and then "fix" the door.
Owner: `src/seon/flow.clj:83-111`, `.claude/skills/seon-flow-architecture/`.
Acceptance: a reader can state, from the docstring alone, why `:mixed` is both
ruled and refused.

**R2. Per-agent `::renders` proc, tagged `:io`, replacing the cluster-serial
production pass.** Owner: `src/seon/cluster/agent.clj:246-270` (+ the delivery
half staying in `src/seon/render/web.clj`). Acceptance: worst-case A's
falsifier — an idle agent's commit→package p95 is unchanged by the presence of
an agent with a 200 ms renderer. Cost budget already measured: +7.3–9.2 KB and
+19 µs per agent, 0 new platform threads
(`agent-flow-render-falsification-2026-07-29.md` §2).

**R3. Every agent-authored renderer evaluation goes through `seon.flow/submit!!`
with its own `time-limit-ms`, one submission at a time per agent.** Owner:
`src/seon/render/block.clj` (the resolution seam at `:891-911`) + the new
`::renders` transform. Acceptance: worst-case B's falsifier (an infinite
renderer bounds within the limit, the proc keeps passing, other agents
unaffected) **and** E's falsifier (no agent holds more than one permit). Adds a
config dial `:seon.config.render/time-limit-ms` beside
`:seon.config.eval/time-limit-ms` — one dial, same door, no second mechanism.

**R4. Close the lifetime-vs-CPU-permit gap (corrected B).** Owner:
`src/seon/flow.clj:285-360,481-523`. Split outstanding admission (`C+Q`) from a
`C`-sized CPU permit released around blocking leaves — and note that, per C1,
**the first blocking leaf already exists** (a cold Datahike index restore), so
this is no longer purely a pre-N5 concern. Acceptance: F's falsifier, ≈100 ms
versus ≈400 ms at M=72/C=18/L=100 ms.

**R5. Name the pull patterns.** Owner: `src/seon/render/walk.clj:307,361`,
`agent.clj:220,340`, `block.clj:239,511`, `web.clj:818`. Acceptance: after ruling
#2's staleness check lands, a commit touching one unrelated attribute leaves
those blocks' staleness checks answering "fresh"; today every one answers
`:all`. Without R5 the whole invalidation design is a no-op that still costs the
check.

**R6. Serve a new tab from the latest keyframe instead of deriving per tab.**
Owner: `src/seon/render/web.clj:569-580`. Acceptance: opening 50 tabs performs
**one** derivation; measured alternative is 31.8–42.5 ms p50 per-tab versus
0.87–1.17 ms p95 shared (`render-pipeline-design:259-268`).

**R7. Bound `submit!!`'s injection wait and publish the queue state.** Owner:
`src/seon/flow.clj:500`. Acceptance: with the queue full, a submitting turn
settles with a `:seon.flow/time-limit` value carrying its wait, rather than
parking without a clock. (Backpressure may wait; an *unobservable* wait is the
defect.)

**R8. Document `compute-timeout-ms` where the one `:compute` proc lives.**
Owner: `src/seon/flow.clj:164-186`. Acceptance: the docstring states that the
5 s default is a fault report and not a cancellation, so nobody later reaches
for `:compute` as a runaway bound.

### Sequencing

R1 (minutes, unblocks everyone) → R2 + R5 (the wave's own two changes, both in
render owners) → R3 (needs R2's proc to exist) → R6 (delivery half) → R4, R7,
R8 (launcher-owned, independent lane, no conflict with the render files).

---

## 6. Skill drift found (not edited — reporting only)

`.claude/skills/seon-flow-architecture/SKILL.md`:

1. The `:mixed` row says it "runs the proc's entire blocking loop and transform
   inline on one cached platform thread" — correct — but the `:io` row does not
   say that `:io` *also* runs the transform inline, on a virtual thread. Readers
   infer the difference is inline-vs-hopped when it is only which executor.
   Add: "every proc holds one thread for its lifetime; the tag chooses which
   kind."
2. `compute-timeout-ms` (default 5000 ms, fault-report-not-cancellation) is
   **absent from the skill entirely**. It is the single most surprising fact
   about `:compute` and belongs in the workloads section.
3. "**`:compute`** — the whole transform is submitted to the graph's compute
   executor. It is bounded only where Seon supplies the process-root executor" —
   true, but it should add that **no production eval uses that executor**: the
   launcher's task executor is virtual-thread-per-task
   (`src/seon/flow.clj:135-137,416`) and the bound is the permit count.
4. The blocked-issue reference to `agent-turns-bypass-the-bounded-compute-door`
   is stale in prose elsewhere: that issue is **archived** and
   `src/seon/cluster/loop.cljc:304-320` now goes through `submit!!`.
5. Nothing in the skill warns that a Datahike read can block (C1). Given that
   the skill is the map people use to choose `:io` vs `:compute`, this is the
   highest-value addition.

`workload-classification-2026-07-28.md` / `render-pipeline-design-2026-07-29.md`
§"Workload placement is part of correctness" need the C1 correction recorded
against them; this document is that record.
