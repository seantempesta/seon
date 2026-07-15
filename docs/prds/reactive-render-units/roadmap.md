---
type: prd
status: planned
tags: [prd, web, database, flow]
---

# Reactive render units roadmap

## Outcome

One normalized unit engine derives every active UI projection from current
database facts, recomputes only units whose observed reads can change, shares
equivalent work across subscribers, and pays no detail/render cost while a unit
is closed.

## Current state

The shared gzip Datastar feed, normalized subscriptions, runtime read capture,
identical-output suppression, bounded recent database reads, and several lazy
debug/data shells exist. The live baseline still proves page-specific
transition logic and a non-transitive declared-attribute gate can skip exact
helper-indirected reads, leaving already-open root, plan, and transcript units
stale while a fresh render is correct. Root, agent, debug, and data projections
also retain different activation/detail paths, and open debug broadcasts can
cost hundreds of milliseconds. A 2026-07-14 read-only default-cluster probe
found one open root-debug subscription retaining 657 observations, all marked
non-replayable because its render thunk re-dereferences a different immutable
database value. Lazy unit activation also invokes its producer outside read
capture and rebinds with the inactive subscription's old dependencies, so a
newly open detail can miss a change read only by that producer.

The complete database-coordinate prerequisite has since landed on committed
HEAD. `seon.db.coordinate`, writer responses/events, replay pages, replica
progress, historical lookup, and web selectors now carry the closed
`{database-id, branch, commit-id, t}` value. Reactive-unit implementation no
longer waits for a replica commit id. Native branch operations, quiesced
restart, and post-commit runtime admission remain lifecycle work, but the pure
unit transition can consume the canonical coordinate now and proceed in
parallel. [[research/reactive-unit-database-browser-reconciliation-2026-07-14]]
defines that boundary and keeps the database browser a consumer of the unit
lifecycle rather than a second cache/listener/activation mechanism.

Completed earlier audits establish two constraints: active-unit reuse needs no
new library, and a Node `lru-cache` layer is justified only by measured reopen
reuse. [[research/reactive-render-source-audit-2026-07-14]] reconciles those
reports with current owners, tests, dependency sources, and the live default
baseline. It also records that the selected ClojureScript `1.12.145` source is
not yet mirrored exactly: the current reference checkout identifies itself as
`1.12.41`, so analyzer-sensitive implementation must close that grounding gap.

The first pure lifecycle kernel is now implemented in `seon.web.view-unit`.
It attaches equivalent consumers to one retained derivation, invokes producers
with the exact supplied immutable database value, captures and eagerly replays
every distinct runtime observation, suppresses equal results and serialized
output, and removes the complete unit after final close. Retained state is
closed plain data containing no database value or producer. A focused
synthetic debug-shaped unit proof passes 1 test/17 assertions. One real
production consumer now crosses that lifecycle: the first non-canvas HTML twin
in each debug catalog. Its activation receives one immutable database value,
captures observed reads, retains one serialized stable-ID element in the
existing feed registry, shares the derivation across subscription fingerprints,
transitions once per broadcast, distributes identical bytes to every consumer,
and releases on final view close. The remaining debug descriptors, whole-debug
transition, agent/root page transition, and declared-attribute veto are still
legacy and remain open work.

The same-subscription framing prerequisite and the first two bounded page
consumers are now implemented. The ordinary agent header and global system
header attach as always-demanded units before first paint, share the exact
immutable database value with page composition, transition only through
`seon.web.view-unit`, and leave no header declared-attribute or
captured-observation branch in `seon.ui.agent-view`. The system-header
coordinate deliberately omits page and agent identity, so different agent
subscriptions share one producer, captured-read set, and serialized complete
element while retaining independent socket consumers. Both retained strings
enter page composition as explicit immutable inputs; historical replacement
inherits no demanded live unit and renders directly from its frozen database
value.

Focused evidence is 59 tests/339 assertions across
`seon.ui.agent-view-test`, `seon.ui.header-test`, and
`seon.web.datastar-test`, with zero failures or errors. It covers
same-fingerprint two-socket sharing,
cross-agent subscription sharing, producer-once first paint and broadcast,
byte-identical global targets, concrete non-nil demanded tokens, same-view
reconnect, structural full-page plus same-id unit convergence, historical
replacement, first-close retention, and final release. Exact focused runner
output is retained at `tmp/test-cljs-20260715-012522-92741.log` and its sibling
report. Default-cluster browser and server-side gzip proof remains pending: the
cluster is currently watcher-only and other active runtime lanes have
uncommitted source, so this slice did not restart or bypass the operator.

