---
type: research
lane: profiling-and-links-research-opus
date: 2026-09-28
status: design with measured prototypes; no src edits
evidence: tmp/profiling-links/proof.clj (JVM-mode MCP probes on default pid 9778, archive 29bdfce8; JDK 26.0.1, aarch64 macOS, 18 cores)
---

# Profiling hotspots and automatic related data (2026-09-28)

What exists: every armed call is already timed into a lifetime LongAdder cell (`seon.profile/timed`), and it is already
counted into the calling evaluation's world sink (S3b). An in-flight lane is adding per-world nanos and a
`:seon.repl/repeated` list. `seon.render.walk/graph` already follows refs, declared reverse attachments and a hand
list of value edges. The approach adds to both owners instead of building anything new. Part A adds one more slot
to the world counter (self nanos), a histogram on the cell, a per-reply hotspot table, and facts written at settle.
Part B derives the walk's value edges from schema declarations and returns its one-hop result as
`:seon.related/data` on every entity render.

## 0. Broken or surprising, found while measuring

1. **`System/nanoTime` does not scale across threads on this machine.** On one thread a call costs 11.6 ns. With
   2, 4, 8 and 16 threads calling it at once, the cost is 47, 129, 230 and 382 ns per call.
   `System/currentTimeMillis` is 217 ns at 8 threads, and `Instant/now` is 433 ns. LongAdder (6.6 ns) and ThreadLocal
   (11.6 ns) stay flat at 8 threads. So the whole timed bracket costs 33 ns on one thread and 520 ns at 8 threads,
   and almost all of that is the two clock reads. The likely cause is HotSpot's macOS `javaTimeNanos`, which
   enforces monotonicity with a CAS on one global. This is not verified: the JDK source is not vendored. Linux does
   not have that guard. Whatever the cause, the rule for the design is **never add a clock read**. Self time below
   reuses the two reads the bracket already makes.
2. **Reading `seon.batch/*world*` allocates 32 bytes.** A bound dynamic Var lookup goes through
   `Frame.bindings.entryAt`, which returns a new MapEntry. `timed` at HEAD reads it twice per outermost call
   (`here#` and `entered-in-world`), which is 64 B/call measured. The armed varargs wrapper allocates 144 B/call in
   total. Reading it once per call halves the timing's share.
3. **`my.program/callers` for `seon.db/q` takes 475 ms through SCI and 97 ms warm in JVM mode.** It makes 107,544
   armed calls: 821 `seon.db/q`, 2,021 `seon.db/with-declarations` (73 ms inclusive), 25,456
   `seon.db/database-value?`, and 4,035 `seon.cluster.store/datahike-log!`. The SCI reply said only `:ms 475`. This is
   the case the hotspot report exists for (§2.3).
4. **`seon.render.test/render-ai` ignores `:seon.render/distance`.** At distance 0 it still runs `seon.test/host`,
   which walks the call closure, so it costs 30–73 ms per test, warm and repeated (`src/seon/render/test.clj:44-62`).
   `seon.issue/render-ai` also ignores distance and returns prose strings. `seon.error/render-ai` at distance 0 shows
   the message and occurrence count, but not whether the fault is open.
5. **Lifetime cells report 212 s maxima for the scope wrappers.** `seon.schema/call-with-projection`,
   `call-with-projection-state` and `seon.db/call-with-custody` show these in `runtime_status`. They are callback
   time (task `5f54ae50d841`), and self time (§2.2) removes them from every ranking.
6. **Non-proc findings** (inclusive, lifetime cells, pid 9778):
   - `seon.db/write-owned-values-error`: max 7,135 ms over 1,051 calls, mean 13.9 ms (task `4bb788513862`).
   - `seon.db/q`: max 905 ms over 46,420 calls.
   - `seon.sci.eval/evaluate`: max 1,860 ms.
7. **Health was already `:faulted` before this lane started**: 11 open faults, 184 failed tests. The cache is at 99.9%
   of its 512 MB budget (511,537,782 bytes).

## 1. What exists (Part A inventory)

