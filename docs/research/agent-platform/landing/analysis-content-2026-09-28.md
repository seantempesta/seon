---
type: research
status: complete
title: Reached analysis entries answer by source content
date: 2026-09-28
---

# Reached analysis entries answer by source content

The existing clj-kondo cache and SHA-256 sidecars remain the sole analysis
cache. A differing digest no longer falls through to timestamp acceptance;
jar namespaces use the containing jar's digest. Each distinct reached source
or jar is hashed once per check and once per recording batch. Work is O(reached
entries + bytes of distinct reached containers), with no scan of unused cache
entries. Dependency filling records the same proofs as ordinary analysis.

Captured source mirrors remain authoritative during analysis. Later private
cache reads resolve those mirror paths back to their original source. Shared
cache entries absent from the current classpath cannot restore a removed
dependency. Missing classpath source returns an empty vector; boot's already
filled dependency cache returns an empty report. The live writer required these
explicit absence shapes when admitting the changed declarations.

## Proof and cost

On the live JVM, scratch candidates used installed owners without replacing
production Vars. Private caches and disposable publication/adoption branches
held all mutations. Full probes and envelopes are committed under
tmp/astra-analysis-nil-20260928; run content-candidates.clj before its probes.

| Operation | Parent | Changed |
| --- | --- | --- |
| Changed directory source, preserved timestamp, 1/10/100 uses | accepted stale entry | one stale entry; one hash per check |
| Changed jar, preserved timestamp, 1/10/100 namespaces | zero stale entries | 1/10/100 stale entries; one hash per check |
| Record 1/10/100 namespaces in one jar | no jar content proof | one hash each |
| Rebuild actual Datahike writer cache | stale fact retained, 5.100 ms | stale fact removed, 53.162 ms; identical caller facts |
| Three subsequent unchanged caller analyses | 4.061/3.727/3.508 ms | 3.765/3.321/3.485 ms; no rebuild |

The final jar checks took 13.766/13.042/17.042 ms including armed-call counting.
The unchanged path did not regress in these bounded probes. Changed content
necessarily pays for the analysis that the parent incorrectly skipped. These
measurements do not establish whole-checkout throughput or CPU cost.

All seven changed declarations published and adopted on two live-store branches;
pulled portable rows were equal. Publication plus adoption alone took 992 ms.
The final combined publication/adoption and jar proof took 1436 ms and emitted
the ordinary slow finding: it includes source admission, two durable writes,
branch retirement, jar creation and the counting probe, not one cache lookup.
Both branches were released and retired. No default Var was replaced.

Archive basis e79a604adfdda761a8c209bee8b44339c4a92958 plus this source/test diff:
full src/script/test lint with --cache false has zero errors and the same 1616
warnings. Contract compilation covers 507 files, with no touched findings and
the same ten foreign local-schema-reference findings in
script/seon/dev/docstring.clj and script/seon/dev/markdown.clj. Full compilation
is therefore not green. Neither file was edited. No tests or drills ran.

Root's focused tests, all in seon.fn.analyzer-test:

- a-directory-entry-answers-by-content-even-when-times-match
- jar-content-is-hashed-once-for-all-reached-namespaces
- a-newer-timestamp-does-not-invalidate-identical-content
- a-cache-entry-answers-by-content-not-by-path
- a-first-party-entry-whose-source-is-gone-is-rebuilt-from-the-classpath
- dependency-analysis-is-missing-exactly-for-unmarked-jars

The timestamp-only simulated race test became the identical-content regression:
a future mtime is not a concurrent content change. Source +93/-87, net +6;
tests +65/-49, net +16. No new registry, manifest, memo or cache kind was added.

## Projection timing finding

Existing task 5f54ae50d841, scope-wrappers-report-callback-time-as-work, owns this
finding. seon.schema/call-with-projection at schema.clj:1206 and
call-with-projection-state at :1214 establish bindings then call their callback.
seon.flow/graph-executor wraps the entire proc Runnable in that scope
(flow.clj:91); cluster's projection executor similarly wraps a submitted callback
(cluster.clj:3251). A proc's lifetime, including blocked waits, can therefore be
the scope's duration. This is not evidence of hours spent deriving a projection.

An already-realized projection with a 20 ms sleeping callback recorded one
call, 25.039375 ms inclusive and 25.039375 ms self. Self time subtracts armed
child calls; it remains wall time and includes unarmed callback waits. The
historical 82-minute counter disappeared on restart, so its exact originating
thread was not identified. The 13-second boot scope likewise needs its child
breakdown before assigning projection work. The foreign owners are schema,
flow and profile; none was edited for this finding. Exact probe and envelope:
projection-scope-probe.clj and projection-scope-result.json beside the analyzer
evidence.

## Remaining store boundary

Member retirement remains open. test selection uses the last fully green run
and subsequent obligations, not merely the latest member per test. Retiring
whole runs also changes cluster/source.clj admission readers and render/test.clj
historical reports. Those consumers are foreign-owned. The previous prototype
did not preserve the green baseline and was not landed; its measurements remain
in store-write-fixes-2026-09-28.md. A coherent consumer cut must precede pruning.
The phase2 DB assertions and transaction-report patch already landed in
88c65fc19; root's 4d1f147a7 supersedes the branching-factor experiment with its
measured store configuration change. Neither is reapplied here.
