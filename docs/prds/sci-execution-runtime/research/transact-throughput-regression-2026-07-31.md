---
type: research
status: active
tags: [research, datahike, architecture]
---

# Transact throughput: 8 tx/s vs "thousands" — 2026-07-31

## The answer first

**There is no regression.** Both numbers are correct, both are reproducible on
this tree today, and they measure two different things:

- **8 tx/s / 123 ms** is the latency of ONE commit issued by ONE serial caller.
  Every serial file-store measurement this repository has ever taken agrees
  with it: 45.09 ms/tx (2026-07-25, small fresh store), 30.25 ms/tx
  (same day, n=1 rung), 74–88 ms for an 8 MB value and ~38 ms for 64 KB
  (2026-07-28), 122.8 ms today at 155,724 datoms. The number grew with the
  DATABASE, not with the code — reproduced below at 98 ms / 1.6k datoms →
  125 ms / 161k datoms on a virgin store built by this investigation.
- **"Thousands of tx/s" is the CONCURRENT number**, and it is Datahike's
  writer coalescing many transactions into one commit. It was measured at
  1,887 tx/s (200 concurrent callers, 2026-07-25 §7.1 run A), 4,336 tx/s
  (65,536 callers, §16.1), and 8,450 tx/s (16,384 callers over 16,384
  transactions, which collapsed into **four commits**). Those runs never
  claimed 4,000 commits per second; they claimed 4,000 *transactions* per
  second across **nine commits**.

The 2026-07-31 benchmark's row 13 measured a single serial writer and
therefore could not see coalescing. Its "8 tx/s" is a per-commit **latency**
statement wearing a throughput unit. The honest reading is:
**one commit costs ~123 ms of fsync no matter how much you put in it, so
throughput is whatever you can get into one commit.**

## Root cause of the 123 ms — measured, not inferred

A konserve file-store `assoc` of ONE key writes a `.new` file, `force`s it
(fsync), closes it, `ATOMIC_MOVE`s it over the real name, then `force`s the
containing DIRECTORY (`konserve/impl/defaults.cljc:104-117`,
`konserve/filestore.clj:39-49,196-205`). Defaults are `{:sync-blob? true
:in-place? false :lock-blob? true}` (`filestore.clj:692-694`). The filestore
is NOT `multi-key-capable?`, so Datahike's commit takes the sequential branch
and issues one such `assoc` per object (`datahike/writing.cljc:528-552`).

A Datahike commit writes `depth+1` node objects **per index**, times the
temporal indices when `:keep-history? true`, plus the schema-meta record, the
commit-graph record, and the branch head (`datahike/doc/write-amplification.md`).

Measured on this machine (`tmp/perf-fsync/probe.clj`, `probe2.clj`):

| quantity | measured |
|---|---|
| `FileChannel.force` on an already-open, already-created file | **0.049 ms** median |
| `force` of the store directory | **0.010 ms** median |
| one konserve `k/assoc`, default config (create + fsync + move + dir fsync) | **7.92 ms** median, p95 8.59 |
| the same `k/assoc` with `:sync-blob? false` | **0.58 ms** median |

The 7.9 ms is not the raw `force` syscall — it is fsync of a **newly created**
file, i.e. APFS metadata synchronization. That is exactly the resource the
2026-07-25 JFR run named independently: 38,580 `jdk.FileForce` events in one
60-second interval, p50 **8.14 ms**, all in konserve's file-store path
(`measurements-2026-07-25.md` §16.2).

Objects per commit, and the resulting commit latency, on a store grown by this
probe (5 datoms per commit, `:keep-history? true`):

| datoms in store | new `.ksv` objects per commit | commit median | commit p95 |
|---:|---:|---:|---:|
| ~200 | 7 | 49.8 ms | 131.3 ms |
| 1,616 | 16 | 98.2 ms | 122.0 ms |
| 6,223 | 18 | 101.7 ms | 115.4 ms |
| 21,337 | 18 | 108.4 ms | 118.0 ms |
| 60,464 | 18 | 101.2 ms | 112.1 ms |
| 161,114 | 24 | **125.1 ms** | 143.1 ms |

**18–24 objects × ~5–8 ms of APFS metadata fsync each = 100–125 ms.** That is
the whole of the 123 ms, and it independently reproduces the benchmark's own
"flat in database size" observation (122.8 ms at 20,514 datoms → 127.7 ms at
155,724) on a store this investigation built from scratch. With history off the
same commit writes 4 objects and takes 38.8 ms.

Nothing in Seon, and nothing in the maintained forks, adds a single fsync. The
cost is `objects-per-commit × cost-of-one-durable-object-write`, and both
factors are library defaults.

