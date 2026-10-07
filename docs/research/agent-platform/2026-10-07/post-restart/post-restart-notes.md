# Post-restart checks, 2026-10-07 (lane post-restart)

Default runs 108dd1c3e (pid 47705, `start --head`, ready 51,002 ms). Every probe went through MCP eval_clj on cluster "default". The scratch JVM namespace `post-restart.probe` was removed after use.

## 1. Threads: a burst, not a leak

- runtime_status showed 881 platform threads. Sampled later: 528 threads, of which 424 were `async-mixed-N` and 59 were `InnocuousThread-N`. All of them were idle in `ThreadPoolExecutor.getTask` (a cached pool's 60 s keepalive). Once the JVM was quiet the pool fell to 35, and the thread total to 138.
- The `:mixed` executor is also `:core-async-dispatch` (core.async 1.10.874-alpha3 `dispatch.clj:98-111`). It is an unbounded `newCachedThreadPool`. Its largest pool size is 751, and it has completed about 609k tasks since boot.
- The threads come from Konserve filestore async mode, which runs blocking file calls inside `go` blocks (`filestore.clj:153-158` `-create-blob`/`-delete-blob`, `defaults.cljc:237`). Each concurrent store call holds a dispatch thread. A 100 ms sampler recorded these top frames: konserve filestore 1,330 samples, `delete_blob` 190, `_create_blob` 77. At most 269 threads were busy at once, during a whole-store collection.
- Thread count grows with concurrent store I/O, not with the number of operations. One ordinary eval_clj makes about 720 dispatches.
- Recorded in the existing task `seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads`, with these numbers added. Fix options for a later lane: a `clojure.core.async.executor-factory` system property that returns a virtual-thread executor for `:mixed`/`:core-async-dispatch`, or the Konserve fork moving these calls to `io-thread`.

## 2. Boot: 51 s, mostly catch-up publication

| layer | ms |
|---|---|
| prepl-server | 362 |
| store | 134 |
| :seon.source/current | 44,505 |
| config-result | 608 |
| sci ctx | 2,437 |
| flow graph | 2,860 |

- The `:seon.source/current` layer is the boot resume publication. It published 132 of 847 inputs, the day's changes. One of them was `.clj-kondo/config.edn`, and an analyzer configuration change reselects every source. Arming happens after this layer, so no profile cell covers it, and `seon.log` holds only two lines for the whole boot. This is the existing task `seon.cluster/boot-publication-phases-are-invisible`.
- Excluded:
  - the dependency-analysis fill: it was already complete (since 10-06), and linting the other roots takes about 0.5 s;
  - `changed-source-paths`: about 79 ms when nothing changed, measured after boot, when it selects 0 paths.
- The 15 s `transact-call`/`call-with-forms` cells come from the adoption that `start --head` runs after readiness, not from boot:
  - `refresh-source!` → `development-source-refresh!` 20.5 s;
  - `adopt-entities!` 15.8 s, of which one `transact!` takes 15.0 s (write-report validation 4.5 s);
  - `published-changes` 3.8 s.
  This is the existing task `seon.cluster/adoption-readopts-what-the-jvm-loaded`; the numbers are added to it.
- Verdict:
  - The 44.5 s publication is catch-up and proportional to changed inputs. An unchanged restart selects nothing.
  - The about 21 s re-adoption is paid on every move to HEAD.
  - No new task; both existing tasks are updated.

## 3. Cleanup

- I retired 13 probe branches with `my.agent/retire-branch!`. None refused:
  - commit-validation-probe, ctx-ex-1, db-watch-probe, deep-value-probe, error-frames-probe;
  - heap-retained-probe, heap-retained-probe2, loop-s1-probe;
  - review-b-probe, review-b-probe2, review-batch-c-probe;
  - vs-probe, waste-rank-probe.
- I also retired this lane's own `post-restart-probe` (:agent-8cd6cf07afb0) at the end. The roster now holds `:cluster-default :current-src :db`.
- 49 agent rows on cluster-default name branches that no longer exist. They are data rows; I left them alone.
- Collection: I called `seon.maintenance/collect!` once from JVM mode (managed and repository root `/Users/sean/src/seon`, `collect? true`).
  - Before: 421,336 KiB on disk, 80,140 files.
  - After: 290,760 KiB, 60,873 files.
  - Reclaimed: about 130 MB on disk (31%) and 19,267 files.
  - The call ran for about 60-70 s. The MCP call timed out at 32 s, so its result value was lost.
  - `maintenance/last-collection` still names the 03:01Z scheduled receipt, so a direct `collect!` writes no receipt; only the schedule records one.
- Stale publication staging directories remain: `data/source/28ab7df1….staging-7042` and `-7044`. Not touched.

## 4. DB watch baseline

I ran `db-watch/watch.clj` with `basis-t 536872613` (the morning basis), at 04:38:52Z, basis 536872674.

| measure | value |
|---|---|
| store real bytes | 137 MB |
| store on disk (block-rounded) | 297 MB |
| store files | 60,896 |
| written in the last hour | 49 MB, 18,853 files (mostly boot publication) |
| roster | 3 branches |
| current datoms | 766,687 |
| history datoms | 982,480 |
| datoms since the morning basis | 58,596 (form-span and analyzed-source-digest 5,897 each: re-publication) |
| transactions in the last hour | 96 (86 `record-evaluated-call`) |
| heap live after GC | 769 MB (morning: 3.32 GB) |
| cache | 59 / 512 MB |

Root's context holds 1,285 live results. That is most of them for about 30 root runs since the restart, so it looks like results carried over or are counted per form. Not investigated.

## 5. Large values through a branch agent's REPL path (SCI mode, `seon.mcp/call`, 25 s limit each)

Allocation is JVM-wide. Armed calls are read from raw profile adders: `seon.profile/cells` itself adds about 18k armed contract calls, and `observation` about 76k, so earlier counts that went through them are inflated. Where a probe was run twice, the table gives the quiet rerun, because the first runs overlapped other work.

| case | ms | alloc MB | armed calls | reply tokens | store KB | correct? | morning ms / alloc / calls |
|---|---|---|---|---|---|---|---|
| (+ 1 2) | 116-133 | 135-146 | 11.2k | 14 | 139-153 | yes | 82-103 / 135-184 / 11.1k |
| first use of the agent | 915 | 1,770 | ~20k | 14 | 459 | yes | 655 / 1,686 / 40k |
| (vec (range 100000)) | 119-141 | 160 | 13.2k | 102 | 138-156 | yes | 505 / 900 / 1.3M; 5.27 MB stored |
| (vec (range 1e6)) | 226-282 | 324-868 | 13.1k | 103 | 143-150 | yes | 226 / 569 / 15k |
| (map inc (range)) | 112-150 | 140 | 12.8k | 45 | 140 | yes | 277 / 914 / 214k |
| (range) | 115 | 198 | 12-16k | 44 | 148 | yes | 210 / 440 / 14k |
| 1 MB string | 219-242 | 360 | 11.3k | 594 | 134-150 | yes | 224 / 318 / 13k; +1,083 KB stored |
| 10,000-deep vector | 128-149 | 147 | 11.9k | 31 | 140-149 | yes, the value comes back | 218-314 / 357-452 / 81-122k; value lost |
| throw with the deep value in ex-data | 144-156 | 174-296 | 72.8k | 91 | 138-143 | yes, the error comes back | — |
| stack overflow (defn, then a call) | 136 | 158 | 12.3k | 89 | 148 | yes, 6 frames | 1,254 / 2,076 / 362k |
| 1,000-link cause chain | 344-353 | 252-256 | 44.5k | 617 | 141-143 | yes | 874 / 2,100 / 111k |
| (seon.db/db) | 116 | 139 | 11.6k | 25 | 140 | yes | ~105 / 387-476 |

The fixes are live:
- The deep value is delivered.
- The stored value copy is gone: about 140 KB per commit for every case.
- The stack overflow and the cause chain cost a tenth of the morning's.

Still growing with size:
- The deep ex-data throw makes about 10k calls each of `database-value?`, `connection-object?`, `environment?` and `projection-value?`, one per nesting level. The error data is walked whole although only 5 levels are shown.
- The 1,000-link chain makes 6 `seon.error/throwable?` checks per link, linear.
- `(vec (range 1e6))` reports `:value-tokens 527366`. The token estimate walks the whole value; this is cheap next to the agent's own evaluation (108 ms).
- The contracted `defn` that started the stack-overflow probe took 982 ms and allocated 2.49 GB.

The 156k and 121k armed-call readings in the first runs came from concurrent work: reruns are flat at 12-13k.
