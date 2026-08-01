---
type: research
status: active
tags: [research, sci, database]
---

# Admission caps and the missing blob fallback

Owner directive (2026-08-01): the eval-result admission caps must never
impede normal agent work. A 189-row query result silently becoming 64 rows
is not acceptable. Caps should sit at "you are doing something stupid"
levels and fire as a loud, serious signal; agents doing real work
occasionally need to move a lot of data, and the transport law says the
answer is a blob.

This report measures where serious cost actually starts, recommends cap
numbers derived from those measurements, designs the blob fallback, and
answers the owner's two direct questions.

Every number below comes from a script committed under
`tmp/admission-caps-2026-08-01/`, run in a fresh `clojure -M:dev` JVM
(no live cluster touched). Scripts:

| Script | What it measures |
|---|---|
| `caps_probe.clj` | wide collections, long strings, deep nesting, node axis (sections A–D) |
| `caps_probe2.clj` | guarded deep nesting (finds the stack cliff), node axis, proposed-cap comparison |
| `caps_probe3.clj` | source listings, `d/q` tuple shapes, wide rows vs proposed caps, `pr-str`/`read-string` round trip |
| `alloc_attribution.clj` | admission walk vs a plain `postwalk` rebuild vs `pr-str` alone |
| `inst_hypothesis.clj` | isolates the `clojure.core/inst?` cost (see §1.5) |
| `transact_cost.clj` | Datahike `:file` transact cost vs `result-edn` size; konserve `bassoc` control |
| `index_pollution.clj` | store growth and query cost, inline `result-edn` vs a digest |

Hardware: this machine, JDK 26, `-Xmx4g` for the probes. Note that the
shipped `:dev` and `:test` aliases run `-Xmx512m`
(`deps.edn:82`, `deps.edn:99`) — that heap is a constraint the numbers
below have to respect.

## 0. What exists today

- Caps are config facts: `config/default.edn:20-26` — `max-depth 12`,
  `max-collection 64`, `max-string 4096`, `max-nodes 4096`.
- Declared as dials in `resources/seon/schema/admit.edn:5-8`.
- Applied by the one bounded walk, `src/seon/sci/admit.clj` — `admit` at
  `src/seon/sci/admit.clj:349-393`, called inside the armed boundary from
  `src/seon/sci/eval.clj:1050-1058`.
- The receipt stores one terminal string: `:seon.cluster.eval/result-edn`
  (`resources/seon/schema/run.edn:33`), asserted by `receipt-settle-tx`
  (`src/seon/cluster/run.cljc:514-538`) via `terminal-tx`
  (`src/seon/cluster/loop.cljc:242-289`).
- There is no blob path anywhere in `src/`. Confirmed: every `blob` hit in
  `src/` and `resources/seon/` is a comment. The deferral is recorded
  explicitly at `resources/seon/schema/context.edn:29-34` — *"Ruled
  blob-backed; the blob archive is quarry order Q1 and does not exist in
  the fresh tree yet."*

## 1. Measured knees

### 1.1 The cost model: nodes, not shapes

Across every shape measured, the full (uncapped) projection costs a
constant per node:

- **≈ 1.4 µs of wall time per projected node**
- **≈ 4.3 KB of thread-allocated bytes per projected node**
- **≈ 10 characters of `result-edn` per node** (mixed maps and scalars)

Derivation, from `caps_probe3.clj` §G (a vector of `n` four-key maps;
each row is 9 nodes — 1 map + 4 keys + 4 values — plus 1 root):

| Rows | Nodes | Full admit | Allocated | `result-edn` | Est. tokens |
|---:|---:|---:|---:|---:|---:|
| 189 | 1,702 | 2.64 ms | 7.4 MB | 19,705 ch | 4,926 |
| 1,000 | 9,001 | 13.13 ms | 39.0 MB | 105,671 ch | 26,417 |
| 4,096 | 36,865 | 52.30 ms | 159.7 MB | 443,135 ch | 110,783 |
| 10,000 | 90,001 | 126.92 ms | 389.4 MB | 1,086,671 ch | 271,667 |
| 100,000 | 900,001 | 1,259.70 ms | 3,908.2 MB | 11,166,671 ch | 2,791,667 |

The knees follow directly:

