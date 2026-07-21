---
type: research
status: completed
tags: [research, web, database, flow]
---

# Reactive-unit and database-browser reconciliation — 2026-07-14

## Decision

The complete database-coordinate prerequisite is implemented on committed
`HEAD` (`b83f09de2591f01074474e4ca2a7e2e529c3aec8`). Reactive-unit work no longer
needs to wait for a replica commit id. `seon.db.coordinate` owns the closed
`{database-id, branch, commit-id, t}` value; writer responses/events, replay
pages, replica progress, historical lookup, and web selectors carry it.

The next dependency-ready slice is therefore the generic active-unit state
machine in `seon.web.view-unit`, not a page migration and not database-browser
detail work. It must:

1. identify one normalized unit with plain inputs plus a complete database
   attachment/current coordinate;
2. render from the exact immutable database value supplied by the transition;
3. retain consumers, runtime read observations/results, and the last serialized
   element only while active;
4. replay every unique observation on a transaction before any candidate index
   is allowed to optimize that set; and
5. release observations/output when the final consumer closes.

This slice can proceed while native branch operations, quiesced restart, and
post-commit runtime admission continue in the lifecycle PRD. Those lifecycle
features are required for multi-branch/restart graduation proof, but they do
not alter the pure unit transition. The unit must consume the canonical
coordinate API now so later branch support does not require another identity
model.

The database browser remains a consumer. `seon.db.browser` owns bounded index
projections and its versioned, coordinate-bound navigation cursor.
`seon.web.view-unit` owns activation, consumers, observations, replay sharing,
serialized output, and cleanup. `/data` page code owns URL presentation and
unit composition. None may grow a second listener, cache, active-unit registry,
or cursor-shaped substitute for another owner's data.

## Scope and method

This was a read-only reconciliation of committed `HEAD`. Concurrent uncommitted
worktree changes were excluded. No pod was restarted, no feed was opened, no
database fact was written, and no production source was edited.

The audit read the two PRD roadmaps and research, the UI architecture target,
the current database coordinate/protocol/replica, database read observer,
Datastar feed and tests, agent-view transition, lazy-unit door, data-browser
projection, and selected dependency source. Earlier live baselines remain
useful but were not repeated because their database sizes and open-feed counts
are observations, not contracts.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and existing usage | Constraint |
|---|---|---|---|
| Datahike | maintained SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in `:writer` and `:cljs` | `reference-code/datahike` at that SHA; `src/datahike/core.cljc:166-224`, `src/datahike/db/search.cljc`; Seon wrappers in `src/seon/db.cljs` | Database values are immutable points. Bounded `seek-datoms`/`rseek-datoms` results and query results are replayable plain data. One Datahike listener remains the transaction signal; it is not a per-page invalidation graph. |
| Konserve | maintained SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve` at that SHA; replica dereferences in `src/seon/db/replica.cljs` | Repeated dereference may yield distinct immutable host values. Snapshot once and thread the same value through capture and producer; never compare or cache database objects by identity. |
| Complete coordinate | first-party `seon.db.coordinate` on `HEAD` | `src/seon/db/coordinate.cljc`, `protocol.cljc`, `writer.clj`, `replica.cljs`; coordinate tests plus writer/replay/replica tests | Unit identity uses stable plain coordinate data. Attachment identifies the live lineage; the current complete coordinate identifies the rendered point. A bare `t` is never sufficient. |
| Read observer | first-party `seon.db/capture-reads` and `read-observation-changed?` | `src/seon/db.cljs`; `test/seon/db/read_observer_test.cljs`, `test/seon/web/datastar_test.cljs` | Runtime observations are the correctness authority. Empty, foreign, lazy, temporal, malformed, or unknown reads widen conservatively. Replay-all is required before adding a reverse candidate index. |
| Datastar | client source SHA `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f`; Clojure SDK SHA `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `reference-code/datastar/library/src/plugins/watchers/patchElements.ts:125-153`; `reference-code/datastar-clojure/.../api/elements.clj`; current `patch-elements` in `seon.web.datastar` | One event may carry several complete stable-ID elements. Default outer mode finds each target by child id. Activation must return this event protocol, not bare HTML or a second channel. |
| Hyperlith pattern | reference SHA `b08a8e8689e1654fd7e0ce654064a703ca1f4772` | `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-185` | Initial render, dropping/latest semantics, compression, and close cleanup validate the transport shape. Hyperlith rerenders a whole view; it is grounding for transport, not the target unit invalidation design. |
| SCI | `0.13.53`, reference SHA `b4917436550c857a18b8f6a4a8b5b26356acc2c4` | `reference-code/sci`; `src/seon/render/sci.cljs` and focused tests | A cache hit avoids an invocation. Never retain/fork an SCI context, deadline cell, var, atom, or host value. Cache only successful plain output and read facts. |
| ClojureScript / Shadow | selected CLJS `1.12.145`; Shadow `3.4.10` | `reference-code/clojurescript` SHA `946d75f...` identifies as `1.12.41`; exact Shadow release commit `d3c0469...` exists in its checkout | The pure lifecycle slice does not need analyzer internals. Renderer/source-digest work must wait until exact CLJS `1.12.145` source is mirrored. Declared source-literal attributes may hint cold candidates but never veto runtime observations. |
| Serialization and gzip | first-party `seon.ui.html`; Node `node:zlib` | `src/seon/ui/html.cljc`, `src/seon/web/datastar.cljs` and tests | Retain the deterministic serialized element, not a hidden Hiccup forest. Preserve `Z_SYNC_FLUSH`, heartbeat, latest-event backpressure, and once-only terminal cleanup. |
| Database browser | first-party `seon.db.browser` | `src/seon/db/browser.cljs`, `test/seon/db_test.cljs`; persistent sorted set selected `0.4.137` | Browser pages remain bounded `limit + 1` index reads. Cursor work is separate from unit work; exact count work still waits for the selected persistent-sorted-set source mirror and a maintained Datahike API. |

