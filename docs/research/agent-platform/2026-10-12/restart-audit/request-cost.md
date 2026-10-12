---
title: "Request cost audit: why (+ 1 2) costs 70-100 ms"
type: research
status: read-only audit at HEAD 015673f47, measured on the running default cluster
---

# Request cost audit: why `(+ 1 2)` costs 70-100 ms

Dated evidence, 2026-10-12, default cluster at HEAD 015673f47, warm JVM (pid 98270), agent `probe-cost` (a
disposable agent branch `:agent-9b2f1341b1b1`, 613,507 datoms on the branch). Nothing was edited or committed. The
only writes were the probe agent's own recording transactions and one empty `d/transact!` on its branch.

## What one request costs today

One sci-mode `(+ 1 2)` through the MCP entrance, as the reply reports it (five runs, all within these ranges):
82-113 ms total; stages `:fork` 16-23, `:settle` 33-45, `:test-step` 11-14, `:evaluate` 1.4-7.5, `:print` ~1,
`:unattributed` 17-35; 7,035 armed calls, 47 reads, step thread 31.7-32.9 MB / 40-55 ms CPU, writer 7.9-9.3 MB /
8.4-11 ms CPU, commit 11.5-13.1 MB / 9.8-13.5 ms CPU. JVM-wide allocation per request is ~65 MB.

What it should cost: read and evaluate ~1 ms, one durable recording transaction of 28 datoms (see 1) at the
platform's fsync floor (~9 ms on macOS, see 1), print ~1 ms. About 12 ms. We are 7-9x over.

### Per-function cost per request (the decisive table)

Measured by diffing each armed cell's cumulative counters (`seon.profile/observation` over `seon.profile/cells`)
around 40 requests, then dividing by 40. Total is inclusive; self excludes armed callees (unarmed private helpers,
such as `regenerate-agent-context!` and `base-bindings`, count as self of their armed caller).

| armed function | calls | total ms | self ms |
|---|---|---|---|
| `seon.mcp/sci-run` | 1 | 74.3 | 0 |
| `seon.turn/resume-turn` | 1 | 55.3 | 0.05 |
| `seon.turn/settle-evaluated!` | 1 | 40.1 | 0.03 |
| `seon.db/transact-call` | 1 | 37.5 | **37.2** |
| `seon.sci.eval/fork-for-turn` | **2** | 18.8 | **18.3** |
| `seon.db/q` | **40** | 11.3 | 0.25 |
| `seon.turn/test-step` | 1 | 11.1 | 0 |
| `seon.cluster.agent/acquire-context!` (inside test-step) | 1 | 10.2 | 0 |
| `seon.db/pull` | **63** | 4.9 | 0.08 |
| `seon.turn/evaluate-sources` | 1 | 4.0 | 0.05 |
| `seon.config/effective` | **12** | 3.4 | 0.05 |
| `seon.profile/live-costs` | 1 | 2.4 | 0.1 |
| `seon.turn/agent-dial` | 6 | 2.2 | 0.05 |
| `seon.db/validate-write-report` | 1 | 1.5 | 0.03 |
| `seon.db/carried-projection` | 127 | 1.4 | 0.58 |
| `seon.db/cost-upserts` (in the writer) | 1 | 1.2 | 0 |
| `seon.db/write-owned-values-error` | 1 | 1.1 | 0.93 |
| `seon.sci.eval/acquire!` | 2 | 1.0 | 0.03 |
| `seon.mcp/with-diff` | 1 | 0.7 | 0 |