The first reverse-index dependency is retained in the maintained Datahike
publication descendant `9ada755087228e10cfb179fa5779ce227a6ed220`, selected
by both Seon dependency aliases in the coordinated root cutover. The public,
pure
`datahike.api/query-attribute-dependencies` function returns concrete where
and literal-pull attributes or conservatively widens to `:all`. The existing
query result cache delegates to that same function; no second parser was
introduced. This also fixes literal pull dependency extraction, whose parser
value was wrapped in `datalog.parser.type.Constant` and therefore widened
despite the prior documented cache contract. Focused JVM proof passes, the
canonical Shadow Node build completes, and the full CLJS Datahike gate passes
105 tests/825 assertions with zero failures or errors. The next ordered slice
is the attribute-first `seon.db` observation-candidate projection and reverse
index inside the existing `seon.web.view-unit` state. It can proceed without
waiting for native branch/restart work: literal query attributes and exact
index prefixes are settled inputs, while point-entity and comparator-aware
range narrowing remain conservatively broad. Datastar must pass the complete
coalesced change and explicitly union renderer-token mismatches before its
all-unit traversal is removed. The database browser remains a consumer: its
opaque cursor is validated and resolved before an observed index read, and
cursor-backed feeds are frozen rather than current-head candidates.
[[research/reverse-render-unit-candidate-selection-2026-07-15]] records the
exact state shape, falsifier, file boundary, and implementation dependency
edges.

The next pure slice is now implemented and passes its focused Slice A gate.
`seon.db/read-observation-candidate` delegates literal-query projection to the
pinned Datahike function and retains broad fallback for every unsafe or
unproved read. `seon.web.view-unit` derives broad and per-attribute reverse
buckets in its existing state, selects from a complete coalesced change with a
caller-owned fail-open fallback, atomically reindexes a rerendered unit, and
removes every entry on final release. Focused projection and lifecycle tests
pass.
The executable JVM dependency probe distinguishes literal attributes from a
dynamic attribute position, while static diff/lint review is clean apart from
the known generated-Datahike-API lint blind spot. The exact CLJS gate requested,
matched, and executed 11 selectors across the focused read-observer and
view-unit namespaces: 11 tests and 116 assertions pass with zero failures or
errors. Its cold first-attachment case caught and now pins the public map-in
request envelope; no dependency or validator warm-up masks the boundary.

Slice B is also implemented in the existing Datastar transition owner. The
complete coalesced routing projection is validated before selection;
incomplete evidence fails open, renderer-token mismatches are unioned
explicitly, and noncandidate coordinates advance without replay or retained
derivation changes. Datastar now reduces only the resulting candidates rather
than traversing every active unit through exact replay. The smallest combined
exact gate requested, matched, and executed all three new boundary tests: 3
tests and 45 assertions pass with zero failures or errors. It proves an
unrelated attribute performs zero replay, producer, serialization, and emission
work; incomplete evidence performs one conservative replay; and a related
complete change replays and renders one shared unit once before identical
fanout. Retained evidence is
`tmp/test-cljs-20260715-045642-83341.log`. Live default-cluster gzip/browser
proof remains open graduation work.

The broader replay-all soundness oracle is now implemented in the existing
view-unit test owner. Six real retained unit shapes capture a literal query,
exact AEVT prefix, mixed literal reads, dynamic query, pull, and zero-read
producer. Seven generated nonempty domain-change combinations run serially
through `seon.db/transact!` on one explicit fresh connection. For each actual
post-write immutable value, the test-only replay-all authority derives every
dirty token and proves that complete changed-attribute routing selects a
superset. The unrelated-only case also proves that no proven literal/index
token becomes a candidate while unproved and zero-read units remain broad.
The exact selector requested, matched, and executed 1 test with 10 assertions,
zero failures, and zero errors. Retained evidence is
`tmp/test-cljs-20260715-052511-49015.log` and its sibling report.

The first bounded unit-cost diagnostic is now implemented in the existing
`seon.web.view-unit` state and Datastar broadcast owner. One latest-transition
plain-data snapshot records every active token as selected or not selected,
the exact distinct-read replay count, producer-boundary and serialization
invocation counts, the `not-candidate`/`equal-reads`/`equal-output`/`emitted`
outcome, and exact retained UTF-8 output bytes. `diagnostics` derives current
active-unit count and total retained bytes on demand. The snapshot contains no
database value, producer, handle, timer, or history; the final release restores
`empty-state`. Broadcast logs expose only bounded aggregates. A zero producer
count also proves zero nested SCI work because SCI can only be crossed inside
that producer; detailed inner SCI timing remains owned by the later render-cost
slice rather than a second hook here. The focused view-unit plus Datastar gate
passes 46 tests and 332 assertions with zero failures or errors. Retained
evidence is `tmp/test-cljs-20260715-075047-60284.log` and its sibling report.

