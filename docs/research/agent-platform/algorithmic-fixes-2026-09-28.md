---
type: research
title: Algorithmic fixes
date: 2026-09-28
status: partial
---

# Algorithmic fixes — 2026-09-28

## Read evidence: one pull dependency walk per selector

`seon.db/query-index-patterns` already asked Datahike to resolve the complete
collection input. It did that once per input binding, then walked every result
entity again. The pull part now resolves once and groups bindings by selector.
For I inputs, R matches and S distinct selectors, its cost changes from
O(I × (query + R × selector-work)) to O(query + I + S × R × selector-work).
Nested clause evidence retains its existing algorithm; this slice does not
claim to eliminate that separate repeated scope resolution. Uncaptured explicit
pulls now skip dependency derivation entirely. Contracts remain on the public
path; both changed private walkers now declare contracts too.

Live evidence: MCP JVM scratch namespace `scratch.astra.algorithmic`, default
pid 9778, loaded source `29bdfce8d85f9168cc62604017050b6f291382a8`, immutable
database basis 536888419. No default definitions or datoms changed. Query:
`[:find (pull ?e [:seon.fn/sym]) :in $ [?e ...] :where [?e :seon.fn/sym]]`,
using 1/10/100 real function entity ids from that database. Warm unarmed walker:

| Inputs | Before ms | Scratch after ms | Equal dependency sets |
| ---: | ---: | ---: | --- |
| 1 | 0.036 | 0.045 | yes |
| 10 | 0.176 | 0.055 | yes |
| 100 | 8.314 | 0.344 | yes |

The final scratch version also matched on collection pull without `:where`,
relation-bound variable selectors, empty inputs, wildcard fallback and negation.
The 100-input improvement is about 24× for this walker, not an end-to-end turn
claim. Allocation was not measured. A final replay measured 0.387 ms at 100.
The probe script is `tmp/astra-algorithmic-fixes-20260928/read-evidence-probe.clj`;
the candidate was evaluated only under the scratch namespace with the database
owner's aliases/imports, calling its unchanged pull helper.

Archive basis `8e9a5f32087e9aa3652c28bc52bd1e0e4d58f0a2` plus only this slice:
`clj-kondo --lint src script test --cache false`: 0 errors, 1,728 warnings,
6.328 seconds. Contracts compile: 497 files, 0 source/test findings; the same
10 pre-existing script findings as the audit (docstring.clj:285/318/334/360,
markdown.clj:1020/1050/1090/1124/1170/1215), 4.361 seconds in the checker.
Those foreign script contracts are untouched. No test request was started.
No publication/index/adoption/boot/arming implementation changed, so this slice
does not require a drill. Source/test delta: +67/−31 lines.

Root's focused tests:

- `seon.db-test/collection-pull-evidence-walks-each-selector-once` (new;
  exact pull-walker calls at 1/10/100 inputs, answer/dependency coverage,
  and zero calls for uncaptured pulls).
- `seon.db-test/collection-bound-query-evidence-covers-every-input-attribute`
- `seon.db-test/query-and-pull-append-evidence-only-when-captured`
- `seon.db-test/component-expanded-pull-evidence-detects-a-child-only-change`

Foreign boundary: `one-path-impl-2` owns turn/SCI/agent/batch/render work and
`resources/seon/schemas/seon.db.edn`. The live cluster was already faulted with
11 open faults (including turn settlement), 184 failed tests and 56 unknown
failed-test observations. None is treated as a pass or attributed to this fix.
Installed armed behavior and the new regressions await root's central adoption
and test round; scratch timing is not evidence of installed publication.

## Boot profile A: batch capability comparison's previous rows

The 7,161 single pulls in the supplied profile are reached from
`seon.fn/assert-capability-contracts!`: it compares each authored definition's
six capability fields with a separate `db/pull`. `file-entities` was already
batched. This change reads the authored batch's prior rows with one
`pull-many`, indexes by symbol (including missing rows), and retains the existing
fallback for referenced functions outside that batch. Work remains O(D) in
authored definitions, but read-owner/contract/projection entry becomes O(1)
instead of O(D). Capability admission and its contracts are unchanged.

