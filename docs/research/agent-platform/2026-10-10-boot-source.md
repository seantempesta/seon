---
type: research
status: measured
created: 2026-10-10
---

# Boot source publication: measured design boundary

Lane `astra-boot-source`, 2026-10-10. No production change is proposed or
claimed. The assignment's stop rule applies: making this cost proportional to
changed inputs needs a change to the persisted derivation inputs and dependency
analysis cache composition. This note records the evidence and options before
that design is selected.

`seon.cluster.boot/stand-boot-layers!` opens the store, calls
`seon.fn.analyzer/ensure-dependency-analysis!`, compares file digests through
`seon.cluster.boot/changed-source-paths`, and calls
`seon.cluster/refresh-source!` when paths differ. That owner calls
`seon.cluster/full-source-refresh!`, then `seon.cluster.source/publish!` and
`seon.cluster/populate-source!`. The instance publishes `:seon.source/current`
only after that work. It therefore includes dependency analysis as well as
source publication.

## Measured boots

All three ran through `bin/seon check --at REV`, on a disposable store cloned
from the existing template of `a6cbe4fcf`. Default was neither restarted nor
adopted. Each check ran the same existing test,
`seon.cluster.boot-test/a-failure-with-no-cluster-database-has-no-world-and-never-panics`;
all three passed (one executed, none reused). These are **before** numbers;
there is no implementation and no after number.

| Candidate relative to the template | Ready ms | Operator boot ms | Check total ms |
|---|---:|---:|---:|
| Same `a6cbe4fcf` | 5,316 | 10,346 | 20,635 |
| One comment added to `src/seon/cluster/boot.clj` | 7,530 | 16,509 | 29,789 |
| Reverse SCI pin, `f7557dc68` | 6,933 | 17,199 | 30,139 |

The dependency transition is explicitly the **reverse** of the reported bump;
its dependency cache already existed. It does not reproduce the missing-cache
18,346 ms readiness in the assignment. The one-file candidate is the temporary
Git object `314e1b5fc1f552714004b6832018207bfd09e52d`: the base tree plus the
comment `; One-file restart measurement.`. It was constructed with an isolated
Git index and `commit-tree`, as the operator constructs check candidates; no
HEAD, shared index, working-tree source or worktree was changed.

The unchanged check overlapped other check activity early in its process
lifetime. Before the subsequent dependency and one-file runs, process inspection
showed no other active check JVMs. Several older idle JVMs remained; none were
touched. CPU contention was observed, not experimentally isolated. The three
numbers are individual observations, not medians or an exact estimate of the
reported default boot's contention.

Raw check values: [checks.edn](2026-10-10-boot-source/checks.edn).

## Findings and limits

1. **Whole-program derivation-input work is confirmed.** Every changed
   publication enumerates all source derivation inputs. It asks
   `seon.cluster/stored-derivation-schema-keys` for their keyword/spec facts,
   parses their contracts, derives their schema closures and recomputes or
   looks up each key before deciding which files are stale. Its
   `seon.cache` entries are process-local: retaining the on-disk store and
   dependency caches across a restart does not retain this JVM heap.

   Deep-stack JFR observed 1,550 MB of inclusive sampled allocation under
   `stored-derivation-schema-keys` in the dependency-transition JVM and
   1,776 MB in the one-file JVM. `full-source-refresh!` accounted for 3,322 MB
   and 3,421 MB respectively. These are sampled, inclusive whole-process
   recording totals, not retained memory; parent and child totals overlap.
   [profiles.json](2026-10-10-boot-source/profiles.json) also separates samples
   whose stack is inside boot before `stand-cluster-runtime!`.

   A warm live replay is dramatically cheaper: the stored-schema-input owner
   took 13.35 ms and 21.3 MB. This replay included resource paths too and is
   not presented as a cold-boot comparison. The existing cache works once
   populated; it does not solve acquisition in a new JVM.

2. **A dependency-set miss analyzes unrelated dependencies.**
   `seon.fn.analyzer/dependency-cache-key` selects one directory from the
   entire dependency set and pins. `analyze-dependencies!` supplies the entire
   dependency classpath to clj-kondo when that directory lacks its completion
   marker. A private empty-cache replay in the already running JVM analyzed
   1,804 files across 267 entries: 3,151.51 ms, 661 MB on the calling thread,
   16,918 MB allocated and 25,272 CPU ms in the JVM-wide window. Parallel kondo
   work is absent from the calling-thread allocation counter; JVM-wide counts
   include unrelated work. This replay used `:copy-configs false` to leave the
   shared configuration untouched, so it is not a complete cold-boot timing.

   Upstream clj-kondo `clj-kondo.impl.core/process-file` already skips unchanged
   non-SNAPSHOT jars using its cache's skip markers, but a newly selected Seon
   directory contains none of those markers or resolver entries. Directory
   dependencies are scheduled afresh. Pinned seam:
   `reference-code/clj-kondo` at `57252e07975710aa579b24f0d1b2b1e04195caa2`.
   `clj-kondo.core/run!` and `clj-kondo.impl.cache/sync-cache` own analysis and
   resolution; Seon should compose those mechanisms, not introduce another
   analyzer.

