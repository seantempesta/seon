---
title: Render reuse in context assembly
type: research
date: 2026-10-14
status: measured research
---

# Render reuse in context assembly

Design and research note for TASK-CHAIN S3d and the render-reuse slice. Measured 2026-10-14 on default. No source or
test change.

**What exists.** `seon.context/model-context` builds `seon.context/current-state`, then prints it through
`seon.sci.eval/shown-result` → `seon.render.value/prepare` → `seon.render/declared-projection` (root only) →
`seon.context/current-state-ai` → `seon.context/must-read-ai` → `seon.render/render-ai` → `seon.render/produce`.
Reuse by content and read evidence exists in `seon.render/render-call`, but this path never calls it.
**Principal approach.** Route the context's units through `render-call`. Move render-call's retained invocations
from caller-held maps and the `:seon.render/cache` atom into one `seon.cache` kind. First make the block's bytes a
function of its content.

## Answer to the owner's expectation

No: context assembly does not cache any render today. Every turn re-renders every must-read from scratch.
Datahike's query cache is the only reuse in play, and it is what made the earlier 148 ms schema figure look large.

Two defects stand between today and "cheap every turn":

1. **The prompt is not deterministic for one database value.** Two `model-context` calls on the same immutable
   branch value printed different prompts. The first difference is at character 5,036:
   `:seon.error/at #inst "…26.765Z"` against `"…26.995Z"`. That refusal also carries `:seon.error/world`, with the
   **reading** evaluation's `:seon.cluster.eval/id`. No content-keyed reuse can hit on that unit, and the provider's
   prefix cache cannot match those bytes either. The producer is `seon.cluster.source/results-verdict`. It builds
   its refusals with `seon.error.refusal/error`, which stamps the time and the reader's world. It is called by
   `seon.context/step-status`, which feeds `seon.context/step-verdict`, which feeds the `:step` section.
2. **The render path bypasses the reuse owner.** `seon.context/must-read-ai` calls `seon.render/render-ai`
   directly. `render-ai` runs `seon.render/produce` → `seon.render/invoke-selected` with no key, no read capture
   and no lookup. The value printer's root render (`seon.render/declared-projection`) does the same.
   `render-call` is reached only from `seon.render.walk` and `seon.render.web`, and it keeps its retained calls and
   invocations in maps its **caller** holds (`:seon.render/retained-calls`, `:seon.render/invocations`), filled
   through `swap!` on caller atoms. `seon.render/shared-cache` keeps the walk's entity cache in an atom on the SCI
   projection state, outside `seon.cache`. `seon.cache/status` lists 46 kinds and none of them is a render kind.

## Measurements

The setup matches `context-and-first-request-cost.md`. Agent `attempt/341f9a9016cf`, branch value at basis
536871340, main at 536871554. Probes ran in JVM mode with `(seon.cluster.boot/connection "default")` and
`seon.sci.eval/base-ctx` over the branch value. MB means allocated MB on the calling thread
(`seon.profile/measure`). Health was `:faulted` (one open fault, 36 errored receipts), so these are shared-JVM
numbers, not a clean-room benchmark. Every row was measured on the same immutable database value.

### Q1. Does the same unit hit on its second render? No.

| Probe (`result/…`) | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Whole `model-context` (`e143727c166c1`) | 697.12 ms / 881.6 MB | 172.85 ms / 268.1 MB | 182.17 ms / 265.9 MB |
| Its reads / instrumented calls | 1,645 / 112,636 | 1,645 / 112,634 | 1,645 / 112,634 |
| Prompt hash | 1613244883 | −1820466190 | −1440042272 |

Reads and calls are identical on every run, so nothing on this path is reused. Run 1 paid
`seon.db/vector-query-call` x130 at **483 ms self**. Runs 2 and 3 paid it at 8 and 7 ms. That difference is
Datahike's query cache warming, not Seon reuse. The three hashes differ because of defect 1.

Must-read renders, called twice each through `must-read-ai` (`ea5fb9c43b4a8`):