- **> 10 ms** at roughly **7,000 nodes** (≈ 800 four-key rows).
- **> 100 ms** at roughly **70,000 nodes** (≈ 8,000 four-key rows).
- **Heap pressure on the shipped `-Xmx512m`**: 10,000 rows churns 389 MB
  of allocation in one walk; 100,000 rows churns 3.9 GB. Above ~100,000
  nodes a single admission is a GC event on the shipped heap.
- **Threatens a `:compute` thread**: admission runs on `:compute`
  (`src/seon/sci/eval.clj:1050`, inside the armed boundary). A
  `:compute` thread must never block, and a 1.3-second walk is
  functionally a block. The 100 ms knee (~70,000 nodes) is the honest
  ceiling for a bounded compute pool sized to cores.

`d/q` tuple results (`caps_probe3.clj` §F) obey the same per-node model:
1,000 three-element tuples (4,001 nodes) = 15.29 ms, 48.8 MB.

### 1.2 Strings

From `caps_probe.clj` §B — a single string node, projected whole:

| Chars | Full admit | Allocated | Est. tokens |
|---:|---:|---:|---:|
| 4,096 | 0.03 ms | 21 KB | 1,024 |
| 100,000 | 0.77 ms | 394 KB | 25,000 |
| 1,000,000 | 7.83 ms | 3.3 MB | 250,000 |
| 10,000,000 | 76.54 ms | 46.6 MB | 2,500,000 |

Strings are **cheap per character** (≈ 7.7 ns/1000 chars) and cost one
node. There is no CPU reason for a 4,096-character cap. The measured
consequence of the current cap on real work (`caps_probe3.clj` §E):

- `src/seon/sci/admit.clj` as a string (16,927 ch) → capped to 4,271 ch
  (**75% of the file discarded**).
- `src/seon/sci/eval.clj` as a string (49,392 ch) → capped to 4,225 ch
  (**91% discarded**), full projection cost 1.18 ms.

Reading a source file today is mutilated by a cap whose whole cost would
have been one millisecond.

### 1.3 Depth — the stack cliff

`caps_probe2.clj` §C, each depth run on its own thread so the overflow
does not abort the JVM:

| Depth | Full admit | Allocated | Result |
|---:|---:|---:|---|
| 12 | 0.15 ms | 359 KB | ok |
| 64 | 0.43 ms | 1.9 MB | ok |
| 1,000 | 5.98 ms | 29.9 MB | ok |
| 3,000 | 15.36 ms | 89.7 MB | ok |
| 5,000 | — | — | **StackOverflowError** |
| 10,000 | — | — | **StackOverflowError** |

This reproduces the 2026-07-27 provenance quoted in
`config/default.edn:17-19` and sharpens it: the walk survives 3,000 and
dies between 3,000 and 5,000. The current cap of 12 is **250×** below
the cliff. Depth 64 nesting caps today (`caps_probe2.clj` §C, SHIPPED
column: 581 chars → 130 chars, `capped? true`).

### 1.4 What the shipped caps actually do to ordinary results

`caps_probe3.clj` §G, SHIPPED column — every row size from 189 to 100,000
produces the identical 6,483-character `result-edn`, which is the
projection of exactly **64 rows** (a 64-row full projection is 6,563
chars, `caps_probe.clj` §A). The owner's complaint is exact and general:
`max-collection 64` truncates every collection to 64 elements, at every
size, and the capped walk costs 0.8 ms — the caps are not buying time,
they are only discarding data.

### 1.5 A defect found while measuring: the walk allocates 26× what it should

`alloc_attribution.clj`, on the 1,000-row / 9,001-node value:

| Operation | Wall | Bytes/node |
|---|---:|---:|
| `admit` (walk + `pr-str`) | 9.59 ms | 4,435 |
| `clojure.walk/postwalk identity` rebuild | 0.50 ms | 172 |
| `pr-str` alone | 0.88 ms | 254 |
| the `interrupt-fn` calls alone | 0.11 ms | 0 |

`admit` is **7× slower than a rebuild plus a print combined** and
allocates **26× a plain rebuild**. `inst_hypothesis.clj` isolates the
cause. `src/seon/sci/admit.clj:249` tests `(inst? value)` in the
`project-node` `cond`, and `clojure.core/inst?` is
`(satisfies? Inst x)` — a protocol lookup, not an `instance?` check. It
runs on every node that is not nil/boolean/number/keyword/symbol/char/uuid,
i.e. **every string, map, vector, set and record**:

| Call, ×100,000 | Wall | Bytes/call |
|---|---:|---:|
| `(inst? {:a 1})` | 436 ms | **29,360** |
| `(inst? [1 2 3])` | 603 ms | **40,752** |
| `(inst? "abc")` | 106 ms | **7,128** |
| `(inst? <a Date>)` | 1.4 ms | 24 |
| `(instance? java.util.Date {:a 1})` control | 0.17 ms | 0 |
| `(number? {:a 1})` control | 0.10 ms | 0 |

`satisfies?` on a class that does not participate in the protocol walks
the protocol's `:impls` map and allocates tens of kilobytes per miss.
This is the entire 26× gap.

**The fix is one line**: the `inst?` arm exists only to normalize a
non-`Date` inst into a readable `java.util.Date`
(`src/seon/sci/admit.clj:245-252`). Reordering to test
`(instance? java.util.Date value)` first — which is free and covers the
common case — and only falling through to `inst?` for the rare
non-`Date` inst removes the per-node protocol lookup entirely. Expect
the per-node cost to drop toward the `postwalk` baseline, which moves
every knee in §1.1 outward by roughly an order of magnitude in
allocation and several-fold in time.

This should be filed as an issue and fixed before the cap numbers are
raised — raising caps over a walk that allocates 4.3 KB per node makes
the allocation problem an order of magnitude worse. It is not a blocker
for the cap change, but it is the cheapest performance win in this
report.

### 1.6 The transaction cost — and the number that decides everything

`transact_cost.clj`, one receipt datom per transact against a real
`:file`-backend Datahike store (the same configuration
`src/seon/cluster/store.clj:155-176` builds):

| `result-edn` | Transact (best of 5) |
|---:|---:|
| 100 ch | 43.96 ms |
| 1,000 ch | 42.99 ms |
| 10,000 ch | 44.91 ms |
| 100,000 ch | 38.19 ms |
| 1,000,000 ch | 51.61 ms |
| 8,000,000 ch | 104.29 ms |

A file-store transact has a **~43 ms floor independent of payload**.
Payload only becomes visible above ~1 MB. Naively this says "inline
everything, it's free" — and that conclusion is wrong, because the write
time is not the cost.

`index_pollution.clj` builds six identical stores, 40 receipts each,
differing only in the `result-edn` payload:

| `result-edn` per receipt | Store on disk | Per receipt | Amplification | Ordinal query |
|---:|---:|---:|---:|---:|
| 64 ch (a digest) | 0.58 MB | 14.5 KB | — | 0.010 ms |
| 4,096 ch (today's cap) | 13.20 MB | 330 KB | **≈ 80×** | 0.012 ms |
| 65,536 ch | 205.39 MB | 5.1 MB | **≈ 80×** | 0.017 ms |
| 262,144 ch | 820.42 MB | 20.5 MB | **≈ 80×** | 0.011 ms |
| 1,048,576 ch | 3,280.57 MB | 82 MB | **≈ 80×** | 0.009 ms |
| 2,000,000 ch | 6,256.87 MB | 156 MB | **≈ 80×** | 0.011 ms |

**Forty receipts carrying 2 MB each produced a 6.3 GB store.** Query time
is unaffected (the string is never read by the ordinal query), so this is
purely write amplification: every subsequent transact rewrites the
konserve index nodes that hold the earlier strings, across three indexes,
with no reclamation. The ratio is roughly linear in payload here because
all 40 receipts land in the same index region; it scales with how many
large-payload receipts share a node, not with a fixed constant.

The control from the same script's sibling, `transact_cost.clj` §2 —
the same bytes through konserve's binary API, which the fresh tree opens
but never uses:

| Payload | `k/bassoc` | Throughput |
|---:|---:|---:|
| 100,000 B | 7.99 ms | 11.9 MB/s |
| 1,000,000 B | 8.04 ms | 118.6 MB/s |
| 8,000,000 B | 10.01 ms | 762.2 MB/s |

**An 8 MB blob write costs 10 ms and is written once. The same 8 MB
inline costs 104 ms to transact and then costs ~80× its own size in
permanent store growth.** That single comparison is the whole argument
for the blob fallback, and it is the reason caps alone cannot be the
answer: with no blob path, every character admitted into a receipt is
paid for roughly eighty times over, forever.

### 1.7 Round trip

`caps_probe3.clj` §H — reading a settled `result-edn` back (which
`src/seon/cluster/work.cljc:158-162` does on every receipt read):

| `result-edn` | `pr-str` | `read-string` |
|---:|---:|---:|
| 1,002 ch | 0.02 ms | 0.01 ms |
| 100,002 ch | 2.29 ms | 0.59 ms |
| 1,000,002 ch | 23.74 ms | 5.96 ms |
| 8,000,002 ch | 190.06 ms | 47.82 ms |

Round-tripping is not a constraint below ~1 MB.

## 2. Recommended cap strategy

### 2.1 The principle the current design is missing

The four caps are currently doing two incompatible jobs: bounding the
**shape** of the walk (so it terminates, does not overflow the stack, and
does not monopolize a `:compute` thread) and bounding the **size** of what
gets committed (so the store does not explode). They are set for the
second job, which is why they mutilate the first.

Separate them:

- **The four caps bound the walk.** Set them where a runaway structure is
  genuinely implied — "you are doing something stupid" levels.
- **The serialized size decides the blob.** Measured after the walk, not
  predicted before it. This is the transport law applied literally.

With that separation the caps can be generous, because generosity no
longer costs 80× store growth.

### 2.2 Recommended defaults

| Dial | Today | Recommended | Measured justification |
|---|---:|---:|---|
| `max-nodes` | 4,096 | **65,536** | §1.1: ≈ 1.4 µs/node → 92 ms worst case, at the measured 100 ms `:compute` knee. ≈ 4.3 KB/node → ~280 MB churn, survivable on the shipped `-Xmx512m` (and far less once §1.5 is fixed). A 4,096-row four-key query is 36,865 nodes and passes uncapped. |
| `max-collection` | 64 | **8,192** | §1.4: 64 silently truncates every real query. 189, 1,000 and 4,096-row results all pass uncapped at 8,192; a collection above 8,192 elements in one eval result is an unbounded query. **Requires the decoupling in §2.4.** |
| `max-depth` | 12 | **64** | §1.3: the walk dies between depth 3,000 and 5,000. 64 is ~50× below the cliff (0.43 ms, 1.9 MB there) and far above any legitimate nested value; 12 caps ordinary structures today. |
| `max-string` | 4,096 | **262,144** | §1.2: a 100,000-char string costs 0.77 ms and 394 KB. The largest first-party source file is ~50,000 chars, so 256 KB gives 5× headroom for source listings and full docstrings; 4,096 discards 91% of `eval.clj`. A string above 256 KB is a file read that belongs in a blob. |

Cross-check, `caps_probe2.clj`/`caps_probe3.clj` PROPOSED columns
(measured with `max-nodes 262144`, i.e. deliberately looser than the
65,536 recommended above, to show the shape of the trade): 189 rows,
1,000 rows and 4,096 rows all report `capped? false`; the full
`src/seon/sci/eval.clj` source string projects uncapped at 1.18 ms;
10,000 rows caps. At the recommended 65,536 the cap fires around 7,300
four-key rows, which is the intended "unbounded query" signal.

### 2.3 Should `max-nodes` stay the master dial?

**Yes for the walk, no for the transaction.**

`max-nodes` is the correct master for CPU, allocation and stack safety:
it is the only dial that bounds total work regardless of shape, and every
knee in §1.1 is a node count. Keep it as the governing dial and treat
depth/collection/string as shape refinements, exactly as
`config/default.edn:17-19` already says.

But `max-nodes` does **not** bound characters, and it never did. At
today's caps the worst case is `max-nodes × max-string` = 4,096 × 4,096 =
**16 MB of `result-edn`**, already committable, already costing ~80× in
store growth. At the recommended caps it is 65,536 × 262,144 = 16 GB.
Tightening the caps to close that hole would mean returning to numbers
that mutilate ordinary results. **The serialized size must be its own
governor, and the blob fallback is that governor** — a fifth number,
measured on the produced `result-edn`, not a fifth walk cap.

Recommended threshold, from §1.6: **route to a blob above 65,536
characters of `result-edn`**. Below that a receipt costs ≤ 5 MB of store
growth and the transact is at its 43 ms floor either way, so a blob buys
nothing. Above it the amplification is what dominates, and a `bassoc`
costs 8–10 ms.

### 2.4 One coupling that must be broken first

`src/seon/render/value.cljc:151` passes
`:seon.config.eval.result/max-collection` as the **page size** of the
routed drill window (`opened-window`, `src/seon/render/value.cljc:78-129`),
and `pager` (`:389-409`) sizes previous/next windows from the same dial.
Raising `max-collection` to 8,192 would make the web page render 8,192
items per window.

These are two different concerns wearing one dial: how much the admission
walk may realize, and how much a reader sees per page. The presentation
caps already have their own home —
`resources/seon/schema/render_value.edn:8-39` declares depth 3,
collection 8, map-visits 32, string 80, shape-sample 8, width 72. The
page size belongs there. Move it before raising `max-collection`.

### 2.5 The warning — derived, and currently thrown away

`seon.sci.admit/admit` already computes the signal:
`:seon.sci.admit/capped?` (`src/seon/sci/admit.clj:388`,
`resources/seon/schema/admit.edn:21`), and `seon.sci.eval/evaluate`
carries it out at `src/seon/sci/eval.clj:1065` and `:1087`.

**It is then structurally dropped.** `:seon.cluster.loop/evaluation`
(`resources/seon/schema/loop.edn:30`) is `:map {:closed true}` and has no
`:seon.sci.admit/capped?` key; `terminal-tx`
(`src/seon/cluster/loop.cljc:242-289`) never reads it; the receipt schema
(`resources/seon/schema/run.edn:25-40`) has no place for it. So today
**an agent whose 189-row query became 64 rows is told nothing at all** —
not a warning, not a marker in the transcript, nothing beyond the
`::admit/elided` scalar buried inside the printed value. That is the
actual defect behind the owner's complaint; the cap number is only half
of it.

The fix keeps the signal derived and stores no flag. Once the receipt
carries the blob reference from §3 it also carries the full serialized
size, and then:

```
capped? = (> :seon.cluster.eval/result-size (count :seon.cluster.eval/result-edn))
```

is a pure function of two facts already on the row. No boolean is stored,
nothing can drift, and the answer survives a restart — which the current
in-flight `capped?` does not.

Two consumers, both of them, for different audiences:

1. **The transcript / AI projection — always.**
   `seon.render.value/render-ai-data` already appends
   `" (elided — this value is larger than the configured caps)"`
   (`src/seon/render/value.cljc:227-228`) and the HTML twin already emits
   a `seon-data-capped` node (`:437-442`). They just need the fact to
   reach them. With the blob reference present this becomes strictly more
   useful: the marker can name the real size and the drill handle, so the
   agent's next move is obvious rather than invisible.

2. **A problems row — only when the cap says "runaway".**
   `seon.cluster.work` already owns problem identity
   (`src/seon/cluster/work.cljc:138-142`) and owner routing
   (`form-owner`, `:203-225`). Which cap fired is itself derivable from
   the receipt: a `result-size` far above the node budget's reach means
   the **node** cap fired, which is the "unbounded query or runaway
   structure" case and deserves a loud, owner-routed row. A string cap
   firing on a 300 KB file read is not a problem and should stay a
   transcript marker. Deriving the distinction from the sizes keeps this
   a computed rule rather than a hand list of cap names.

## 3. The blob fallback — design proposal

### 3.1 Ground

- **Transport law**, `docs/seon/architecture/architecture.md:411-413`:
  *"Anything recovery or another process could ever need is a database
  fact… with bulky payloads as blobs whose row carries identity, digest,
  and size."*
- **Size, not kind**, `docs/seon/architecture/observability.md:91-93`:
  *"Datom vs blob is decided by size, never by kind. The DB stores
  projections and small values; large text — a prompt, a raw reply, a big
  eval result — lives in the blob archive behind a datom ref. The DB is
  never a text dump."*
- **konserve already implements binary storage.** `PBinaryKeyValueStore`
  at `reference-code/konserve/src/konserve/protocols.cljc:41-44`
  (`-bget`, `-bassoc`); public wrappers `bget`
  (`reference-code/konserve/src/konserve/core.cljc:633-655`) and `bassoc`
  (`:657-672`). The default backing-store implementation carries it
  (`reference-code/konserve/src/konserve/impl/defaults.cljc:580`), which
  is what `konserve.filestore/connect-fs-store` builds on — **the exact
  store Seon already opens** at `src/seon/cluster/store.clj:155-176`
  (`:backend :file`). Nothing new needs vendoring, configuring or mounting.
- **konserve also ships GC**:
  `reference-code/konserve/src/konserve/gc.cljc` — `(sweep! store
  whitelist ts batch-size)`, a whitelist + timestamp reachability sweep.
  The quarry's blob store had **no** GC (`src-old/my/blob.cljc:340`:
  *"The source chunks stay stored (append-only, no GC)"*). Building on
  konserve inherits a reclamation path the old design never had.