MCP scratch `scratch.astra.capability`, same live immutable database as above:
canonical `database-manifest` artifact for `src/seon/db.clj`, restricted to
1/10/100 of its stored function rows. Before/after answers equal the supplied
artifacts in every case. Warm times in milliseconds: 0.700/0.169,
0.887/0.348, 5.037/2.315. These measure this comparison, not a claim that all
10 seconds of inclusive pull time disappear: the supplied full profile reports
1,441 ms in the 7,161 public single pulls, while its batched pulls also do real
entity decoding. Memory was not measured. Reproduction body:
`tmp/astra-algorithmic-fixes-20260928/capability-probe.clj`.

Archive HEAD `1d05b4f164bc84788defefa5fd74ef298ee2ace1` plus fn source/test only:
lint 0 errors, 1,725 warnings, 7.258 s; contracts 497 files, same 10 foreign
script findings, zero source/test findings, 5.142 s. No test request started.
`bin/seon drill --with src/seon/fn.clj test/seon/fn_test.clj` passed:
candidate `14d4f556154a65a19cf159584fe3ef22d4547f4a`; base from scratch ready
246.259 s, move ready 93.164 s, candidate from scratch ready 201.977 s;
total 673.434 s. The drill stopped its JVMs and removed its passing root.
This is the explicitly requested expensive drill, not ordinary warm latency.
Other lanes were independently drilling; no other lane's process was controlled.

Root's focused tests (not run by this lane):

- `seon.fn-test/capability-comparison-batches-prior-rows` (new, deterministic
  captured-read counts at 1/10/100 definitions: one batch, zero single pulls).
- `seon.fn-test/capability-metadata-is-one-program-graph-contract`
- `seon.fn-test/capability-indexing-refuses-every-malformed-declaration`
- `seon.fn-test/file-entities-read-in-batches-not-per-entity`

The foreign boundary remains the one-path lane and the pre-existing script
contract failures above. Only fn source/test and this evidence land in this slice.

## Requested test reach: share entries and use the single root's graph

`reach-graph` already computes exactly the union reachable from the requested
roots. For one test that union is its answer: `reach-entry` now uses the sorted
names and schemas directly, avoiding both all-vertex closure matrices. Its
derivation is O(V+E+K+J) graph acquisition and O(V log V + K log K) ordering,
not all-pairs transitive closure. Multiple roots keep the existing amortized
bitset algorithm. A map-based per-root walk was rejected: 100 roots grew from
76 ms to 408 ms. An indexed variant also lost at that size; neither lands.

The new `seon.cache` kind `:seon.test.runner/reach-entries` retains combined
digests/memberships under requested symbols and each reached declaration's
leaf digest (undigested nodes carry their full unknown input). This is the same
content identity already used by `reach-graph` for nodes. A hit still acquires
and compares the reached graph, O(V+E+K+J); it rebuilds zero closures and hashes
zero closure digests. `record-latest-tx` derives memberships only for results
whose memberships the completion did not already carry. Partial carried maps
still get their missing entries; existing contracts and unknown handling stay.

MCP `scratch.astra.reach`, real 1/10/100 stored test symbols, exact entry maps
equal. The recording caller asks for both digests and memberships, so the
paired probe includes two entry requests with a cold combined-entry cache:
76.787→31.070 ms (1), 52.816→42.342 ms (10), 125.805→86.245 ms (100).
Subsequent individual hits were 12–17 ms; earlier individual calls were
24–63 ms. First multi-root misses can add about 5–13 ms for key construction
and cache admission; the measured caller's paired work improves. These are
scratch derivation measurements, not a claim of an installed test-run latency.
Memory was not measured. The bounded cache weighs the retained leaf keys and
result memberships without walking declaration trees again.

