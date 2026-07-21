---
type: research
status: completed
tags: [research, database, web, flow]
---

# Database browser source audit — 2026-07-14

## Decision

The current `/data` path is the correct base, not a prototype to replace. It
already has a cheap shell, the canonical normalized gzip Datastar feed,
installed-schema navigation, bounded AEVT pages, URL-owned view state, and
exact read-result replay. The next implementation should extend
`seon.db.browser` in place and compose its details through the render-unit
engine from [[../../reactive-render-units/roadmap]].

Four contracts must settle before details are added:

1. Lifecycle must supply the complete resolved database coordinate
   `{database-id, branch, commit-id, t}`. A numeric `t` or current replica
   name cannot make a stable bookmark across branches or head replacement.
2. Browser cursors must be versioned, opaque, validated data tied to a
   coordinate, index, direction, and exact last datom. The current exposed
   `[entity value tx]` EDN tuple is only an AEVT continuation key.
3. The maintained Datahike fork must own any exact slice-count API. Its index
   layer already delegates to `persistent-sorted-set`'s counted subtree
   primitive, but no public Datahike API defines wrapper/history behavior.
4. Reactive units must receive one frozen database value. The data feed's
   initial render currently dereferences the replica twice and can make its
   otherwise replayable reads foreign.

Do not wait for an exact count to make the browser useful. Entity, reference,
transaction metadata, provenance, and bounded current/history windows can be
implemented without global counts. Unsupported historical counts should be
absent or explicitly unavailable rather than silently scanning.

## Scope and live observation

This audit read the active browser, database API, protocol/replica, feed, and
tests; the selected dependency source; the architecture target; and the
database-lifecycle and reactive-unit audits. It made no database writes and did
not touch ACME.

A read-only default-cluster probe observed basis `536870929`, 15,851 current
datoms, five namespaces and 26 attributes in the default navigator. The first
namespace was `:dh.ref`, proving the current system-attribute classifier leaks
Datahike implementation schema into the domain view. GET `/data` returned a
2,908-byte cheap shell with `loading data…`; a server-side compressed request
to `/data/feed` immediately emitted one `datastar-patch-elements` frame. The
frame contained the bounded navigator and the incorrect `:dh.ref` row. This is
recorded in
[[../../../seon/issues/database-browser-misclassifies-datahike-system-attributes]].

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source read | Browser constraint |
|---|---|---|---|
| Datahike | maintained git SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in both `:writer` and the CLJS override; nominal CLJS Maven `0.8.1681` is overridden | `reference-code/datahike` is exactly that SHA; `src/datahike/{core,db,datom}.cljc`, `db/{interface,search,utils}.cljc`, `index/{interface,persistent_set}.cljc`, `api/impl.cljc`, and `schema.cljc` | EAVT orders `[e a v tx]`, AEVT `[a e v tx]`, and AVET `[a v e tx]`. `datoms` is an exact prefix slice, `seek-datoms` begins at or after a key, and maintained `rseek-datoms` lazily walks at or before a key. Historical wrappers merge current and temporal indexes and apply time predicates, so a raw index count is not automatically a wrapper-correct result. |
| Persistent sorted set | Maven `org.replikativ/persistent-sorted-set` `0.4.137`, exact tag SHA `e1a17bbe767c7801e67407c81f64efabfd2f1601` | Exact source is missing from `reference-code`; this audit read a disposable project-local checkout at the tag, especially `persistent_sorted_set.{clj,cljs}`, `btset.cljs`, and its generative/invariant tests | `count-slice` is inclusive and O(log n) only when subtree counts are present; an old tree with unknown counts may degrade to O(n). Datahike exposes `-count-slice` and `-has-subtree-counts?` internally but no public prefix-count contract. Mirror this exact source before implementing the maintained dependency change. |
| Konserve | maintained git SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve` at the selected SHA; lifecycle findings in [[../../database-lifecycle-recovery/research/database-lifecycle-source-audit-2026-07-14]] | Each replica dereference may reconstruct a distinct immutable database value. Snapshot once and thread it through capture and every projection; never key a cursor or unit by a database object. |
| Seon database surface | first-party `seon.db`, `seon.db.browser`, protocol/replica | `src/seon/db.cljs`, `src/seon/db/browser.cljs`, `src/seon/db/{protocol.cljc,replica.cljs}` and focused DB tests | `index-datoms` and `rseek-datoms` already bound and record exact replayable results; `installed-schema`, `entity`, `pull`, `history`, `as-of`, `since`, `basis-t`, and `datom-count` exist. Valid-time wrappers and a public count-slice operation do not. The replica lacks commit id. |
| Datastar client | vendored RC.7-family bundle, source SHA `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | `reference-code/datastar/library/src/plugins/watchers/patchElements.ts` | One event can morph several complete stable-ID elements. Database details must be units in the existing feed, not new endpoints or client-side query state. |
| Datastar Clojure SDK | source SHA `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `reference-code/datastar-clojure`, especially `api/{elements,sse}.clj`, gzip adapter, and `examples/tiny_gzip.clj` | The current Node port correctly preserves `text/event-stream`, gzip, sync flush, and whole-element patches. Browser tooling cannot prove long-lived SSE; retain server-side gunzip acceptance. |
| Reitit | Maven `0.10.1`; reference SHA `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | `reference-code/reitit`; current `src/seon/web/router.cljs` | `/data` and `/data/feed` are still static-supplement routes. This PRD should not invent child action routes; navigation remains validated query/route data and feeds remain the one read channel. |
| Hiccup/HTML | first-party `seon.ui.html`, no external Hiccup dependency | `src/seon/ui/html.cljc` and tests | Stable unit IDs and deterministic serialization are available. Closed details must stop before Hiccup construction and token formatting, not merely hide serialized bodies with CSS. |
| Reactive unit engine | first-party, currently split across `seon.web.datastar` and `seon.web.view-unit` | [[../../reactive-render-units/research/reactive-render-source-audit-2026-07-14]], `src/seon/web/{datastar,view_unit}.cljs` | This PRD consumes the settled unit lifecycle. It must not add a database-browser cache, listener, activation registry, or changed-attribute shortcut. |