## Prior measured numbers — the table that dissolves the discrepancy

| date | source | what was measured | number | callers | commits |
|---|---|---|---|---|---|
| 2026-07-25 | `measurements-2026-07-25.md` §7.1 run A | file store, 200 single-datom txs | **45.09 ms/tx** (22 tx/s) | 1 | 200 |
| 2026-07-25 | §7.1 run A | same work, concurrent | **0.53 ms/tx** (1,887 tx/s) | 200 | — |
| 2026-07-25 | §7.1 run B | swept curve | 30.25 / 6.73 / 2.04 / **0.73** ms/tx | 1 / 10 / 50 / 200 | — |
| 2026-07-25 | §16.1 | fixed 16,384 txs | 193.71 → **8,450.04 tx/s** | 200 → 16,384 | 161 → **4** |
| 2026-07-25 | §16.1 | fixed 131,072 txs, the knee | **4,336.40 tx/s** | 65,536 | **9** |
| 2026-07-25 | §16.2 | the cost centre | fsync p50 **8.14 ms**, 38,580 forces / 60 s | — | — |
| 2026-07-28 | `flow-mechanics-2026-07-28.md` | `:file` transact of 8 MB | **74–88 ms**; 64 KB ~38 ms | 1 | 1 |
| 2026-07-31 | `perf-benchmark-2026-07-31.md` row 13 | small commit, live-forked branch | **122.8 ms** (8 tx/s) | 1 | 1 |
| 2026-07-31 | row 15 | batched | 560 ms at 1,000 rows = **2,000 rows/s** | 1 | 1 |
| 2026-07-31 | this document | virgin store, 161k datoms | **125.1 ms**, 24 objects | 1 | 1 |

Read down the "callers" column: every "thousands" row is a many-caller row, and
every one-caller row is 30–125 ms. The serial figure drifted from 45 ms to
123 ms because the measured store grew from a fresh benchmark database to a
fork of the live cluster branch carrying 171 attributes and 155k datoms — which
this document reproduces as an object-count effect (7 → 24 objects), not a code
change.

**`rows/s` vs `tx/s` is the third unit in play.** Row 15's 2,000 rows/s is one
serial caller amortizing one commit over 1,000 rows. It is the same mechanism as
coalescing (more work per fsync storm), reached by batching at the caller
instead of by concurrency at the writer.

## The "thousands" reproduced on today's tree

Same store, same commit shape, same code — only the number of concurrent
callers changes (`tmp/perf-fsync/probe2.clj`, 1,024 five-datom transactions
per rung, virtual-thread callers against one connection):

| concurrent callers | wall | tx/s | vs serial |
|---:|---:|---:|---:|
| 1 | 98,173 ms | **10** | 1× |
| 4 | 72,946 ms | 14 | 1.4× |
| 16 | 28,477 ms | 36 | 3.6× |
| 64 | 10,669 ms | 96 | 9.6× |
| 256 | 2,942 ms | 348 | 35× |
| 1,024 | 693 ms | **1,477** | **148×** |

The mechanism is Datahike's own two-stage writer: a serial processing loop
threads `db-before → db-after` and pushes onto a commit queue; the commit
thread drains **everything queued** as one batch (`writer.cljc:204-267`,
the `(repeatedly #(poll! commit-queue))` at `:211`). Batch size self-tunes
upward with offered load, so the fsync storm is paid once per batch instead of
once per transaction. Nothing about this changed between the eras.

On a fresh small store the serial figure reproduces the July-25 number almost
exactly — **45.2 ms** here against 45.09 ms then — which is the cleanest proof
available that no code regression exists.

**`commit-wait-time` is the one unused dial** (`writer.cljc:83,266`,
`DEFAULT_COMMIT_WAIT_TIME = 0`). Measured on a fresh store:

| `commit-wait-time` | serial commit median | 64 concurrent callers |
|---:|---:|---:|
| 0 (today) | 45.2 ms | 247 tx/s |
| 5 ms | 51.9 ms (+15 %) | **564 tx/s (2.3×)** |

Fifteen percent of serial latency buys 2.3× of concurrent throughput, with
**no durability change whatsoever** — the batch is still fsynced before any
caller's report is delivered.

## Config / fork delta — there isn't one

- **Store options are byte-identical.** Old: `{:backend :file :path path :id
  store-id}` (`src-old/seon/db/backend.clj:175-177`). Fresh: the same three
  keys (`src/seon/cluster/store.clj:164-172`). Neither passes konserve
  `:config`, so both ran on `{:sync-blob? true :in-place? false :lock-blob?
  true}`. `git log -S":sync-blob?" -- src/` returns nothing: Seon has never set
  it.