Verification from archive `21a8b04f0` plus runner source/test: lint 0 errors,
1,725 warnings, 6.409 s. Contracts: 497 files, same 10 foreign script findings,
zero source/test findings, 15.421 s with other lanes' drills active. No test
request was started; no publication/boot/arming implementation changed.
Reproduction: `tmp/astra-algorithmic-fixes-20260928/reach-probe.clj`.

Root's focused tests:

- `seon.test.runner-test/reach-digests-and-memberships-share-content-valid-work`
  (new, 1/10/100 requested tests, equal answers and zero repeated closure builds).
- `seon.test.selection-test/definition-content-agrees-across-exploratory-branches`
- `seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content`
- `seon.test.selection-test/selection-derives-bases-obligations-and-exact-symbol-reach`
- `seon.test.runner-test/nested-runs-own-their-counters-and-evidence`

Foreign boundary and installation limits are unchanged from the first slice.

## Stored EDN: parse and canonicalize each text once

`decode-attribute-value-in` now retains successful canonical decoding in
`:seon.schema.datahike/decoded-edn`, keyed by the stored text, attribute and
loaded codec functions/readers. Reloading that codec selects a new key.
Non-string, malformed and noncanonical storage keeps the same refusal data;
failed derivations are not retained. Attribute identity in the key also keeps
concurrent malformed reads from sharing another attribute's refusal. A tuple
holds decoded nil, so successful nil is cacheable. Reading still does not
reapply the current write schema to historical values. All contracts remain.

For R reads of length-L text, parsing and canonical reprinting changes from
O(R × codec-work(L)) to O(codec-work(L)) per resident content key; lookups
remain, and hashing a freshly allocated equal Java string is still O(L).
This is not an O(1) claim for obtaining arbitrary new text. Estimated retained
weight is ten times text length plus the central cache's entry allowance.

MCP scratch `scratch.astra.codec`: the actual first stored
`:datahike.read/revision` datom on default (581 characters), same immutable
database as above. Final candidate timings at 1/10/100 decodes: 0.540→0.404,
1.015→0.116, 9.626→1.035 ms, with identical values. Separate probes confirmed
equal values/refusal data for nil, malformed text, noncanonical map ordering
and non-string storage. Memory was not measured. The earlier prototype had
additional public-wrapper overhead and measured 18.66→4.42 ms at 100; the
final numbers above are the candidate actually landing.

Archive `3e0370417` plus bridge source/test: lint 0 errors, 1,725 warnings,
6.416 s. Contracts: 497 files, same 10 foreign script findings, zero source/test
findings, 4.275 s. No test request started. This changes read reuse, not schema
projection, encoding, publication or arming. Probe:
`tmp/astra-algorithmic-fixes-20260928/codec-probe.clj`.

Root's focused tests:

- `seon.schema.datahike-test/repeated-canonical-storage-is-decoded-once`
  (new, decoder call count at 1/10/100 and successful nil).
- `seon.schema.datahike-test/edn-backed-attributes-have-one-canonical-datahike-round-trip`
- `seon.schema.datahike-test/a-write-encodes-a-slot-by-its-installed-attribute`
- `seon.db-test/durable-pull-digests-replay-without-retaining-read-results`

Foreign boundary and installation limits remain as above.

## Publication keys: derive only missed file content

`derivation-keys` retains the schema dependency world by its actual forms,
dependencies and row shapes, then caches each file key by analyzer identity,
kondo inputs, file content, that world and the file's schema roots. Schema
closure and selected form digests are shared separately by world and roots.
The stored key format is unchanged. A warm request over F files still makes
O(F) lookups, but rehashes and walks closures only for missed content; one
changed file no longer rehashes F files. A changed schema population causes
a conservative recheck; irrelevant schemas still leave the stored keys equal.
`stored-derivation-schema-keys` still discovers/parses stored inputs across
the program: this slice does not make the whole publication O(delta).

