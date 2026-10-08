---
type: research
status: proposed
date: 2026-10-08
lane: storage-roles
---

# Storage roles: cache, blob and datom

Lane `storage-roles`, 2026-10-08. Design and census only; no source changed.

Owner, 2026-10-08: "It should be like every other repl form -- we cache the full result and store the more limited
value printer version. This could get large so I'm also open to using the blob system to store the value printer
version and we just store the ref to it."

What exists: three places hold values. `seon.cache` (one Caffeine cache under `:seon.config.cache/max-bytes`,
`src/seon/cache.clj:53-74`), `seon.blob` (content-addressed UTF-8 or binary files in the store's Konserve backend,
`src/seon/blob.clj:304-334`), and Datahike datoms. Today the full value of an evaluation lives in its SCI context
without a bound, the shown text is a datom, and Seon's own errors store a third thing: the value printer's internal
node tree, serialized, as a blob. The rule below removes that third thing and puts the live value under the cache's
budget.

## 1. The rule

**One value, two stored forms, three places.**

- **Cache** holds the runtime-only full value: a live result, a derived value. Keyed by content or by an immutable
  identity (an evaluation id names one value forever), weighed at insert, evictable. Losing it is never an error: the
  handle then answers that the value is gone. Nothing printed lives here.
- **Datom** holds small queryable facts: ids, digests, sizes, counts, enums, refs, and shown text at or under the
  blob threshold. Every stored attribute names its consumer.
- **Blob** holds durable text over the blob threshold: shown text, captured `:out`, model replies and reasoning,
  external content (files, shell output, web bodies). The datom keeps the digest and the byte size next to where
  the inline text would have been.