- **Writer backend is the same** `{:backend :self}`, and
  `DEFAULT_COMMIT_WAIT_TIME = 0` (`datahike/writer.cljc:83`) in both eras.
- **The old system merely EXPOSED dials the fresh one dropped.**
  `src-old/seon/db/backend.clj:179-191` translated
  `:seon.config.database.writer/{transaction-queue-size,commit-queue-size,commit-wait-time-ms}`
  into Datahike's writer config; the fresh `datahike-configuration` has no such
  seam. They defaulted to unset then too, so this is a lost knob, not a
  regression.
- **The `flock` forces no syncs.** It is one `FileLock` held for the process
  lifetime on a `.lock` file beside the store; it is never re-acquired per
  commit (`src/seon/cluster/store.clj`, flock commentary).
- **No sync-behaviour commit exists in either fork.** `git log` over
  `konserve/src/konserve/filestore.clj` + `impl/defaults.cljc` and
  `datahike/src/datahike/{writer,writing}.cljc` shows read-path, tiered,
  multi-assoc, GC and lifecycle work — nothing that changed when or how often
  a blob is forced.

## Is 8 tx/s "normal"?

For durable-per-commit semantics on APFS, **yes, given this object count**. One
fsync of a newly created file is ~8 ms here; a commit that must durably land 18
of them cannot beat ~145 ms without either writing fewer objects or fsyncing
less often. Nothing pathological is happening — no whole-index snapshot, no
re-write of the database, no repeated `.lock` churn: the object count is
`depth+1` per index, exactly what the persistent-tree design implies, and it
grows only logarithmically (7 → 24 objects over three orders of magnitude of
datoms).

What IS worth calling pathological is that Seon pays this per *transaction*
while its own architecture already has the two amortizers — the writer's
coalescing and per-caller batching — switched off by default, and while the
maintained Datahike fork already ships three options that cut the object count
by 9× and that nobody has turned on.

## Ranked fixes — every row measured

All measured on freshly built ~21,000-datom stores, 5-datom commits, one serial
caller, n=25 (`tmp/perf-fsync/probe3.clj`, `probe4.clj`). "objects" is the net
new `.ksv` count for one commit.

| # | change | objects | median | tx/s | gain | durability traded |
|---|---|---:|---:|---:|---:|---|
| — | today's default | 18 | 99.9 ms | 10 | 1× | — |
| **1** | `:fuse-index-roots? true` + `:index-config {:diff-buf-size 256}` | 1 | **19.0 ms** | 53 | **5.3×** | **NOTHING** |
| **2** | `commit-wait-time 5` (concurrent load only) | — | 51.9 ms serial | 564 @64 | **2.3× concurrent** | **NOTHING** |
| 3 | #1 + konserve `:in-place? true :no-backup? true` | 1 | 10.0 ms | 100 | 10× | a torn node file instead of an intact old one |
| 4 | `:fuse-index-roots?` alone | 12 | 67.0 ms | 15 | 1.5× | nothing |
| 5 | `:diff-buf-size 256` alone | 7 | 44.8 ms | 22 | 2.2× | nothing (cold range scans ~2× node work) |
| 6 | `:keep-history? false` | 9 | 54.8 ms | 18 | 1.8× | all time travel |
| 7 | `:in-place? true :no-backup? true` alone | 18 | 48.4 ms | 21 | 2.1× | torn node file |
| 8 | #1 + `:commit-graph? false` | 2 | 10.1 ms | 99 | 9.9× | **INADMISSIBLE — see below** |
| 9 | #8 + `:keep-history? false` | 0 | 9.0 ms | 111 | 11× | inadmissible + all time travel |
| 10 | konserve `:sync-blob? false` | 18 | 4.9 ms | 204 | **20×** | **the crash model** — see below |

### Recommended: rows 1 and 2, together

`:fuse-index-roots?` inlines each index's root node into the db record
(removing one object per index per commit) and `:diff-buf-size` records a small
commit's change as a compact diff inside the nearest ancestor that must be
rewritten anyway, instead of rewriting the whole root-to-leaf path
(`datahike/doc/write-amplification.md`). Both are pure representation changes:
every object still gets its full fsync, the branch head still lands last, and
the crash model is untouched. **5.3× for nothing.**

Upstream's own doc says these options "on a local filesystem they change little
and can be left off." **That is falsified here.** It is true when an extra
object is a cheap buffered write; it is false on a store where every object
costs an ~8 ms APFS metadata fsync. Object count is the whole cost function on
this backend too, and the doc's own closing advice — measure against a
representative workload — is what produced the table above.

