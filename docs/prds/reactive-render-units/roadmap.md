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

The first reverse-index dependency is now implemented in the maintained
Datahike fork at `417649383c65e13f15ea41d394fb1ed742477965` and selected by
both Seon dependency aliases. The new public, pure
`datahike.api/query-attribute-dependencies` function returns concrete where
and literal-pull attributes or conservatively widens to `:all`. The existing
query result cache delegates to that same function; no second parser was
introduced. This also fixes literal pull dependency extraction, whose parser
value was wrapped in `datalog.parser.type.Constant` and therefore widened
despite the prior documented cache contract. Focused JVM proof passes, the
canonical Shadow Node build completes, and the full CLJS Datahike gate passes
105 tests/825 assertions with zero failures or errors. The next ordered slice
is the one `seon.db` observation-candidate projection.

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
3. Derive the conservative reverse candidate index from runtime-observed
   database read requests. **The Datahike public pure query/find-pull
   projection is complete at `41764938`;** next expose one `seon.db`
   observation-candidate boundary, then derive the existing unit state's
   reverse index. Do not duplicate Datahike's parser or ship broad-query as an
   interim mechanism.
   [[research/reverse-candidate-index-dependency-boundary-2026-07-15]] records
   the coordinated cross-repo cut, deletion boundary, and falsifiable proof.
   Exact result equality remains the final authority.
4. Normalize equivalent subscribers across tabs and prove single execution.
   Add recent-output LRU reuse only if profiling crosses its acceptance gate.
5. Harden the database navigator and coordinate-bound cursor as consumers, then
   move root, agent, canvas/context, debug, and data detail bodies onto the one
   lifecycle and delete every superseded transition/feed/cache path.
6. Attribute query, SCI, Hiccup, serialization, gzip, and drain cost; mechanize
   omission and latency evidence for closed, unchanged, and changed units.

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
