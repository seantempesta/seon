---
type: research
status: active
tags: [research, runtime]
---

# JVM reactive render design investigation

## Conclusion

The recorded architecture is the right process boundary and is not an option
selected by this report: authored code belongs in the cluster JVM under the
one `:interrupt-fn`; the web-render JVM must receive ordinary data and perform
trusted derivation only (`docs/seon/architecture/ui.md:23-27,161-166`;
`docs/seon/architecture/architecture.md:239-262`).

It does **not yet form a complete or safe implementation design**:

1. Datahike caches eager database reads, not SCI renderer output. The current
   reactive registration shares a completed value only while at least one
   consumer remains; the last `unobserve!` deletes it
   (`src/seon/reactive.cljc:558-611,613-640`). Consequently overlapping tabs
   render once, but reconnect after zero consumers evaluates again. The target
   itself says both “closing the final consumer releases the registration” and
   that a page subscription retains the last event
   (`docs/seon/architecture/ui.md:490-493,518-527`).
2. Lazy hiccup can escape containment. `sci.eval/evaluate` returns the raw value
   and cancels the timer before returning (`src/seon/sci/eval.clj:108-129`);
   both the structural check and HTML serializer later realize seqs
   (`src/seon/render/canvas.cljc:209-277`;
   `src/seon/ui/html.cljc:292-312`). The measured probe returned a
   `clojure.lang.LazySeq` with zero authored callback calls inside evaluation
   and one only when realized outside it. This is a correctness hole, not an
   optimization question.
3. The JVM data feed's sliding buffer of one does not bound http-kit's socket
   queue. http-kit 2.9.0-beta2 appends partial and later writes to an unbounded
   `LinkedList<ByteBuffer>`
   (`reference-code/http-kit/src/java/org/httpkit/server/ServerAtta.java:6-8`;
   `reference-code/http-kit/src/java/org/httpkit/server/HttpServer.java:368-400`).
   The target's “slow browser cannot form an unbounded queue” claim is false
   (`docs/seon/architecture/ui.md:494-498`). This is filed as
   [[http-kit-streaming-writes-have-an-unbounded-socket-queue]].
4. The existing JVM driver records only duration from SCI's diagnostic in the
   terminal receipt; it drops `:seon.eval/fn-entries` and allocated bytes
   (`src/seon/agent/driver.clj:151-164`). There is therefore no current
   end-to-end path from a timed-out canvas renderer to an agent-visible fault
   that contains the spin-versus-blocked diagnostic.

The implementation recommendation is cluster-side materialization: keep
`:seon.render.canvas/content` as the authored pin, evaluate one active authored
canvas registration per agent in the cluster JVM, deeply realize and cap its
ordinary result before disarming SCI, suppress equal results, and commit the
newest complete result as one cardinality-one, no-history database value.
The web-render JVM reads that fact and never invokes authored code. This is the
only evaluated design in this report that simultaneously gives process
containment, zero agent evaluation for any number of tabs, and zero evaluation
after all tabs disconnect and later reconnect.

The strongest counter-argument is substantial: this stores a derived value,
keeps one dependency registration per authored canvas even with no viewers,
and adds up to one transaction for every unequal render. It gives up the
target's demand-lazy release rule. The owner must explicitly rule that
render-once across a zero-consumer gap is stronger than derive-don't-store;
otherwise “zero additional evaluation” can only be promised for overlapping
consumers, not reconnect.

## 1. Dependency ledger

### Inspected revisions and conditions

Source investigation began at Seon
`63099a5231698056c4610ed1e768ba7d16379a6a`. Concurrent deletion lanes advanced
the shared branch while this lane was read-only over their owners; the measured
mockup ran at
`7d435fbb22edd0b596426718a98a3f8182f107a4`. Historical line references to
`src/seon/web/datastar.cljs`, `src/seon/agent/ctx/driver.cljs`, and the deleted
host path refer explicitly to the starting revision. No source file under
`src/` was changed by this lane.