MCP scratch `scratch.astra.publication`, actual stored paths, 3,732 projection
schemas. Warm 1/10/100 file populations: 2.444→0.156, 6.793→0.250,
50.811→0.703 ms. Exact key maps equal, including separate probes changing
one file's input at each size. Memory was not measured. The world cache
conservatively charges 4,096 estimated bytes per schema. Reproduction:
`tmp/astra-algorithmic-fixes-20260928/publication-probe.clj`.

Final archive `712eb81a5` plus publication source/test: lint 0 errors, 1,725
warnings, 6.850 s; contracts 498 files, same 10 foreign script findings,
zero source/test findings, 4.501 s. No test request started.
`bin/seon drill --with src/seon/cluster.clj test/seon/cluster_test.clj` passed:
candidate `34771ce010d1f0d970f4e9e734dad5c38801d5c1`, base ready 213.586 s,
move ready 87.301 s, candidate ready 220.480 s, total 646.182 s. Its passing
root and JVMs were removed by the drill. This is the explicitly authorized
expensive drill. Source/test delta: +61/−8 lines.

Root's focused tests:

- `seon.cluster-test/publication-derives-only-the-changed-files-key`
  (new, one changed file causes at most one key derivation among 1/10/100).
- `seon.cluster-test/a-derivation-key-follows-what-its-file-derivation-reads`
- `seon.cluster-test/the-analyzer-digest-follows-the-analyzing-declarations-only`
- `seon.fn.publication-cache-test/lint-direct-callers-after-committed-declaration-changes`

Foreign boundary remains the one-path lane and script contracts above.
Latest health read: default pid 32304, start 2026-09-28T15:03:45Z, still
11 open faults, 184 failed tests and 54 unknown failed-test observations.
This lane did not replace default. Drill proof and scratch equality do not
claim root's installed regression round has passed.

## Arity gate: reuse Datahike's exact attribute revisions

`arity-base` now first looks up the base with the raw database's connection,
generation, conservative revision and the gate's selected attribute revisions.
These are Datahike's own change-tracking identities, including speculative
values (`datahike.db/speculative-cache-context` and
`datahike.query/source-context-unchanged?`). Temporal/detached values retain
the content fallback. Commit and datom-content caches remain for cross-lineage
reuse; report validation seeds both the content and revision entries.
No contract or validation is skipped.

Previously each speculative lookup materialized and compared all D arity
datoms even on a cache hit. Repeated unchanged lookups now take O(A), where A
is the fixed set of gate attributes, and scan zero datoms. A new lineage or
changed arity report still builds a content key once; this does not claim that
every changed write is O(delta). Alias entries conservatively charge the
base's weight again, so the bounded cache can evict earlier; retained memory
was not measured.

MCP scratch `scratch.astra.arity`, one immutable `db/with` value with no tx
data, 125,701 relevant datoms. Final warm 1/10/100 lookup timings:
2.801→0.021, 26.522→0.052, 269.779→0.235 ms; complete bases equal.
Reproduction body: `tmp/astra-algorithmic-fixes-20260928/arity-probe.clj`.
This optimizes writer lookup reuse, not publication/boot/arming machinery;
no additional drill or test request was started for this slice.

Final archive `682edab18` plus only db source/test: lint 0 errors, 1,725
warnings, 6.944 s; contracts 498 files, the same 10 foreign script findings,
zero source/test findings, 4.853 s. An earlier check took 34.624 s while drills
were active; that is contention evidence, not warm operation latency.
Source/test delta: +51/−8 lines.

Root's focused tests:

- `seon.db-test/repeated-speculative-arity-reads-do-not-rescan-program-datoms`
  (new, exact zero key scans at 1/10/100 repeated reads, equal bases).
- `seon.db-test/a-write-seeds-its-final-values-arity-base`
- `seon.db-test/a-report-touching-no-function-reuses-the-committed-arity-comparison`
- `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`

Foreign boundary and installation limits remain as above.