## Maintained dependency findings

### Prefix and seek semantics are sufficient for bounded pages

Datahike's comparators make the cursor layouts unambiguous:

- EAVT: `[entity attribute value transaction]`;
- AEVT: `[attribute entity value transaction]`; and
- AVET: `[attribute value entity transaction]`.

`datahike.db/contextual-datoms` slices between prefix-expanded lower and upper
bounds. `contextual-seek-datoms` slices from the lower bound to the end of the
index. The maintained reverse implementation uses `-rslice` from the expanded
upper key toward the beginning rather than realizing everything after the key.
Seon's public wrappers immediately `take limit` and normalize datoms, so their
read and retained observation are bounded.

The current AEVT page correctly requests `limit + 1` without a cursor and
`limit + 2` with one. It drops the first row only when the exact cursor datom
still exists. If that datom was retracted, the seek result begins at the next
available row and nothing extra is skipped. This behavior has a focused test,
but the test proves only disjoint adjacent pages on one immutable value. It
does not prove cross-head or cross-branch continuation.

### History changes the key and count problem

`d/history` is a `HistoricalDB` whose search context merges current and
temporal indexes. Temporal datom comparators add the `added` flag after
`[e a v tx]`, `[a e v tx]`, or `[a v e tx]`. A history cursor therefore needs
the assertion/retraction flag in addition to the ordinary four components;
reusing the current three-field browser cursor would not uniquely address a
historical event.

`as-of` and `since` are wrapper values that apply predicates over those merged
indexes. The wrapper `count` implementations call `count` on the visible datom
sequence. Therefore exposing the current index root's count for an as-of,
since, filtered, or history value would be fast and wrong. Counting current and
temporal subtrees independently can also double-count events that the history
merge deduplicates. The maintained Datahike API must define wrapper behavior;
Seon must not reach through wrapper fields or claim an O(log n) result it
cannot prove.