| Dependency | Selected source/version | Source actually read | Relevant contract |
|---|---|---|---|
| Datahike | `caf526850084` (`reference-code/datahike`) | `src/datahike/query.cljc:2425-2537,2975-3050,3076-3103,4597-4704`; `src/datahike/query/single_flight.cljc:16-180`; `src/datahike/db.cljc:387-411` | Weighted eager-query result cache; exact committed identity; dependency plans; JVM single-flight. |
| Datastar protocol | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` (`reference-code/datastar`) | protocol sources plus the Clojure constants below | Complete `outer` morph semantics used by Seon. |
| Datastar Clojure SDK | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2`, SDK `1.0.0-RC7` | `libraries/sdk/src/main/starfederation/datastar/clojure/{consts.clj,api.clj,api/elements.clj,api/sse.clj}`; `libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/{http_kit.clj,impl.cljc}` | `outer` is default; SDK frames events and delegates accepted writes to http-kit. |
| http-kit | vendored 2.9.0-beta2, incorporated as submodule by concurrent commit `2953a3b2f` | `src/org/httpkit/server.clj:79-216,254-312,343-395`; Java `AsyncChannel.java:251-293`, `ServerAtta.java:1-35`, `HttpServer.java:364-400` | Request workers, asynchronous channel acceptance, and the unbounded pending socket list. |
| SCI | `8fac6e88f32d` | `doc/interrupt.md:1-85`; `src/sci/interrupt.cljc:1-45`; `src/sci/core.cljc:310-325` | `:interrupt-fn` runs on every interpreted function and loop/recur body entrance; `interrupt!` is uncatchable by authored code. |
| core.async | `b871f3519de6` | `src/main/clojure/clojure/core/async.clj:119-130,555-565`; `impl/dispatch.clj:122-134` | A sliding buffer drops oldest; workload definitions are in `thread-call`, while dispatch only constructs executors. |
| Hyperlith | `b08a8e8689e1654fd7e0ce654064a703ca1f4772` | `src/hyperlith/core.clj:86-108`; `src/hyperlith/impl/datastar.clj:122-181`; `examples/` | Its render-per-connection, dropping-buffer, Brotli loop is a comparison, not the target. The stale `reference-code/n/examples/` reference was corrected to `reference-code/hyperlith/examples/`. |

`deps.edn:55-72` selects SCI and both Datastar SDK local roots.
`reference-code/datastar-clojure/libraries/sdk-http-kit/deps.edn:1-3` resolves
SDK 1.0.0-RC7 and http-kit 2.9.0-beta2.

### First-party idioms that already survive

- `src/seon/web/server.clj:1-16,256-318` already owns an independent http-kit
  process, a database session, bounded request executor, and no SCI dependency.
  Its implemented view is `/data`, not the complete UI
  (`src/seon/web/server.clj:281-305`).
- `src/seon/web/feed.clj:107-160` uses the Datastar http-kit adapter and gives
  each connection one virtual drain thread. Its sole patch call is
  `datastar/patch-elements!` with no options
  (`src/seon/web/feed.clj:33-43`), so the SDK's default `outer` morph applies
  (`reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/consts.clj:46-48,62-82`).
- `src/seon/web/feed.clj:22-25` performs `.clear` then `.offer`. This is a
  **sliding buffer of one**, and makes configuration values greater than one
  structurally unreachable. `::mailbox-depth` remains dead configuration at
  `config/system.edn:171`, `src/seon/config/resolve.cljc:284,1133-1135,2054-2055`,
  `src/seon/web/server.clj:21,33,293`, and
  `src/seon/web/feed.clj:112-115`.
- `src/seon/reactive.cljc:252-287` retains the newest pending database value;
  `:449-502` suppresses equal completed values; `:558-611` normalizes
  consumers so a later consumer receives the established value without
  recomputation.
- `src/seon/db/writer.clj:3153-3185` selects interests from `::by-attribute`
  before delivery.
- `src/seon/sci/ctx.clj:15-40` owns one real base and forks it per evaluation.
  `src/seon/sci/interrupt.clj:53-99` owns the one time flag and the
  `:seon.eval/fn-entries`, duration, allocation, and outcome diagnostic.
  `src/seon/sci/eval.clj:33-138` runs SCI on named platform threads with a
  semaphore and one armed interrupt per evaluation.
- The render engine and UI serializers are already portable:
  `src/seon/render.cljc`, `src/seon/ui/html.cljc`,
  `src/seon/ui/markdown.cljc`, `src/seon/ui/clojure.cljc`,
  `src/seon/render/{canvas,schema}.cljc`, and `src/my/{canvas,ui}.cljc`.

## 2. Ruled architecture and evaluation criteria

This report does not compare process architectures. It accepts the binding
target in `docs/seon/architecture/{architecture,ui,context,observability}.md`:
cluster JVM authored execution, web-render trusted derivation, one database
coordination path, Datahike eager-read caching, reactive equality suppression,
and complete outer morphs. It evaluates that target against four falsifiers:

- no authored closure can execute after the SCI interrupt is disarmed;
- `N` equivalent tabs cause one authored evaluation;
- reconnect after zero consumers causes zero authored evaluation;
- one slow or malicious consumer cannot wedge a process or grow an unbounded
  queue.