3. **Unchanged comparison hashes the complete inventory, but is not the
   reported 15-second dominant cost.** Armed live measurement: 173.08 ms,
   176.5 MB, 869 SHA-256 calls. `capture-paths` ran once, 121 ms self;
   `input-path?` ran 11,152 times, 34 ms self. The dependency cache hit check
   took 3.14 ms and 0.5 MB. Git's committed input inventory can eventually
   select changed files without reading all bytes; correctness must retain
   deletions and non-Git publication entrances.

4. **Other-root analysis uses archive paths and mtimes as identity.**
   `seon.fn.analyzer/roots-stamp` includes absolute paths, lengths and
   modification times. It is not content identity. Relocated archives can
   relint unchanged scripts, while equal-length edits with preserved mtimes
   can be missed. Simply making its key path-independent is not sufficient:
   resolver entries include filenames, and the shared directory can hold a
   different script population from another archive. Immutable content reuse
   must preserve resolver and source-location correctness together.

5. **Boot profiling itself has an existing gap.** Source publication precedes
   `arm-host-program!`, so its original calls have no armed profile cells.
   JFR establishes inclusive allocation and execution samples, not exact
   invocation counts or per-function wall milliseconds. The unchanged
   recording used default stack depth; the other two recorded depth 256.
   Neither sample counts nor their differences are called exact timing gains.
   The later full host arming also costs substantial allocation (about
   2.6–2.7 GB sampled in the two changed JVMs), outside the source phase.

Exact probe forms and counter results:
[probes.edn](2026-10-10-boot-source/probes.edn).
The reusable [JFR summarizer](2026-10-10-boot-source/summarize_jfr.py) prints
sample counts and weighted allocation, never invented invocation counts.

To reproduce a check, set `JAVA_TOOL_OPTIONS` to
`-XX:FlightRecorderOptions=stackdepth=256 -XX:StartFlightRecording=settings=profile,dumponexit=true,filename=OWNED_DIRECTORY`
and run the named `bin/seon check --at REV --test NS/VAR` above. Use an
existing owned directory; the recorder names each JVM's file independently.
Pass the boot JVM's recording to the summarizer. The small evidence files
here retain the derived observations; raw recordings remain local and are
not required to read this report.

## Why a shortcut is not a correct fix

The only durable per-file cache fact is
`:seon.fn.file/derivation-digest`. A digest alone does not identify which
analyzer, kondo configuration and transitive schema inputs produced it.
Skipping the scan solely because a file's bytes did not change would miss
changes in those inputs. Reading every function's contracts again merely
recreates the present cost. A persistent input seal or dependency facts are
needed to prove that the old derivation is still valid without reconstructing
it. This is a data/interface change, not a safe local loop replacement.

The existing audit already identifies this same incomplete algorithm:
[algorithmic audit, finding 7](algorithmic-audit-2026-09-28.md#7-per-file-publication-reuse-still-derives-every-files-reuse-key).
Its partial fix explicitly leaves program-wide discovery:
[publication keys](algorithmic-fixes-2026-09-28.md#publication-keys-derive-only-missed-file-content).
Related existing tasks are
`seon.cluster/boot-publication-phases-are-invisible`,
`seon.cluster/adoption-readopts-what-the-jvm-loaded` and
`seon.fn.analyzer/local-root-dependency-analysis-goes-stale`.

## Priced design options

These are engineering estimates, not measured execution guarantees. Each
requires a red work-count proof before implementation and the lane gate.

1. **Persist one publication input seal — roughly 1–2 hours.** Record the
   exact analyzer and dependency/configuration inputs that the publisher
   actually used. If those inputs and schema content are unchanged, derive
   keys for selected source files only; otherwise use the existing complete
   validation. Guarantee: ordinary one-file publications avoid the global
   contract-input read. Sacrifice: a schema/analyzer change still scans the
   whole program, and a new dependency-set cache is still expensive. The
   missing-seal upgrade must perform one complete validation.
2. **Persist per-file derivation inputs — recommended, roughly 3–5 hours.**
   Store the producer's actual schema and analyzer input identities with its
   file artifact, and use indexed dependency edges to select affected files.
   Guarantee: an ordinary change performs changed-file and reached-dependent
   work; unchanged file contracts need not be reparsed after restart.
   Sacrifice: schema/publication changes, live-store adoption proof and tests
   for edge addition/removal are required. This fixes the measured
   whole-program discovery rather than just its common case.
3. **Option 2 plus dependency-cache composition — roughly 5–8 hours total.**
   Reuse dependency analysis artifacts by each dependency's content in an
   immutable resolver population for the loaded set. Let kondo own resolver
   entries and jar skipping; explicitly handle deleted/renamed namespaces,
   configuration changes and archive-relative locations. Guarantee: a SCI
   pin change does not analyze unchanged libraries. Sacrifice: the broadest
   proof surface, including concurrent JVMs on different dependency sets.

## Verification and delivery state

No source, test, task or schema was edited. No new tagged red proof exists,
and no performance fix, after timing, publication or adoption is claimed.
Stopping here follows the assignment's explicit design-change stop rule.
The three measured archive checks passed. The running default health was
already faulted: lookup, write-back and store-release findings were reported
at entry and left with their owners.

Requested working-tree lint was run with
`clj-kondo --lint src script test --cache false`: 21,422 ms, one error,
1,470 warnings. The foreign error is unresolved
`seon.test/arm-first-loads!` in
`seon.test.first-load-arming-test`. No edited Clojure file belongs to this
lane, and the error was not hidden or worked around.