### Counted subtrees exist but need a fail-honest public contract

Datahike's index protocol already has `-count-slice` and
`-has-subtree-counts?`, and its persistent-set index delegates them to release
`0.4.137`. The exact release documents inclusive `[from,to]` semantics and
tests empty, lazy/reopened, modified, transient, and multi-layer trees.
Fresh-tree generative tests assert counts remain known.

The important compatibility edge is explicit in the release: missing subtree
counts can degrade a range count to O(n). A public Datahike operation should
either require/return the counted capability or expose an unavailable result
for an old index. It must never turn a UI label into an accidental full index
walk. Whole-current-database count remains simpler: `seon.db/datom-count`
already uses `di/-count` on the current EAVT root.

### AVET is conditional; reference AVET is available

AEVT exists for every installed attribute and is the correct carrier-entity
page. AVET is valid only for attributes Datahike marks indexed. Datahike's
schema derivation adds `:db/index` to uniqueness and reference value types, so
outbound and reverse ref navigation can use schema facts plus bounded AVET
prefixes. Ordinary non-indexed scalar attributes must remain AEVT samples and
must not imply complete value search or ordering.

Reverse references across every ref attribute have no single cross-attribute
index. The browser must first derive the finite installed ref-attribute list
from schema, then make each opened attribute its own bounded AVET unit. An
“all incoming refs” summary may merge capped per-attribute windows, but it must
state truncation and cannot manufacture a complete count by scanning every
ref attribute while closed.

### Transactions have no primary transaction-leading index

Transaction entities are directly addressable by EAVT because a transaction
id is also its entity id. User, process, instant, valid-time metadata, and any
custom tx fact are therefore cheap exact-prefix reads. The effective domain
datoms belonging to a transaction are not: none of EAVT/AEVT/AVET leads with
`tx`, and history reconstruction with a bound tx may scan broadly.

The first transaction view should page transaction ids backward from the
resolved head and lazily read exact transaction-entity metadata. Opening the
effective-datom body may run one explicit capped history reconstruction with a
measured budget. If grown-store profiling cannot meet that budget, the fix is
a Datahike-owned transaction-leading index, not stored Seon transaction
projections or a browser cache.

### Valid time is selected but not exposed through Seon

The maintained fork includes `valid-at`, `valid-between`, `valid-during`, and
`valid-all`. The first is a marker used by valid-time-aware secondary indexes;
ordinary Datalog patterns still need the built-in valid-time rule. Seon's
public database namespace currently exposes transaction-time `history`,
`as-of`, and `since`, but no valid-time wrapper. A browser claiming bitemporal
navigation must add one fully specified Seon boundary or explicitly graduate
transaction-time history first and leave valid-time controls absent. Calling
`datahike.api` from the web layer is forbidden.

## Current Seon mechanisms

### Correct pieces to preserve

- `GET /data` renders only the global header, feed opener, and loading stub.
- `/data/feed` uses `open-view-feed!`, the same normalized subscription,
  heartbeat, gzip, backpressure, and cleanup machinery as other pages.
- View state is in query parameters and no browser selection writes a datom.
- `attribute-groups` reads installed schema only.
- `attribute-page` uses AEVT and reads at most 51 rows for a 50-row page.
- `seon.db/index-datoms` captures the normalized bounded request and result for
  exact replay.
- Equivalent query projections share a feed key; repeated identical output is
  suppressed by the existing subscription owner.
- Tests cover domain/system grouping, bounded adjacent AEVT pages, replay on a
  changed window, exact feed key parsing, normalized first paint, and transport
  framing.

### Exact current gaps

1. **No lifecycle coordinate.** `data-params` carries namespace, attribute,
   AEVT cursor, and system toggle only. Protocol events and replica state do
   not expose commit id, so links cannot be lineage-complete yet.
2. **Cursor is transparent and under-specified.** It is reader-parsed EDN
   `[entity value tx]`, with a schema containing `:any`. It has no version,
   database coordinate, index, direction, attribute binding, or history
   `added` component. A cursor can be replayed under another attribute or
   database head and will be interpreted rather than rejected.
