---
type: research
status: complete
tags: [research, database, web, flow]
---

# Coordinate-bound cursor contract

## Decision

The complete database coordinate and pure render-unit lifecycle have landed,
so cursor hardening no longer waits on replica identity. The next bounded
database-browser slice can replace the raw AEVT tuple with one opaque,
versioned, coordinate-bound cursor before adding entity/history pages. It does
not need the reverse reactive candidate index or a count API.

A cursor identifies a continuation in one immutable database projection. A
first page selected from a live branch head resolves and records the current
complete coordinate. Following its continuation freezes navigation at that
point by resolving the retained commit through `seon.db/at-coordinate`; it does
not reinterpret the cursor against a later head. A stale, mismatched, malformed,
or unresolvable cursor returns a typed data error before an index read.

This contract removes the current retraction-recovery ambiguity. Once a cursor
exists, the database value is frozen, so its last datom cannot disappear
between adjacent pages. A later live head is a different point and must restart
or deliberately open a fresh first page.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source read | Cursor constraint |
|---|---|---|---|
| Datahike indexes/history | maintained SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc`; clean `reference-code/datahike` branch `sync-upstream` | public index arities at `src/datahike/core.cljc:166-186`; prefix/seek/reverse bounds at `src/datahike/db.cljc:246-279`; history/as-of composition at `src/datahike/api/impl.cljc:152-198` and `src/datahike/db.cljc:471-673`; comparator order at `src/datahike/datom.cljc:324-368` | Current order is EAVT `[e a v tx]`, AEVT `[a e v tx]`, AVET `[a v e tx]`. Historical order adds `added` after those four components. Forward and reverse reads remain lazy and bounded through Seon's wrappers. |
| Complete coordinate | first-party committed `seon.db.coordinate` | `src/seon/db/coordinate.cljc:14-101`; retained-commit resolution at `src/seon/db.cljs:1547-1612`; focused proof in `test/seon/db/time_travel_test.cljs:54-107` | Every cursor carries `{database-id, branch, commit-id, t}`. `at-coordinate` checks attachment, retained commit, and temporal range and returns an errors-as-data value. Temporal wrappers cannot reconstruct commit identity themselves, so the resolved coordinate remains an explicit request input. |
| Render-unit lifecycle | first-party committed `seon.web.view-unit` | state/requests at `src/seon/web/view_unit.cljs:20-72`; coordinate transition at `src/seon/web/view_unit.cljs:144-236` | The opaque cursor token is stable semantic coordinate data; the resolved database point remains the separate `::database-coordinate`. A live first-page unit can advance. Any cursor page is frozen and excluded from current broadcasts. No database value enters unit identity or retained state. |
| Transit JSON | selected `com.cognitect/transit-cljs` `0.8.280`, backed by transit-js `0.8.874` | exact tag commits are present locally: transit-cljs `v0.8.280` at `afee4fd25cf04260a3428a05fe68b59d0b80611e`; transit-js `v0.8.874` at `88c484de99cc8f6f09989b80e1560c5f1100b17a`; selected dependency at `deps.edn:126-130` | Reuse the already-selected typed wire codec, then base64url the UTF-8 bytes. Transit handles keywords, symbols, UUIDs, dates, and `Uint8Array`; native JavaScript `BigInt` needs an explicit tagged handler and round-trip test. Decode is size-bounded before Transit runs. |
| Current browser | `seon.db.browser` plus `/data` adapter | `src/seon/db/browser.cljs:13-119`; `src/seon/web/debug.cljs:695-727,788-865,970-990`; tests at `test/seon/db_test.cljs:132-210` | Replace the tuple schema, `[:maybe ...]`, reader parsing, and always-present nil continuation in place. Preserve installed-schema navigation, bounded AEVT reads, URL ownership, the one feed, and exact read replay. |

The exact selected Transit tag objects are present even though the reference
checkouts currently point three and seven commits later, respectively. The
implementation must read or test the selected tag source, not assume the later
checkout is byte-identical.

## Source findings

### Five-component history cannot be delegated blindly

Datahike's historical comparator orders `added` after `[e a v tx]`,
`[a e v tx]`, or `[a v e tx]`. The public `datoms`, `seek-datoms`, and
`rseek-datoms` functions expose only four ordinary components after the index.
`components->pattern` destructures exactly four values at
`datahike/db/utils.cljc:205-209`.

A disposable in-memory JVM probe against the selected fork created one
cardinality-one replacement. The history rows were:

```clojure
[[2 :probe/value "first" 536870914 true]
 [2 :probe/value "first" 536870915 false]
 [2 :probe/value "second" 536870915 true]]

