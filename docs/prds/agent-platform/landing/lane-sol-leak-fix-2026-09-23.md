---
type: landing
status: implementation landed; adoption proof incomplete
created: 2026-09-23
---
# Default heap retention: schema registries and callable wrappers

## Design and cost before code

Malli fork `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`:
`reference-code/malli/src/malli/registry.cljc:81-95` lets a lazy provider
resolve a ref through its whole registry, while
`reference-code/malli/src/malli/core.cljc:1437` retains the supplied options.
Seon supplies the whole projection registry at `src/seon/schema.clj:597-602`
and reuses old compiled siblings at `:2900-2904`. An unchanged schema can
therefore retain every schema of an older adoption. The first-party caller is
`seon.schema/projection-registry`; its inputs are forms, contracts, predicate
bindings and retained compiled schemas. Its recomputation event is a changed
declaration or contract. A valid compiled product should be reused without
keeping unrelated schemas from its former registry.

The target graph has one compiled node per live declaration and one retained
resolver edge per direct canonical reference. Memory is O(live schemas +
reference edges), independent of adoption count after old projections leave
the bounded LRU. A changed declaration should recompile only itself and its
transitive reverse dependents; unchanged nodes are shared. The simplest
alternative is to retain Malli's compiled schemas but give each one a registry
of its direct compiled dependencies instead of the whole generation. Local
recursive `:schema` registries remain Malli's own mechanism. A full rebuild on
each adoption would cut retention but make work proportional to the program.

`seon.schema/projection-cache-value` currently retains wrapper results keyed by
callable identity. A callable changes on reload even when its contract does
not; retained callable objects and policies can grow with reload count. The
installation already owns its wrapper and Malli compiled contract nodes are
shared in the projection. Wrapper creation may be proportional to installations
and calls, but retained memory should follow live installations and distinct
contracts, not historical callable generations.

## Evidence and verification

The implementation remains in `src/seon/schema.clj` and
`src/seon/instrument.clj`; no Malli fork change was needed. The registry
builder uses Malli's lazy registry only as a temporary compile scope and
retains direct compiled children in each final schema's options. The first
derivation after adoption replaces legacy schemas that captured whole
registries. A wrapper installation now retains at most one supplied
projection wrapper plus its bootstrap wrapper.

Exact host JVM probes on default pid 24835:

- Before editing, `@(var-get #'seon.db/projection-cache)` held 24 realized
  entries and 3 distinct registries; the sampled registries had 5,511
  schemas. `jcmd 24835 GC.heap_info` showed 2,016 MiB old regions before
  adoption (2,515,248 KiB total used).
- `(#'seon.schema/projection-registry forms predicates contracts {}
  schema-dependencies)` on the current 3,373 forms and 1,990 function
  contracts compiled 5,511 registry entries in 109.24 ms. The sampled
  `seon.cluster/dropped-summary` schema's option registry had 152 entries
  (Malli defaults plus three direct references), rather than the whole
  5,511-entry generation; `:seon.db/database-value` resolved and its function
  schema had one arity.
- `seon.schema/projection-with-schema` on a cached legacy projection took
  98.69 ms for the one-time 5,364-node migration. Changing that new
  projection again took 5.88 ms and retained the identical unrelated
  `:seon.agent/id` compiled schema. One ordinary contracted
  `predicate-functions-in` call took 0.002875 ms. A parent-commit timing
  could not be obtained without loading old code over default's live Vars;
  the before/after hot-path gate is therefore unproved.
- `declaration-projection` over packaged forms built 3,519 registry entries
  and compiled the touched `projection-registry` Malli contract in 81 ms.
  The full `build-projection` form currently refuses the in-flight
  `:my.background/error` declaration at
  `:my.background/authored-form`; this is a separate source-population
  boundary, not a compilation success claim.
- The new regression passed directly in the host JVM with 7 assertions,
  including local recursive refs. The installed named authority also
  passed: `bin/test-check default --policy named --ns
  seon.schema.registry-retention-test`, run `86eda6665a35`, executed 1,
  reused 0, pass 7, fail 0, error 0, 5,444 ms.

Two failed derivation delays left by an earlier draft caused the first named
request to refuse provenance. A read-only census found exactly one failed
entry in each of `seon.db/projection-cache` and
`seon.db/value-projection-cache`; `clojure.core.cache/evict` removed only
those invalid entries (24 → 23 and 4 → 3). The next named request passed.
No valid cached entry was cleared.

Explicit adoption was attempted after lint. The first loadable draft
adopted in 27,040 ms; after corrections, source refresh refused at the
existing `:my.background/error` declaration. This lane does not own
`resources/seon/schemas/my.background.edn`, so the final source revision is
REPL reloaded and tested but **not fully adopted**. The requested three
adoption plus test cycles cannot run while that declaration blocks source
refresh. `jcmd 24835 GC.run` then `GC.heap_info` showed 2,904 MiB old
regions after the first draft and 2,592 MiB after the later probes (total
used 5,309,478 → 3,323,897 KiB). The final cache census had 24/24
realized entries, 3 distinct registries and one legacy registry. This is
insufficient for the requested 1–2-registry, three-cycle heap proof.

### Timings above one second

| Operation | Wall | Work and limit |
|---|---:|---|
| First explicit adoption | 27,040 ms | Whole-source refresh ran analysis and `seon.db/with-declarations` 108,811 times; this exceeds ten seconds and is a publication defect for the orchestrator's existing slow-operation issue. |
| Later explicit adoption | 13,606 ms, refused | Whole-source analysis and capability checks reached the separate `:my.background/error` declaration refusal; 18,677 declaration reads were profiled. Same slow-operation defect class. |
| Named in-process test request | 5,444 ms | Test provenance, branch acquisition, execution and recording ran as one request; the recorded test body itself was 9 ms in the host diagnostic. Request overhead remains above the sub-second target. |

No cache hit/miss counters were exposed by these requests. The 24-entry
projection-cache census records retention, not hit rate. No platform gate
or browser observation was run by this lane.

Changed paths: `src/seon/schema.clj`, `src/seon/instrument.clj`,
`test/seon/schema/registry_retention_test.clj`, and this landing note.
Code commit: `5ddeac5fb`. Net source lines: +56; net test lines: +41. Source growth provides a
direct-reference resolver needed for incremental schema reuse; it replaces
the whole-registry capture and projection-owned wrapper history.