| Group | Count | Run 1 | Run 2 | Reads (both runs) |
|---|---:|---|---|---:|
| Schema | 3 | 6.39 ms / 3.5 MB | 7.75 ms / 3.5 MB | 12 |
| Test | 5 | 172.93 ms / 163.8 MB | 149.22 ms / 162.4 MB | 1,090 |
| Function | 8 | 7.14 ms / 6.4 MB | 6.75 ms / 6.4 MB | 8 |

This corrects the earlier note. Warm, the three schema renders cost **6–8 ms**, not 148.81 ms. That figure matches
a cold Datahike query cache, as in Run 1 above. The test renders are the real per-turn cost: about 30 ms each, every
turn.

### Q2. Does the existing mechanism hit when it is used? Yes. The key and the evidence are already right.

`render-call` was called on the same 16 units (`e094c26091989`, `ed3eb68d79e24`, `e540789076d8c`). The first call
got an empty `:seon.render/invocations`. Later calls got the first call's captured invocations.

| Probe | ms | MB | Reads |
|---|---:|---:|---:|
| One test unit, first `render-call` | 101.75 | 105.7 | 217 |
| Same unit, second call, same database | **3.98** | 2.1 | 0 |
| Same unit, second call, main head (214 transactions newer) | **3.53** | 2.1 | 0 |
| 16 units, direct `must-read-ai` (today) | 112.73 | 171.4 | 1,110 |
| 16 units, fresh `render-call` | 160.68 | 178.8 | 1,094 |
| **16 units, reused `render-call`** | **21.88** | **13.8** | **0** |

All 16 reused outputs equal the direct `render-ai` output. Reuse cuts the must-read cost by 5× in time and 12× in
allocation.

- **Key.** `seon.render/invocation-cache-key` is
  `[selected output (hash program-evidence) (hash projection-evidence) (hash selection-input)]`.
  The selection input covers the invocation argument (`seon.render/invocation-argument-evidence`). That argument
  is the `seon.render/render-argument` keys, including profile and distance, with the value. Its database, context
  and connection are removed.
- **Read evidence.** Each read made inside the producer's guarded invocation is captured into
  `:seon.render.call/captured-reads`. `seon.db/read-evidence` then turns it into evidence. A test unit has 215
  entries, almost all from the `seon.test/host` walk. A schema unit has 3 entries, and a function unit has 0.
- **Validation cost, measured per unit** (`eb32a16b21d45`): 0.01–0.1 ms for function and schema units, and
  0.42–0.77 ms for test units (215 entries) on the same value. Against main head, test units took 0.53–3.63 ms; the
  3.63 ms was the first call.
- **Self time of a reuse** (16 units, 21.88 ms in total):
  - `seon.db/read-evidence` x16, 4 ms. `seon.render/refresh-read-evidence` rebuilds evidence on every hit, even on
    the same committed value.
  - `seon.render/render-program-evidence` x16, 4 ms. `call-reach` is cached, but a map of up to 832 entries is
    rebuilt and compared for each test unit, because no retained `previous` call exists.
  - `seon.render/render-call` x16, 3 ms.
  - `seon.db/read-evidence-current?` x16, 3 ms.
  - Warm `seon.render/call-cache-evidence` cost 4.12 ms for all 16 units, and hashing the keys cost 1.15 ms.

There is no warm miss to explain, because no cache is consulted. Two inputs would still change the key or the bytes
every turn:

- **Defect 1, the stamped refusal.** It is inside the block, not inside a must-read, so it breaks reuse of the
  `:step` section and the prompt bytes, not the must-reads.
- **Volatile argument keys.** `seon.render/render-argument` keeps `:seon.turn/id`, `:seon.render.value/root`
  (a `result/…` handle form) and `:seon.render/rendering` in the argument. The selection input hashes that
  argument. In the turn's `model-context` request, `:seon.turn/id` and `:seon.repl/handle` are absent
  (`seon.turn` builds it from connection, caps, ctx and time limit only), so the must-read key should be stable.
  This is an inference from source. The proof below must verify it on the real path.