## Reconciled implementation state

### Implemented and worth preserving

- Complete database coordinates now exist throughout the committed read/write
  and historical-web path. The older database-browser claim that the replica
  lacks commit id is stale.
- `seon.db` captures normalized immutable read request/result facts and replays
  them conservatively without retaining database values.
- `seon.web.datastar` has one transaction listener, bounded coalescing, shared
  normalized subscriptions, shared first paint, output suppression, gzip
  flushing, heartbeat, backpressure, and final-consumer pruning.
- Stable unit tokens, cheap catalogs/stubs, trusted activation lookup, exclusive
  active sets, and a Datastar event response for `/view/unit` exist.
- Debug and `/data` already consume the canonical gzip feed. The retired
  duplicate SSE registries must stay deleted.
- `seon.db.browser/attribute-page` uses installed-schema gating and a bounded
  AEVT `limit + 1` window with exact observer coverage.

### Remaining correctness gaps

- `seon.web.view-unit` still owns encoding only. Unit consumers, observations,
  results, serialization, and final-close cleanup remain in feed/page-specific
  maps.
- `seon.ui.agent-view/transition` can veto actual helper-indirected runtime
  observations with a non-transitive declared-attribute intersection.
- Debug initial capture and `/data` initial capture pass one database value to
  `capture-reads` while their thunks dereference `db/*conn*` again. The reads
  become foreign/non-replayable instead of exact.
- Lazy activation invokes the producer outside observation capture, then moves
  subscription membership while retaining the inactive dependency set. A fact
  first read by the opened producer can therefore miss its next update.
- The browser's raw `[entity value tx]` cursor is not versioned, opaque, or
  bound to coordinate/index/prefix/direction. That is browser work after the
  generic unit transition, not a reason to put navigation data in unit state.
- The system-attribute classifier omits Datahike namespaces such as `dh.ref`.
- Root, agent, debug, and data still compose distinct transition shapes; the
  root is an ordinary agent layout whose system face is duplicated.

## Smallest dependency-ready implementation slice

The first code slice should stay inside `seon.web.view-unit`, its focused tests,
and the thinnest necessary call-through in `seon.web.datastar`. It should not
migrate a page yet.

Define one namespaced plain-data unit record with:

- normalized semantic coordinate and complete database attachment;
- stable plain inputs and renderer/source generation token;
- consumer ids;
- captured normalized read observations/results;
- deterministic serialized complete element; and
- active/inactive lifecycle state derived from consumer membership.

Implement pure transitions for attach, initial observed render, transaction
replay, replace observations/output, detach, and final release. The transaction
transition initially replays every distinct observation once against the one
supplied `db-after`; equal results skip the renderer, equal serialized output
skips the patch, and unsafe reads render conservatively. Producers receive that
same database value explicitly. No changed-attribute index is part of this
slice.

Then route exactly one existing lazy debug unit through it as the vertical
proof. Activation must capture the producer's reads; a relevant write must
patch that unit; an unrelated write must replay and suppress it; deactivation
and final feed close must remove its observation/output state. Only after this
works should candidate indexes and page migrations begin.

This order deliberately separates correctness from optimization. Removing the
agent page's false declared-attribute veto is urgent, but replacing it with a
generic unit transition gives one mechanism instead of another interim page
patch.

## Database-browser consumer contract

The browser consumes two upstream values without redefining either:

1. A render descriptor names the browser projection and its small inputs. The
   generic unit engine attaches the resolved database coordinate, captures the
   projection's reads, owns active consumers, and emits the complete element.
2. A browser cursor is opaque URL data decoded only by `seon.db.browser`. It
   includes version, complete fixed coordinate (or an explicitly resolved live
   head policy), index, prefix, direction, and exact last datom. Decode validates
   size and every field before any index read.

