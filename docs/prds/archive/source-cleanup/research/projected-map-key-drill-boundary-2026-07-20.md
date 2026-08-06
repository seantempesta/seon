---
type: research
status: complete
tags: [research, rendering, web, architecture]
---

# Projected map-key drill boundary (2026-07-20)

## Decision

Close the projected-key contradiction in the existing bounded skeleton. Keep
`:seon.render.value/map-entries` as the ordered vector of
`[display-key sampled-value]` pairs, remove the aggregate
`:seon.render.value/projected-keys` count, and add one deterministic metadata
field on the containing map marker:

```clojure
{:seon.render.value/map-entries
 [[display-key sampled-value] ...]
 :seon.render.value/non-drillable-key-indexes [0 3]}
```

The indexes refer to positions in the final retained `map-entries` vector,
not positions in the source map or pre-ranking candidate window. A missing or
empty vector means every retained displayed key is also its original legal
path component. An index in the vector means the key is a display label only:
the UI emits no drill control and no request is constructed from it.

This is the smallest one-mechanism fix because it:

- preserves the existing entry pairs and bounded map skeleton;
- carries no second copy of ordinary keys;
- never ships the original huge, collection, record, or host-object key;
- gives each entry an explicit drillability fact instead of an unusable
  aggregate count; and
- lets text and HTML derive the existing "shown safely" count from the same
  vector rather than storing both a count and an index set.

Do not introduce opaque key tokens, a parent-side key registry, hashes, or a
lookup table. Such a token would need mutable lifetime, collision, ownership,
and retirement semantics parallel to the live value. The owning child already
has the value; the honest contract is that a key which cannot cross the
declared path codec is visible but not drillable.

## Dependency ledger

| Dependency or mechanism | Selected revision and source | Contract consumed |
|---|---|---|
| Bounded value sampler | Unit 0 `d42a88de`; `src/seon/render/value.cljs:268-282,395-463` | `map-key-projection` is the one safe display projection and the map branch is the one bounded walk. Retention order is frozen before drillability indexes are emitted. |
| Unit 1B plain-data projection | [[schema-aware-value-projection-boundary-2026-07-20]] (`817a821f`) | `render-html-data` samples exactly once and uses the same skeleton for presentation, completeness, and later drilling. Non-drillable keys remain a strict partiality marker, so schema confirmation stays `:shape-only`. |
| Eager ordinary wire predicate | `src/seon/db/protocol.cljc:111-174` | Execution frames accept eager ordinary Transit values only. This broad predicate is necessary for a projection but is not sufficient as the HTTP path grammar: collections, binary data, dates, URIs, and tagged values are not automatically legal path elements. |
| Transit CLJS | `3d8a2c49ff1911fd7adfacce2776c3a6b8cc1fce`; selected dependency `com.cognitect/transit-cljs` `0.8.280` in `deps.edn`; `reference-code/transit-cljs/src/cognitect/transit.cljs:212-295` | The existing execution writer preserves ClojureScript maps, vectors, keywords, symbols, sets, and UUIDs as ordinary data. The child protocol continues to encode only the already-bounded projection and path; it never encodes the raw source key merely to discover whether it survives. |
| Existing execution codec | `src/seon/execution.cljs:163-198` | `encode-message` and `decode-message` are the single Transit boundary and both apply `ordinary-wire-value?`. No renderer-local writer or alternate key codec is added. |
| Read-only value route | [[value-route-authorization-boundary-2026-07-20]] (`7b6e2243`) | The HTTP path is a URL-encoded EDN vector with a closed scalar grammar and byte/segment limits. Marker maps, collections, projected display labels, and trailing EDN are rejected before child IPC. |
| Data-oriented render law | `src/seon/render/AGENTS.md` and `docs/seon/architecture/ui.md` | Drillability is immutable derived projection data, never stored browser state. The generic renderer remains the single guarded walker and the UI is only its consumer. |

Orchard's elided-tail pattern remains relevant to paging, but it contributes no
key-token mechanism. Reitit, Datastar, Datahike, and execution-host lifecycle
are downstream consumers, not dependencies of this projection repair.

## Exact implementation owner

The dependency-ready projection repair owns exactly:

- `src/seon/render/value.cljs`; and
- `test/seon/render/value_test.cljs`.

In `value.cljs`, the map sampler derives
`:seon.render.value/non-drillable-key-indexes` after ranking and retention,
using output-local `map-indexed` positions. `truncated?`, the text emitter, and
the HTML projection derive their partiality or display count from that vector.
The obsolete aggregate `:seon.render.value/projected-keys` key and its prose
are deleted in the same change.

No route, child, config, database, or generic HTML interaction code belongs in
this first edit. The current `src/seon/render.cljs` view has no server drill
request and only displays the bounded tree, so changing it now would invent
the later UI mechanism. Its future interactive migration consumes the frozen
index vector and receives its own focused tests. The route parser independently
rejects illegal path elements; it does not reconstruct drillability from a
display label.

## Drillable-key law