Adding `commit-wait-time 5` costs 15 % of serial latency and multiplies
concurrent throughput 2.3×. Both dials belong in
`seon.cluster.store/datahike-configuration`, which currently hard-codes a
three-key store map and an empty `{:backend :self}` writer map.

### Why `:commit-graph? false` is inadmissible for Seon

It is the fastest single option in the table, and Seon cannot have it. **A new
cluster forks its exact published `current-src` commit ID** — that is the boot
tower's third rung and the reason a fork is 17 ms instead of a re-index.
`:commit-graph? false` explicitly gives up branching from a bare commit id
(`write-amplification.md`, "Behaviour"). Row 8's 9.9× is 4.6× more than row 1
buys, and it costs the cluster model. Do not take it.

### Why `:sync-blob? false` is the wrong 20×

It is the largest number in the table and it deletes the durability the whole
crash model rests on. Seon's recovery rule is *nothing re-executes*: reopen the
store, mark dangling receipts `:interrupted`, re-derive. That is only sound if a
committed fact is actually on the disk when `transact!` returns. With
`:sync-blob? false` the crash window is the OS page cache — an unclean shutdown
can lose commits the runtime already told agents were durable, and worse, can
lose them **out of order**, leaving a branch head pointing at index nodes that
were never written. That is exactly the "torn batch leaves a head pointing at
values that were never written" case `writing.cljc:502-511` is written to
prevent. It is a legitimate dial for a scratch or benchmark cluster and must
never be the default.

### Not recommended, and why

- **`:in-place? true`** (rows 3, 7) is a real 2.1× and a real trade: konserve's
  write-new-then-`ATOMIC_MOVE` is what makes a single blob's replacement
  all-or-nothing. Most of Seon's objects are content-addressed and never
  overwritten, so the exposure is narrow — but "narrow" is not "none", and
  row 1 gets most of the win for free. Revisit only if 19 ms is still too slow.
- **A different konserve backend.** `konserve.tiered` (memory frontend +
  file backend, `:write-behind`) would return in memory-store time and fsync
  asynchronously — the same durability loss as `:sync-blob? false` with more
  moving parts. A JDBC/SQLite backend would replace 18 fsyncs with one WAL
  append and is the only structurally different answer, but it is a backend
  migration, not a dial, and should be considered only after row 1 lands.
- **Batching at the caller.** Already available and already measured (2,000
  rows/s at 1,000 rows/tx). It is the right answer for bulk publication
  (`bin/seon init`) and the wrong answer for a per-fact runtime write.

## What this changes about the benchmark's row 13

Row 13 should not read "8 tx/s — the one real ceiling today". It should read:

> **One durable commit costs ~123 ms**, of which ~99 % is 18–24 sequential
> APFS metadata fsyncs, one per index object. Throughput is therefore whatever
> can be amortized into one commit: 10 tx/s at one caller, 1,477 tx/s at 1,024,
> 2,000 rows/s batched. Untouched configuration is leaving ~5× on the table.

## Caveats

- One developer laptop (M5 Max, macOS 26.5.2, APFS), not sole-tenant: another
  lane's JVM was at ~350 % CPU during part of the run. The default-config
  control (99.9 ms) reproduces the benchmark's 122.8 ms at a smaller store and
  the July-25 45.09 ms at a fresh one, so the comparisons are internally sound;
  absolute numbers are pessimistic.
- `:diff-buf-size` and `:fuse-index-roots?` shape the on-disk representation and
  are **fixed at database creation**, adopted from the store on reconnect. An
  existing process-root store cannot adopt them; landing row 1 means creating
  the store with them (a republish of `current-src` and a refork, which is the
  ordinary `bin/seon init` path), not a config edit against live data.
- `:diff-buf-size` needs persistent-sorted-set ≥ 0.4.126, and ≥ 0.4.137 for
  correct concurrent reads. Verify the pin before landing.
- Object counts are net new `.ksv` files; an overwritten key (the branch head)
  does not increase the count. The latency column is the ground truth.
- Probes: `tmp/perf-fsync/probe.clj` (fsync pricing, konserve assoc),
  `probe2.clj` (growth curve, coalescing, `commit-wait-time`), `probe3.clj` +
  `probe4.clj` (the fix table). Raw output beside them in `*.out`.

## Follow-on

Row 1 is a bounded change to one function
(`seon.cluster.store/datahike-configuration`) plus a store rebuild, and it is
worth an issue: the dials exist, the fork already implements them, and nothing
in Seon's model is traded for the 5×.
