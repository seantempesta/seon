---
type: research
status: complete
tags: [prd, web, database, research]
---

# Reverse candidate index dependency boundary

## Decision

Ordered work item 3 cannot soundly narrow query-backed render units inside
`seon.web.view-unit` alone. The selected Datahike fork already owns the
conservative Datalog and pull dependency projection, but that projection is a
private implementation detail. Seon's captured read observations retain the
normalized request and result, not the dependency projection. The next
implementation is therefore a coordinated dependency/API slice, followed by
the unit index. It must not introduce a second Datalog parser, call a private
Datahike var, or ship an interim policy where every query is broad.

This is a sequencing boundary, not a correctness regression. The current
replay-all transition remains conservative and exact; it is expensive because
every active unit is visited and every distinct observation is replayed after
each database-coordinate advance.

## Dependency ledger

| Dependency or mechanism | Selected identity and checkout state | Exact source | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc`; `reference-code/datahike` is on clean branch `sync-upstream`, with `origin` at `git@github.com:seantempesta/datahike.git` | `reference-code/datahike/src/datahike/query.cljc:2462-2536,4038-4064`; indexes/listeners at `src/datahike/core.cljc:166-224` | Promote the existing conservative query and find-pull projection into one public pure API and make the result cache consume that same API. Do not copy its parser into Seon. |
| Seon dependency coordinates | writer pin at `deps.edn:25-29`; CLJS override at `deps.edn:147-149` | both select the same maintained SHA | A later Datahike commit must update both pins together and advance the `reference-code/datahike` gitlink to the exact selected commit. Default and ACME inherit the same root aliases. |
| Seon read observer | first-party `seon.db/capture-reads` and `read-observation-changed?` | request schemas at `src/seon/db.cljs:418-498`; conservative replay at `src/seon/db.cljs:1681-1778`; normalization in `src/seon/db/internal.cljs:956-1111,1244-1265` | `seon.db` owns observation-to-candidate normalization because it owns operation semantics. Unknown, foreign, malformed, temporal, lazy, and unsafe reads remain broad. Exact replay remains the correctness authority. |
| Active render units | lifecycle kernel at committed HEAD | `src/seon/web/view_unit.cljs:25-245`; all-unit traversal at `src/seon/web/datastar.cljs:1335-1365` | Extend the existing plain unit state with one derived reverse index. Do not create another registry, listener, feed, or page-specific gate. |
| Transaction evidence | listener input supplies post-db, pre-db, normalized datoms, and attr index | `src/seon/db/internal.cljs:1935-1964`; coalescing at `src/seon/web/datastar.cljs:700-727` | Candidate selection receives the complete coalesced change, not only `dbv`. Attribute/entity/index intersections are hints; exact read-result equality decides dirtiness. |

The Datahike source already implements the required conservative laws:

- literal data-pattern attributes accumulate into a set;
- a variable attribute becomes `:all`;
- nested boolean clauses are traversed;
- unknown rule/function shapes become `:all`;
- wildcard or input-bound pull patterns become `:all`; and
- query-cache invalidation consumes the merged where/find-pull result.

Those laws live at `query.cljc:2462-2536` and are applied at
`query.cljc:4060-4064`. They are private today. Seon's bounded query wrapper
also supplies work/result budgets, which bypass Datahike result caching at
`query.cljc:4038-4043`; reading cache internals cannot recover the projection.

## Coordinated API slice

### Datahike

Expose one pure public function over the same normalized query accepted by
`d/q`. It returns either a set of concrete attributes or `:all`. It owns both
where-clause and find-pull analysis, and the query result cache delegates to it.
Its dependency tests must cover literal attributes, source-qualified patterns,
`or`/`not`/joins, variable attributes, rules, wildcard pulls, input-bound pull
patterns, and malformed/unknown shapes. The public function must never execute
a query or retain a database value.

### `seon.db`

Add one pure observation projection whose result is closed plain data, for
example concrete attribute keys, resolved entity ids, index-window descriptors,
and `broad?`. It consumes the normalized observation plus only the immutable
facts needed to resolve a point ref/window at capture time. Operation policy:

- query uses Datahike's public conservative attribute projection;
- explicit pull combines that projection with a conservatively resolved point
  entity when possible;
- touched entity records its resolved point entity and is otherwise broad;
- EAVT/AEVT/AVET reads derive only prefixes/windows that can be matched safely
  against normalized transaction datoms;
- installed-schema and basis reads are broad until a narrower transaction law
  is proven; and
- unknown, foreign, lazy, temporal, malformed, opaque, or non-replayable reads
  are broad.

The projection belongs beside capture/replay. `seon.web.view-unit` must not know
query syntax, pull grammar, lookup-ref resolution, or Datahike index ordering.

### `seon.web.view-unit`

Retain the projection with each observation and derive one reverse index inside
the existing `::state`. Attach adds a token to every descriptor bucket.
Rerender replaces its old buckets with the newly captured conditional reads.
Final detach removes the token structurally. Candidate selection unions broad,
attribute, entity, and safe index-window matches plus renderer-token changes.
Non-candidates advance their proven database coordinate without replaying a
read or invoking a producer.

The exact `read-observation-changed?` result is still the final data authority,
and serialized string equality is still the emission authority. Candidate
descriptors may over-select but may never suppress a changed observed result.

### Datastar deletion boundary

Pass the complete coalesced change into the unit transition. Replace the
all-unit selection at `src/seon/web/datastar.cljs:1335-1365` with tokens returned
by the unit state's reverse index. Datastar continues to resolve trusted
producer descriptors and fan emitted complete elements to socket consumers; it
does not own dependency interpretation. Delete no legacy page transition in
this slice because consumers not yet migrated to `view-unit` still depend on
it.

## Falsifiable proof

The coordinated slice is complete only when focused tests prove:

1. A literal query attribute plus an unrelated transaction performs zero
   observation replay, producer calls, Hiccup construction, serialization, and
   emission for that unit.
2. A matching attribute on another entity selects the unit, exact replay finds
   an equal result, and the producer still runs zero times.
3. A changed observed result invokes only the owning normalized unit; equivalent
   subscribers receive byte-identical output from that one execution.
4. Variable attributes, rules not safely analyzable, wildcard/input-bound
   pulls, foreign/lazy/temporal/unknown/malformed observations, installed schema,
   and basis reads are broad and therefore cannot be skipped.
5. Explicit point entity and bounded index reads are never skipped when a
   matching normalized datom can alter their result; unrelated datoms select
   no work when the descriptor proves disjointness.
6. A conditional rerender atomically removes old reverse-index entries and adds
   its new observations; final consumer close removes every entry and retained
   output.
7. Renderer-token change selects its unit even when database datoms are
   unrelated.
8. Generated transaction/observation cases compare candidate routing with the
   replay-all oracle: every unit whose replayed result differs is in the hinted
   candidate set. Over-selection is allowed; one false negative fails the gate.

Live MCP evaluation could not run on 2026-07-15 because the default cluster had
one watcher and zero pod runtimes. The attempted cluster-qualified evaluation
returned `expected one :client runtime ... found 0`, matching `bin/seon status`
(`watcher alive`, writer and pod absent). No process was restarted and ACME was
not touched. Current source is sufficient to locate the cost: Datastar reduces
over every retained unit and `transition-unit` replays every distinct
observation whenever the coordinate changes.

## Ordered handoff

1. Finish the current owner of dirty `src/seon/db.cljs`; do not overlap it.
2. **Complete:** the public pure projection is committed in the maintained
   Datahike fork at `417649383c65e13f15ea41d394fb1ed742477965`; both Seon
   aliases and the reference gitlink select that exact commit. The result cache
   consumes the public function, literal pull `Constant` wrappers are handled,
   focused JVM proof passes, and the canonical Shadow Node gate passes 105
   tests/825 assertions.
3. Add the one `seon.db` observation candidate API and direct tests.
4. Add the `view-unit` reverse index, complete-change transition, and generated
   replay-all soundness proof.
5. Cut Datastar's all-unit selection loop, run focused CLJS gates, then capture
   one coordinated live default/browser/gzip proof when the default pod is
   available.