The per-connection resource budget is one admitted socket, one SDK generator,
one virtual drain thread, one sliding buffer of one before send, and one
consumer callback. The per-authored-canvas budget recommended below is one
idle dependency registration and one newest complete committed value, with no
idle thread. Any implementation requiring a second web-render SCI context,
per-tab authored evaluation, or a non-database result channel fails before
measurement.

## 3. Does the recorded target hold up?

### Claim-by-claim result

| Recorded target | Result in the inspected tree | Evidence and consequence |
|---|---|---|
| Web-render uses http-kit and its own immutable database session | **Partly implemented** | The process and `/data` view exist (`src/seon/web/server.clj:256-318`), but the ordinary agent/root/canvas routes still belonged to the Bun path at starting revision `63099a5:src/seon/web/datastar.cljs:1030-1268`. |
| Web-render never executes authored code | **Implemented for the current JVM `/data` path; not yet the whole UI** | The JVM server requires no SCI namespace (`src/seon/web/server.clj:1-16`) and its render closure calls only `seon.web.data/render` (`:281-284`). |
| Cluster JVM evaluates authored renders under the one `:interrupt-fn` | **Contradicted at the starting revision; deletion has exposed the seam** | The old host explicitly refused render calls and kept them on the pod (`63099a5:src/seon/host/invoke.clj:167-173`). The new `seon.sci.eval` door exists, but no cluster-side canvas render owner connects it to committed output. |
| Authored code exposes only ordinary data to trusted render functions | **Not proven and unsafe for lazy values** | `sci.eval/evaluate` returns raw values (`src/seon/sci/eval.clj:108-129`). Hiccup accepts seq children and realizes them later (`src/seon/render/canvas.cljc:172-291`; `src/seon/ui/html.cljc:292-353`). The measured lazy callback ran outside containment. |
| Datahike dependency plans select matching reactive reads | **Implemented for eager reads, weakened at Seon's writer boundary** | Datahike returns plans with eager read results (`reference-code/datahike/src/datahike/query.cljc:139-153,2877-2944`). Seon's writer reverse index selects by changed attribute (`src/seon/db/writer.clj:3153-3185`), losing entity/input selectivity. Missing evidence becomes `:all` (`src/seon/reactive.cljc:139-153`). |
| Settling plus configured maximum latency guarantees progress | **Implemented** | `due-at` is the minimum of dirty-time plus max latency and current-time plus settle delay (`src/seon/reactive.cljc:184-220`). The facts are `:seon.config/reactive-settle-ms` = 16 ms, structural settle = 300 ms, and max latency = 500 ms in the default manifest used for the inspected run (`config/system.edn:97-106`). |
| Datahike bounded weighted cache retains immutable eager results at exact identities | **Implemented, but narrower than the prose suggests** | Default cache limits are 64 database snapshots and shallow structural weight 1,000,000 (`reference-code/datahike/src/datahike/query.cljc:2445-2491`). Keys use committed `[connection-id generation commit-id]` (`reference-code/datahike/src/datahike/db.cljc:387-406`) plus query and non-database arguments (`reference-code/datahike/src/datahike/query.cljc:3076-3103,4630-4647`). This caches database query output, not SCI renderer output. |
| Concurrent equivalent consumers render once; a new consumer gets current value | **Implemented while the registration exists** | The key maps all consumers onto one registration (`src/seon/reactive.cljc:579-599`); a delivered value is pushed directly to a later consumer (`:599-607`). The 32-client mockup measured exactly one SCI evaluation. |
| Reconnect equals repaint | **Visual correctness implemented; zero authored evaluation after a zero-consumer gap is not** | Initial observation always obtains a current database value (`src/seon/reactive.cljc:558-611`), so it can repaint. The final `unobserve!` removes the registration and value (`:617-640`), so later reconnect recomputes. The measured returned-value analogue performed one additional evaluation after the gap. |
| One-slot mailbox prevents an unbounded slow-client queue | **False** | Seon's pre-send queue is one (`src/seon/web/feed.clj:22-25`), but http-kit accepts then appends pending socket buffers without a bound (`AsyncChannel.java:251-293`; `ServerAtta.java:6-8`; `HttpServer.java:368-400`). |

### The garbled capability sentence

`docs/seon/architecture/ui.md:161-166` says “invokes authored code with the one
`:interrupt-fn`, door, and exposes…”. The intended sentence is: “The cluster
JVM invokes authored code **through the one bounded evaluation, with its one
`:interrupt-fn`, and exposes only deeply realized, bounded ordinary data** to
trusted render functions.” The missing “through” is editorial; the missing
deep-realization contract is architectural.

