---
title: Why a defn costs 200 MB
type: evidence
lane: defn-why (ledger RP-0, reframed)
status: active
created: 2026-10-08
---

# Why a defn costs 200 MB

Owner framing: judge each stage against what the work must do. No targets. A stage 10× or more over its
first-principles expectation is marked **wrong** and traced to what it is proportional to.

## Method and limits

- Default JVM, two archives: `75aa6990b` (pid 97988; series `a`) and, after root's restart, `d9d8cb6b8` (pid 33183,
  includes PB-02 `754e20ff6`; series `h`/`i`). Passes enter through `seon.pass-probe/window` around
  `seon.mcp/call` eval_clj (SCI mode) for a new agent `why-*`; JVM-mode `eval_clj` as agent `defn-why` drove it.
- Totals: ThreadMXBean all-thread allocated bytes, wall ms, armed calls, durable commits (always exactly 1, only the
  agent's branch: no other writer in any window), store files/bytes from konserve write hooks.
- Owners: JFR `ObjectAllocationSample` (5000/s), stack depth 256, partitioned once per sample by the first matching
  stage owner (classifier `tmp/defn-why/stages.py`, `stages2.py` restricts to the pass's threads: virtual carriers,
  `Thread-N`, `async-mixed-N`). JFR totals below are sampled estimates; the unrecorded rows are the totals.
- Limits: series `h`/`i` stacks are more often truncated at 256 frames, so their queries land in "unowned reads";
  series `a` attribution is the clean one. Two 822 MB / 891 MB / 1,190 MB passes seen on fresh agents were
  contaminated by concurrent work on another prepl connection (`seon.fn/analyzed-artifacts`, full
  `projection-build`, 600 MB on thread `Clojure Connection`) or were base misses; not reproduced in six later
  sequences. They are not used below.
- The model pass (fixture provider) was **not** measured in this lane; prior evidence is
  `2026-10-08/model-pass/profile.md`.

## Measured pass totals (unrecorded)

| Pass (contracted one-line defn) | MB | ms | armed calls | datoms | nodes written / bytes |
|---|---:|---:|---:|---:|---|
| ordinary `(+ 1 2)` | 55-57 | 60-90 | 7.4-8.5 k | 27 | 36-44 / 265-290 KB |
| warm defn | 178-210 | 205-290 | 30-34 k | 157 | 132-161 / 590-690 KB |
| first defn of an agent | 373 | 362 | 34 k | 157 | 161 / 656 KB |
| pass after a defn | 94-97 | 90-105 | 57 k | 27 | 36-42 / 265-280 KB |

## Expectation table: one warm defn pass (series `a`, 197 MB sampled)

Floors measured in the same JVM with library calls only (thread-allocated bytes, warm, per call).

| Stage | Expected | Measured floor | Measured in the pass | Over | Verdict |
|---|---|---|---:|---:|---|
| read the form | ~KB | edamame 19 KB, 0.02 ms | SCI read+eval together 2.4 MB | — | ok-ish |
| SCI analyze + eval | ~KB | `sci/eval-string*` on a fork 51 KB, 0.1 ms; `sci/fork` 0.6 KB | (above) | ~40× | high, small |
| arm one contract | ~KB | `malli.core/-instrument` 3 KB, 0.03 ms | 4.6 MB (`install-function-contract!`: config read 2.2, `declared-schema-keys` 1.6) | 1500× | **wrong** (small) |
| clj-kondo, one form | ~2 MB | bare clj-kondo, no cache 3.7 MB, 5.5 ms | 8.3 MB (config resolution from disk 2.0, cache sync 2.1) | 2-4× | near floor; config re-read |
| one recording tx (157 datoms) | ~1 MB | plain Datahike file store, history, 480 k datoms: 27 datoms 20 MB, 60 datoms 39 MB | storage 71 MB + checked writer 14 MB | 85× | **wrong** (dependency) |
| reproject one declaration | ~KB | — | 17.8 MB | 1000×+ | **wrong** |
| print the Var | ~KB | 3 KB | 1.4 MB (render + print) | — | ok-ish |
| *not expected:* installation coverage | 0 | — | 35.1 MB on `75aa699`; 3.0 MB on `d9d8cb6` (PB-02 landed) | — | was wrong; fixed by PB-02 |
| *not expected:* run bookkeeping queries | ~0 | `d/datoms` lookup 5 KB | 15.9 MB | — | **wrong** |
| *not expected:* context fork/regenerate | ~KB | `sci/fork` 0.6 KB | 10.3 MB (30 MB on the pass after) | 10⁴× | **wrong** |
| *not expected:* wake routing, config, facts, other | — | — | 3.2 + 1.6 + 1.8 + 5.5 MB | — | residual |

Prediction from first principles: low single-digit MB and a few ms. Measured: 178-210 MB, 205-290 ms.

## Wrong-work findings, ranked by bytes

Each names its owner, what its cost follows, and the slice. "Task" is the filed `ns/task-name`.

1. **Durable write serializes ~100× the bytes it stores: 70 MB per defn, 18 MB per ordinary pass.** Owners are
   dependency code under `datahike.writing` / konserve: fressian `WriteHandlerLookup` → `Fns.soloEntry` allocates a
   `HashMap` entry iterator per written object (13 MB), `FressianWriter.doWrite` (20 MB), a fresh
   `LZ4FrameOutputStream` with 64 KB buffers per node in konserve's `Lz4Compressor` (10 MB), konserve io state
   machines (~10 MB). Follows: index leaves touched × 512-datom leaf size (157 datoms → ~150 leaves rewritten whole),
   not datoms written. Reproduced outside Seon (table above). Slice: Datahike/konserve fork — cache write handlers by
   class, reuse LZ4 buffers per writer thread; proof: allocation per commit within 10× bytes written. Task
   `seon.cluster.store/each-commit-serializes-hundreds-of-times-the-bytes-it-writes` (node count per commit is the
   existing `seon.cluster.store` write-amplification task).
2. **Installation coverage: 35 MB** (`seon.sci.eval/installation-covers-program-change?`, the component-parent query
   over every touched entity × component attribute). Already cut to 3.0 MB by PB-02 `754e20ff6` on the restarted JVM;
   led-pb02b owns the rest. No new task.
3. **Pass after a defn re-derives schema-only values: 25-37 MB, 49 k extra armed calls.** Every contracted defn builds
   a new projection; `seon.schema/projection-cache-value` holds derivations by the projection value, so
   `seon.program/program-attributes` (24.9 MB, 25.6 ms: `storable-properties-in` over all 3,403 schema forms) and
   `seon.error/observation-selector` (12.8 MB, 13.5 ms) recompute although no schema changed. **Fixed for
   program-attributes in this lane** (below). Remaining: task
   `seon.schema/schema-only-derivations-are-rederived-per-projection` and
   `seon.error/fault-window-derives-the-error-selector-when-no-error-occurred` (`occurred-since` derives the selector
   even for an empty window, `src/seon/error.clj`, held by led-storage).
4. **Reprojection of one declaration: 17.8 MB.** `seon.db/speculative-projection` → `declaration-content-key`
   (`src/seon/db.clj:1485`) copies every declaration datom (~30 k) into vectors to key the defn's speculative value
   (6-8 MB), then `seon.schema/load-projection` walks three whole AEVT ranges (6 MB) to find one changed row. Follows
   the program, not the write. Slice: key by the basis revision key plus the report's declaration datoms
   (`db.clj`, held by led-cacheb). Task `seon.db/a-definition-write-reads-every-declaration-datom-for-its-projection-key`.
5. **Run bookkeeping queries: 14-16 MB of every pass.** `seon.turn/terminal-agent-ordinal` (`src/seon/turn.clj:3331`)
   is a 4-clause query whose plan fuse-scans every evaluation on the branch: 4.4-4.8 MB, 2.6-3.6 ms at 742
   evaluations; the same answer by entity + AVET datoms is 31 KB, 0.04 ms (140×). The other settlement queries
   (`fault-window`, `spent-runs`, `opening-deferred?`, `system-run-plan`, `close-call`, `latest-answering-turn-t`,
   `next-ordinal`, `agent-dial`) cost 0.5-2 MB each. Slice: read known entities by identity (`turn.clj`, held by
   led-storage). Task `seon.turn/a-pass-reads-its-run-through-whole-branch-queries`.
6. **Datahike query floor (under 5 and every read).** A one-clause query with a new argument is 0.38 MB / 0.2 ms
   against 5 KB / 6 µs for `d/datoms`: 57% `result-cache-get` → `inheritable-entry` → `compatible-source-keys?`
   walking every retained result bucket (`reference-code/datahike/src/datahike/query.cljc:3030-3086`, pin
   `86c3301a00`), 25% `get-or-create-plan` making a plan per argument value because substituted constants key the
   plan (`query.cljc:3608-3634`). And every node access does a core.cache `LRUCache/hit` through `swap!`
   (`index/persistent_set.cljc:450-455`, `index/interface.cljc:14-17`): 58% of the 4-clause query. Slice: Datahike
   fork. Tasks `seon.db/a-datahike-query-walks-every-retained-result-bucket`,
   `seon.db/every-index-node-access-updates-an-lru-map`.
7. **Checked writer: 14 MB per defn** (`seon.db/write-owned-values-error` 6.8, `write-digest-error` 2.1,
   `write-report-error` 1.6; `transaction-report-datom?` called 21 k times for 157 datoms). Existing tasks in
   `resources/seon/tasks/seon.db.edn` (every commit validates its write report three ways; hot predicate counts).
8. **Context regenerate: 10 MB per defn, up to 30 MB on the pass after.** `seon.sci.eval/base-bindings`
   (`src/seon/sci/eval.clj:2893`) snapshots every Var of every namespace and `regenerate-agent-context!` walks the
   whole private layer; `sci/fork` is 0.6 KB. Follows the program's Vars. Task
   `seon.sci.eval/a-program-change-walks-every-var-to-carry-the-private-layer` (existing related:
   `seon.sci.eval/acquisition-rebuilds-the-whole-program`).
9. **clj-kondo re-resolves its configuration from disk per analysis: ~4 MB of 8.3 MB.** `resolve-config`,
   `process-cfg-dir`, `read-edn-file`, `sync-cache` under `seon.fn.analyzer/invoke-kondo`. Task
   `seon.fn.analyzer/each-analysis-resolves-the-lint-configuration-from-disk`.
10. **Arming one contract: 4.6 MB** against 3 KB: effective configuration read per arm (2.2 MB) and
    `declared-schema-keys` re-derived per projection (1.6 MB, covered by finding 3's task).

## Fix landed: `65cf8c26d`

`seon.program/program-attributes` keeps its per-projection holder and also holds its answer in `seon.cache` under
`[schema forms, schema admissions]`; the derivation moved to `derived-program-attributes` unchanged (EQUIVALENT: it
reads only schema forms through their registry). Files `src/seon/program.cljc`, `test/seon/program_test.clj`, task
`seon.program/a-contract-write-rederives-program-attributes`.

- Before (installed code, default): one derivation per new projection 24.9 MB, 25.6 ms. After: the hit costs the key
  hash 0.30 MB, 0.45 ms (first hash of a new forms map) plus equality 1.07 MB, 0.33 ms, measured on the same values.
- Proof: `bin/lane commit` gate run `9f2f4f15ddc0`, 3 tests, 9 pass, 0 fail:
  `seon.program-test/equal-schema-forms-share-one-program-attribute-derivation` (new: equal forms answer the
  identical set; different forms derive their own),
  `seon.program-test/program-partition-selects-roots-and-components-with-one-query`,
  `seon.fn-test/every-program-attribute-is-installed`. Lint 0 errors.
- Not yet proven: the pass-level drop on default needs root's restart; expected about 25 MB off the pass after a
  defn (and off defn passes that derive it).

## Reproduction

Driver forms are in this note's history and `tmp/defn-why/` (classifiers `stages.py`, `stages2.py`, `sub.py`,
`incl_under.py`, `callers.py`; recordings `*.jfr`). The probe entrance is `seon.pass-probe/window` with
`(@#'seon.pass-probe/mcp-pass {"code" … "agent" a "cluster" "default" "mode" "sci" "namespace" …})`.