- **Datahike has no blob tier.** `bassoc`/`bget`/`PBinaryKeyValueStore`
  appear nowhere in `reference-code/datahike/src/`. It has a
  `:db.type/bytes` *value* type
  (`reference-code/datahike/src/datahike/schema.cljc:18-20`), but that
  puts bytes straight into the index — precisely the 80× amplification
  measured in §1.6. `:db.type/bytes` is the wrong tool.
- **The quarry's design, for lessons not for porting**: `my.blob`
  (`src-old/my/blob.cljc`, 439 lines) + `core.cljc` + `host.clj` +
  `schema.cljc`. SHA-256 content addressing
  (`src-old/my/blob/core.cljc:6-14`), a 2-char sharded filesystem layout
  (`:26-29`), temp-file + fsync + atomic move
  (`src-old/my/blob/host.clj:121-153`), digest re-verification on every
  read (`:93-110`), and a four-attribute database row —
  `::hash` (identity), `::tokens`, `::media`, `::at`
  (`src-old/my/blob.cljc:26-29`). **Do not port the host leaf**: it
  hand-rolled `java.nio` for what konserve's `bassoc`/`bget` already
  does, and it was string-only despite a docstring promising images and
  audio (`src-old/my/blob/host.clj:96,141` vs
  `src-old/my/blob.cljc:380-383`). Keep the ideas — content addressing,
  digest verification, a compact row — and let konserve own the bytes.