3. **Only one list exists.** There is no bounded EAVT entity fact page, outbound
   ref navigation, reverse ref page, transaction list/detail, provenance join,
   history event page, raw bounded unit, or valid-time selector.
4. **Rows are not navigable.** Entity and transaction cells are text; values
   are clipped display only. The page demonstrates the index but does not yet
   let an operator follow the graph.
5. **Whole browser is one unit.** Namespace navigation and opened attribute
   rows share `#data-browser`; the later detail doors do not yet exist.
6. **Initial data capture uses two db values.** `data-feed-definition` passes
   `@db/*conn*` to `render-observed`, then its thunk dereferences again. The
   change transition correctly threads its provided db. This shared feed-plan
   bug is added to
   [[../../../seon/issues/debug-feed-captures-foreign-database-reads]].
7. **System classification is incomplete.** `system-attribute?` recognizes
   only `db*` and `seon.*`; selected Datahike's `:dh.ref/*` implicit schema
   appears as domain data.
8. **Schema/result shape needs cleanup.** `::optional-cursor` uses the banned
   `[:maybe ...]` shape and every page includes `::next-cursor nil`. The new
   page result should omit the optional continuation key when no next page
   exists. Polymorphic datom values are a genuine dependency boundary, but the
   encoded cursor still needs an explicit supported-value codec and validation.
9. **No grown-store budgets.** Tests prove bounds by row count, not restored
   nodes, elapsed time, allocation, serialized bytes/tokens, or closed-detail
   omission on a grown historical database.

## Coordinate and cursor contract

The browser needs two related but distinct values.

### Database selector

A resolved immutable point is:

```clojure
{:seon.db/database-id #uuid "..."
 :seon.db/branch :db
 :seon.db/commit-id #uuid "..."
 :seon.db/t 536870929}
```

Entity, datom, transaction, history, provenance, and raw links carry that
resolved coordinate. A live branch-head view may use an explicit head selector
containing database id plus branch, but every render resolves it to a complete
point before reading. “Copy link” and historical navigation serialize the
resolved point. A fixed point is frozen and its feed is non-live; a head
selector advances through the normal feed. The semantic route selector and
the unit's current attached coordinate must not be conflated, or every commit
would create a new subscription identity.

### Cursor envelope

The URL should carry one opaque encoding of plain validated data such as:

```clojure
{:seon.db.browser.cursor/version 1
 :seon.db.browser.cursor/database-coordinate {...}
 :seon.db.browser.cursor/index :aevt
 :seon.db.browser.cursor/prefix [:my.plan/id]
 :seon.db.browser.cursor/direction :forward
 :seon.db.browser.cursor/last
 [:my.plan/id 1234 "plan-123" 536870900]}
```

History adds the `added` component; AVET and EAVT use their exact comparator
order. Decode returns a typed error value for an unknown version, malformed
value, coordinate mismatch, index/prefix mismatch, unsupported value type, or
stale lineage. Page size is server policy, not cursor authority. The cursor is
not a database fact and needs no signature for the loopback trust boundary,
but it must be bounded in encoded bytes before decoding.

## Data-oriented detail projections

Every projection accepts one explicit immutable db value plus one namespaced
request map and returns small plain data. The web layer owns URLs and Hiccup.

