---
type: research
status: completed
tags: [research, database, web, flow]
---

# Entity and reference projection reconciliation

## Question and decision

What can the database-browser implement next without editing the active
reactive-unit lane, operating a pod, or creating another cursor or feed?

The opaque coordinate-bound cursor and `/data` adapter are sufficient
foundations, but they are not yet the entity/ref/transaction/provenance/history
browser promised by the PRD. The smallest source-disjoint executable slice is
pure entity and reference projection in `seon.db.browser` with a new focused
test namespace. It reuses `index-page` for EAVT and AVET, returns plain data,
and makes no `seon.web.*` change. Transaction/provenance and history UI remain
later slices because the active reactive lane owns their pay-for-open unit
composition.

One cursor defect must be fixed in that pure slice or immediately before it:
the decoded payload's request facts are compared before reading, but its sealed
`last` datom is not proven to share the encoded prefix and its scalar encoding
is not canonicalized before the seek. This is recorded in
[[../../../seon/issues/database-browser-cursor-boundary-is-not-pre-read-validated]].

## Dependency ledger

The audit was run at Seon revision
`f19afe23dc1f41b77a8e71d997bfdcd6d70014bf`. The working
`reference-code/datahike` pointer had advanced to test-only commit
`eb3e2239b650635977fdc8e73e7c657b23bf3383` in another lane. Seon's selected
dependency remains `417649383c65e13f15ea41d394fb1ed742477965`; every Datahike
claim below was read with `git show` from that exact revision, without moving
the shared checkout.

