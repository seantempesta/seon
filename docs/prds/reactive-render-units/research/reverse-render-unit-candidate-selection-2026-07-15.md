---
type: research
status: complete
tags: [prd, web, database, research]
---

# Reverse render-unit candidate selection

## Decision

The earliest sound reverse-selection slice is an attribute-first projection,
not a complete entity/window optimizer. `seon.db` projects each captured read
observation to closed plain candidate data. `seon.web.view-unit` derives and
maintains the reverse token index inside its existing state, and Datastar gives
that engine the complete coalesced transaction change. A transaction whose
attributes do not intersect a unit's concrete query or exact-prefix index
dependencies then selects no token and performs no read replay, renderer/SCI,
Hiccup, serialization, or emission work for that unit.

This consumes the already-public Datahike query projection without copying its
parser. It also consumes the database browser's settled opaque-cursor boundary
without decoding a cursor in the unit engine: the browser validates the token
and coordinate before issuing an ordinary observed `index-datoms` or
`rseek-datoms` request. Cursor-backed feeds resolve the retained point before
opening and are frozen, so they do not participate in current-head broadcast
selection. A current first page can expose a concrete exact prefix through its
captured read request.

Point-entity resolution and comparator-aware seek/rseek range narrowing are
useful later refinements, but they are not prerequisites. In this slice, every
observation that is not proven to have concrete attributes widens the whole
unit to the broad bucket. Exact replay remains the correctness authority for
selected units, and serialized equality remains the emission authority.

## Dependency ledger