### 3.2 The one mechanism

One namespace, `seon.blob`, over the cluster's already-open konserve
store:

- `put!` — takes bytes (or a string), computes the SHA-256 digest using
  the digest idiom already in `src/` (`src/seon/fn.clj:75-82`,
  `src/seon/cluster.clj:447-613`), calls `k/bassoc` under that digest,
  returns `{digest, size}`. Idempotent by construction: same content,
  same key.
- `get` — `k/bget` by digest, verifying the digest on read (the quarry's
  one genuinely good invariant).

Two receipt attributes, an accretion on
`resources/seon/schema/run.edn` — nothing existing changes meaning:

- `:seon.cluster.eval/result-blob` — the digest string, the blob
  reference.
- `:seon.cluster.eval/result-size` — the full serialized character count.

Together with the existing `:seon.cluster.eval/result-edn` this is
exactly `architecture.md:411-413`'s "identity, digest, and size", and it
makes `capped?` derivable per §2.5. Presence is the state, as everywhere
else in the receipt (`resources/seon/schema/run.edn:36-40`): a receipt
with no `result-blob` had nothing bulky.

### 3.3 Where the write happens, and what it writes

`seon.sci.admit/admit` must **not** do the blob write. Its docstring is
explicit — *"Admission opens no resources and writes no durable state"*
(`src/seon/sci/admit.clj:15-16`) — and it runs on `:compute`, where
blocking is forbidden. A `bassoc` is blocking I/O.

The write belongs at the settle seam, on `:io`, before the transact —
i.e. in the caller that builds `terminal-tx`'s request
(`src/seon/cluster/loop.cljc:242-289`).

The subtle question is *what* gets blobbed, because serializing the raw
object whole is the unbounded cost the caps exist to prevent. The answer
is already in the tree: **the blob carries the admitted projection at the
generous caps; the receipt's `result-edn` carries a display window of
it.** `seon.render.value/opened-window`
(`src/seon/render/value.cljc:78-129`) already computes exactly such a
window, from an already-admitted value, without a second safety codec —
its namespace docstring says so at `src/seon/render/value.cljc:1-15`.

So the flow is one walk, two destinations:

1. `admit` walks once at the §2.2 caps and produces the bounded
   projection and its `result-edn` — unchanged from today.
2. If `(count result-edn)` exceeds the §2.3 threshold (65,536 chars),
   the settle seam writes that string as a blob and puts a window of the
   projection in the receipt's `result-edn`, plus `result-blob` and
   `result-size`.