The reads (40 `q` + 63 `pull`, the reply's "47 reads" counts only `read-entrances` at the top) are 16 ms of the
request. A JFR `profile` recording over 60 requests (scratch only, not kept) agrees: on the agent's turn thread 56%
of allocation and 33% of all sampled CPU is under `fork-for-turn` → `regenerate-agent-context!`, half of it in
`base-bindings`.

Ranked by milliseconds a fix at the owner removes:

| # | cost | ms now | after the fix | owner |
|---|---|---|---|---|
| 1 | two context regenerations per request | 18.3 (+1.0 acquire) | ~0.5 | `seon.sci.eval/regenerate-agent-context!`, `base-bindings` |
| 2 | the recording transaction | 37.5 | ~15-20 (fsync floor ~9 stays) | `seon.db/transact-call` → Datahike writer/commit → konserve |
| 3 | the test step on a pass that changed nothing | 11.1 | 0 | `seon.turn/step-due?`, `seon.turn/test-step` |
| 4 | 103 reads at ~10x Datahike's own cost | 16.2 | ~3-5 | `seon.db/q`, `seon.db/pull` and their callers |
| 5 | effective config recomputed 12x, dials 6x | 5.6 | ~0.5 | `seon.config/effective`, `seon.turn/agent-dial` |
| 6 | cost accounting over every executed definition, twice | ~4.5 | ~0.5 | `seon.profile/live-costs`, `seon.profile/selected`, `seon.db/cost-upserts` |
| 7 | contract predicate volume | ~0.5 | - | symptom of 4; not a target itself |

1 and 3 overlap: one of the two regenerations is the test step's `acquire-context!`. Fixing 1 leaves 3 at ~2 ms;
fixing 3 alone removes one regeneration (~9 ms).

## 1. Fork: two full regenerations per request, each O(all program Vars)

**Owner:** `seon.sci.eval/fork-for-turn` → `seon.sci.eval/regenerate-agent-context!` → `seon.sci.eval/base-bindings`.
Callers: `seon.turn/resume-turn` (the pass's fork phase) and `seon.cluster.agent/acquire-context!` (via
`acquire-once!`, called again by `seon.turn/test-step`).

**Measured.** The agent context holds 654 namespaces, 11,085 Vars (base: 653 / 10,876). One `base-bindings` of the
base: 3.86 ms, 5.27 MB, a pure function of the base. `regenerate-agent-context!` calls it on the base (`current`),
then walks every Var of every namespace of the agent's context (`sci/namespace-state ctx`), and for each Var does
`get` on `current`, `same-program-root?`, `own-bindings` lookup, generation check, and possibly `replaced-definitions`.
It then `sci/fork`s the base, merges, and `reset!`s four atoms. 9 ms per call, two calls per request = 18.3 ms self;
~22 MB of the step thread's 40 MB (JFR). The JFR leaves are `APersistentVector.hasheq` (the `[ns name]` path keys),
`PersistentHashMap` node walks and `LazySeq` realization: it is map building over 11k entries, twice.

**Proportional to** all Vars in all namespaces (11k), per call, twice per request. **Should be** proportional to
the agent's own layer (its generation-stamped Vars and `own-bindings`, a handful), and zero when the program did not
move: the base is immutable between program changes, so its snapshot is computed once per base, and an agent context
that the previous request already regenerated against this same base needs nothing.

**Smallest fix (a deletion).** This is exactly phase E4 of the accepted hybrid
(`docs/research/agent-platform/2026-10-12/hybrid-immutable-env.md`, "E4. The idle boundary forks only when the
program moved"; status NOT STARTED per `restart-audit/state-vs-docs.md` row E4): `resume-turn` and `acquire-context!`
compare program keys and fork only when moved; `base-bindings` and `::base-bindings` are deleted (the held base value
is the snapshot); the layer diff runs over `own-bindings` and the generation-stamped Vars only. Its red proof
`seon.turn-test/an-eval-request-on-an-unchanged-program-forks-nothing` does not exist yet; the numbers here
(9 ms / ~11 MB per regeneration, 11k Vars, up from 8,435 Vars and 2.3 ms in the hybrid's probe) should be its
measured-cost assertion via `seon.profile/measure`. Expected saving ~18 ms and ~22 MB per request.

## 2. Settle: the recording transaction (37 ms, 11-13 MB commit, 8 MB writer)

**Owner:** `seon.db/transact-call` (its 37.2 ms self is the wait on Datahike's writer and commit), Datahike's
`datahike.writer/create-thread` → `datahike.writer/measured-commit!` → `datahike.writing/commit!`, konserve's
`konserve.impl.defaults/update-blob` and `konserve.compressor/Lz4Compressor`.

**What the transaction writes.** The last transaction on the probe branch, read from its history: **28 datoms, all
assertions, 28 distinct attributes, one datom each** (`:seon.cluster.eval/*` 9, `:seon.eval/*` 8, `:seon.turn/*` 7,
`:seon.sci.eval/ending-ns`, `:seon.turn.work/situation`, `:seon.db.transaction/{operation,agent}`, `:db/txInstant`).
No cost entities were written (no sample moved). This is small and correct.

**What the commit writes.** Files the store gained during one `(+ 1 2)`: **23 files, 125,251 bytes** (largest 37 KB,
the commit record with the six fused index roots inlined; then 20 KB, 8.8 KB, ...; most 1-6 KB). No whole-index flush,
no full root rewrite per index, no branch-metadata rewrite: the fork's root fusion (`:fuse-index-roots? true`) and
the PSS diff buffers (`:index-config {:branching-factor 128 :diff-buf-size 32}`) already keep the write to the dirty
path. `schema-meta` is not rewritten (write-cache hit).

**Where the time goes.**

- **Durability floor, ~9 ms wall.** A raw `(d/transact! branch-conn {:tx-data []})` on the probe branch (one
  `:db/txInstant` datom): 13.8 ms wall, commit `:duration-ms 13`, commit CPU 4.1 ms, 3.3 MB; processing 0.19 ms.
  The ~9 ms of wall beyond CPU is the two macOS `F_FULLFSYNC` barriers konserve's `:defer-immutable-sync?` plan
  leaves per commit (`docs/research/agent-platform/commit-floor-2026-09-26.md`; task text in
  `resources/seon/tasks/seon.cluster.store.edn`). "Durable before it answers" makes this floor legitimate.
- **Commit CPU, ~11 ms / 12 MB for 125 KB written (100x write amplification in allocation).** JFR allocation under
  `measured-commit!`: **50% `net.jpountz.lz4.LZ4FrameOutputStream.<init>`**: `konserve.compressor/Lz4Compressor`
  constructs a frame stream with `BLOCKSIZE/SIZE_64KB` per value, which allocates its 64 KB block buffer and
  compressed buffer for every 1-6 KB index node; **35% fressian serialization**, of which
  `org.fressian.handlers.WriteHandlerLookup.requireWriteHandler` misses (`Fns.soloEntry`) are 14% and
  `datahike.datom/seq-datom` (each datom turned into a seq to be written as a list) 9%; `hasch.core/uuid` of each key 6%.
- **Writer processing, 8-10 ms / 8 MB.** Seon's own armed work inside the writer is small (`validate-write-report`
  1.5 ms incl. `write-owned-values-error` 1.1 ms; `cost-upserts` 1.2 ms). The rest is Datahike's transact:
  JFR allocation under `processing-measured` puts 52% under `datahike.db.transaction/retry-with-tempid` (a tempid
  that an earlier op allocated meets a `:db.unique/identity` value of an existing entity, and the whole transaction
  restarts from its first op) and 25% under `datahike.db/speculative-cache-context` via `in-transaction-db` (one
  per transaction-function call: `cost-upserts`, `absent-call`, ...).

**Proportional to now:** dirty index nodes x per-value fixed overhead (LZ4 frame buffers ~130 KB each) + tx-data
replayed once per upsert retry + two drive flushes. **Should be:** the bytes written (~125 KB) + one pass over 28
datoms + the fsync floor.

**Smallest fixes, at the owners (all in our forks):**

1. konserve `Lz4Compressor`: compress with the LZ4 block API into a reused, thread-confined buffer (or a frame with
   `SIZE_4KB`, which `LZ4FrameInputStream` still reads, since block size is in the frame header). Removes ~6 MB of
   the 12 MB commit allocation; the file format stays readable.
2. Fressian: cache the write-handler lookup per class (the `WriteHandlerLookup` misses), and write datoms with a
   direct handler instead of `seq-datom` (datahike's `datahike.index.persistent-set` fressian handler).
3. The recording transaction: name existing entities by lookup ref (`[:seon.agent/id id]`), never by a tempid that
   upserts, so `retry-with-tempid` never replays. Verify first: count retries for this transaction's tx-data on a
   `d/with` (the tx-data is built in `seon.turn/settle-batch!`).

Expected: commit CPU 11 → ~5 ms, processing 10 → ~3 ms, settle 37 → ~18 ms (floor ~9 ms plus ~9 ms work).

## 3. Test step: runs after every evaluated form, and re-acquires the world to run nothing

**Owner:** `seon.turn/step-due?` (gate) and `seon.turn/test-step`, called from `seon.turn/step`.

**Why it runs at all.** `step-due?` is `(pos? (:seon.turn.loop/forms-run report))`: any pass that evaluated a form,
`(+ 1 2)` included. The probe agent holds no task (so `task-proofs` is empty) and changed no program entity, so
nothing can run, and nothing does.

**Measured:** 11.1 ms per request, of which `seon.cluster.agent/acquire-context!` is 10.2 ms. That acquisition runs
`seon.sci.eval/acquire!`, `adopt-cluster-declarations!` and a **second `fork-for-turn`** (the regeneration of 1) just
to hand `test-changes`, `run-reaching` and `record-broken!` an execution. The rest (`task-proofs` 0.18 ms,
`record-broken!` 0.1 ms, `run-reaching` 0.03 ms) is the actual work.

**Proportional to now:** program Vars (through the acquisition), per evaluated form. **Should be:** zero when the
pass changed nothing a test reads; proportional to the reached tests otherwise.

**Smallest fix.** Gate the step on what changed, not on forms run: the pass report already carries the batch's
`:seon.batch/changes`. Order `test-step` so the cheap questions come first (`task-proofs` on the database, the change
set from the committed value) and acquire the context only when a proof or reaching test must actually execute.
Proofs that read data (a question task's answer) are covered by asking whether the settlement wrote any fact a proof
reads, which is the test system's existing reuse rule (unchanged members answered from their record). The existing
test `seon.request-cost-test/a-reply-carries-its-request-cost` already expects `:test-step` only on a defining request.
Saves ~11 ms (or ~2 ms once 1 lands).

## 4. Reads: 103 per request at ~10x Datahike's cost

**Owner:** `seon.db/q` and `seon.db/pull` (`with-declarations` → `read-declarations` → `vector-query-call` /
`pull-call`, read-evidence append, encode/decode through the projection), and the request path's callers.

**Measured:** 40 `seon.db/q` (11.3 ms) and 63 `seon.db/pull` (4.9 ms) per `(+ 1 2)`. Microbenchmark on one database
value, warm: a 2-clause scalar query costs 67 µs / 99 KB / 55 armed calls through `seon.db/q` against 5.5 µs / 7 KB
through `d/q`; a 2-attribute pull costs 290 µs / 45 KB / 42 armed calls against 50 µs / 14 KB through `d/pull`.
The visible callers include `seon.cluster.agent/db<-`, `settle-kept-branches!`, `seon.turn/previous-ending-namespace`,
`seon.turn/fault-window`, `seon.turn/open-for-agent` (6x), `seon.sci.eval/agent-ran?`/`agent-namespace`,
`seon.turn/task-proofs`, `seon.db/stored-costs`, `seon.db/sink-digests`.

**Proportional to now:** reads issued x per-read seon overhead (~10x). **Should be** a handful of reads per request:
most answer facts the agent's previous request already derived on an unchanged value.

**Smallest fix:** first count the reads by caller for one request (add the caller to `seon.profile/measure`'s
`:seon.profile/called`, or one probe with read evidence grouped by operation) and delete the repeats at their
callers: one read of the agent row per request carried in the world, not one per helper. The per-read overhead
(declarations, projection lookups: `carried-projection` alone is 127 calls per request) is a second slice after the
count drops.

## 5. Effective configuration recomputed per dial

**Owner:** `seon.config/effective` (12 calls, 3.4 ms per request), `seon.turn/agent-dial` (6 calls, 2.2 ms),
`seon.mcp/dial`. `seon.turn/step` alone reads `step?`, `step-time-limit-ms`, `test.cost/top` and
`fn.cost/min-total-us` each through `agent-dial` on a fresh `(db/db connection)`. **Proportional to** dials read.
**Should be** one effective configuration per request (per database value), held in the world and read by key; or
cached in `seon.cache` keyed by the commit revisions of the config attributes. Saves ~5 ms.

## 6. Cost accounting: every executed definition, twice per request

**Owner:** `seon.profile/live-costs` and `seon.profile/selected` (reply side; called from `seon.turn/step` and
`seon.mcp/jvm-run`), `seon.profile/transaction-observation` from `seon.db/cost-upserts` (writer side), plus
`seon.mcp/with-diff` → `seon.db/stored-costs`.

**Measured:** the call sink of one `(+ 1 2)` holds ~695-744 executed definitions (the 7,035 armed calls spread over
them). `live-costs` passes `min-calls 1 min-total-us 0 limit Integer/MAX_VALUE` to `selected`, so **every** sink row
goes through `cost-scope` (which calls `cells-of` and validates its returned vector of cells against
`:seon.profile/cell`: that is where the 695 calls each of `thread-local?`, `histogram?`, `accumulator?`,
`throw-counts?` and 2,085 `adder?` come from) and `cost-entity` (2 `seon.batch/sample` each: 1,488), is sorted, and
only then are host rows filtered by the `min-total-us` floor. `cost-upserts` does the same pass again inside the
writer. Total ~4.5 ms per request (2.4 + 1.1 + 0.7 + stored-costs 0.4).

**Proportional to now:** distinct executed definitions (~700), twice, with contract validation per row. That is
O(called), not O(all cells): the roster is consulted per executed key, not walked. The cost is the per-row
constant and doing it twice. **Should be:** a pass over the sink's counters that drops rows under the floor before
building anything; only the survivors (the agent's own interpreted definitions plus a few host rows) become entities.

**Smallest fix:** in `selected`, apply `qualifies?` on the raw row (its self nanoseconds are already in the sink
counter) before `cost-scope`; derive scope from the sink key's digest (an interpreted definition's cell is installed
with `:seon.profile/scope :seon.profile/context`, so record the scope in the sink key at installation instead of asking
`cells-of` per row). Saves ~4 ms.

## 7. Contract predicate volume (`database-value?` 2,382 per request)

**Measured:** one armed `seon.db/database-value?` call costs 0.2 µs and 88 bytes (10,000 calls: 2.0 ms, 0.88 MB),
so 2,382 calls are ~0.5 ms. Callers: each `seon.db/q` makes 11 (its internal armed helpers each validate the same
database argument: `schema-database` x3, `read-declarations`, `vector-query-call`, `with-declarations`, ...), each
`seon.db/pull` makes 15; 40 q + 63 pulls ≈ 1,385 of the 2,382; the rest are function contracts with a
`:seon.db/database-value` input (`agent-dial`, `config/effective`, `fork-for-turn`, ...).
`seon.schema.datahike/query-variable?` (1,685, ~42 per query) is the query lowering re-walking each query form on
every call. These counts are a symptom of 4, not a separate cost: cut the reads and they fall with them. Caching
the lowered form per query value and projection (`seon.cache`) is a later, smaller slice.

## Other findings

- **The value printer renders plain integers as entity references.** `(frequencies (map :a tx-datoms))` (values all
  `1`) printed the count of every ref-typed attribute key (`:seon.cluster.eval/ns`, `:seon.cluster.eval/run`,
  `:seon.turn/agent`, `:seon.turn/opened-tx`, `:seon.turn/closed-tx`, `:seon.turn/starting-ns`) as
  `[:db/ident :seon.db.transaction/operation]`; `(sort-by val ...)` of the same map printed `1` correctly. A map
  keyed by attribute keywords is treated as an entity and its numbers resolved as entity ids. Wrong data shown to
  an agent; the owner is the reference resolution in `seon.render.value` (`reference-value`). Needs a task.
- **JIT never settles.** Even warm, each request's `:seon.cost/shared` shows 17-48 ms of JIT (1.9 s over 60
  requests). Unexplained; candidates are per-request generated classes or deoptimization churn in the armed
  wrappers. Worth a JFR `jdk.Compilation` / `jdk.Deoptimization` look.
- **A JVM-mode no-op costs the same settle.** Any jvm-mode evaluation as root shows `:settle` 26-37 ms and 600-3,000
  armed calls: the same recording transaction (section 2).
- The JFR default stack depth (64) truncates the bottom of Seon's deep instrumented stacks, so its inclusive
  numbers under-count deep frames; the armed-cell table above is the authoritative attribution.

## How this was measured

All probes in jvm mode as `root` (jvm mode refuses other agents) driving `probe-cost` through `#'seon.mcp/eval-clj`:

```clojure
;; per-function cost per request: diff armed cells around 40 requests
(defn snap [] (reduce (fn [m o] (update m (:seon.profile/sym o) (fnil (fn [[c t s]] [(+ c (:seon.profile/calls o)) (+ t (:seon.profile/total-ms o)) (+ s (:seon.profile/self-ms o))]) [0 0 0])))
                      {} (map seon.profile/observation (filter #(syms (:seon.profile/sym %)) (seon.profile/cells)))))
(def before (snap))
(dotimes [_ 40] (#'seon.mcp/eval-clj {:seon.mcp/code "(+ 1 2)" :seon.mcp/agent "probe-cost" :seon.mcp/cluster "default" :seon.mcp/mode "sci"}))
(def after (snap))

;; the recording transaction's datoms on the probe branch
(def bdb (seon.db/branch-database (d/db conn) br))   ; br = (:seon.agent/branch of probe-cost)
(def last-tx (->> (d/datoms bdb :aevt :db/txInstant) (map :e) sort last))
(filter #(= last-tx (:tx %)) (d/datoms (d/history bdb) :eavt))   ; 28

;; the commit floor
(seon.profile/measure #(deref (d/transact! (seon.db/branch-connection db0 br) {:tx-data []})))

;; regeneration size and cost
(count (#'seon.sci.eval/base-bindings bctx))   ; 10,876; 3.86 ms, 5.27 MB
```

Files written per commit: `find data/store -newer MARK -type f` around one request. JFR:
`jcmd PID JFR.start settings=profile duration=20s` around 60 requests, aggregated by frame with a scratch script.