### The synchronous JVM simplification

`src/seon/reactive.cljc` is 680 physical lines at the measured revision. A
manual, reproducible range inventory finds **73 lines (10.7%)** that exist only
to support CLJS async/platform behavior and disappear when the surviving JVM
branches become plain synchronous code:

- CLJS clock/timers: `:94,99,107` — 3 lines;
- Promise rejection/log handling: `:162-170` — 8;
- asynchronous interest acknowledgement: `:356-362` — 7;
- empty-interest Promise chain: `:395-412` — 18;
- listen Promise chain: `:428-437` — 10;
- compute Promise chain: `:537-546` — 10;
- `observe!` async metadata/Promise/await sites:
  `:558,566,571-573,605,610` — 7;
- `unobserve!`: `:613,639` — 2;
- `close!`: `:643,655-661` — 8.

This count deliberately excludes ordinary scheduling logic and the already
present CLJ alternatives. The lexical lower-level inventory is three
`js/Promise` sites, five `.then`, five `.catch`, five `await`, and three async
metadata sites. The correct JVM conversion is therefore deletion and
straight-line blocking, not a Promise port.

One current JVM thread placement is nevertheless wrong. The scheduled
`seon-reactive-settle` platform thread calls `start-evaluation!` directly
(`src/seon/reactive.cljc:85-103,197-220`), and the CLJ branch performs the
entire computation synchronously (`:547-556`). A trusted computation that can
both wait for a database session and compute a complete view is `:mixed`, not
`:io` or `:compute`, under core.async's definitions
(`reference-code/core.async/src/main/clojure/clojure/core/async.clj:555-565`).
The timer thread must only publish readiness; it must not become the render
worker.

### Deletion accounting

At starting revision `63099a5`, the unconditional CLJS deletion set is:

| File | Physical lines |
|---|---:|
| `src/seon/web/datastar.cljs` | 1,268 |
| `src/seon/agent/ctx/driver.cljs` | 605 |
| `src/seon/ui/agent_view.cljs` | 93 |
| `src/seon/ui/header.cljs` | 47 |
| **Total under the named CLJS-only set and under `src/seon/`** | **2,013** |

The JVM-only simplification can additionally remove the 73 CLJS async lines
from `src/seon/reactive.cljc`, for **at least 2,086 physical source lines**
deleted relative to the starting revision. Concurrent lanes already changed
these totals after the investigation started; the table is intentionally
revision-qualified.

The forbidden alternative—arming another `:interrupt-fn` inside web-render—is
dead in one line: it violates `docs/seon/architecture/ui.md:23-27,161-166` and
duplicates the containment mechanism being deleted.

## 4. Why the Bun version was a resource pig

### The actual multiplier

The old cost centre is **over-selected, complete agent-view recomputation
followed by complete serialization**, not interpreter speed and not one
evaluation per socket.

At starting revision `63099a5`:

1. Equivalent sockets were already normalized onto one subscription
   (`src/seon/web/datastar.cljs:74-89,465-528`). This rules out
   per-registration evaluation for equivalent tabs as the primary cause.
2. `render-read` reran the complete subscription renderer and serialized the
   complete event before equality suppression
   (`src/seon/web/datastar.cljs:397-420`). Equality suppression existed, but
   only after the work at `src/seon/reactive.cljc:449-494`; it saved network
   notification, not database acquisition, authored rendering, or HTML
   construction.
3. One agent-view recomputation acquired the agent, global agent count, and
   configuration; acquired the canvas; sometimes queried 50 recent messages;
   selected every block; invoked every symbol-backed renderer with
   `Promise.all`; materialized every surface; and rebuilt the header
   (`src/seon/agent/ctx/driver.cljs:444-605`).
4. Interest selection was only attribute-indexed
   (`src/seon/db/writer.clj:3153-3185`). Two agent views reading the same
   high-churn attribute were both candidates even when the changed entity
   belonged to one agent. This exact defect and its retained acceptance
   criteria are recorded in
   [[attribute-only-feed-interest-recomputes-unrelated-agent-views]].
5. Missing read evidence degraded to `:all`
   (`src/seon/reactive.cljc:139-153`), and every new registration initially
   installed `:all` before its first evaluation
   (`src/seon/reactive.cljc:558-610`). The JVM `/data` feed currently hardcodes
   `:all` on every result (`src/seon/web/feed.clj:145-153`). This is the same
   over-wake failure class as a global commit listener.