3. Otherwise the receipt is exactly what it is today.

The caps still bound the walk; nothing unbounded is ever serialized; and
the receipt row stays small in every case.

### 3.4 The drill handle

The print floor already has the affordance. `path-url`
(`src/seon/render/value.cljc:54-59`) builds
`<route-base>?path=<pr-str path>&offset=N`, with `breadcrumbs`
(`:378-388`) and `pager` (`:389-409`). Today that drill re-windows a
value that lives in the render unit. With a blob reference on the
receipt, the same route can `bget` the blob and window it — the drill
becomes durable and survives the process, which the render-unit version
does not. `summary` (`:185-199`) already prints
`"string · N tokens"` via `seon.ai.tokens/estimate`, so the marker can
state the real size honestly.

### 3.5 Slice 1, and what it costs

Slice 1 is deliberately not agent-facing:

| Piece | Where | Size |
|---|---|---|
| `seon.blob` — `put!`/`get` over `k/bassoc`/`k/bget` on the cluster store | new `src/seon/blob.clj` | ~100 lines |
| `:seon.cluster.eval/result-blob`, `:seon.cluster.eval/result-size` | `resources/seon/schema/run.edn` (accretion) | 2 attributes |
| the size test + blob write at the settle seam | `src/seon/cluster/loop.cljc` caller of `terminal-tx` | ~15 lines |
| derived `capped?` and the loud marker | `src/seon/render/value.cljc` (consumes the two new facts) | ~10 lines |
| one regression: a receipt whose value exceeds the threshold round-trips through the blob | `test/` | one test |

Measured cost at runtime: **8–10 ms of `:io`** for the `bassoc`
(§1.6), against a transact that keeps its ~43 ms floor. Strictly cheaper
than committing the same bytes inline (104 ms for 8 MB) and it removes
the ~80× store amplification entirely.

Explicitly **not** in slice 1: an agent-facing `my.blob` toolkit surface,
`concat!`/`text`/`stat`, media-type hints, retention/materialization, and
the blob-backed cutover of `:seon.context.capture/prompt` that
`resources/seon/schema/context.edn:29-34` already names. Those are
accretions on the same mechanism once it exists, and konserve's
`gc.cljc:8` gives reclamation a home when it is needed.

## 4. The owner's two questions, answered

### "Is this related to the `result/<id>` values?"

**Related, but a different tier — and the `result/<id>` mechanism does
not exist on this tier at all.**

The three-tier rule
(`docs/seon/architecture/observability.md:131-135`) is: datoms are
projections, blobs are persistent full content, and volatile live values
stay in their owning process. `result/<id>` was the **third** tier: a
live var handle to the actual in-process object. It was implemented only
in the deleted CLJS pod — `globalThis.result.<munged-id>`, a reserved
`result` namespace, a 200-entry cap with insertion-order eviction, in
`src/seon/eval.cljs`, deleted whole in commit `fbc6b28b5` (readable via
`git show fbc6b28b5^:src/seon/eval.cljs`). It was **never implemented on
the JVM**: `docs/seon/issues/archive/jvm-result-symbols-not-bound-r32.md:12-15`
records *"`result/<id>` is never bound in SCI on the JVM tier"*, and
`docs/prds/sci-execution-runtime/research/redesign-ledger-2026-07-25.md:497-502`
confirms *"That clause is not implemented anywhere… the JVM never had
it."* Residue survives in the reader
(`src-old/seon/repl/parse.cljc:438-457`, `result-ref-symbol?`) and in
architecture prose (`architecture.md:462-464`,
`observability.md:152-157`), but there is no implementation behind it.

The fresh tree deliberately replaced the symbol handle with a **routed
drill** — `path-url` and the pager in `src/seon/render/value.cljc:54-66,
378-409`, windowing after admission rather than holding a live object.
That is a better fit for the stateless claim-native model: a route is a
database-derived fact, a live var is process state that a crash erases.

So the caps problem is a **tier 1 vs tier 2** problem — the datom
projection is being asked to be the full content because tier 2 does not
exist. `result/<id>` is a separate, tier 3 gap, and the blob fallback
does not depend on it.