| Dependency or existing mechanism | Selected version or SHA | Source read | Constraint on this slice |
|---|---|---|---|
| Datahike query projection | maintained fork `417649383c65e13f15ea41d394fb1ed742477965`, selected by both root aliases | `reference-code/datahike/src/datahike/query.cljc:2462-2557,4052-4083`; public API declaration at `src/datahike/api/specification.cljc:295-327`; proof at `test/datahike/test/query_test.cljc:42-77` | Call `datahike.api/query-attribute-dependencies` through the public API. Accept only `:all` or a set wholly composed of qualified keywords; any other library result widens. Never reproduce Datalog or pull parsing in Seon. |
| Datahike indexes and listener | same fork SHA | `reference-code/datahike/src/datahike/core.cljc:164-224`; index ordering/storage in `src/datahike/db.cljc`; Seon adapter at `src/seon/db.cljs:983-1054` and `src/seon/db/internal.cljs:1922-1958` | Captured exact-prefix index reads may project their first stable routing component. Seek/range shapes remain broad until comparator soundness is proved. Datahike's listener is only the refresh signal; Seon's normalized change remains the unit input. |
| Seon read observer | current branch owner `seon.db` | schemas/capture at `src/seon/db.cljs:418-573`; normalization at `src/seon/db/internal.cljs:939-1140`; replay at `src/seon/db.cljs:1681-1781`; tests at `test/seon/db/read_observer_test.cljs` | This namespace alone knows operation semantics. The projection is pure over a normalized observation and retains no database, Entity, function, or cursor object. Malformed, foreign, temporal, lazy, opaque, unknown, non-replayable, installed-schema, basis, pull, and entity reads are broad in the first slice. |
| Opaque database-browser cursor | current branch `seon.db.browser` | `src/seon/db/browser.cljs:26-115,155-274,276-437`; tests at `test/seon/db_test.cljs:162-389` and `test/seon/web/datastar_test.cljs:1390-1476` | Cursor decoding, scalar restoration, coordinate binding, and page boundary selection remain in `seon.db.browser`. `view-unit` must never parse the token. The observer sees only the resulting normalized index read; a cursor feed is frozen after its retained point is resolved. |
| Active unit lifecycle | current branch `seon.web.view-unit` | `src/seon/web/view_unit.cljs:13-256`; focused proof at `test/seon/web/view_unit_test.cljs` | Extend the one existing state. Attach indexes initial observations; a dirty rerender atomically replaces old buckets with new conditional observations; final detach removes the token from every bucket. Do not add another registry or subscription entity. |
| Coalesced Datastar change and feed | Datastar source gitlink `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f`; datastar-clojure gitlink `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | Seon coalescing at `src/seon/web/datastar.cljs:593-728`; all-unit traversal at `:1335-1365`; framing at `:295-321`; SDK semantics at `reference-code/datastar-clojure/libraries/sdk/.../elements.clj:51-124`; browser `outer` morph at `reference-code/datastar/library/src/plugins/watchers/patchElements.ts:39-155,193-219` | Pass the whole normalized change, not just `db-after`. Candidate routing occurs before producer or serialization. Datastar still resolves the trusted descriptor and fans one complete stable-ID element; selection does not introduce a second event or diff mechanism. |
| SCI-bound agent rendering | `org.babashka/sci` `0.13.53`; reference gitlink/tag `b4917436550c857a18b8f6a4a8b5b26356acc2c4` / `v0.13.53` | `src/seon/render/sci.cljs:392-534`; `reference-code/sci/src/sci/core.cljc:256-287`; interpreter checks at `src/sci/impl/fns.cljc:20-75` | SCI is downstream of the producer boundary. A noncandidate transaction must never initialize/fork an interpreter, rebuild an environment, evaluate source, or force lazy output. SCI has no role in dependency selection. |
| Hiccup serialization | current branch `seon.ui.html` | `src/seon/web/view_unit.cljs:120-133`; Datastar framing at `src/seon/web/datastar.cljs:295-321` | Serialization happens only after exact replay proves a selected unit dirty and its producer returns. The reverse index retains only the already-existing serialized string. |

The root dependency graph pins ClojureScript `1.12.145`, Datahike at
`41764938`, SCI `0.13.53`, and Transit CLJS `0.8.280`. No analyzer behavior is
needed by this slice; declared `:seon.fn/read-attrs` remain only a cold/focus
hint and cannot veto runtime observations.

## Current one-mechanism data flow

The implementation already has every boundary needed, but currently traverses
all active units:

1. `seon.db/capture-reads` runs the producer against one immutable database
   value and records normalized request/result facts.
2. `seon.web.view-unit/derive-unit` deduplicates those observations and retains
   them with the unit's coordinate, renderer token, consumers, and serialized
   element.
3. The one `seon.db/listen!` callback supplies `db-before`, `db-after`, plain
   datoms, and `attr-index`. Datastar coalescing preserves the earliest before,
   latest after, unioned attributes, and all normalized datoms.
4. `transition-active-units` currently reduces over every retained unit and
   `transition-unit` replays every distinct observation whenever its database
   coordinate advances.
5. Equal read results skip the producer; an unequal result invokes it once;
   equal serialized bytes suppress the patch; emitted complete elements fan to
   all consumers.

The new projection and index strengthen steps 1–4 in place. They do not alter
the producer, lifecycle, output, or transport contracts.

## Candidate projection owned by `seon.db`

Add one public pure function beside `read-observation-changed?`, with one named
map request and one closed response. The response needs only:

- a `broad?` boolean;
- a set of concrete qualified attributes; and
- optionally a set of concrete entity ids when an exact EAVT prefix proves one.

The first implementation can omit entity ids entirely and still be sound. An
attribute-only result gives the high-value query cut and leaves every other
shape broad. If entity ids are included in the same slice, they are an
additional over-selecting bucket, never a required intersection that could
suppress an attribute match.

Projection laws:

- The observation must be captured-source, replayable, valid for its known
  operation, and composed only of observer-normalized data. Otherwise it is
  broad.
- A query request is safely denormalized through the observer's existing
  denormalizer. Its query form goes to
  `datahike.api/query-attribute-dependencies`. `:all`, an exception, or a set
  containing anything other than qualified keywords becomes broad. A concrete
  set is the attribute descriptor.
- An exact `index-datoms` request (`seek? false`) with AEVT or AVET components
  beginning in a qualified attribute may name that attribute. An exact-prefix
  `rseek-datoms` request (`index-prefix? true`) may do the same. Empty prefixes,
  forward seeks, reverse non-prefix ranges, malformed component shapes, and
  other indexes widen unless separately proved.
- Pull and touched-entity requests remain broad in the first slice. Do not
  manufacture a fake query merely to reuse Datahike's pull parser; that would
  be a second semantic adapter whose contract Datahike does not expose.
- Installed-schema and basis observations are broad. The existing replay test
  already proves basis changes on every transaction.
- Foreign, lazy, temporal, opaque, unknown, malformed, and non-replayable
  observations are broad, matching `read-observation-changed?`.

An empty observation vector remains broad for this cut because the current
oracle treats it as dirty on every coordinate advance. Changing zero-read
semantics is a separate contract decision, not a candidate-index optimization.

## Reverse index derived inside `seon.web.view-unit`

Extend `::state`; do not create a sibling owner. The minimal closed plain shape
is:

```clojure
{:seon.web.view-unit/units {token unit}
 :seon.web.view-unit/broad-tokens #{token}
 :seon.web.view-unit/tokens-by-attribute {attribute #{token}}}

```

If exact point entities graduate in the same implementation, add one
`by-entity` map under this state. Do not store one candidate entity per
transaction or persist the index in Datahike.

Index maintenance is a pure replacement:

- first attach derives candidates from the captured observations and inserts
  the token once in each bucket;
- another consumer changes no buckets;
- a dirty rerender removes the token using the old observation candidates and
  inserts it from the newly captured conditional reads in the same returned
  state value;
- an unchanged replay advances only the unit's database coordinate and leaves
  the index byte-equal; and
- final detach removes the unit and every reverse entry, pruning empty sets and
  maps so `empty-state` remains exact.

Derive helper functions with `reduce`/`into`; do not add mutable per-bucket
atoms. `!feeds` remains the single process-local publication cell, and
`commit-registry!` continues to publish one completed pure transition without
retrying a producer.

## Candidate selection from one coalesced change

`view-unit` receives the complete change data already built by Datastar:

- `:seon.db/db-before` — earliest immutable value when present;
- `:seon.db/db` — latest immutable value;
- `:seon.db/datoms` — all normalized datoms in the burst;
- `:seon.db/attr-index` — datoms grouped by attribute; and
- `:seon.db/changed-attrs` — the conservative union.

Candidate tokens are the union of the broad bucket and every attribute bucket
named by `changed-attrs` or `attr-index`. If required change evidence is missing
or malformed, selection returns every active token. This fail-open rule keeps a
non-listener caller, test double, or future change shape from creating a false
negative.

Datastar then reduces only those tokens. It still resolves each token through a
current trusted consumer descriptor, invokes `transition-unit`, and distributes
one emitted string to every consumer. Noncandidate units do no replay or
producer work. Their retained database coordinate may be advanced by one pure
bulk state transformation so later renderer-token or forced transitions do not
replay intermediate coordinates; that transformation must not touch their
observations, output, or reverse buckets.

Renderer-token changes remain an explicit selection input, not something
inferred from transaction attributes. Today production tokens are stable
version strings. The integration function should union any token whose current
trusted descriptor token differs from its retained token before reducing. This
preserves the existing `transition-unit` law without assuming that every future
source change is represented by a particular database attribute.

## Opaque cursor boundary

The reverse index does not know that a cursor exists. `seon.db.browser` owns
four facts before any read occurs:

1. the token is canonical bounded base64url Transit;
2. its payload satisfies the closed scalar vocabulary;
3. its complete database coordinate and projection/index/prefix/direction
   match the request; and
4. its last datom becomes the internal index boundary.

For a cursor URL, Datastar resolves the retained database point before opening
the feed and the feed is not live. Therefore no current transaction should
select or advance it. For a current page without a cursor, `attribute-page`
issues an observed exact AEVT `[attribute]` prefix, which is eligible for the
attribute bucket. A later cursor continuation may issue a seek/range read; if it
ever becomes live, the first candidate implementation widens it rather than
guessing Datahike comparator semantics.

This is the clean dependency edge: cursor → validated read request → observation
candidate. There is no cursor → render-unit parser or cursor-owned cache.

## Shortest unrelated-write falsifier

Use one active unit whose producer performs only a literal-attribute query, for
example `:seon.agent/id` plus `:seon.agent/purpose`. Attach two consumers so the
shared-unit property is simultaneously exercised. Count these boundaries:

- candidate-token selection;
- `db/read-observation-changed?` calls;
- producer calls;
- SCI invocation (a test seam or an agent-authored producer in the integration
  case);
- `html/->string` calls; and
- emitted serialized elements.

Apply a transaction containing only a separately registered attribute, such as
`:seon.web.view-unit-test/unrelated`. The expected evidence is:

```clojure
{:candidate-tokens #{}
 :read-replays 0
 :producer-calls 0
 :sci-calls 0
 :serializations 0
 :emitted-elements []}

```

Then transact a matching query attribute on a different entity. The unit is a
candidate, exact replay returns an equal result, and producer/SCI/serialization
remain zero. Finally change the owning result: one unit replays, renders once,
serializes once, and both consumers receive byte-identical output.

The second case distinguishes candidate selection from correctness. Attribute
routing is deliberately allowed to over-select; exact result equality prevents
the expensive false positive from crossing the producer boundary.

## Files and implementation boundaries

The first implementation owns only these files:

- `src/seon/db.cljs` — candidate schemas and pure observation projection;
- `test/seon/db/read_observer_test.cljs` — projection widening and literal
  query/exact-prefix cases;
- `src/seon/web/view_unit.cljs` — derived reverse buckets, candidate selection,
  atomic reindex/cleanup, and noncandidate coordinate advance;
- `test/seon/web/view_unit_test.cljs` — pure state invariants and replay-all
  soundness oracle; and
- `src/seon/web/datastar.cljs` plus focused
  `test/seon/web/datastar_test.cljs` — pass the complete change, union renderer
  token mismatches, remove the all-unit traversal, and prove fanout.

No change belongs in `seon.db.browser`, `seon.render.sci`, page-specific
transitions, the Datahike fork, the JVM writer, the feed protocol, or the
Datastar event builder. The public Datahike projection is already committed and
selected.

## Test and live evidence

Focused implementation proof must cover:

1. concrete literal query attributes and Datahike's `:all` cases;
2. malformed/non-replayable/foreign/temporal/lazy/unknown observations widening;
3. exact AEVT/AVET prefix eligibility and seek/range widening;
4. unrelated-attribute zero replay/producer/SCI/Hiccup/serialization/emission;
5. same-attribute equal-result replay with zero producer work;
6. changed result invoking one shared unit once and emitting identical bytes to
   every consumer;
7. conditional rerender replacing old index buckets;
8. final close returning exact `empty-state` with no reverse entries;
9. renderer-token mismatch selecting a unit independent of changed attrs; and
10. generated observations/changes checked against the current replay-all
    oracle: every unit whose exact replay changes must be selected. False
    positives are allowed; one false negative fails the gate.

Run the existing database browser cursor gates unchanged to prove the consumer
boundary did not move. Then run the focused read-observer, view-unit, and
Datastar namespaces through the one CLJS runner.

Live graduation follows the coordinated default-cluster restart, not this
read-only lane. Open two equivalent pages and one unrelated writer action;
server-side counters/log evidence must show one shared retained unit and zero
replay/render work for the unrelated attribute. A relevant write must produce
one gzip `datastar-patch-elements` event carrying the stable complete element to
both sockets. Close one socket and prove retention; close the final socket and
prove the unit plus every reverse bucket disappears. Browser proof confirms the
stable-ID outer morph updates without losing signals.

No CLJS or live-system command was run during this audit. The unit-1 runner gate
owned a coordinated source freeze, and this lane was explicitly documentation
only. Current source and retained test evidence were sufficient to define the
falsifier and implementation boundary without invalidating that gate.

## Dependency edges and ordered handoff

1. **Already complete:** Datahike public query-attribute projection at
   `41764938`, selected by both Seon runtime aliases.
2. **Already complete:** complete database coordinates and opaque browser
   cursors, including retained-point resolution for cursor feeds.
3. **Complete:** the pure `seon.db` candidate projection and attribute-first
   `view-unit` reverse index pass their focused exact oracle tests.
4. **Complete:** Datastar consumes the complete coalesced routing projection,
   unions explicit renderer-token mismatches, advances noncandidates, and
   reduces exact replay only over candidates. Page-specific transitions for
   consumers that have not migrated remain intact.
5. **Next dependency-ready slice:** at the next coordinated default-cluster
   checkpoint, collect browser,
   server-side gzip, sharing, unrelated-write, and final-release evidence.
6. Only after the attribute-first oracle is green, refine point-entity and safe
   exact-prefix/range descriptors when profiling proves remaining replay cost.
7. Continue consumer migration and delete declared-attribute/page-specific
   invalidation only as each consumer crosses the one lifecycle.

## Implementation checkpoint — 2026-07-15

Slice A is implemented and proven in the existing owners without crossing into
Datastar integration:

- `seon.db/read-observation-candidate` shares the exact replay-admission
  boundary with `read-observation-changed?`, delegates literal-query analysis
  to Datahike's public projection, narrows exact AEVT/AVET prefixes, and widens
  every other read;
- `seon.web.view-unit` derives broad and per-attribute token buckets inside its
  one retained state, reindexes on a producer transition, removes every bucket
  on final release, and selects from exact complete routing evidence;
- Datastar validates that projection and fails open to every active unit when
  coalesced routing evidence is absent or malformed; and
- focused tests cover literal/dynamic queries, exact/seek index reads,
  attribute selection, fail-open selection, and exact reverse-index cleanup.

The executable dependency probe returned
`{:literal #{:example/value}, :dynamic :all}` from the pinned public Datahike
function. `git diff --check` is clean, and static lint found no new structural
finding beyond its existing inability to resolve Datahike's generated public
API vars. The exact focused gate requested, matched, and executed all 11 test
vars across `seon.db.read-observer-test` and `seon.web.view-unit-test`: 11 tests
and 116 assertions passed with zero failures or errors. The retained evidence
is `tmp/test-cljs-20260715-043626-48898.log` and its sibling report.

The gate first exposed a malformed internal call: `unit-candidate` passed a
captured observation directly to the public map-in function, so its request
destructured to nil and widened. The corrected owner wraps the observation in
the required `:seon.db/read-observation` request; the cold first attachment now
lands immediately in both literal-attribute buckets. No Datahike warm-up,
validator warm-up, cache, or special-case was added.

Slice B now passes the complete change through Datastar's existing transition
owner. It validates the closed routing projection, fails open to all active
tokens when that evidence is incomplete, unions renderer-token mismatches,
advances noncandidate coordinates without touching their retained derivation,
and reduces exact replay over candidates only. The focused integration law
proves an unrelated attribute performs zero replay, producer, serialization,
or emission work; incomplete evidence performs one conservative replay; and a
related complete change replays and renders the one shared unit once before
distributing identical bytes to both consumers.

The smallest combined exact gate requested, matched, and executed all three
new boundary tests across the database observer, view-unit, and Datastar
namespaces: 3 tests and 45 assertions passed with zero failures or errors.
Evidence is retained at `tmp/test-cljs-20260715-045642-83341.log` and its
sibling report. Live default-cluster gzip/browser evidence remains the next
ordered boundary; no page-specific transition was removed in this slice.

The broader soundness oracle now compares candidate routing with the retained
replay-all authority rather than restating projection examples. It captures six
representative unit shapes from real reads: a literal query, exact AEVT prefix,
mixed literal reads, dynamic query, pull, and a zero-read producer. A generated
matrix of all seven nonempty combinations of value, unrelated, and additional-
entity changes is applied serially through the public `seon.db/transact!`
boundary on one explicit fresh connection. Each scenario captures its own
pre-write retained state, inspects the actual post-write immutable database
value, and fails when any replay-all dirty token is absent from the candidates
selected by complete domain plus provenance transaction evidence. The
unrelated-only case separately keeps every literal/index unit out of the
candidate set while dynamic, pull, and zero-read observations remain broad.

The exact selector requested 1 var, matched 1 var, and executed 1 var. It
passes 1 test and 10 assertions with zero failures or errors. Evidence is
retained at `tmp/test-cljs-20260715-052511-49015.log` and its sibling report.
The oracle is test-only; production still has one candidate router and one
exact replay authority.

This ordering advances unit 2 without assuming unit-1 native branch/restart
contracts or the later root/debug/data consumer lifecycle. The pure candidate
mechanism consumes only contracts already committed on the current branch.