```

`(d/history (d/as-of db 536870914))` returned only the first assertion, proving
that as-of then history preserves the temporal cut through Datahike's composed
search context. Passing a fifth boolean component to `d/seek-datoms` did not
error, but `true` and `false` produced identical results because the fifth
value is silently ignored by `components->pattern`.

Therefore a history cursor must retain `added?` for exact row identity, while
the bounded seek passes only the first four Datahike components and applies the
full five-component comparison/drop in `seon.db.browser`. This is safe without
an unbounded scan: at most the equal four-component boundary rows precede the
next page, and the page still takes a fixed overfetch. Direct five-component
Datahike seek must not be claimed until the maintained dependency exposes and
tests that contract.

### A continuation freezes the point

`seon.db.coordinate/resolved` now derives the physical database id, branch,
commit id, and current `t` from one committed db value. `at-coordinate` loads
the named retained commit, verifies that it resolves the exact requested point,
and returns `d/as-of` at `t`. Datahike's `history` composes with that wrapper and
preserves its time predicate.

This gives adjacent pages snapshot semantics without an optimistic “cursor
datom was retracted” branch. The page request has two explicit values:

- the immutable database value used for the bounded read; and
- the resolved coordinate that was validated before constructing that value.

The latter remains necessary because an `AsOfDB` intentionally cannot derive
its containing commit id. Browser functions must not dereference the ambient
connection or infer identity from wrapper fields.

### Opaque is encoding, not process-local storage

The cursor is self-contained plain data encoded as Transit JSON and base64url.
It is not encrypted or signed for the loopback browser, and it is not an atom,
database fact, or server-side token registry. A strict encoded-byte ceiling is
checked before base64 decode and Transit read. The decoded map is then validated
against one closed version schema before any database operation.

The version-1 payload is structurally:

```clojure
{:seon.db.browser.cursor/version 1
 :seon.db.browser.cursor/database-coordinate
 {:seon.db.coordinate/database-id #uuid "..."
  :seon.db.coordinate/branch :db
  :seon.db.coordinate/commit-id #uuid "..."
  :seon.db.coordinate/t 536870915}
 :seon.db.browser.cursor/projection :history
 :seon.db.browser.cursor/index :aevt
 :seon.db.browser.cursor/prefix [:probe/value]
 :seon.db.browser.cursor/direction :forward
 :seon.db.browser.cursor/last
 {:seon.db/e 2
  :seon.db/a :probe/value
  :seon.db/v "first"
  :seon.db/tx 536870915
  :seon.db/added? false}}