Reads made outside the guarded invocation are not captured. Examples are `seon.render/request-profile`'s
`config/effective` and selection itself. Every function unit showed 1 read and 0 captured entries. The profile
value is in the key through the argument, so that one read is covered. Any other read made before `invoke-selected`
is a reuse correctness gap.

### Q4. Is the uncached work proportional to what it shows?

- **`seon.test/host`, per test render.** It cost 22.2 and 23.17 ms, 30 MB, 215 reads and 3,234 calls each time
  (`ed8314cf469ff`). It calls `seon.test/destructive-reach`: 22.95 ms, 213 reads. Its memo misses on every call.
  The edge-attribute query `(or [_ :seon.fn/invokes ?attribute] (and [?declaration …] …))` still returns
  `{:seon.db/invalid-read true, :seon.error/message "Join variable not declared inside clauses: [?declaration]"}`.
  The memo key is then nil, and the program-wide walk runs again. The `or-join [?attribute]` form returns the 9
  attributes. The memo itself is a `clojure.core.cache` LRU inside the schema projection cache: a second cache
  beside `seon.cache`. The work is proportional to the program's destructive reach, while the view shows one test's
  host.
- **Schema usage.** Warm, it costs about 2 ms per schema (3 queries each). It is proportional enough. Its cold
  cost belongs to Datahike's query cache, and only the first window after that cache misses pays it. It still reads
  a full reverse-usage report that a doc view does not show.

## Root cause

The context path has no unit of reuse. `seon.context/must-read-ai` and the printer's root render call the producer
directly. The one owner that keys renders by content and validates them by read evidence is
`seon.render/render-call`, and it was built for the web walk. It keeps its memory in maps and atoms that its caller
must hold and hand back. The turn holds nothing between passes, so there is nothing to hand back. On top of that,
one producer, `results-verdict`'s refusals, puts the time and the reader's identity into the block. The same
database therefore never yields the same bytes. Datahike's own cache does its part; Seon's layer above it has no
owner that remembers across turns.

## Proposed fix, at the owners

### R0. Make the block a function of its content (producer: `seon.cluster.source/results-verdict`)

A verdict that only reads is a value of its inputs. Its refusals carry no `:seon.error/at` and no
`:seon.error/world`. This is the errors cut's target: the base drops `:seon.error/at`. Until that cut lands, the
verdict's refusals are built without the stamp. The time and world belong to the entrant that records an error, not
to a derived judgment that a context shows.

- **Proof.** A new deftest case: two `seon.context/current-state` calls on one database value are `=`, and two
  `model-context` calls print equal `:seon.ai/prompt`. It is red today, as measured above.
- **Cost.** None, because a stamp is removed.

### R1. One render kind in `seon.cache` (owner: `seon.render/render-call`)

Replace the caller-held `:seon.render/invocations` map with
`(seon.cache/lookup :seon.render/invocation key weigh derive retain?)`.
The `captured-invocations` atom write and the `:seon.render/cache` atom go with it.

- **Key.** The key is today's `invocation-cache-key` plus `seon.render/branch-scope`. Its connection and
  generation are Datahike's own cache scope.
- **Value.** The value is `{output, read-evidence, basis}`. It holds evidence, never results, which settles
  `seon.render.web/the-render-cache-keeps-every-debug-view`.
- **Reuse rule.** An entry is used when `seon.db/read-evidence-current?` holds against the request's database.
  Otherwise the unit renders again and `seon.cache/evict!` replaces the entry.
- **Weight.** The weight is the evidence count times a per-kind calibration, as `seon.cache` requires. The kind is
  bounded by `:seon.config.cache/max-bytes`.

This is the same move USER-SURFACES already specifies for calls, shared invocations and packages. It is one owner
and one kind for the web and the context. It is not a second cache.

On a hit, the work shrinks to what a hit must do:

- **Skip `refresh-read-evidence`** when `seon.render/same-committed-database?` holds, or when every revision is
  equal. Measured, that is 4 of the 21.88 ms.
