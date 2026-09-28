# Shared derivation schema hashes — 2026-09-28

RESTART NEEDED for installed proof. No test requests or restarts were run by this lane.
Default was temporarily unreachable, then returned on fb14373122e. Root test work
ran concurrently; the profile's global totals are NOT attribution for this probe.

The publication key builder hashes overlapping schema forms once per file
(src/seon/cluster.clj:1612 at parent). It now hashes each form once per immutable
world and selects those digests per file. The per-file derivation-forms cache is
deleted. File keys, ordering and absent-form semantics remain byte-identical.
The dependency is ordinary Clojure map selection; no new cache implementation.

SIMPLER: production +9/-8 (net +1, including three documentation lines); the
nested per-file cache and its weight function disappear. Cache kinds go 3 -> 2.
Tests +26 lines. The new form-digest table replaces 464 overlapping retained maps.
FASTER: 480 real file-key inputs, 3,638 schemas, same live immutable database and
code digest, parent/candidate anonymous JVM bodies with cache misses forced by a
lexically supplied lookup. No production Var, cache or instrumentation was changed.
Parent ms 934.566 / 938.389 / 892.059; candidate 358.514 / 362.105 / 359.609.
Current-thread allocations 2,722-2,723 MB -> 287.3 MB. Existing live cache showed
21,697,792 weighted bytes in derivation-forms, 464 misses, 29 hits (5%), 502 ms load.
Warm/end-to-end boot not measured; this is a key-builder reduction, not the whole
17.3 s source-layer fix. Stored schema-key extraction was 76.658 ms.
CORRECT: all 480 resulting keys equal in each of three comparisons. New data
regression: seon.cluster.derivation-keys-test/file-keys-follow-transitive-schema-content.
It covers transitive form changes, unrelated forms, and independent file bytes.
Root also runs seon.cluster.program-change-test/a-launch-on-analyzer-bytes-with-unchanged-definitions-publishes-selected.
Both are UNRUN here, by assignment. Branch publication/adoption and installed
arming are pending root; no claim of installed acceptance.

Validation: git archive of recorded HEAD plus only this diff; clj-kondo src script
test --cache false: 0 errors, 1,600 existing warnings, 0 new findings against parent.
Contract compile via seon.contracts-compile-test/check over archive source and test:
[] in 509 ms, against the archive's packaged resources. Malli seam m/schema at
reference-code/malli/src/malli/core.cljc:2596 (pin 43c6dca8); validator at 2672.

Store observation: initial du -sk data/store 751868; later 855388 during concurrent
root boots/tests. That shared delta cannot be attributed to this lane. The pure
benchmark performs no database writes itself; MCP settlement adds ordinary run
records. No scratch store or persisted per-file measurement population was made.
Bytes per 1,000 evaluations: not measurable from shared du with concurrent root
work; do not present the global delta as a probe-cost estimate. The pure key-builder
itself writes 0 bytes per 1,000 invocations. Further store-attributed probes await
an observation that can separate this lane's settlements.

Remaining larger cause is the separately recorded task
schema-form-keys-re-derive-files-whose-entities-do-not-change (4f15f9a12): 97/106
re-derived files wrote only changed derivation keys, approximately 7.6 s. Removing
those invalidations needs proof of which schema properties analysis actually reads;
this change preserves the complete key and does not weaken invalidation.