```

`projection` distinguishes current from historical index semantics. `prefix`
binds the cursor to the requested entity/attribute/value slice. `direction`
binds forward versus reverse continuation. `last` uses the existing normalized
datom map instead of an unlabelled tuple; current rows still carry `added? true`
because `seon.db` already normalizes that field.

Page size is server policy and is intentionally absent. An unsupported Transit
value, native BigInt without the owned handler, unknown version, extra key,
wrong coordinate, wrong projection/index/prefix/direction, or non-comparable
last component produces one typed cursor error. It never falls back to the
first page.

## Render-unit relationship

Cursor hardening does not need to wait for the reverse candidate index. The
first implementation slice leaves the current whole-browser feed in place and
changes only its validated data contract. When the browser moves onto
`seon.web.view-unit`:

- a live first page uses a semantic unit coordinate such as page + projection +
  index + prefix + direction, while its current resolved point is the separate
  lifecycle database coordinate;
- a continuation includes the opaque cursor string in the semantic coordinate,
  attaches the cursor's resolved point as its database coordinate, and is
  frozen/non-live;
- equivalent tabs at the same cursor share one producer, observations, and
  serialized complete element; and
- close releases the unit through the existing final-consumer transition.

Do not include the changing head commit in a live first-page unit identity; it
would create one normalized unit per transaction instead of advancing the
existing unit. Do not let a frozen cursor subscribe to current broadcasts.

## Narrow implementation boundary

### Slice A — pure cursor and bounded page contract

Own only `src/seon/db/browser.cljs` and the established browser coverage in
`test/seon/db_test.cljs`:

1. Add closed versioned cursor payload/error/request schemas and Transit
   base64url encode/decode with a pre-decode size ceiling.
2. Replace `::optional-cursor` and raw tuple input with an optional opaque token
   plus explicit resolved coordinate/projection/index/prefix/direction request
   facts.
3. Validate the cursor against the request before calling `index-datoms` or
   `rseek-datoms`.
4. Generalize the small pure datom-component projection for EAVT/AEVT/AVET and
   current/history ordering; pass at most four components to Datahike and drop
   the full boundary row locally.
5. Return `::next-cursor` only when another bounded row exists. Omit the key at
   the end; never store or return nil.

No web, feed, render-unit, count, entity-detail, or history UI edit belongs in
Slice A.

### Slice B — one web adapter cut

After Slice A is proven, update only the existing `/data` query adapter and its
Datastar tests:

1. Parse cursor as an opaque bounded string, never `reader/read-string`.
2. Snapshot one db value and its resolved coordinate once for first paint.
3. When a cursor is present, resolve its point through `at-coordinate` before
   rendering; surface its typed failure as data without an index read.
4. Use the same cursor string in the feed/subscription key and generated links.
5. Preserve the one `/data/feed`, gzip framing, normalized subscription, and
   whole-browser morph until the later unit migration.

No new endpoint, listener, cache, browser-side writer, or page-specific
reactive mechanism is allowed.

## Falsifiable tests

Slice A is ready only when focused tests prove:

1. Transit/base64url round-trips the closed cursor for string, keyword, symbol,
   boolean, integer, floating point, UUID, instant, bytes, ref/eid, and native
   BigInt index values with exact type/equality semantics.
2. Oversize input is rejected before Transit read; malformed base64/Transit,
   unknown version, missing/extra keys, and unsupported values return typed data
   errors and throw nothing.
3. Database id, branch, commit, `t`, projection, index, prefix, and direction
   mismatches perform zero `index-datoms`/`rseek-datoms` calls.
4. A five-row AEVT fixture produces disjoint deterministic pages from one
   immutable point, including cardinality-many rows sharing an entity.
5. Advancing the live head after page one makes direct reuse against the new
   point stale; resolving the cursor's retained point reproduces the original
   second page exactly.
6. A removed boundary row cannot occur within the frozen point. A deliberately
   stale cursor is rejected rather than silently seeking a later head.
7. Forward current EAVT/AEVT/AVET and reverse current windows honor exact
   comparator order, bounded overfetch, and omit `next-cursor` at the end.
8. History pages include assertion/retraction identity. The selected probe
   shape pages `[first/add, first/retract, second/add]` without duplication or
   omission; changing only the ignored fifth argument at Datahike cannot be the
   implementation.
9. `history(as-of(point))` returns no event after the selected `t`, and every
   emitted continuation carries that same complete point.
10. Captured index reads remain replayable and retain no database/entity value;
    cursor encode/decode performs no database read.

Slice B additionally proves one immutable first-paint value, opaque URL
round-trip, errors-as-data rendering, frozen non-live continuation, equivalent
feed sharing, server-side gzip framing, reconnect, and final cleanup. Real
browser verification waits for a coordinated default pod; repository MCP
currently reports zero default pod runtimes, and this audit did not restart or
touch ACME.

## Deletion boundary

Delete together after Slice B parity:

- `::cursor [:tuple :int :any :int]` and `::optional-cursor`;
- `datom-coordinate`, `after-cursor`, and the retraction-recovery doc claim in
  their tuple-specific form;
- `/data` cursor `reader/read-string` and `pr-str` URL encoding; and
- always-present `::next-cursor nil`.

Retain `limit + 1` pagination, selected-attribute AEVT prefixing, normalized
datom rows, installed-schema navigation, exact read capture/replay, URL-owned
view state, and the canonical Datastar feed. The later render-unit migration
deletes the whole-browser transition only after navigator/detail parity; this
cursor slice must not pre-delete it.