## Research evidence

- [[research/reactive-render-source-audit-2026-07-14]] — current dependency
  ledger, live observations, ownership graph, deletion map, and regression
  matrix.
- [[research/reactive-ui-dependency-routing-2026-07-12]] — observed-read
  invalidation design and stale-unit evidence.
- [[research/sci-render-cache-source-audit-2026-07-12]] — SCI execution and
  render-cache boundary.
- [[research/datastar-sse-render-allocation-profile-2026-07-12]] — feed/render
  allocation and latency baseline.
- [[research/clj-cljs-bounded-cache-library-audit-2026-07-14]] — active-unit
  reuse and optional bounded recent-cache decision.
- [[research/root-reactive-system-view-audit-2026-07-14]] — root layout,
  reactive correctness, and browser acceptance evidence.
- [[research/reactive-unit-database-browser-reconciliation-2026-07-14]] —
  current coordinate reconciliation, smallest dependency-ready unit slice,
  database-browser consumer boundary, and falsifiable proof.
- [[research/agent-header-next-consumer-cutover-audit-2026-07-15]] — exact next
  page consumer, demanded-unit first-paint seam, deletion map, overlap boundary,
  and focused/live acceptance evidence.
- [[research/reverse-render-unit-candidate-selection-2026-07-15]] — the
  attribute-first reverse-index slice, opaque-cursor boundary, unrelated-write
  falsifier, state derivation, and implementation dependency edges.

## Ordered work

1. **First production unit and framing complete:** `seon.web.view-unit` owns pure attach,
   observed render, replay-all transition, output suppression, detach, and final
   release. `seon.web.datastar` commits one real lazy debug HTML descriptor into
   that state in its existing registry and fans emitted bytes through the one
   gzip transport. Managed fan-in emits a shared element once per normalized
   subscription while every socket receives the event.
2. **Both header source cuts and focused proof complete; live proof pending:** the generic
   always-demanded attachment/first-paint seam owns the ordinary agent header
   and the globally shared system header. Their legacy page dependency/veto
   paths are deleted. After active source lanes converge, perform one
   coordinated default restart and capture
   browser, server-side gzip, reconnect, cross-agent sharing, and final-release
   evidence. Continue agent/root units onto the lifecycle and remove the
   remaining page-specific dependency map rather than adding an interim
   routing path.
3. **Reverse candidate index and focused integration complete; live proof
   pending:** derive the conservative reverse candidate index from runtime-observed
   database read requests. **The Datahike public pure query/find-pull
   projection is retained at public descendant `9ada7550`;** `seon.db` exposes
   one attribute-first
   `seon.db` observation-candidate boundary and derives the reverse index
   inside the existing unit state. Literal query attrs and safe exact index
   prefixes narrow; every unproved operation/range remains broad. Datastar passes the
   complete coalesced change plus explicit renderer-token mismatches into the
   unit transition and no longer crosses exact replay for every active unit. Do not
   duplicate Datahike's parser or ship broad-query as an interim mechanism.
   [[research/reverse-candidate-index-dependency-boundary-2026-07-15]] records
   the coordinated cross-repo cut; the now-settled implementation boundary and
   falsifiable proof are in
   [[research/reverse-render-unit-candidate-selection-2026-07-15]].
   Exact result equality remains the final authority.
4. Normalize equivalent subscribers across tabs and prove single execution.
   Add recent-output LRU reuse only if profiling crosses its acceptance gate.
5. Harden the database navigator and coordinate-bound cursor as consumers, then
   move root, agent, canvas/context, debug, and data detail bodies onto the one
   lifecycle and delete every superseded transition/feed/cache path.
6. **First bounded work counters complete; detailed attribution pending:** use
   the retained latest-transition projection to capture the coordinated live
   checkpoint, then attribute query/replay time, inner SCI setup/body, Hiccup,
   event framing, gzip, and drain cost without adding history or another
   registry. Mechanize omission and latency evidence for closed, unchanged,
   and changed units.

## Graduation

- A helper-indirected read updates an already-open unit; an unknown read widens
  conservatively; declared attrs cannot suppress correctness.
- Unrelated transactions invoke zero corresponding queries, renderers, SCI, or
  serialization, while an affected change updates only the owning units.
- Equivalent tabs share work; final close releases active data; eviction may
  change latency but never output.
- Closed debug/data/root details construct no body or source/token work.
- Root, agent, canvas, debug, and data use one transition/feed mechanism and no
  cache retains a database/entity value.
- Focused gates plus real-browser static/interaction checks and server-side
  gzip frames prove initial render, live morph, reconnect, and cleanup.
