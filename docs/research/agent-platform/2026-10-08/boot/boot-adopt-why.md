# Boot and adoption after the 4 GiB heap cap: why (lane boot-adopt-why)

Question: after c62a1d6b1 and 9f43acf5e default's ready went from 18-20 s to 41.9 s and adoption from 3.5 s
to 15.3 s. Hypothesis: boot allocates so much that the 4 GiB heap garbage-collects heavily.

## Verdict

The hypothesis is refuted. GC pauses are 1.2 s of a 58 s boot at 4 GiB and 0.8 s of a 69 s boot at 11 GiB.
The heap cap did not make boot slow; what is slow is the work, and it allocates about 105 GB in one boot.
The cause of default's 41.9 s was not reproduced here (see Limits), but the largest wrong-work owner in
boot's publication and adoption was found and filed.

## Method

Throwaway roots under the session scratchpad, `bin/seon --root R start`, JAVA_TOOL_OPTIONS carrying
`-Xlog:gc*` and `-XX:StartFlightRecording=settings=profile`. The 11 GiB root used
`--config` with `{:seon.config.operator/heap-max-bytes 11811160064}`. Both started on an empty store at
the then-current HEAD (HEAD moved between runs; each run's commit is in the table). Default was never touched.
Pause time is the sum of every `Pause` line in the GC log up to ready.

| run | heap | HEAD | ready-ms | store ready (source/current) | GC pauses | total pause | max pause | full GC |
|---|---|---|---|---|---|---|---|---|
| A  | 4 GiB  | 9d69253e1 | 78057 | 73990 | 95 | 988 ms  | 46 ms  | 0 |
| B  | 11 GiB | 04874f1d7 | 68945 | 65443 | 62 | 836 ms  | 58 ms  | 0 |
| A3 | 4 GiB  | 4e307ec54 | 58051 | 54693 | 91 | 1164 ms | 119 ms | 0 |

Ready-ms swung 58 to 78 s between identical 4 GiB runs while other lanes used the machine; the 4 GiB run
A3 was faster than the 11 GiB run B. Heap size explains none of the spread. Pause time is 1.2-1.7 percent
of ready in every run. Peak heap after boot at 4 GiB stayed near 2.4 GB (G1 `2435M->582M` young pauses).

## Where the time and bytes go (run A3, JFR, 58 s)

Allocation: 104.6 GB sampled (ObjectAllocationSample weights), 35.7 GB on `main`, 28.8 GB on two async-mixed
threads. Inclusive by owner (GB, `file:line` at HEAD 4e307ec54):

| GB | owner |
|---|---|
| 31.6 | `seon.schema/call-with-projection` (`src/seon/schema.clj:1137`), by caller: `seon.db/validate-write-report` 11.7 (`seon/db.clj:5411,5435`), `seon.cluster/refresh-source!` 7.2 (`cluster.clj:2704,2752`), `seon.cluster/accrete-schema-population!` 6.5 (`cluster.clj:1197`) |
| 14.4 | `seon.schema/predicate-symbols-in` (`schema.clj:178-190`) called from `predicate-bindings` (`schema.clj:2122,2136`) from `build-projection` (`schema.clj:2313,2352`) |
| 7.1 | `seon.fn/analyzed-artifacts` / `analyze-entities` (`fn.clj`), clj-kondo `seon.fn.analyzer/analyze` 6.1 |
| 6.1 | `seon.cluster.source/publish!` |

CPU (4470 execution samples at 20 ms, all threads): `call-with-projection` 1507 samples, `predicate-bindings`
458, of which `predicate-symbols-in` 434 (about 15 percent of all sampled CPU); `analyzed-files` 495;
`seon.db/validate-write-report` 581.

First principles: `build-projection` is memoized (`seon.cache/lookup ::projection` keyed by forms, function
contracts and options), so a call whose forms did not change should cost one lookup. But
`predicate-bindings` runs first, on every non-replacement call, and walks every schema form and every
function contract recursively (`into #{} (mapcat predicate-symbols-in) (vals forms)`), allocating a set per
nested collection, to compute an input of a memo whose key already contains those forms. The work is
proportional to the whole population on every call, not to the change.

## Limits (honest)

- The roots started on an empty store, so they exercise publication from zero (source/current at 55-74 s),
  not default's restart onto a changed commit (adoption 15.3 s). The store identity is a function of the
  canonical store path (`src/seon/cluster/store.clj:194`), so default's store cannot be cloned into a
  scratch root (a clone refuses with "Store identity mismatch"). The restart-with-small-diff case is
  therefore unmeasured here; its per-write paths (`validate-write-report`, `call-with-projection`) are the same
  owners, and the predicate walk runs per write in both.
- JFR ran with the default stack depth of 64, not 256; frames above depth 64 are cut. The owner attribution
  above uses the nearest `seon.*` frame to each sample, which is sufficient for the owners named.
- Whether `fn/analyze-entities` (clj-kondo over files) re-analyzes files whose bytes did not change on a
  restart is not measured: from an empty store it must analyze everything. Not filed as a task; it needs
  a restart-on-existing-store probe first.
- Machine had other JVMs running; timings vary by 20 s between identical runs, so treat ready-ms as +-10 s.

## Tasks

- Filed: `seon.schema/build-projection-walks-every-form-before-its-memo` in
  `resources/seon/tasks/seon.schema.edn`.
- Already existing, not duplicated: the per-commit cost of `validate-write-report`, `write-report-error` and
  `write-owned-values-error` (`resources/seon/tasks/seon.db.edn:122`).
