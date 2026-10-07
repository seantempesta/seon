---
type: research
status: design-boundary
---

# Publication cost: raw analysis and Seon derivation

Publication currently sends every stale derivation input through clj-kondo and then derives Seon entities from its output. The proposed decomposition retains raw analysis by its own inputs and reruns Seon's transformation when its analyzer declarations change.

## Entry and scope

Parent: b66af420e. The requested targets are an analyzer-change cold boot with about twenty changed files below 10 s ready, and one-file publication below 100 MB. Neither target is established yet. Prior-lane numbers are 22,019 ms ready and 171–205 ms / 156–225 MB for warm comment appends; they are context, not this lane's same-probe baseline.

`bin/seon status` and MCP `runtime_status` both reported default faulted: three open fault signatures, seventeen errored receipts, three regressions, and recorded readiness 23,931 ms. The prior lane records the same fault classes (shown-failure nil, branch custody, absent agent branch). Publication performance remains linked to task 253286d36d52; catch-up adoption to 5dbb7de36bdb. This lane never stops, resets or installs prototype code into default.

MCP JVM observation, agent `publish-cost3`, cluster `default`:

```clojure
(let [connection (seon.cluster.boot/connection "default")]
  {:basis (seon.db/basis-t (seon.db/db connection))
   :kondo clj-kondo.impl.version/version
   :analyze-contract (:malli/schema (meta #'seon.fn.analyzer/analyze))
   :cache-directory seon.fn.analyzer/cache-directory})
```

Returned basis 536871540, kondo `2026.07.24`, cache directory `.clj-kondo/.cache/dependencies/7ac1e9371289`, and the installed captured-source analysis contract in 6 ms. No error was returned. The contract display was elided by the value printer (roughly 500 estimated tokens); the source contract was also inspected directly.

Foreign dirty paths at entry are the HTTP-kit submodule, `tmp/test-data-assertions/proofs.edn`, untracked next-turn/printer review notes, an existing malformed newline-containing documentation directory, and `src-clm/`. They are excluded from this lane's candidate. No other lane's session or files are changed.

## Dependency seam and correctness boundary

Pinned clj-kondo: 57252e07975710aa579b24f0d1b2b1e04195caa2.

- `reference-code/clj-kondo/src/clj_kondo/core.clj:242–270`: primary analysis always runs before `index-defs-and-calls`, `sync-cache`, and final usage lint. The output analysis and findings are not replayed from disk.
- `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:20–36,65–81,127–215`: the disk cache stores namespace definition data, by language and namespace; it supplies resolution and type information to final lint. It is not a raw per-file result cache. Its existing Transit and cache-lock ownership are the first persistence seam to consider.
- `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:4452–4560`: `file-analyzed-fn` receives progress coordinates, not a reusable analysis value. It is not a raw-analysis replay seam.
- `src/seon/cache.clj:53–74,87–88`: Seon's central cache is a process-local Caffeine cache. Retaining raw results there alone cannot improve a replacement JVM's cold boot.
- `src/seon/fn.clj:2911–2925`: selected files' old namespaces are removed from the resolver cache before their batch is analyzed. Analyzer-identity invalidation selects unchanged files too. Reusing their result without restoring the namespace definitions would remove context needed by freshly analyzed files.

The result cache key must account for the submitted file, resolved configuration and hook implementation, kondo implementation, and the declarations actually supplied by the batch and dependency cache. Unchanged caller bytes do not prove unchanged findings. Cached namespace definitions must participate in the same resolution phase as freshly analyzed definitions. Canonical source filenames and metadata (including `:as-alias`) must survive persistence.

This is a correctness design constraint, not yet a refutation of the requested decomposition. No byte-only result cache or skipped validation is proposed.

## Measured probes

`cache-seam.clj` ran through the ordinary MCP JVM entrance on the owned root. The full response is `cache-seam.edn`; the returned form took 17 ms without errors. Its progress callback counts actual primary file analyses.

| Input | ms | Exact total allocated bytes | Files analyzed | Callee arity seen by caller |
|---|---:|---:|---:|---|
| Two files, first run | 8.193 | 2,255,968 | 2 | 1 |
| Same two files and unchanged cache | 2.339 | 2,229,560 | 2 | 1 |
| Caller only, callee from namespace cache | 1.746 | 2,049,536 | 1 | 1 |
| Same caller bytes, callee changed to zero arguments | 1.978 | 2,227,992 | 2 | 0, with `:invalid-arity` |

The cached namespace map had keys `[f :filename :source]`. It did not hold caller analysis or findings. This establishes the installed seam and a required invalidation regression. It does **not** refute the abstract purity of raw analysis when all its actual inputs are included.