| Piece | Where (HEAD) | What it records | Cost measured |
|---|---|---|---|
| Lifetime cell per installed callable: calls, total ns, max ns, throws, and a per-cell re-entry ThreadLocal | `seon.profile/cell` `src/seon/profile.clj:69`, `with-cell` `:105` | inclusive wall time per `[sym digest scope]`, never reset | 2,535 live cells |
| The bracket | `seon.profile/timed` `:145`, used by `seon.instrument/wrap-interpreted` `src/seon/instrument.clj:638-640` (SCI) and `arm-var!` `:927-935` (host) | outermost entry per thread: two `nanoTime`, three adders; a counter in the world sink | fixed arity 31 ns over the original; +8 ns in a world |
| Armed call total | the Malli wrapper `reference-code/malli/src/malli/core.cljc:2229` (varargs) inside `timed` | contract validation | `seon.profile/adder?` 107 ns armed vs 1 ns original; 144 B/call |
| Per-world call sink | `seon.batch/sink` `src/seon/batch.clj:72`, bound per evaluation (`src/seon/turn.clj:5280`); `freeze-step` `:392` folds form sinks into the batch sink | `[sym digest]` → calls (HEAD). **In flight, uncommitted:** `[calls nanos]` as an `AtomicLongArray`, plus `batch/call-profile` and `:seon.repl/calls`/`:seon.repl/repeated` (`[sym calls ms]` over 100 calls) | +2 ns |
| Stored evidence | `code-evidence` `src/seon/turn.clj:5190` | `:seon.cluster.eval/executed` `#{[sym digest]}` only, with no counts or times | — |
| Reply | `seon.repl` `:seon.repl/ms` (`src/seon/repl.clj:210`) | wall ms of the arm | — |
| JVM-wide window | `profile/begin` `:251`, `explain`, `explain-slow` `:323`; callers `src/seon/test.clj:2039,2058` and `src/seon/cluster.clj:3330,3371` | cell deltas across every thread (tasks `c7bc0c506aa4`, `2f667b760734`) | 133 µs per `begin` (eval-feedback design) |
| Status | `profile/top`/`summary` `:221,:349`; `src/seon/cluster.clj:659` | top five lifetime cells and over-second lines in `runtime_status` | — |

## 2. Part A: recommended design

### 2.1 Options compared

| Option | Per-call overhead | Per-evaluation attribution | Fit |
|---|---|---|---|
| **Existing cells + world sink, extended (recommended)** | measured 38 ns fixed arity, 43 ns in a world (today 33 / 41) | exact: the sink is bound per evaluation and conveyed across thread hops | already on every armed call, with no new dependency |
| JFR custom events | not measured: `jdk.jfr.Event` needs a subclass, which Clojure cannot produce without gen-class/AOT; each event is an object commit | none per evaluation: a RecordingStream consumer is asynchronous and attributes by thread, not by world | keep it for allocation and sampling of **unarmed** code, as perf-profile-2026-09-26 did |
| async-profiler / clj-async-profiler | zero per call (sampling) | none per evaluation | native agent, not vendored in `reference-code/`; ad-hoc "why is this one slow" flamegraphs only |
| tufte | not measured; not vendored | its own thread-local accumulator | duplicates the cells and adds a second profiler, which the law forbids |

### 2.2 Accumulation (prototype §A2/§A2b)

This extends `timed` (`profile.clj:145`) and the in-flight `[calls nanos]` world counter. It adds:

- **Self time.** One per-thread `long[1]` frame holds "nanos my callees used". On entry, save it and zero it. On
  exit, `self = elapsed − frame[0]` and `frame[0] = saved + elapsed`. This needs no extra clock read, so it avoids the
  §0.1 contention.
- **A third world slot:** `[calls nanos self-nanos]` in the same `AtomicLongArray`, allocated once per definition
  per world. Reply rankings and the "your defect" judgment use self time. Scope wrappers (`call-with-projection`,
  `with-arm`, `evaluate`) then fall to the bottom of every list, which fixes `5f54ae50d841`.
- **On the lifetime cell:** a self-nanos LongAdder and a 48-bucket log2 histogram (`AtomicLongArray`, 400 B per cell,
  about 1 MB for 2,535 cells). Bucket = `64 − numberOfLeadingZeros(elapsed)`, so p50/p99 read to within 2×. That is
  enough to say "p99 is 4 ms, the bound is 1 s".