6. Each connection optionally owned a native gzip stream and flushed it per
   event (`src/seon/web/datastar.cljs:251-281`), so gzip was a per-connection
   multiplier after shared serialization. It is real secondary cost, not the
   source of unnecessary agent evaluation.

The retained focused Node benchmark in
[[attribute-only-feed-interest-recomputes-unrelated-agent-views]] measured the
pure recomputation and serialization penalty. Conditions: Node 26.4.0,
compiled ClojureScript `seon.ui.agent-view/render-agent-view` followed by
`seon.ui.html/->string`, one fixed ordinary projection, 200 warmups per scale,
3,000 samples at 2 and 5 surfaces and 1,500 samples at 10 surfaces. Results:

| Surfaces | Serialized bytes | p50 | p95 | Mean |
|---:|---:|---:|---:|---:|
| 2 | 9,110 | 0.236 ms | 0.371 ms | 0.258 ms |
| 5 | 50,558 | 1.094 ms | 1.478 ms | 1.151 ms |
| 10 | 181,078 | 3.880 ms | 6.861 ms | 4.324 ms |

Thus one unrelated high-churn datom could spend the full 50,558-byte
projection's 1.094 ms p50 on every unrelated five-surface agent, before
equality said “do not send.” The benchmark excludes database query, authored
execution, scheduling, gzip, and socket work, so it is a lower bound on an
unnecessary wake, not an end-to-end latency measurement.

What remains [UNVERIFIED] is the production share of total CPU and heap among
database acquisition, authored surface execution, string construction, gzip,
and socket backlog. No retained production profile attributes those shares.
The mechanism and multiplier are proven; the exact percentage called “the”
dominant resource is not.

### Why the ruled JVM design is better only if implemented fully

The JVM split removes Bun self-host/Promise machinery and prevents each web
connection from reaching authored execution. It does **not** by itself fix
attribute-only fan-out, `:all` evidence, complete-view cost, per-connection
compression, or http-kit's internal queue. A straight port of the old
subscription renderer would preserve the resource pig on another runtime.

The required cost chain is:

`actual eager read plan → entity/input-selective interest → one cluster render
per affected authored canvas → = suppression before commit → one complete
committed snapshot → one trusted web derivation per normalized view → one
bounded latest-complete transport value per connection`.

## 5. Open choices left by the target

### Commit versus return

The target says the cluster “commits or returns ordinary render data”
(`docs/seon/architecture/ui.md:23-27`) but does not say when. These semantics
are not interchangeable:

| Choice | N overlapping tabs | Reconnect after zero consumers | Process restart | Cost |
|---|---:|---:|---:|---|
| Return into one normalized reactive registration | 1 authored evaluation | 1 new evaluation | 1 new evaluation | No result transaction; lifetime tied to demand |
| Commit newest complete result as a database fact | 1 authored evaluation per relevant input state | 0 | 0, until source/input changes | Up to one transaction per unequal render; idle dependency registration |

The mockup measured exactly this distinction with 32 real SSE consumers:
returned registration `1` evaluation on the first round and `1` on reconnect;
retained ordinary result `1` then `0`.

**Recommendation:** commit. The owner's zero-evaluation reconnect requirement
cannot be derived from Datahike's query cache or a released process-local
registration. A new process-local authored-result cache would be a second
non-database channel and needs an owner ruling; it would still fail process
restart.

Do **not** overwrite `:seon.render.canvas/content`. That attribute is the
explicit renderer pin and currently stores either a symbol or literal
(`src/seon/render/canvas.cljc:339-372`;
`src/my/canvas.cljc:73-116`). Overwriting it with output erases the program.

The implementation should model a separate, cardinality-one complete rendered
snapshot on the canvas-owning entity. Its conceptual value is a closed ordinary
map containing:

- the fully realized hiccup or flat error value;
- the renderer source fingerprint;
- the immutable input database basis used;
- captured Datahike read evidence; and
- the SCI diagnostic on failure.

The exact attribute name and Malli shape are [UNVERIFIED] and intentionally not
registered by this design lane. The attribute should carry the
`:seon.db/no-history?` facet because it is high-churn presentation state; only
the newest complete value matters. The authored pin remains
`:seon.render.canvas/content`. Equality suppression occurs against the prior
complete snapshot before transaction submission. The cluster render owner,
not web-render, commits it.

This recommendation stores derived state, contrary to the default
derive-don't-store rule. It is justified only by the stronger explicit
requirements: durable cross-process handoff, process-restart recovery, and
zero authored evaluation after no consumers remain.

### Demand-lazy versus one active authored registration per canvas