- **Key program evidence by the reach digest instead of an 832-entry map.** `seon.test.runner/reach-digests`
  already names a test's reach. That is 4 of the 21.88 ms.
- **Capture every read the unit makes, selection included.** Bind the sink around the whole of `render-call`, not
  only `invoke-selected`.

The expected hit cost is the evidence check: measured at 0.01–0.8 ms per unit.

### R2. Context units through R1 (owner: `seon.context`)

`seon.context/must-read-ai` calls `seon.render/render-call` with `:seon.render.call/id [:must-read name distance]`.
That is a one-line change of callee; the function has no other caller. Strip volatile identity keys
(`:seon.turn/id`, `:seon.render.value/root`) from the invocation evidence in
`seon.render/invocation-argument-evidence`, because they do not change what a render returns. Then help, the
`:step` and `:task` sections and each history unit can each become a `render-call` unit of its own producer. A
recorded evaluation is immutable, so a history unit's evidence is its own entity and never goes stale.

**Expected cost.** It is an estimate from the measured parts, so a hypothesis until R2 lands. On a second turn
after one new evaluation:

| Part | Cost |
|---|---|
| `current-state` | 30 ms; about 1 ms once its sections are units |
| 15 unchanged must-reads at about 0.3 ms each | 5 ms |
| One changed unit | its own cost |
| Printer | under about 10 ms (the block print measured about the must-reads' cost) |
| **Total** | **under about 20 ms, against 136–182 ms today** |

### The red proof

S3d already owns the assertion, unchanged. In `seon.context-test`, a `seon.profile/measure` around a second
`seon.context/model-context` on the same agent after one new evaluation:

- (a) the prompt equals a third call's prompt on the same value (R0);
- (b) its reads are at most the changed unit's reads plus the evidence checks: 0 reads for unchanged must-reads,
  read today as 1,110;
- (c) its ms is at most k × (the changed unit's fresh render) + n_units × 1 ms, where k is set from the parent's
  measurement.

It is red today. Measured reads are 1,645 on every call, and the bytes differ on every call. The parent and the
candidate must be timed with the same probe on a quiet JVM.

## Where it lands

**TASK-CHAIN S3d absorbs:**

- **Doc views.** `seon.render.test/render-ai` and `seon.render.schema/selection` at brief depth drop `seon.test/host`
  and `seon.render.schema/usage`. The test unit's evidence falls from 215 entries to about 3, and its fresh cost
  from about 20 ms to about 1–2 ms.
- **The R2 callee change in `must-read-ai`.**
- **The proof above, unchanged.**

S3d is Sonnet work and depends on R1.

**A separate slice, Opus, file-disjoint from S3d:**

- **R1 in `seon.render`, together with the USER-SURFACES phase that moves `:seon.render/cache` into `seon.cache`.**
  It is one design: render-call semantics on the one REPL path. It also settles
  `seon.render/cold-renders-recompute-read-evidence` and `seon.render.web/the-render-cache-keeps-every-debug-view`.
  Watch `seon.render/captured-invocations-blank-source-faces` when the captured atom is removed: a source face must
  come from the entry's `:seon.render.call/source`.

**Its own small slices:**

- **R0, at `seon.cluster.source/results-verdict`.** Sonnet, with the red determinism case. It is a prerequisite of
  S3d's proof, part (a).
- **The `or-join` query and moving the memo into `seon.cache`.** Both are already owned by
  `seon.test/destructive-reach-keeps-a-second-cache`. It is independent and worth 20+ ms per test view wherever the
  host report is still read.

## Limits

- Read-evidence invalidation, where a real write moves a test result and the hit must re-render, was not exercised
  here. A write on default was out of scope. The existing `render-call` tests own that behavior. R1's slice must add
  a case in which a changed result re-renders exactly its unit.
- The stable must-read key on the turn's real request is inferred from `seon.turn`'s request map, not observed.
- Separately, a JVM-mode parse error echoed the whole submitted source twice (`:text` and `:seon.error/offending`)
  in one reply. It is noisy context, filed here for the reader owner (`seon.sci.reader/error-value`).