`map-key-projection` must report two independent facts: the safe display value
and whether the original key may be a path component. A key is drillable only
when the displayed value is the original value and the frozen route scalar
codec proves this round trip:

```text
source key
  = EDN-decode(URL-decode(URL-encode(EDN-encode(source key))))
  = Transit-decode(Transit-encode(decoded key))
  = key used by get/get-in in the owning process
```

The initial closed grammar is conservative: nil, booleans, finite numbers,
bounded strings, bounded keywords, and bounded symbols. A UUID or another
tagged scalar becomes drillable only when the route registers its EDN reader
and a focused end-to-end codec test proves equality through both the HTTP and
Transit legs. Non-finite numbers, negative zero if it is not identity-stable
under the selected reader, collections, marker maps, records, host objects,
and any clipped or replaced key are display-only.

This rule is stricter than `ordinary-wire-value?` on purpose. The ordinary
predicate answers whether a complete eager value can cross a protocol frame;
it does not promise that URL-encoded EDN reconstructs a map key with lookup
identity. The route grammar owns admission, while the skeleton's index vector
owns whether the UI may offer that route for a visible entry.

Vector positions remain ordinary non-negative integer path components.
Sequence and set rows are pageable views, not stable `get-in` branches. A map
key that is display-only does not become addressable because its sampled value
contains a drillable descendant: the path is broken at that entry and every
descendant control is omitted.

## Determinism and bounds

The non-drillable index vector is ascending in final entry order. It is not a
set, because a vector has canonical Transit/EDN order and therefore preserves
the byte-identity law without relying on set serialization. Repeated sampling
of the same value at the same activated projection and budget must produce the
same entries and the same index vector.

Deriving the vector is bounded by the retained entries already in memory. It
does not revisit the source map, print an original projected key, or encode a
trial path. Unit 0's work law remains unchanged: inspect at most the bounded
candidate window plus one tail sentinel and recursively sample only visited
candidate values. The projection contains only bounded display markers, so a
one-megabyte string key and an opaque key with a hostile printer never enter an
IPC frame or path query.

The vector is also a completeness marker. Any non-empty value keeps
`:seon.render.value/truncated?` true and prevents Unit 1B validation/explanation,
matching the current semantics of projected keys. Empty omission is preferred
to storing an empty vector.

## Shortest falsifiers

1. Sample a map containing ordinary, long-string, collection, and hostile
   opaque keys. Assert `map-entries` remains pairs and the ascending
   non-drillable indexes identify exactly the latter three in final retained
   order.
2. Force ranking to discard and reorder candidates. Assert indexes refer to
   the final retained vector, not source or candidate positions.
3. For every entry not named by the vector, append its displayed key to the
   current path and prove `get-in` on the original value returns the displayed
   entry's raw child.
4. For every named index, prove the presentation model exposes no path
   component. A projected marker submitted manually is rejected by the route
   parser before lookup and before a child-host send.
5. Use a one-megabyte string key and a record key whose printer increments or
   throws. Assert the output and Transit frame remain bounded, the original key
   is absent, and the printer is never invoked.
6. Repeatedly sample insertion-equivalent persistent maps at the same budget.
   Assert byte-identical `pr-str`/Transit output and identical ascending index
   vectors.
7. Round-trip each admitted scalar path through the actual URL/EDN parser and
   the existing execution Transit codec, then use the decoded path against the
   original map. Include Unicode strings and namespaced keywords/symbols;
   reject non-finite or otherwise non-identity-preserving numbers.
8. Assert a non-empty index vector makes the projection incomplete and Unit
   1B emits only `:shape-only`, with validator and explainer spies untouched.
9. In the interactive UI unit, assert an ordinary key and vector index render
   a drill control, a projected key and every descendant beneath it do not,
   and the visible safe label remains present.
10. In the child/route unit, assert an admitted ordinary path reaches the
    correct child value while an illegal or projected path yields no child
    request. Transit-round-trip the complete request and bounded projection.

## Handoff order

1. Freeze Unit 1A, then implement Unit 1B's single-sample projection.
2. Land this two-file projection repair and its pure falsifiers. Close the
   issue only after the later route and UI consumer proofs also pass.
3. Freeze the sibling total-work/config contract from
   [[../../seon/issues/value-drill-has-no-total-work-bounds]].
4. Extend the execution child with the already-ruled closed correlated sample
   frames. It consumes the path grammar and bounded projection without
   inventing key tokens.
5. Add the value route parser/authorization seam. Its strict parser repeats
   path admission before any child send.
6. Migrate the interactive generic UI. It forms a child path only from a map
   entry absent from `non-drillable-key-indexes`, and propagates the disabled
   state to every descendant.
7. Run the integrated sampler, Transit, route, child-retirement, and real
   browser matrix; archive the issue with that proof.

This ordering lets the projection owner define one honest fact, then makes
each authority enforce its own boundary. No downstream consumer may recover
an original key from a summary, hash a projected label, or make the display
marker itself a path component.