A demand-lazy cluster renderer needs a durable demand fact or an RPC from
web-render to cluster. A leaf RPC is explicitly forbidden for authored layouts
and route handlers (`docs/seon/architecture/ui.md:161-166`), while a new
non-database demand channel requires an owner ruling. A durable “tab wants
render” fact is session bookkeeping in the database and creates cleanup races.

**Recommendation:** one active dependency registration for each pinned
agent-authored canvas, regardless of connected tabs. It owns no idle thread:
only a read-evidence interest, current complete value, and equality state.
Matching commits schedule one SCI fork; unrelated commits must not. This makes
tab count irrelevant and allows recovery from the committed snapshot.

The resulting resource equation is explicit:

- per connected tab: one admitted socket + SDK generator + virtual drain
  thread + pre-send sliding buffer of one + callback; zero SCI contexts and
  zero authored evaluations;
- per pinned authored canvas: one dependency registration, one retained
  complete value, zero idle threads, and zero idle forks;
- per matching dirty interval: at most one active SCI fork and one newest
  pending database value;
- database transactions: at most one result transaction per unequal completed
  render, therefore `0` while inputs are quiet and at most the unequal-render
  rate under churn. An honest commits/second number is [UNVERIFIED] until the
  representative throwaway-cluster workload in section 8 is run.

Strongest counter-argument: agents with canvases that nobody views still pay
re-evaluation and result commits. The one measurement that would overturn this
recommendation is an isolated-cluster workload with representative authored
canvas read sets showing that unequal render-result commits consume material
writer headroom or heap at expected idle-agent scale. The report cannot
substitute the existing generic transaction benchmarks for that workload.

For context only, a separate JDK 26.0.1/Datahike virtual-thread benchmark
reported 0.49 ms per submitted transaction at 200 concurrent callers versus
23.77 ms serial because the commit loop batched them
(`docs/prds/sci-execution-runtime/research/simplification-design-2026-07-25.md:188-200`).
Those conditions do not measure render-result size, interest feedback, or
steady-state canvas cadence, so they do not settle this choice.

### Deep realization inside the armed boundary

**Recommendation:** make “ordinary render data” an admission operation inside
the same compute task and before `interrupt/stop!`. It must:

1. walk every supported collection, including lazy seqs;
2. invoke any delayed SCI closure while the same `:interrupt-fn` is armed;
3. enforce existing depth/item/string/output caps during realization;
4. reject host objects and non-EDN values as a flat agent error; and
5. return only a deeply realized persistent value.

Calling `doall` later in web-render is dead: it executes authored code in the
wrong process. Calling it after `sci.eval/evaluate` returns is also dead: the
timer was cancelled in `finally` (`src/seon/sci/eval.clj:108-129`).

### Infinite-loop fault path

The required path should be:

1. Authored canvas evaluation runs on a cluster `:compute` platform thread
   through `seon.sci.eval/evaluate` (`src/seon/sci/eval.clj:33-138`).
2. `(loop [] (recur))` enters the SCI interrupt function on each loop body
   (`reference-code/sci/doc/interrupt.md:6-19`). The scheduled time flag makes
   `sci.interrupt/interrupt!` throw an uncatchable marker
   (`src/seon/sci/interrupt.clj:53-105`).
3. The compute task catches it and returns a flat value containing
   `:seon.error/kind :time` and the full record
   (`src/seon/sci/eval.clj:58-79,108-138`).
4. The cluster render owner calls the existing error owner with fault
   `:agent`. The persisted fault entity carries at minimum
   `:seon.error/fault :agent`, `:seon.error/message`, and bounded
   `:seon.error/data-edn`
   (`src/seon/error.cljc:519-547,716-767`). The same result transaction stores
   the complete error snapshot so the browser repaints without executing the
   renderer.
5. The render-specific terminal receipt must retain
   `:seon.eval/fn-entries`, `:seon.eval/duration-ms`,
   `:seon.eval/allocated-bytes`, and `:seon.eval/outcome`. The current generic
   driver loses all but duration (`src/seon/agent/driver.clj:151-164`), so this
   is an implementation gap.
6. The next agent context derives a warning from the committed agent fault and
   says, for example: “Your canvas renderer `<symbol>` exceeded its 50 ms
   limit. It entered interpreted function bodies 9,639,035 times, which is
   consistent with a spin; fix the loop.” A low entry count instead says the
   render was probably blocked in a host call. The exact warning consumer is
   [UNVERIFIED]; no current source path proves this final delivery.

The diagnostic is evidence, never a limit. The time-limit is the only limit.

### Thread workload classifications