- **One `*world*` read per call** instead of two, which removes 32 B/call (§0.2).

Measured, same JVM, 3M calls, three runs (ns/call over a 2 ns original):

| bracket | fixed arity | fixed arity in a world | 8 threads |
|---|---|---|---|
| `timed` today | 33.1–33.4 | 41.0–43.1 | 520 |
| proposed (self + histogram + world self) | 37.7–40.1 | 43.0–45.2 | 587 |

Allocation: 0 B/call outside a world for both. In a world it is 64 B (today) against 32 B (proposed), which is the
single `*world*` read. The proposed bracket adds **+4 to +7 ns per call** single-threaded. Under contention both
are clock-bound (§0.1). The prototype first wrote the bracket as a plain function taking boxed longs and cell-map
lookups, which cost 76–88 ns. So the landed version must stay a macro with hinted locals captured at install, as
`with-cell` does today.

### 2.3 Per-reply hotspot report

The report is built from the evaluation's own sink at freeze (`batch.clj:392`), with no JVM window. It gives the top
N by self time, then every definition called at least `hot` times. It uses the `dir` shape (`:seon.repl/columns`,
`:seon.repl/rows`), the same for MCP and Seon agents, and passes through the floor printer. It replaces the
in-flight `:seon.repl/repeated` `[sym calls ms]` vectors with the same key and a column header. It is shown only
when the evaluation exceeds its own bound (`:seon.config.eval/bounds`, eval-feedback §4), or on request.