Where the two do meet is the drill affordance. The quarry paired them
explicitly (`src-old/seon/render/value.cljc:1156-1170`) — the hint line
offered both `(get-in result/<id> […])` to navigate the live value and
`(my.blob/put! result/<id>)` to make it durable, with the docstring at
`:1195-1198` calling that *"BOTH recovery… AND durability."* §3.4 above
is the fresh-tree form of the durable half: the routed drill reads from
the blob rather than from a live var, so it works after a restart.

### "We need to store everything? I thought we had fallback to blobs for this?"

**The old system had it. The fresh tree has none of it. That is the gap.**

- **The old system had a real blob tier.** `my.blob`
  (`src-old/my/blob.cljc:2-8`): *"Store and retrieve large content by its
  SHA-256 address… the agent-facing disk tier for content too large for
  database datoms."* Five agent-facing functions —
  `put!` (`:286-306`), `get` (`:308-318`), `concat!` (`:330-343`),
  `text` (`:370-386`, paged lines), `stat` (`:425-439`) — over a sharded
  filesystem store with atomic writes and digest verification
  (`src-old/my/blob/host.clj:93-153`), leaving a four-attribute row in
  the database (`src-old/my/blob.cljc:26-29`). Real callers used it:
  web-fetch content (`src-old/seon/agent/web/host.clj:347-348`), turn
  prompt and reply blobs (`src-old/seon/agent/turn.cljc:24-25, 88-89`).

- **The fresh tree has zero blob code.** Every `blob` occurrence in
  `src/` and `resources/seon/` is a comment. The deferral is recorded in
  the schema itself, `resources/seon/schema/context.edn:29-34`: *"Ruled
  blob-backed; the blob archive is quarry order Q1 and does not exist in
  the fresh tree yet, so this ships as a string attribute with the
  blob-ref cutover named as an accretion at that rung."* The receipt
  likewise stores one string, `:seon.cluster.eval/result-edn`
  (`resources/seon/schema/run.edn:33`), and nothing else.

So no — we do not currently have the fallback, and yes, today we do store
everything inline, up to a cap set so low it destroys ordinary results.
The caps became the *only* size mechanism because the tier they were
supposed to hand off to was never built, and so they were tuned as if
they were a storage budget rather than a walk budget.

Two facts make this urgent rather than merely untidy:

1. §1.6 — inline `result-edn` costs roughly **eighty times its own size**
   in permanent store growth, so even today's 4,096-char cap costs
   ~330 KB of store per receipt. The caps are not protecting the store as
   much as their tuning implies.
2. The capability is already open and already paid for: Seon's store is a
   konserve `:file` store (`src/seon/cluster/store.clj:155-176`) whose
   default implementation provides `bassoc`/`bget`
   (`reference-code/konserve/src/konserve/impl/defaults.cljc:580`), at
   **10 ms for 8 MB**. The blob tier is one small namespace and two
   attributes away, not a subsystem.

## 5. Recommended order

1. **Fix `inst?` in `src/seon/sci/admit.clj:249`** (§1.5) — one line, 26×
   less allocation on the hot walk. Do this before raising caps.
2. **Decouple the render page size from `max-collection`** (§2.4) — move
   it to `resources/seon/schema/render_value.edn`.
3. **Land blob slice 1** (§3.5) — `seon.blob`, two receipt attributes,
   the settle-seam write.
4. **Raise the caps** to §2.2's numbers and set the blob threshold to
   65,536 chars of `result-edn`.
5. **Wire the derived warning** (§2.5) — the transcript marker always,
   the problems row when the node cap fired.

Steps 1, 2 and 3 are independent and can run in parallel lanes; 4 depends
on all three; 5 depends on 3.

## Open questions for the owner

- **The blob threshold is a number this report chose from measurement
  (65,536 chars) but did not falsify against a real agent workload.** It
  should be a config dial like the caps, and its first calibration should
  come from observed receipt sizes once slice 1 is live.
- **Blob retention.** Slice 1 writes and never reclaims. konserve's
  `sweep!` (`reference-code/konserve/src/konserve/gc.cljc:8`) gives a
  reachability sweep, and the reachable set is a query over
  `:seon.cluster.eval/result-blob`. Whether that runs, and on what
  trigger, is a design decision this report does not make.
- **The `max-collection` recommendation of 8,192 assumes §2.4 lands
  first.** If the render page size is not decoupled, `max-collection`
  cannot rise past a readable page size, and the node cap alone would
  have to carry the "unbounded query" signal.