| Work | Required thread/tag | Reason |
|---|---|---|
| SCI authored execution and deep realization | `:compute` platform thread | It computes and must never block. Reads are against the acquired immutable database value; leaf calls are forbidden. |
| Cluster database interest/session wait | `:io` virtual thread or nonblocking callback | It may block, but must not perform extended rendering. |
| Trusted complete web derivation if local replica reads are eager/in-memory | `:compute` platform executor | It computes and must not block. |
| Trusted derivation while the current remote session can block | `:mixed` virtual thread | Combining wait and compute violates both strict tags; this is the honest classification until the immutable replica is local. |
| SSE mailbox take and socket send/framing | `:mixed` virtual thread | `.take` blocks, while HTML/SSE/gzip construction computes. Calling it `:io` would violate “must not perform extended computation.” |
| Settle timer | scheduler only; no render work | The current direct call into synchronous compute (`src/seon/reactive.cljc:197-220,547-556`) must be split so the timer does not become an unlabelled mixed platform worker. |

The definitions come from
`reference-code/core.async/src/main/clojure/clojure/core/async.clj:555-565`;
`impl/dispatch.clj:122-134` merely constructs the executors.
The current custom executors do not literally attach core.async workload-tag
metadata: SCI names its platform thread `seon-sci-compute`
(`src/seon/sci/eval.clj:33-38`), the settle executor names its thread
`seon-reactive-settle` (`src/seon/reactive.cljc:85-91`), and the feed calls
`Thread/startVirtualThread` directly (`src/seon/web/feed.clj:122-140`). The
table is therefore the required semantic classification and identifies the
places where today's execution violates it.

## 6. Measured mockup

### Prediction stated before the run

For `N = 32` simultaneous SSE consumers of one canvas:

- normalized single-flight evaluation produces exactly one SCI evaluation;
- a second simultaneous round after all first-round consumers close produces
  one new evaluation when the result was only registration-local;
- it produces zero when the complete ordinary result survives the
  zero-consumer gap;
- 32 consumers of an infinite-loop canvas produce one timed-out SCI
  evaluation, all receive an error morph, and a later `/health` request is
  HTTP 200; and
- a lazy authored result performs no callback during evaluation but does when
  realized outside it, falsifying the current “ordinary data” boundary.

### Harness and conditions

Reproducible script:
`bench/jvm_render_design_mockup.clj`.

Command:

```sh
clojure -J-Xmx512m -M:writer:host \
  bench/jvm_render_design_mockup.clj 32
```

Conditions:

- Seon revision:
  `7d435fbb22edd0b596426718a98a3f8182f107a4`;
- macOS 26.5.2 build 25F84, Darwin 25.5.0, arm64;
- Apple M5 Max, 18 logical CPUs, 128 GiB physical RAM;
- Homebrew OpenJDK 26.0.1, G1, `-Xmx512m`, incubating vector module enabled;
- SCI `8fac6e8`, Datastar Clojure SDK 1.0.0-RC7, http-kit 2.9.0-beta2;
- loop `time-limit` 50 ms;
- server bound to loopback and clients used JDK virtual threads;
- `sci/fork` measurement warmed 1,000 forks, retained 50,000 forks from the
  real `seon.sci.ctx/base`, invoked `System/gc`, and sampled MXBean used heap.

Raw output is retained at `tmp/jvm-render-design-32.edn`; the committed script,
command, and conditions make the result reproducible.

### Results

| Probe | Consumers completed | Agent evaluations | Wall time |
|---|---:|---:|---:|
| Registration-local, first round | 32/32 | 1 | 31.449 ms |
| Registration-local, reconnect after zero consumers | 32/32 | 1 | 5.744 ms |
| Retained complete result, first round | 32/32 | 1 | 5.179 ms |
| Retained complete result, reconnect after zero consumers | 32/32 | 0 | 3.779 ms |
| Infinite-loop retained result | 32/32 | 1 | 63.858 ms |

The loop diagnostic was:

```clojure
{:seon.eval/fn-entries 9639035
 :seon.eval/duration-ms 55
 :seon.eval/allocated-bytes -1
 :seon.eval/outcome :time
 :seon.sci.eval/semaphore-wait-ms 0}
```

All 32 consumers received the Datastar `datastar-patch-elements` event carrying
the error view, and the subsequent health response was `HTTP/1.1 200 OK`.
The 9,639,035 entries in 55 ms are the spin signature; allocation was `-1`
because the JVM did not expose a usable per-thread sample for that timed-out
path. It is not reported as zero.