| Dependency or mechanism | Selected identity | Exact source read | Constraint |
|---|---|---|---|
| Datahike | maintained SHA `417649383c65e13f15ea41d394fb1ed742477965` | `src/datahike/{core,db,datom,schema}.cljc`, `src/datahike/db/{utils,transaction}.cljc`, `src/datahike/api/impl.cljc` at the selected object | EAVT/AEVT/AVET prefix and seek reads are lazy; history ordering adds `added`; only four public seek components exist; transaction metadata is EAVT-addressable by tx eid. |
| Konserve | maintained SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | exact `reference-code/konserve` checkout | One immutable database value must be captured and threaded; no database value belongs in a cursor or unit key. |
| Transit CLJS | Maven `0.8.280` | selected tag evidence retained by [[coordinate-bound-cursor-contract-2026-07-15]] | The existing version-1 base64url cursor is the only continuation. New projections extend its index/prefix use; they do not add tokens or registries. |
| Datastar | source `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | exact `reference-code/datastar` checkout | One event can carry multiple complete stable-id elements. The later UI composes units into `/data/feed`; no entity-specific feed is needed. |
| Datastar Clojure | source `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | exact `reference-code/datastar-clojure` checkout | Preserve the canonical gzip SSE channel and server-side stream proof. |
| Reitit | Maven `0.10.1`, source `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | exact `reference-code/reitit` checkout | Entity/ref/tx selectors remain `/data` query state; do not add action routes. |
| Seon database boundary | current `seon.db` and `seon.db.browser` | `src/seon/db.cljs`, `src/seon/db/browser.cljs`, focused DB tests | Reads use the sole API, one explicit db value, the complete coordinate, and replayable bounded index observations. |
| Reactive unit engine | active first-party migration | `src/seon/web/{datastar,view_unit,debug}.cljs` and reactive PRD evidence | This audit does not edit it. Closed detail omission and shared output become UI acceptance only after that lane settles. |

Selected Datahike grounds the reference path directly:

- `db.utils/attr->properties` adds `:db/index` whenever value type is
  `:db.type/ref`, so installed ref attributes have AVET support without a
  browser-maintained registry.
- `contextual-datoms`, `contextual-seek-datoms`, and
  `contextual-rseek-datoms` delegate to bounded index slices. EAVT `[eid]`
  returns one entity's facts; AVET `[ref-attr target-eid]` returns incoming
  references for one installed ref attribute.
- `flush-tx-meta` writes metadata as ordinary datoms on `current-tx`. A tx id
  is therefore also an EAVT entity id.
- temporal comparators order `added` after the ordinary four components, while
  public seek arities accept only four. Seon's local five-component boundary
  drop remains necessary for exact assertion/retraction continuation.

## Acceptance comparison

### Existing `/data` adapter

Already proven:

- GET is a cheap shell and `/data/feed` remains the one normalized gzip feed.
- URL state selects installed namespace, attribute, opaque cursor, and system
  visibility without writing browser state to the database.
- One database snapshot and its complete head coordinate feed first paint.
- A cursor feed resolves `at-coordinate` before opening, becomes frozen, and
  renders malformed/unavailable coordinates as data.
- The current visible table is a bounded AEVT attribute-carrier page.

Not yet accepted:

- entity and transaction ids are text, not stable coordinate-bearing links;
- no entity facts, outbound refs, reverse refs, transaction metadata,
  provenance, history, as-of, or raw selected-value detail is presented; and
- the browser is still one whole render target, so closed detail omission is
  not yet independently measurable.

### Opaque cursor Slice A

Already proven:

- one closed version-1 payload seals coordinate, current/history projection,
  EAVT/AEVT/AVET index, prefix, direction, and five-field last datom;
- forward and reverse pages are disjoint and bounded;
- retained-coordinate replay survives a moving live head;
- history continuation distinguishes assertion and retraction at the same
  ordinary four-component position; and
- string, keyword, symbol, boolean, integer, double, UUID, instant, BigInt, and
  bytes have explicit cursor tags.

Remaining defect:

`index-page` validates that the payload's coordinate/projection/index/prefix/
direction equal the request, then decodes `last` and seeks. It does not first
prove that decoded `last` round-trips to the one canonical encoding or that its
ordered components begin with the sealed prefix. For example, an encoded
keyword payload not beginning with `:` satisfies the current tuple schema and
is silently transformed by `(keyword (subs payload 1))`. A last datom outside
the selected prefix also passes the request comparison. Output stays bounded by
the subsequent `take-while`, but the invalid cursor has already caused an index
read. This violates the explicit zero-read malformed/mismatched contract.

### Entity and references

`index-page` already provides the primitive, but no named projection owns its
semantics. The next slice should add:

- an entity-facts request that delegates to current EAVT `[eid]`;
- installed-schema-derived ref annotation on returned rows;
- a reverse-ref request that accepts one installed ref attribute and target eid
  and delegates to current AVET `[attr eid]`; and
- a typed pre-read error for missing/non-ref/non-indexed attributes.

There is no all-incoming-ref index. A future aggregate must enumerate installed
ref attributes and open bounded per-attribute projections. It may show
truncation, never manufacture completeness or scan while closed.

### Transactions and provenance

A transaction id is an entity id, so metadata is a bounded EAVT `[tx]` page.
`:seon.db/user`, `:seon.db/process`, and `:db/txInstant` are ordinary tx facts;
the user and process displays follow those refs and their identity attributes.
No created-by/created-at projection belongs on a domain entity.

Backward transaction navigation may derive a capped descending id window from
the resolved head, but it must prove which ids correspond to committed tx
entities and handle gaps honestly. Effective datoms do not have a tx-leading
primary index. That body stays closed and capped until grown-history profiling
proves its reconstruction budget or Datahike owns a transaction index.

### History

The pure cursor supports five-field current/history continuation and
`at-coordinate` provides frozen transaction-time views. No browser links or
units consume them yet. Valid-time controls remain absent because `seon.db`
still has no public selected-source wrapper for Datahike's valid-time APIs.
Transaction-time navigation should graduate independently and label itself
honestly.

## Smallest source-disjoint executable slice

Own only `src/seon/db/browser.cljs` and a new
`test/seon/db/browser_projection_test.cljs`. Do not edit `seon.web.debug`,
`seon.web.datastar`, `seon.web.view-unit`, or their active test owners.

1. Strengthen the existing cursor boundary in place: canonical decode/re-encode
   every encoded component and require `last` to share the selected index
   prefix before calling `index-datoms` or `rseek-datoms`.
2. Add one entity-facts projection over current EAVT `[eid]`, returning bounded
   existing rows plus a derived ref flag from the one installed schema map.
3. Add one reverse-ref projection over current AVET `[ref-attr target-eid]`.
   Reject an absent, non-ref, or non-indexed attribute as data before AVET.
4. Reuse `index-page`, its cursor, row representation, coordinate, and errors.
   Do not add a database-browser cache, count, query scan, feed, route, or raw
   rendering path.
5. Replace the remaining authored `[:maybe :map]` return schema on
   `attribute-schema` with an honest namespaced result shape while touching its
   owner; absence remains explicit data rather than a stored nil convention.

Exact falsifiers:

- a crafted noncanonical scalar and a `last` outside the sealed prefix each
  return a typed cursor error with zero `index-datoms`/`rseek-datoms` calls;
- an entity with scalar and ref facts returns only that eid's EAVT rows, marks
  only the schema-proven ref row, and requests at most `limit + 1` rows;
- a scalar integer equal to an existing eid is not mislabeled as a ref;
- a ref attribute targeting one entity returns only incoming AVET rows for that
  attribute and target, with disjoint opaque-cursor pages;
- a non-ref or unknown reverse attribute returns a typed error with zero AVET
  calls;
- a missing entity returns an empty bounded page without pull, query, or whole
  database enumeration; and
- advancing the live head rejects direct cursor reuse while resolution of its
  retained coordinate reproduces the original entity/ref page.

Focused acceptance command after the slice exists:

```bash
bin/test-cljs seon.db.browser-projection-test
```

The web composition, closed-unit zero-work proof, server-side gzip stream, and
real-browser journey remain a later checkpoint after the reactive lane hands
back its settled unit contract.