| Projection | Index/read plan | Bound and omission rule |
|---|---|---|
| Attribute navigator | installed schema | O(installed attributes); system groups excluded by derived classifier unless explicitly requested |
| Attribute carriers | AEVT `[attr ...]` | `page-size + 1`; no count required |
| Indexed attribute values | AVET `[attr ...]` | only when installed schema proves indexing; otherwise omit capability |
| Entity facts/outbound refs | EAVT `[eid ...]` | one entity's fact window; ref rows link using schema value type |
| Reverse refs | installed ref attrs, then AVET `[ref-attr eid ...]` | one active unit per opened ref attr; bounded merge/truncation for any aggregate |
| Transaction list | arithmetic ids from resolved head | bounded backward window; no history scan |
| Transaction metadata/provenance | EAVT `[tx ...]`, then exact ref reads for user/process | absent fields omitted; user/process/instant/valid-time displayed from tx facts |
| Transaction effective datoms | history query/reconstruction bound to tx | closed by default; explicit cap, deadline, and truncation; Datahike tx index if profile fails |
| Entity history | history EAVT prefix `[eid ...]` | bounded five-component cursor including `added` |
| Attribute/value history | history AEVT/AVET | only opened; bounded and coordinate-bound |
| As-of | `seon.db/as-of` from a resolved lineage point | frozen, non-live unit; selector errors are data |
| Valid time | future specified `seon.db` wrapper over selected Datahike behavior | omit controls until the public Seon contract exists |
| Raw EDN | already selected bounded row/entity/tx data | no second read; render only while open, with output-token cap |

## Deletion and replacement map

| Current owner | Delete or replace after parity | Retain |
|---|---|---|
| `seon.db.browser/system-attribute?` | Delete the incomplete string-prefix heuristic when one source-grounded framework/system classifier owns `db*`, `dh.ref`, `db.secondary`, and Seon attributes | `attribute-groups` as installed-schema-only navigation |
| `::optional-cursor` and always-present nil continuation | Replace with an optional `::next-cursor` key and a concrete opaque cursor codec | `limit + 1` continuation proof and seek-after-retraction behavior |
| `data-params` cursor EDN tuple parsing | Replace with one bounded decode/validate function returning data errors | URL-owned navigation and back/forward semantics |
| Text-only entity/transaction cells | Replace with reverse-routed graph links carrying the resolved coordinate | Compact bounded table presentation |
| Whole `#data-browser` render transition | Decompose navigator/list/detail into canonical active render units after the reactive PRD lands | `/data/feed`, normalized subscription, gzip, heartbeat, coalescer, and final-close cleanup |
| Initial `@db/*conn*` inside render thunk | Delete the second dereference and thread one frozen value | Existing exact read capture/replay |
| Any proposed browser count cache or stored projection | Never add | Datahike index counts and on-demand bounded projections |
| Any proposed `/data/sse`, browser writer, or client-side query engine | Never restore/add | Existing shell + canonical Datastar feed |

## Ordered implementation slices

### Slice 0 — consume upstream contracts

- Wait for database lifecycle to expose and validate the complete coordinate.
- Wait for reactive render units to own activation, observations, serialized
  output, sharing, and close.
- Mirror exact persistent-sorted-set `0.4.137` source under `reference-code`
  before modifying the maintained count path.

### Slice 1 — harden the existing bounded navigator

- Thread one db value through initial data capture.
- Replace system namespace guessing and prove `:dh.ref` classification.
- Introduce versioned cursor encode/decode with coordinate/index/prefix checks.
- Omit absent continuation data instead of returning nil.
- Preserve current AEVT page behavior and add malformed/stale cursor tests.

### Slice 2 — entity and reference graph

- Add explicit bounded EAVT entity facts.
- Derive outbound refs from installed schema and make their values links.
- Add opened per-ref-attribute AVET reverse-ref units.
- Compose navigator, table, entity facts, and raw selected data as independent
  active units; delete the whole-browser transition after parity.

### Slice 3 — transactions and provenance

- Page transaction ids backward from the resolved head.
- Read the transaction entity and user/process refs by exact EAVT prefixes.
- Make every visible row link to its transaction and entity coordinate.
- Add the closed effective-datom reconstruction unit with explicit cap and
  profile it on grown history.

### Slice 4 — temporal navigation

- Add five-component assertion/retraction history cursors.
- Add frozen as-of links resolved against database id, branch, commit, and t.
- Add valid-time controls only after `seon.db` has a specified selected-source
  wrapper; otherwise state transaction-time scope honestly.
- Prove stale coordinate and head-replacement rejection.

### Slice 5 — Datahike counts if still useful

- Add a library-general public count operation over index bounds.
- Define exact current, history, as-of, since, old-tree, empty, inclusive-bound,
  indexed, and non-indexed behavior before exposing it in Seon.