Real work, measured (the inclusive ms column comes from lifetime-cell deltas because HEAD's sink holds counts only):

```clojure
;; my.program/callers {:seon.program/subject 'seon.db/q} — 97 ms warm, 147 tokens
{:seon.profile/wall-ms 97, :seon.profile/armed-calls 107544, :seon.profile/definitions 59,
 :seon.repl/columns [:sym :ms :calls],
 :seon.repl/rows [[my.program/callers 97 1] [my.program/caller-data 96 1] [my.program/read-result 96 1]
                  [seon.db/with-declarations 73 2021] [seon.db/q 56 821] [seon.db/database-value? 1 25456]
                  [seon.db/query-binding-values 1 11526] [seon.db/schema-database 3 6884]
                  [seon.db/query-input-values 4 5746] [seon.cluster.store/datahike-log! 1 4035]]}
```

Sizes:

| evaluation | report size | full table |
|---|---|---|
| `seon.render.var/status` for `seon.profile/top` (12 ms, 13,555 armed calls) | 144 tokens | 823 tokens |
| `function-ai` (1 ms, 303 calls) | 103 tokens | — |

With self time the first three rows of the `callers` report collapse into the one real cost: 2,021 `with-declarations`
calls for 821 `q` calls, an N+1 read in `caller-data`. Building the report is a fold over about 60 entries, in
microseconds.

This deletes `profile/begin`/`explain`/`explain-slow`, their `snapshot?`/`growth-line` helpers and their three
callers (`test.clj:2039,2058`; `cluster.clj:3330,3371`). The JVM-wide window is what blames an agent for other
lanes' work (`c7bc0c506aa4`). That is about 100 lines deleted. `top`/`summary` stay for `runtime_status`.

### 2.4 Persistence (prototype §A4)

Timing data is not program, so it does not go on the program `:seon.fn/fn` row. It goes on its own data-partition
entity, **`:seon.profile/observation`**, with identity `:seon.profile/key` = `seon.id/id [sym digest]`. Its members
are `:seon.profile/sym` (declared as a reference to `:seon.fn/sym`, §3.2), `digest`, `calls`, `total-ns`, `self-ns`,
`max-ns` and `p99-ns`. A new digest is a new key, so a new definition starts fresh with nothing reset. The old
digest's row remains as history.

It is written by the database owner's fire-and-forget write, once per settle, never per call. The rows are the
definitions this batch executed whose cell changed. It is not a periodic sweep of all cells.

| write (`d/with` on default's value) | time |
|---|---|
| 100 observations | 10 ms |
| all 1,485 observations with digests, first write | 118 ms |
| rewrite of the same 1,485 | 45 ms |
| read one function's observation | 0.6 ms |

A settle touches about 60 definitions, so under 10 ms before fsync. Commit coalescing belongs to the writer
(fix-write-cost).

### 2.5 Auto task for a slow function

This reuses the fault machinery rather than inventing a closure rule:

- **Detect** at settle. Any executed definition whose max in this batch exceeds its bound is a finding. The bound is
  `:seon.profile/bound-ms` in the definition's metadata when declared, else `seon.profile/slow-ms` 1000.
- **Record** one fault through `seon.fault/fault!`, carrying `:seon.instrument/fn sym`. The signature is over
  `[:seon.profile/slow sym]`, so repeats count occurrences.
- **Close** by derivation, never by a stored flag. `seon.error/resolution` (`src/seon/error.clj:1156`) already
  resolves a fault when its function's definition digest changes after the latest occurrence, and reopens it when
  the fault recurs under the new code. That is exactly "overwrite it, the problem goes away, and it comes back if it
  regresses".
- **Task.** The law says "a fault without a linked task is a finding". The first occurrence creates one runtime task
  through `seon.task/create!` (`src/seon/task.clj`) with aspect `[ns "slow <name>" :function]`. Its intent carries
  the numbers and digest. It has `:must-read #{sym}`, and its done-when reads "a reaching test bounded under the
  bound passes at a new digest".
- **Proof.** The agent writes a deftest with `{:seon.test/long-ms <bound> :seon/tasks #{"<handle>"}}`. The runner
  already fails a body over its declared bound (`src/seon/test.clj:278-285`, `runner/duration-failures`), and the
  handle makes it the task's proof (`seon.issue/task-edges`, `src/seon/issue.clj:727`).
- **Closed means derived:** the fault is resolved, and every proof passes at the current digest.

Prototype §B3 created that task on `d/with` for `seon.db/write-owned-values-error`, handle `31fe6c5fc95b`, and §3.5
shows it discovered with no hand list.

## 3. Part B: automatic related data

### 3.1 Hand-written attachment lists today

| Where | What it hand-lists |
|---|---|
| `seon.render.walk/interim-value-edges` `src/seon/render/walk.clj:922-930` | 9 `[holder identity definition?]` rows: calls, must-read, see-also, test subject, task must-read, task tasks, arity/schema refs. Already marked "RETIRED when …" |
| `seon.render.ns/function-neighbours` + `function-ai` `src/seon/render/ns.clj:156-194` | callees and callers by two bespoke queries |
| `seon.issue/task-edges` `src/seon/issue.clj:727-743`, used by `task-ai`/`task-html` `ns.clj:110-151` and `seon.task/assignment` | subjects, proofs and referrers by an `or` join over five named attributes |
| `seon.render.var/status` `src/seon/render/var.clj:85-126` | reaching tests (closure through `fn/gate-sets`), pending/newly red/green, missing contract |
| `seon.render.test/evidence` `src/seon/render/test.clj:9-22` | selector `[:db/id :seon.test/sym :seon.test/source :seon.fn/form-span :seon.fn/calls]` |
| `seon.error/resolution` `src/seon/error.clj:1156` | the fault → function link through `:seon.instrument/fn` by name |
| `:seon.render/units` declarations (`seon.issue.edn:48`, `seon.agent.edn:37`, `seon.cluster.status.edn:7`) | declared reverse attachments: the right mechanism, used by 3 schemas |

### 3.2 The rule

An entity's related data is its one-hop neighbourhood in both directions:

- forward refs;
- reverse refs;
- **value references**: an attribute whose values name another entity's identity;
- reverse value references.

It is bounded per attribute. Each item is rendered at distance 0 by its own schema's render. Items without a render
are only counted.

Value references are derived from the schemas and are never listed by hand. Attribute A references identity
attribute I when either:

- **A's value schema bottoms out in the same named schema as I's.** For example, `:seon.task/tasks`,
  `:seon.task/assigned` and `:seon.task/merge` all end in `:seon.task/handle`, the leaf of `:seon.task/id`. Blob
  digests end in their blob identity's leaf. This is derived today with no new declaration.
- **A, or its leaf, declares `:seon.fn/reference-to I`.** This declaration exists on `:seon.render/ai|html|form` and
  the schedule task. The prototype assumes three new declarations, rename-worthy as `:seon.db/reference-to`:
  `:seon.program/edge-symbol` → `#{:seon.fn/sym :seon.test/sym}`, `:seon.instrument/fn` → `:seon.fn/sym`, and the
  new `:seon.profile/sym` → `:seon.fn/sym`.

Measured derivation (`structure`, once per projection): **2.7 ms**. It finds 48 identities, 259 ref attributes and 31
value references. Among them are all six fn/task rows of `interim-value-edges`, plus `:seon.fn/references`,
`:seon.fn/unresolved-references`, `:seon.test/reach`, `:seon.test.seed/function`, `:seon.instrument/fn`,
`:seon.task/assigned` and `:seon.task/merge`, which the hand list misses. It does not derive the three
`:seon.schema/key` rows (arity input/output refs, schema references): `:seon.schema/key` needs the same declaration.
The rows' `definition?` flag (definitions-first ordering) also needs a declaration, for example
`{:seon.db/reference-to … :seon.program/definition-edge true}`.

Two value references are unindexed: `:seon.task/must-read` and `:seon.fn/unresolved-references`. Reverse lookup on
them costs one AEVT scan of that attribute. Declare `:seon.db/index true` on `:seon.task/must-read`.

### 3.3 Where it goes

The traversal already exists: `seon.render.walk/graph` (`walk.clj:1057`). Measured at distance 1 in both directions
on `seon.db/write-owned-values-error`, it takes **5.8 ms warm** (43.7 ms first) and returns 13 nodes. Forward-only
mode follows only `:seon.fn/ns`, because `:seon.fn/fn` declares no `:seon.render/units`. So the landed version
works like this:

1. `graph-structure` (`walk.clj:932`) takes its `::value-attributes` from §3.2's derivation. `interim-value-edges` is
   deleted.
2. `:seon.related/data` is the walk at distance 1, both directions, with its per-attribute `max-collection` cut
   lowered to a render-profile dial (3 in the prototype). Each group carries `:count` and, when cut, its drill form
   as `:seon.render/operations`. Items render at distance 0 through `seon.render/render-call`, so selection, caching
   and elision are the render path's. `seon.render.value` attaches it whenever the value **is or identifies** an
   entity: a pulled map with an identity, a lookup ref, a Var (→ `[:seon.fn/sym s]`), or an error with
   `:seon.instrument/fn`. It is attached beside the schema's own render, never inside it. The floor printer's one
   budget clips it like anything else.
3. Specificity stays in each render function's distance-0 view. The **only** schema-specific code is each schema's
   own render; no related-data code names a schema.

The prototype (`related-edges`, `related`) reads datoms directly, to measure the rule without touching the walk. It
is a probe and **must not land** as a second traversal.

### 3.4 Renders must honor distance 0

Related data is only as cheap as the distance-0 renders:

- `seon.render.test/render-ai` at distance 0 costs 30–73 ms per test because of the `host` walk. The prototype's
  name+state view (`seon.test/recorded-result` only) costs under 1 ms.
- `seon.issue/render-ai` returns prose.
- `seon.error/render-ai` should add open/resolved (`seon.error/resolution`) at distance 0.

Each is a small change in its own render.

### 3.5 Prototype output (abridged), real entities

`seon.db/write-owned-values-error`, with its lifetime cell written as a profile observation and the auto task, both
on `d/with`:

```clojure
{:seon.fn/_calls {:count 2, :items [#:seon.fn{:sym seon.db/write-report-error, :doc "One final check for native operations and all expanded transaction-function output."}
                                    {:seon.test/sym seon.owned-value-test/a-transaction-entity-root-reaches-the-arity-gate, :seon.render.test/state "pass"}]},
 :seon.fn/calls {:count 5, :items [#:seon.fn{:sym seon.db/diagnostic} #:seon.fn{:sym seon.db/write-entity-error, :doc "…"} #:seon.fn{:sym seon.db/write-entity-value, :doc "…"}],
                 :seon.render/operations [(seon.related/items (quote [:seon.fn/sym seon.db/write-owned-values-error]) :seon.fn/calls {:offset 3})]},
 :seon.fn/ns {:count 1, :items [#:seon.ns{:name seon.db, :doc "The one database namespace for all things Datahike. …"}]},
 :seon.fn/references {:count 1, :items [#:seon.fn{:sym seon.db/transact!, :doc "…"}]},
 :seon.profile/_sym {:count 1, :items [#:seon.profile{:calls 1051, :max-ms 7135, :mean-us 13937, :current? true}]},
 :seon.task/_must-read {:count 2, :items [#:seon.task{:id "4bb788513862", :kind :function, :ns seon.db, :name "the-checked-writer-is-slow-on-a-fresh-branch"}
                                          #:seon.task{:id "31fe6c5fc95b", :name "slow write-owned-values-error", :kind :function, :ns seon.db}]},
 :seon.task/tasks {:count 1, :items [#:seon.task{:id "4bb788513862", …}]},
 :seon.related/skipped {:seon.db.vector/cells 1, :seon.fn/file 1, :seon.lint/_fn 2, :seon.test/_adoption-identities 23}}
```

That view took **4.8 ms warm** (15.5 ms first) and is **530 tokens**; discovering the edges alone took 2.3 ms. It
shows the profile, both tasks (the authored one and the auto one), the reaching test and its state. Nothing in the
code names any of those attributes.

`seon.test/run` shows faults, callers and issues. It took 7.3 ms warm and is 1,092 tokens, because the 23 legacy
issues render as prose:

```clojure
{:seon.instrument/_fn {:count 13, :items [#:seon.error{:occurrence-count 1, :message "Nothing found for entity id …"}
                                          #:seon.error{:message "recording writer refused", :occurrence-count 1} …],
                       :seon.render/operations [(seon.related/items (quote [:seon.fn/sym seon.test/run]) :seon.instrument/_fn {:offset 3})]},
 :seon.fn/_calls {:count 19, :items [#:seon.fn{:sym my.program/propose-merge!, …} #:seon.fn{:sym my.test/check, …} …]},
 :seon.issue/_functions {:count 23, :items ["Issue a-data-only-commit-rebuilds-the-whole-sci-program: still open; …" …]},
 :seon.related/skipped {:seon.instrument/_fn 16, :seon.test/_adoption-identities 18, …}}
```

A task, `[:seon.task/id "4bb788513862"]`, took 1.3 ms warm and is 185 tokens. It shows its subject (via
`:seon.task/_tasks`) and its three must-reads. `seon.issue/task-edges` alone takes 4.4 ms.

What it deletes, once each render reads `:seon.related/data`:

- `interim-value-edges` and its `definition?` use (about 12 lines);
- `function-neighbours` and the callers/callees half of `function-ai`/`function-html` (about 30);
- `task-edges`' subjects and proofs in `task-ai`/`task-html` (about 15). `task-edges` stays for `seon.task/assignment`
  until that reads the same value.

`render.var/status` stays: the "newly red since this definition" comparison is a derivation, not a link. Its test list
comes from the closure (`gate-sets`), which is two or more hops. The related group shows direct callers only and drills
to `my.test/check`.

## 4. How A and B meet: the loop

1. An agent's evaluation is slow. The reply's hotspot table names `f` by self time, from its own world only.
2. At settle, `f`'s observation row is written, and because `f` exceeded its bound, one fault and one task are
   recorded.
3. The agent, or root's queue, pulls up `f`. Its render carries `:seon.related/data` with the profile (`max-ms`,
   `current? true`), the task, the open fault and the reaching tests with their state. None of that is hand-designed.
4. On a branch the agent overwrites `f` (`defn` after `in-ns`) and adds a deftest bounded by
   `:seon.test/long-ms` with `:seon/tasks #{handle}`. `my.test/check` runs it.
5. The new digest makes a fresh observation row (`current? true`, small max). The fault resolves by digest change,
   and the task's proof passes at the current digest. The next view of `f` shows it closed. A regression reopens the
   same fault.

## 5. Integration points and ordered steps (about 100 lines each)

The steps below assume the in-flight `[calls nanos]` sink change lands first. It is uncommitted in
`src/seon/{profile,batch,repl}.clj` and belongs to another lane; these steps build on it and do not compete with it.

| # | Slice | Files | Adds / deletes |
|---|---|---|---|
| 1 | Self time: per-thread frame, third world slot `[calls nanos self]`, one `*world*` read, cell self adder and log2 histogram; `observation` gains `self-ms` and `p99-ms` | `profile.clj` (`timed`, `with-cell`, `cell`, `observation`), `batch.clj` (`counters`), `seon.profile.edn`, `seon.batch.edn` | +35 / −8 |
| 2 | Hotspot report from the sink: `call-profile` → top by self plus hot, in the columns/rows shape; shown past the evaluation's bound; test members and MCP JVM mode read their own world. Delete the JVM window | `batch.clj`, `repl.clj`, `test.clj:2039-2076`, `cluster.clj:3330-3374`, `profile.clj` (`begin`…`explain-slow`, `snapshot?`, `growth-line`) | +45 / −120 |
| 3 | `:seon.profile/observation` schema and its settle write (changed executed definitions only) | `seon.profile.edn`, `profile.clj`, the settle owner in `turn.clj` | +60 / 0 |
| 4 | Slow finding → fault (reuses `seon.error/resolution`) → one runtime task on first occurrence; `:seon.profile/bound-ms` optional metadata | `profile.clj` or `fault.clj` at settle, `seon.task` call | +50 / 0 |
| 5 | Declared value references: generalize `:seon.fn/reference-to` to `:seon.db/reference-to`; declare it on edge-symbol, `:seon.instrument/fn`, `:seon.profile/sym`, `:seon.schema/key`; index `:seon.task/must-read`; derive `graph-structure`'s value attributes; delete `interim-value-edges` | `seon.program.edn`, `seon.instrument.edn`, `seon.task.edn`, `seon.schema.edn`, `walk.clj:922-956,1046` | +30 / −15 |
| 6 | `:seon.related/data`: the walk at distance 1 with a per-group item dial, items through `render-call` at distance 0, attached by `seon.render.value` for values that are or identify entities; drill form `seon.related/items` | `walk.clj`, `render/value.clj`, `seon.render.edn`, a dial in `seon.config.render` | +80 / 0 |
| 7 | Distance-0 renders: test (no host walk), issue (data, not prose), error (open status); function/task renders read `:seon.related/data`, which deletes `function-neighbours` and the task-edges use | `render/test.clj`, `issue.clj`, `error.clj`, `render/ns.clj` | +30 / −50 |

Steps 1–4 and 5–7 touch disjoint files and can run in parallel. Net across all seven: about +330 / −193. The
additions above 100 lines are in step 3 (a new entity) and step 6 (the related data itself). Step 6 is justified by
the deletions in step 7 and by the hand designs it prevents.

## 6. Proposed task entries (for root to add; this lane edits no task files)

- `seon.render.test`: "distance-zero test render walks the call closure". The render costs 30–73 ms per test at
  distance 0 (§0.4). Done when a distance-0 render reads only the recorded result.
- `seon.profile`: "timing reads the dynamic world twice per call" (64 B/call, §0.2). Also: "the clock does not scale
  across threads on macOS" (§0.1). Done when the bracket makes one world read, and a multi-thread measurement is
  recorded beside the bound.
- `my.program`: "callers of a hot function makes 107k armed calls". 821 `seon.db/q`, 2,021 `with-declarations`, 97
  ms warm and 475 ms through SCI (§0.3). Done when the callers read is a set-at-a-time join.
- `seon.render.walk`: "value edges are a hand list". This is step 5, and it references the RETIRED note already on
  `interim-value-edges`.

## 7. Limits of this evidence

- Per-evaluation inclusive ms in §2.3 comes from lifetime-cell deltas restricted to the sink's keys, because HEAD's
  sink holds counts only. Other threads' concurrent calls to the same definitions would inflate them.
- Self time was measured only on the scratch bracket, not on real call trees: nothing here rebinds a Var root.
- The §0.1 cause is inferred, not read from the JDK source.
- Persistence cost is `d/with`; a durable commit adds the writer's fsync.
- The auto task and the profile rows exist only on a `d/with` value. The attributes `:seon.profile/*` are not
  installed.
- No test was run. The one disposable branch `profiling-links-probe` was created, used for three SCI forms (`(inc 1)`
  took 2 ms twice; the `callers` form took 475 ms), and retired.