The cursor may be an input to a unit coordinate, but it is not the unit
lifecycle coordinate. Conversely, the unit token is not a database cursor.
Keeping them distinct prevents the database page from inventing a cache,
listener, activation graph, or alternate temporal identity.

After the generic unit proof, the smallest browser slice is navigator hardening:
thread one database value, fix system classification, implement the versioned
cursor in `seon.db.browser`, and make navigator/list separate unit descriptors.
Entity/ref/transaction/history units follow only after that parity cut.

## Falsifiable proof

### Correctness and reactivity

- A renderer whose direct source declares no relevant attribute calls a helper
  that reads one fact. Changing that fact updates an already-open unit.
- The same attribute changes on another entity while the captured result stays
  equal. The read is replayed, but renderer/SCI/serialization counts remain
  zero and no SSE element is emitted.
- A broad or non-replayable read never suppresses a needed render.
- Every emitted batch's elements derive from the same supplied complete
  coordinate. A frozen unit never receives current transaction work.
- Opening a lazy unit captures its first reads; closing it releases them; final
  subscriber close leaves no unit state.

### Sharing and cost

- Two equivalent tabs attach to one normalized unit. One relevant transaction
  produces one replay, at most one render/SCI invocation, one serialization,
  and fan-out to both sockets.
- Closed debug and database details record zero producer, query, pull, history,
  token-formatting, Hiccup, and serialization calls.
- Counters expose candidates, unique replays, unsafe reads, dirty units,
  renderer/SCI calls, serialized tokens, emitted elements, and released units.
- Profile query, replay, SCI, Hiccup, serialization, gzip/write, and drain
  separately. Do not raise the SCI deadline or add an LRU to improve a mixed
  latency number.

### Browser-specific proof

- A fixed-coordinate browser cursor remains frozen after concurrent writes; an
  explicitly live-head policy advances deterministically.
- Malformed, oversized, wrong-coordinate, wrong-index, or wrong-prefix cursor
  data is rejected before a Datahike read.
- Server-side gunzip shows initial paint, only relevant unit morphs, heartbeat,
  reconnect, and cleanup. A real browser verifies stable DOM identity,
  disclosure, back/forward navigation, focus, and a clean console.

## Dependency order from here

1. Implement and test the pure active-unit lifecycle with replay-all and one
   immutable database value.
2. Prove one lazy debug unit end to end, including activation and final close.
3. Move agent/root transitions onto the same lifecycle and delete the declared
   attribute veto and page-specific dependency map.
4. Derive conservative attribute/entity/index candidate descriptors from
   runtime observations; equality remains the final authority.
5. Harden the database-browser navigator and coordinate-bound cursor as a
   consumer, then split its opened projections into units.
6. Migrate remaining debug/data/root details and delete whole-view transitions.
7. Mirror exact CLJS `1.12.145` before analyzer/source-digest integration;
   mirror persistent-sorted-set `0.4.137` before count work.
8. Profile active sharing. Add a bounded recent-output LRU only if measured
   reopen reuse justifies its dependency and token budgets.

## Explicit non-claims

- Complete coordinates do not mean native branch create/delete, quiesced
  restart/restore, or post-commit runtime admission have graduated.
- Earlier live latency and observation counts are baselines, not current
  measurements for this source-only reconciliation.
- Datahike index seek semantics support bounded pages; they do not yet prove a
  public wrapper-correct exact slice count.
- The existing unit token encoding is stable for its current schema, but this
  audit does not claim collision-resistant content digests or warm-cache keys
  are implemented.

## Implementation evidence — 2026-07-14

The first pure lifecycle kernel now lives in `seon.web.view-unit`:

- `attach-consumer` derives only for the first consumer or an advanced
  coordinate/renderer token;
- `transition-unit` eagerly replays every distinct captured observation against
  the one explicitly supplied immutable database value, renders only when a
  result or renderer token changed, and emits only when serialization changed;
- `detach-consumer` retains shared units until the final consumer and then
  removes the entire unit; and
- retained unit maps contain the semantic coordinate, complete database
  coordinate, renderer token, consumer ids, immutable observations, and
  serialized complete element—never a database value or producer function.

`test/seon/web/view_unit_test.cljs` exercises a lazy debug-shaped producer with
two consumers and two helper-indirected reads. It proves no speculative render,
one shared initial render, eager replay beyond the first changed result,
unchanged suppression, relevant output, and final release. The focused gate is
1 test/17 assertions with zero failures/errors.

No production feed consumes the kernel yet. The next slice remains one real
lazy debug descriptor through this state machine, followed by agent/root
cutover; the existing whole-view and page-specific mechanisms must not be
described as graduated before those deletions.

## Links

- [[../roadmap]]
- [[reactive-render-source-audit-2026-07-14]]
- [[../../database-browser/roadmap]]
- [[../../database-browser/research/database-browser-source-audit-2026-07-14]]
- [[../../database-lifecycle-recovery/roadmap]]
- [[../../../seon/architecture/ui]]