The one-file probe is the previous lane's exact `src/my/background.clj` comment append followed by `seon.cluster/refresh-source!`, measured inside the operation. It runs in the owned archive, never the shared checkout or default. `warm.clj` is the committed probe.

| Same probe on b66af420e | ms | Exact total bytes | Exact calling-thread bytes | Armed calls |
|---|---:|---:|---:|---:|
| First append | 383.246 | 521,078,752 | 499,413,552 | 53,877 |
| Second append | 187.615 | 180,948,168 | 159,456,208 | 22,823 |

Fresh-store installation reached ready in 38,843 ms. An unchanged restart reached ready in 4,221 ms. Neither is the requested analyzer-change-plus-twenty-files incremental cold workload, which this lane has **not** reproduced yet. No new cold before/after improvement is claimed.

The warm JFR records eight further appends. `warm256-profile.json` contains 61 execution samples and 1,323,968,296 weighted sampled allocation bytes across all threads, including the enclosing evaluation and printing. The leading weighted owners are pull-many 225.6 MB, cache claim 145.9 MB (mostly per-file derivation keys), queries 121.9 MB and kondo 106.2 MB. These are weighted samples, not exact operation allocation counters. The first export accidentally used `jfr print`'s default five-frame output; the retained report was regenerated with `--stack-depth 256`. An earlier recording at JVM depth 64 is not used for owner claims.

## Rejected local prototype

EQUIVALENT candidate: use each file's held raw schema candidate set in its derivation cache key and filter registered schema names only on a miss. The complete declaration-world digest already occurs in the key, so a newly registered schema still misses. This avoids rebuilding a filtered set before every lookup.

The definition was evaluated and re-armed only in the owned throwaway JVM, through the normal MCP JVM entrance. Two later same-file appends returned 171.219 ms / 156,979,632 total bytes / 135,606,864 calling-thread bytes and 171.572 ms / 165,343,616 total bytes / 136,630,600 calling-thread bytes. The surrounding baseline samples were about 160 MB; there is no established material allocation improvement. The source edit was removed exactly, and the prototype JVM was stopped. No production or test change remains from this experiment.

## Design check and next scope decision

The decomposition is still appropriate, but neither installed cache supplies the requested durable raw-result replay. A naive byte-keyed replay is rejected: it can retain stale caller findings, and it removes the selected unchanged namespaces from the resolver without supplying their definitions. Restoring old namespace entries and repeatedly relinting dependencies is also not an established solution: inferred dependency facts and imported declarations must converge to the same batch result, and no proof of that algorithm was obtained.

The remaining choices, with engineering estimates rather than measured guarantees:

1. **Memory-only raw reuse in `seon.cache` (roughly 1–2 hours).** Smallest storage change; can remove repeated identical analysis inside one JVM. Sacrifices the cold-start target because a new JVM has no entries. This alone does not satisfy the assignment.
2. **Raw replay at clj-kondo's analysis/resolution boundary (recommended investigation, roughly 4–8 hours).** Keep persistence, metadata and cache ownership in the maintained dependency; preserve final lint against the current combined declaration world. Requires proving a serializable replay value and independent invalidation tests before integration. Offers the intended cold-work reduction; under-ten-second readiness still needs measurement and any further fixes. Publishing the maintained fork revision requires explicit owner authorization under AGENTS.md.
3. **Durable raw-result support in Seon's central cache (roughly 4–8 hours).** Avoids a dependency API extension but adds disk lifecycle/retention work to the cache owner and still requires complete dependency identity. Larger Seon scope, with no demonstrated advantage over the dependency seam. Do not add an ad hoc analyzer-owned disk cache.

No target is marked complete. There is no accepted implementation, no candidate cold after-number, and no assertion that the requested targets are impossible. The owner-scope question about extending and publishing the maintained fork remains pending.

## Verification and cleanup

Full checkout lint: **0 errors / 1,458 warnings**, 5,760 ms, `clj-kondo --lint src script test --cache false`. Existing warnings are not presented as zero findings. The edit hook reported existing cluster docstring/lint warnings while the discarded prototype was present; the final source diff is empty.

The evidence commit requests the existing analyzer, publication, adoption, cluster and fn proofs through `bin/lane commit --test`. Its terminal output is retained at `scratchpad/publish-cost3/commit.txt`; green tests would prove only the existing boundaries, not a performance fix.

Owned pid 97006, start instant 2026-10-07T16:25:56.682Z, was stopped before relaunch for the deeper profile. Owned pid 99066, start instant 2026-10-07T16:34:49.393Z, was then stopped and `:process-exit? true` recorded in `down.edn`. The unresolved probe store and raw JFR files are retained under the owned scratch directory as evidence. No owned JVM remains. Default was never stopped, reset or loaded with prototype code, and needs no restart or reset for this evidence-only work.