- Fail honest when counted subtrees or wrapper-correct semantics are absent.
- Use it only for labels that improve navigation; pagination never depends on
  a count.

### Slice 6 — graduation profile and cleanup

- Build fresh, grown-current, and grown-history fixtures.
- Attribute restored nodes, elapsed time, RSS/heap, output tokens, and render
  invocations per unit.
- Run focused DB/UI tests, full relevant gates, real-browser static/navigation
  checks, and server-side gunzip live/reconnect/close proof.
- Delete all superseded cursor, whole-view, and classification paths in the
  same refactor.

## Acceptance matrix

| Scenario | Focused test evidence | Live/REPL evidence | Browser/feed evidence |
|---|---|---|---|
| Fresh database | Installed attrs render without entity scan; empty pages omit continuation | Schema size and datom count read from maintained indexes | `/data` shell 200; initial gzip frame contains a calm empty/domain navigator |
| Grown current database | Every page reads at most `n+1`; cursor round-trips supported value types | Restored-node and latency budget for AEVT/EAVT/AVET windows | Next/back/reload preserve exact rows and URL state |
| Concurrent current write | Cursor boundary insertion/retraction cases have deterministic semantics | Same head selector advances; fixed coordinate remains unchanged | Relevant open unit morphs; unrelated unit emits nothing |
| Cross-branch same `t` | Coordinate/cursor mismatch returns typed error data | Two lineages with colliding t resolve to distinct commits | A bookmark cannot silently display the other branch |
| Entity navigation | EAVT facts and outbound refs are bounded; absent entity is omission | Follow entity/ref links against one db snapshot | Back/forward and ref traversal preserve coordinate |
| Reverse refs | AVET is used only for installed ref/index attrs | Open one ref attr and observe capped rows | Closed reverse-ref units issue zero read/render/serialization work |
| Transaction/provenance | Exact tx entity yields instant/user/process; effective datoms cap | Grown history meets deadline or records Datahike-index requirement | Tx links round-trip; raw body remains closed until activated |
| History/as-of | Five-field history cursor distinguishes add/retract; as-of is frozen | Current and historical values return expected facts | Frozen feed is non-live; returning to head resumes live updates |
| Valid time | Selected Seon wrapper has source-matched semantics or control is absent | Valid-at/between/during probe matches maintained Datahike | UI never labels transaction-time-only navigation as bitemporal |
| Equivalent tabs | One normalized active unit execution feeds both | Unit consumer count returns to zero after final close | Two tabs receive equal morphs; final close releases subscription data |
| Feed lifecycle | Initial render captures replayable reads from one db value | Unrelated write replays and suppresses without rerender | Server-side gzip shows initial/relevant morph, heartbeat, reconnect, cleanup |
| Real browser | Valid URLs reject malformed/oversized cursor data safely | No core fault or unexpected log | Phosphor layout, keyboard/back-forward, links, disclosures, and console are clean |

## Success measures

- All list paths are comparator-correct bounded index windows; no Datalog
  offset, whole entity scan, or complete history materialization serves a
  normal page.
- Every stable entity, datom, transaction, history, and provenance link is
  lineage-complete and rejects stale/mismatched cursors as data.
- Closed details execute zero database read, renderer, formatter, Hiccup, or
  serialization work.
- A current relevant commit updates only the open owning units; fixed history
  never rerenders; unrelated commits produce no browser morph.
- Counts are maintained-index operations with proven capability, or absent.
- `/data` remains one read-only composition over `seon.db`, the one render-unit
  engine, and the one gzip Datastar feed.

## Links

- [[../roadmap]]
- [[../../database-lifecycle-recovery/roadmap]]
- [[../../reactive-render-units/roadmap]]
- [[../../../seon/architecture/ui]]
- [[../../../seon/architecture/data-model]]
- [[../../../seon/architecture/observability]]
- [[../../../seon/issues/debug-feed-captures-foreign-database-reads]]
- [[../../../seon/issues/database-browser-misclassifies-datahike-system-attributes]]