The lazy probe returned `clojure.lang.LazySeq`,
`:calls-before-outside-realization 0`, and
`:calls-after-outside-realization 1`. Its SCI record reported zero function
entries because the authored callback had not executed before the armed
evaluation completed.

The real Seon-base fork probe retained 50,000 forks in 7,472,688 bytes:
**149.454 retained bytes/fork**, with **321.747 ns/fork** over the creation
interval. This replaces the earlier empty-`sci/init {}` measurement for this
design question. It is an MXBean/GC differential on this machine and JVM, not
an object-layout proof.

### What the mockup does not show

- The retained map is an analogue for a committed result, not a Datahike
  transaction. The experiment discriminates lifetime/evaluation count; it
  does not measure result-commit latency, disk amplification, interest
  feedback, or no-history storage.
- It uses the real SCI base, interrupt, http-kit, Datastar adapter, and sockets,
  but not a throwaway Seon cluster. It did not touch the default cluster.
- The error was delivered to SSE consumers as ordinary data. The mockup does
  not prove the missing production fault-datom-to-agent-context path.
- It does not bound http-kit's pending socket queue; the clients read promptly.
- SSE wall times include startup/JIT and loopback scheduling and are not
  throughput claims.

## 7. RECOMMENDATION — implementation sequence

This is an implementation design for the ruled target, not implementation:

1. Define the cluster-side canvas render acquisition: authored symbol/source
   fingerprint, exact immutable database value, ordinary inputs, and captured
   read evidence. Do not reuse the deleted Bun driver shape.
2. Extend the one `seon.sci.eval` admission boundary so render results are
   deeply realized and bounded before `interrupt/stop!`.
3. Add one cluster-owned authored-canvas reactive registration per pinned
   canvas. Its first evaluation installs conservative interest; completed
   eager-read evidence replaces it. Preserve newest-pending and maximum-latency
   scheduling from `seon.reactive`.
4. Compare the complete ordinary snapshot with the prior value using `=`.
   Commit only unequal values to a separate cardinality-one, no-history render
   result attribute; never overwrite the authored content pin.
5. On failure, commit the complete error snapshot and record an `:agent` fault
   with the full SCI diagnostic. Add the derived agent-context warning that
   distinguishes high-entry spin from low-entry host blockage.
6. Make web-render routes read only the committed snapshot, then call the
   portable trusted render/HTML path. A fresh consumer always receives an
   unconditional current repaint.
7. Replace the timer-thread direct computation with an explicitly classified
   worker boundary.
8. Fix or fence http-kit's unbounded pending socket list before claiming
   bounded per-connection memory.
9. Delete the four CLJS-only files and JVM-obsolete reactive async branches.

## 8. What remains unsettled

| Unsettled question | Exact evidence that settles it |
|---|---|
| Is durable materialization acceptable despite derive-don't-store? | Owner ruling explicitly choosing zero evaluation after zero consumers/process restart over demand-lazy derivation. |
| What is the committed render-result schema and owner namespace? | A data-model review that names the owning entity/attribute, registers one closed Malli value shape, proves no-history/cardinality-one derivation, and keeps `:seon.render.canvas/content` as the pin. |
| Can dependency evidence retain entity/input constraints instead of attributes only? | A Datahike read-evidence fixture whose plan includes the actual agent/canvas input, followed through Seon's writer interest representation, proving a datom for agent A does not select agent B. |
| What is the steady-state cost of one active registration per authored canvas? | Throwaway-cluster benchmark at representative agent counts, read-set breadth, update cadence, and render-result sizes; report heap, selected registrations per commit, SCI evaluations, unequal result commits/s, writer p50/p95, and reopen correctness. |
| What render-result commit rate overturns materialization? | The same workload driven until writer p95, transaction queue, or heap crosses the program's chosen service limit. No such limit or measurement exists yet. |
| How does the agent receive a render mistake? | One isolated live agent authors an infinite-loop renderer; evidence must include the `:seon.error/fault :agent` datom, persisted SCI diagnostic, derived warning in the next agent context, error morph, and surviving cluster/web processes. |
| How are slow SSE writes bounded? | A paused-read socket probe through the selected SDK/http-kit versions measuring both Seon mailbox occupancy and http-kit pending bytes across a long morph burst. |
| Which cost dominated the Bun production process? | A retained production profile partitioning database acquisition, authored render, trusted view construction, HTML event construction, gzip, socket backlog, heap, and GC under unrelated high-churn commits. The existing focused benchmark proves the avoidable multiplier but not the full percentage attribution. |

Until these are settled, the architecture is validated as a process boundary,
but its cache, ordinary-value, bounded-resource, and agent-feedback contracts
must not be described as implemented.