What is stored is always either **data** (facts, the agent's own answers, configuration) or **the value printer's
shown text**. Never a second serialization of a live value (`pr-str`, the printer's node tree, a "value artifact").
The full value goes to the cache only.

**Threshold.** Reuse the existing dial `:seon.config.eval.result/blob-threshold`, 4096 bytes on default
(`resources/seon/schemas/seon.config.eval.result.edn`, value read on default 2026-10-08). Model replies already use
it (`src/seon/turn.clj:84-92`), as do test failure texts (`src/seon/test/runner.clj:1426-1434`). One dial, one
comparison in UTF-8 bytes (`seon.blob/utf8-size`), applied by one function at every writer of text.

Why 4096, measured on default 2026-10-08 (JVM pid 60864) from the `storage-roles` agent's own branch:

| Operation | 512 B | 4 KB | 16 KB | 64 KB |
| --- | --- | --- | --- | --- |
| `seon.blob/put!` new content, ms (median of 5) | 8.0 | 8.0 | 8.0 | 8.0 |
| `seon.blob/put!` content already stored, ms | 0.26 | 0.13 | 0.16 | 0.17 |
| `seon.blob/get`, warm, ms (median of 5) | 0.06 | 0.05 | 0.08 | 0.08 |
| `d/transact` of one entity carrying the string, ms (median of 4) | 11.9 | 13.1 | 15.0 | 23.0 |

- A blob write is a flat 8 ms (publication permit, write, read-back verification), whatever the size. A datom
  string costs about 0.17 ms per KB on top of a 12 ms transaction. Write time alone breaks even near 48 KB.
- Disk decides lower. A datom string is not written once: it sits in an index leaf (branching factor 512,
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:498`), and every later commit that touches that
  leaf writes a new copy until the leaf stops changing. Measured: 10 small transactions on the agent branch wrote
  2.0 MB of new node files; one transaction carrying a 64 KB string wrote 0.69 MB; the next 10 small transactions
  wrote 2.17 MB, about 2.5 further copies of that string. A blob is written once and deduplicated by digest.
- Under 4 KB a blob costs a whole file-system block and 8 ms for little saving, and history units that the context
  reads on every model pass stay one datom read. On default, 1 to 2 percent of `:seon.eval/shown` values are over
  4096 (p99 4784 characters), so the 8 ms lands on a few settlements in a hundred.

**Diagnostics follow the same rule.** A later stage's failure attached beside an evaluation's value (printer,
renderer, settlement, a recorded error) is a REPL result like any other: its full error map is bound under a result
handle in the cache, and its value-printer shown text is stored inline under 4096 bytes, as a blob digest above.
The stored copy of an error is "the value printer's shown text plus ids, nothing more" (CLAUDE.md, Separate storage
from observation).

## 2. Census

Sizes are characters of the stored value, measured read-only on default's head (536 evaluations, 2026-10-08) unless
marked as the agent's own branch. "Agent" rows are the `storage-roles` agent's own 31 evaluations through MCP.

### Values of evaluations

| What | Where it lives today | Size | Read how often, by whom |
| --- | --- | --- | --- |
| Live result `result/e…` | Interned in the agent's SCI context, namespace `result` (`src/seon/sci/eval.clj:579-598`), and again in the context's `::result-objects` atom (`src/seon/sci/eval.clj:3053`). No bound: every value the context ever answered stays reachable until the context dies. | Agent: 31 values held after 31 evaluations, including two whole database values | Rarely: when an agent names the handle; `seon.render.walk` continuation (`src/seon/render/walk.clj:812`), `seon.repl` (`src/seon/repl.clj:607`) |
| Handle after restart | Marker map bound per settled evaluation (`src/seon/sci/eval.clj:3018-3036`), from `:seon.eval/value-class` datom | 492 datoms, p50 29 chars | On every fresh fork, one query per agent |
| Shown text `:seon.eval/shown` | Datom string (`resources/seon/schemas/seon.eval.edn:53`) | 536; p50 65, p90 953, p99 4784, max 4800; 220 KB total. Agent: 31; p50 53, max 3198 | Every model pass (context history), every MCP reply, transcript and debug pages: `seon.context`, `seon.mcp`, `seon.render.transcript`, `seon.repl`, `seon.eval.drive` |
| Token counts, elisions, class, timings | Datoms (`seon.eval.edn:35-51`) | integers | Replies and health |
| `:out` `:seon.cluster.eval/output` | Datom string, whole, up to `:seon.config.eval.result/max-bytes` = 8 MB (`src/seon/sci/eval.clj:4144-4163`, `:3860`) | 6; p50 34, max 13 890 | Replies, transcript |
| Diagnostics `:seon.cluster.eval/diagnostics` | Datom string, the floor's printed vector of flat errors (`src/seon/sci/eval.clj:3799-3802`) | 1; 796 | Replies, transcript |
| Error message `:seon.cluster.eval/error` | Datom string | 45; p50 71, max 341 | Replies, health problems |
| Source `:seon.cluster.eval/source` | Datom string, no history | 536; p50 85, max 3324 | Context history, transcript |
| Delimiter repairs | Datom vector of `[line op text]` | 0 on default | Replies, transcript |
| Read evidence | Component entities per evaluation; plan and revision are canonical EDN strings (`seon.schema.datahike` codec, `src/seon/schema/datahike.clj:1125-1140`) | 1812 rows; plan p50 396 max 14 167; revision p50 290 max 16 483; 1.5 MB total, about 2.9 KB per evaluation | Refresh of kept units (`src/seon/eval.clj`), health (`src/seon/health.clj:70`) |
| Retired `:seon.eval/value-digest` | Attribute declared, no datoms, no readers | 0 | none |

### Seon's own errors

| What | Where | Size | Read by |
| --- | --- | --- | --- |
| Error fact text `:seon.error/data-edn` | Datom string, admitted EDN capped to the inline limit | 15; p50 898, max 4751 | Health, problems, error render |
| Error evidence `:seon.error/data-blob` and `:seon.error.occurrence/blob-digest` | Blob of the value printer's **node tree** (`render.value/artifact-edn`, `src/seon/error.clj:781`, staged by `evidence-stage` `:1489-1506`) | 9 blobs; p50 44 039 bytes, max 54 785; 383 KB total. Each is 4.3 times the EDN of the value it describes (54 785 vs 12 767; 33 874 vs 7 897) and carries `:seon.error/frames` | Only a dig form an agent may run (`src/seon/error.clj:2197`) |
| Error result `prepare-result` | A second node-tree blob through `blob/put!`, written synchronously **outside** the settlement transaction (`src/seon/error.clj:541-596`) | per error with a profile | Same dig form |
| Error value in the context | Bound under a handle when a ctx is present (`src/seon/error.clj:592-593`) | live | Agent |

### Tests

| What | Where | Size | Read by |
| --- | --- | --- | --- |
| Failure expected, actual, message | Shown text (`report-value`), inline at or under 4096 bytes, blob above (`src/seon/test/runner.clj:1426-1434`) | actual: 11; p50 3003, max 3107 | Test render, agent context status block |
| Thrown actual `:seon.test.failure/actual-blob` | Blob of the node-tree artifact of the error (`thrown-artifact`, `src/seon/test/runner.clj:1394-1410`, `:1445-1451`) | 9; p50 17 936 bytes, max 22 497 | Dig form only |
| Run facts (`seon.test.run/*`) | Datoms: digests, ids, counts | small | Proof status, merge |

### Turns, model calls, effects

| What | Where | Size | Read by |
| --- | --- | --- | --- |
| Model reply `:seon.turn/reply` / `reply-blob` | Inline at or under the threshold, blob above (`src/seon/turn.clj:76-92`) | 128 inline; p50 99, max 3451; 0 blobs | Transcript, context |
| Reasoning `:seon.ai.attempt/reasoning-blob` | Blob above threshold (`src/seon/turn.clj:4212-4220`) | 0 on default | Transcript |
| Model settings and usage `settings-edn`, `usage-edn` | Datom strings made with `pr-str` (`src/seon/turn.clj:4258-4260`) | 1; 3065 and 176 | Transcript |
| Effect results, shell, file and web bodies | Blobs staged and published with the settlement (`src/seon/effect.clj:551-625`, `src/seon/shell/jvm.clj:139`, `src/seon/web/jvm.clj:203`) | 0 on default | Agents through dig forms |
| Answers (`my.agent/answer!`, `:seon.agent/answer`) | Not installed (no source matches) | n/a | Task proofs (design) |
| `seon.blob/store-faithful-edn` | Function with **no callers** in `src` (`src/seon/blob.clj:43-63`); prints with `pr-str` | n/a | none |

### Profile data

| What | Where | Size | Read by |
| --- | --- | --- | --- |
| Call counters and histograms | Process roster of `LongAdder` cells (`src/seon/profile.clj:127`) | live | Health, `seon.profile/summary` |
| Measurements | Datoms, no history, written only when a bucket moves (`src/seon/profile/measurement.clj:1-25`) | 469 rows, 64-character digests | Health over-bound warnings |

### Caches and held state outside `seon.cache`

| Holder | File | Bound |
| --- | --- | --- |
| `projection-cache`, `value-projection-cache` (`clojure.core.cache` LRU) | `src/seon/db.clj:1441-1458` | 24 and 4 entries |
| `program-identity-cache`, `refusal-recording-cache` (LRU) | `src/seon/sci/eval.clj:3089-3097`, `:3338-3340` | 256 entries each |
| Snapshot memo per projection (LRU) | `src/seon/call_preparation.clj:382-403` | 24 per projection |
| Destructive reach memo (LRU) | `src/seon/test.clj:410-420` | 4 per projection |
| Compiled schema memo (basic cache, no bound) | `src/seon/schema.clj:420` | grows with the projection |
| Render evidence cache, an atom in the context's projection state | `src/seon/render.clj:1692-1719` | none found |
| `memoize manifest-roots` | `src/seon/fn/analyzer.clj:256-258` | one per checkout path |
| `!fallback-counts` atom (counter, stated reason) | `src/seon/schema.clj:1026` | per calling function |
| `loaded-identity` atom (process roster) | `src/seon/sci/eval.clj:1017` | one per loaded program |
| `pending-work` (process roster of owned futures) | `src/seon/sci/kernel.clj:644-645` | per open evaluation |
| Web view atoms (`latest-packages`, `interest`, `registration`) | `src/seon/cluster.clj:3426-3431` | per cluster |
| Per-call memoize and accumulator atoms | `src/seon/fn.clj:3660`, `src/seon/test/runner.clj:1742`, `src/seon/db.clj:5241`, `src/seon/batch.clj:336`, `src/seon/render/web.clj` captures | one call; a fold, allowed |

`seon.cache` itself held 144 MB of its 512 MB budget on default.

## 3. Mismatches, fixes and storage effect

Ordered by storage effect, largest first.

1. **The printer's node tree is stored as error and test evidence.** `render.value/artifact` serializes the value
   printer's internal node tree (`src/seon/render/value.clj:1740-1772`) as the "whole" value of an error
   (`src/seon/error.clj:781`, `:589-591`) and of a thrown test actual (`src/seon/test/runner.clj:1404`). It is neither
   the full value nor shown text: a printed intermediate, 4.3 times larger than the value, with stack frames.
   An error can store three copies (`data-edn` datom, `data-blob`, the `prepare-result` blob).
   Fix: cache the full error value under its result handle (as `prepare-result` already binds it); store only the
   shown text (`prepare-result` already computes `shown`), inline at or under 4096 bytes, blob above. Delete
   `artifact`, `artifact-edn`, `read-artifact`, `artifact-value` from the storage path and the dig form that reads
   them; retire `:seon.error/data-blob`, `:seon.error.occurrence/blob-digest`, `:seon.test.failure/actual-blob`.
   Effect: about 44 KB less per error occurrence and 18 KB less per thrown test failure; 542 KB less on default
   today. Shown text is about 1 to 5 KB.
2. **`prepare-result` publishes a blob outside the settlement.** `blob/put!` (`src/seon/error.clj:590`) runs its own
   publication, 8 ms, before the fact that names the digest commits; a collection that runs between the two can
   sweep it, and a refused settlement leaves an orphan. Fix: removed by mismatch 1; any blob a fact names is staged
   and published by `blob/with-publication!` with that fact's transaction (the pattern of `src/seon/turn.clj:84-92`).
   Effect: one fewer blob write per error; no orphan files.
3. **`:out` stored whole as a datom up to 8 MB.** `:seon.cluster.eval/output` (`src/seon/sci/eval.clj:3860`, `:3895`)
   is bounded only by `max-bytes`. A large string in an index leaf is copied by later commits (section 1). Fix: apply
   the threshold: inline at or under 4096 bytes, else `:seon.cluster.eval/output-blob` with
   `:seon.cluster.eval/output-size`. Effect: the 13.9 KB value on default and every future large print leave the
   indexes; no growth for ordinary prints.
4. **Live results are held without a bound.** The SCI `result` namespace and `::result-objects` hold every value the
   context ever answered (`src/seon/sci/eval.clj:579-598`, `:3053`); the owner ruled they go through `seon.cache` as an
   LRU under its byte budget. Fix: one cache kind `:seon.repl/result` keyed by evaluation id, weighed from the
   printer's existing `:seon.eval/value-tokens` (times 4 bytes) so nothing walks the value; readers
   (`src/seon/render/walk.clj:812`, `src/seon/repl.clj:607`) look up that kind; a miss answers the existing
   `:seon.eval/unstored` marker. The SCI namespace must stop holding values strongly; the smallest form is to intern,
   before a pass evaluates, only the handles its forms name (the reader already has them), from the cache. This is a
   change to the one REPL path: an Opus slice. Effect: memory only; retained heap per idle agent stops growing with
   its history.
5. **Shown text over 4096 bytes stays in the indexes.** `:seon.eval/shown` has no blob form; with the display
   budget override (`seon.print/*budget*`) it can grow without limit. Fix: `:seon.eval/shown-blob` with
   `:seon.eval/shown-size` above the threshold, read by the same history and reply readers through one function.
   Effect: 1 to 2 percent of evaluations move 4 to 5 KB each out of the index leaves.
6. **Diagnostics are stored as text but their values are not reachable.** `:seon.cluster.eval/diagnostics` stores the
   floor's printed vector (`src/seon/sci/eval.clj:3799-3802`); the error maps themselves get no handle. Fix: bind the
   diagnostics vector under its own result handle in the cache (the same kind as mismatch 4) and store its shown
   text by the threshold rule. Effect: none on disk; agents can dig the full diagnostics.
7. **Printed copies stored as strings.** `:seon.ai.attempt/settings-edn` and `usage-edn` are `pr-str` output
   (`src/seon/turn.clj:4258-4260`), a second printer into storage. Fix: store usage as its numeric attributes and
   settings as references to the configuration facts they came from. Effect: about 3 KB less per model attempt.
8. **Dead second printer.** `seon.blob/store-faithful-edn` and `store-faithful?` have no callers and print with
   `pr-str`. Fix: delete them; answers, when built, are data in datoms through the canonical EDN codec, inline or
   blob by the threshold. Effect: none on disk; one printer fewer.
9. **Caches beside `seon.cache`.** Seven `clojure.core.cache` holders, one unbounded basic cache, one `memoize` and
   one atom of render evidence (table in section 2) keep derived state outside the one byte budget. Fix: each
   becomes a kind in `seon.cache`, keyed as it is today, weighed by a count it already has. The unbounded compiled
   schema memo (`src/seon/schema.clj:420`) and the render evidence atom (`src/seon/render.clj:1692-1719`) first,
   since they have no bound at all. Effect: memory only.
10. **Text threshold compared in characters.** `src/seon/turn.clj:85` compares `(count text)` with a byte dial. Fix:
    the one threshold function compares `seon.blob/utf8-size`. Effect: none measurable.

Not a mismatch, recorded for its owner: read evidence is about 2.9 KB per evaluation, seven times the average shown
text, as canonical EDN in component entities. Whether every evaluation needs durable read evidence, or only kept
units that are refreshed, is a question for the evaluation spec owner; it is data, not a printed copy, so the rule
allows it.

## 4. Slices

Each slice is about fifteen minutes, one owner file set, and lands with the named deftest asserting data. Order: 8,
10, 3, 1, 2 (falls out of 1), 5, 7, 4, 6, 9.

| Slice | Files | Proof test (asserts) |
| --- | --- | --- |
| A. Delete `store-faithful-edn` and `store-faithful?` | `src/seon/blob.clj`, any test naming them | `seon.blob-test/no-second-printer-in-blob` : `(ns-resolve 'seon.blob 'store-faithful-edn)` is nil |
| B. One threshold function in bytes: `seon.blob/inline-or-stage` taking connection, text, inline key, size key, blob key; returns the fact map and staged writes | `src/seon/blob.clj`, `src/seon/turn.clj:76-92`, `src/seon/test/runner.clj:1426-1434` | `seon.blob-threshold-test/text-at-threshold-is-inline-over-is-staged` : at 4096 bytes the map holds the text; at 4097 it holds the digest and size, with one staged write; a 2049-character text of two-byte characters is staged |
| C. `:out` by the threshold | `resources/seon/schemas/seon.cluster.eval.edn`, `src/seon/sci/eval.clj` producer, readers in `src/seon/repl.clj`, `src/seon/render/transcript.clj`, `src/seon/eval/drive.clj` | `seon.repl-differential-p1-test/large-out-is-a-blob-ref` : printing 5000 bytes stores `:seon.cluster.eval/output-blob` and `output-size` 5000, no `output` datom, and the reply's `:out` equals the printed text |
| D. Errors store shown text, cache the full value | `src/seon/error.clj`, `resources/seon/schemas/seon.error.edn`, `seon.error.occurrence.edn` | `seon.error-result-test/error-stores-shown-text-not-node-tree` : a recorded error's fact has `:seon.error/shown` (string, under the threshold) and no `data-blob`; its handle resolves to the full error map |
| E. Thrown test actual: shown text only | `src/seon/test/runner.clj:1394-1451`, `seon.test.failure.edn` | `seon.test-failure-storage-test/thrown-actual-has-no-artifact-blob` : a test throwing `ex-info` stores `:seon.test.failure/actual` shown text and no `actual-blob` |
| F. Shown text over the threshold to a blob | `seon.eval.edn`, `src/seon/sci/eval.clj` settlement, history readers in `src/seon/context.clj`, `src/seon/mcp.clj` | `seon.repl-differential-p1-test/large-shown-is-a-blob-ref` : with `(binding [seon.print/*budget* 4000] (range 3000))`, the evaluation stores `shown-blob` and `shown-size`, no `shown` datom, and the MCP reply text equals the blob's text |
| G. Model usage and settings as data | `src/seon/turn.clj:4250-4262`, `seon.ai.attempt.edn` | `seon.ai-test/attempt-usage-is-numbers` : the attempt entity has integer token attributes and no `usage-edn` |
| H. Live results through `seon.cache` (Opus) | `src/seon/sci/eval.clj` (`bind-result!`, `::result-objects`), `src/seon/render/walk.clj:812`, `src/seon/repl.clj:607` | `seon.cache-test/results-evict-under-budget` : with a 1 MB cache, binding 20 values of 100 KB leaves at most 10 retained under `:seon.repl/result`; an evicted handle answers `{:seon.eval/unstored …}`; `seon.repl-differential-p2-test/handle-reads-cached-value` : `(count result/eN)` on a held vector answers its count |
| I. Diagnostics get a handle | `src/seon/sci/eval.clj:3790-3802` | `seon.repl-differential-p5-test/diagnostics-dig-to-full-errors` : a renderer that throws leaves the value shown, `:seon.cluster.eval/diagnostics` as text, and the diagnostics handle resolving to a vector of maps with `:seon.error/type` |
| J. Unbounded caches into `seon.cache` | `src/seon/schema.clj:420`, `src/seon/render.clj:1692-1719` | `seon.cache-test/schema-compiled-kind-is-weighed` : after compiling one projection, `(seon.cache/status)` lists the kind with positive bytes |

Each further `clojure.core.cache` holder in section 2 is its own slice of the shape of J.

## 5. Findings outside this assignment

- **A `defn` that uses a plain `def` of its own namespace records an error at program installation.** Reproduction on
  the agent branch: `(def db …)` then `(defn dist [a] … db …)`; the second evaluation returned its value and the next
  evaluation's reply carried `seon.sci.eval/install-row!`: "Cannot interpret scratch.storage-roles/dist: Unable to
  resolve symbol: db" (signature `7c52d6ce…a91d`, branch `:agent-44adcccbab71`). Same class as the open task
  `seon.sci.eval/a-private-function-def-disappears-between-mcp-evaluations`; this is a smaller reproduction for it.
- **Every Datahike commit writes about 200 KB of new node files before collection** (10 small transactions on the
  agent branch: 128 files, 2.0 MB). Storage growth on consumer laptops is bounded only by collection; the owner of
  `seon.db` should measure which indexes the nodes belong to.

## Probe record

- Measurements ran through MCP as agent `storage-roles` (branch `:agent-44adcccbab71`) in scratch namespace
  `scratch.storage-roles`. Datom writes went to that branch only.
- A first probe ran in JVM mode and interned `user/conn`, `user/db`, `user/dist` in default's JVM; they were unmapped
  in the next evaluation (`ns-resolve` answers nil for all three).
- The blob timing wrote 25 unreferenced blobs of 512 B to 64 KB (about 435 KB) to the shared store; the next
  collection reclaims them.
