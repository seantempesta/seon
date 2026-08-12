---
type: research
status: complete
tags: [research, database, architecture]
---

# Database result-union boundary (2026-07-20)

This report grounds the remaining Stage 5 database-result discriminator work
under the settled ruling in [[../roadmap]] and
[[../../../seon/issues/arbitrary-database-results-collide-with-error-shape]]:
message presence is not a discriminator; fixed success results may remain bare
only when their registered shape is provably disjoint from one closed database
error shape; arbitrary database values require an explicit closed outer union.

This is an implementation boundary, not another design choice. The public
facade remains `seon.db`, the wire remains `seon.db.protocol`, errors remain
`:seon/error` data, and `seon.result` remains the owner of the shared
`:seon.result/ok?` discriminator. No compatibility facade or second predicate
is permitted.

## Dependency ledger

| Dependency or existing mechanism | Selected source | Constraint established here |
|---|---|---|
| Malli | `metosin/malli` 0.20.0; `reference-code/malli` at `80138076960e` | `reference-code/malli/src/malli/core.cljc:1210-1319` proves `[:map {:closed true} ...]` rejects every unregistered key; `:or` uses child validators at `:980-1038`. Closedness, not key sniffing, establishes disjointness. |
| Datahike | maintained fork at `reference-code/datahike` commit `6f2569087ed3` | Query and pull results retain native arbitrary shapes. The protocol deliberately registers their payload as `:any`; the facade must not reinterpret payload keys. |
| Wire response union | `src/seon/db/protocol.cljc:199-233, 752-956` | Every wire response is already discriminated by the closed `:seon.db.protocol/success?` variants. The defect appears only when `seon.db` projects a successful query/pull/schema response to its bare payload. Do not change the wire. |
| Public database facade | `src/seon/db.cljs:21-175, 211-212, 778-1199` | `::error` is currently open, and private `error-value?` recognizes only a string message. `query`, `pull`, and `entity` return collision-capable bare values; the other reads need concrete fixed facade schemas. |
| Shared result discriminator | `src/seon/result.cljs:1-10` | Reuse `:seon.result/ok?`; add only the generic payload shape required by the database result union. Do not create `:seon.db/ok?`. |
| Schema-derived validation | `src/seon/schema.cljc:511-522` | `schema/valid-candidate-value?` is the existing registry-aware validator seam. The one public database predicate derives from `::error` through this seam; no repeated structural checks remain. |
| Existing collision regressions | `test/seon/agent/home_test.cljs:142-181`; `test/seon/agent/debug_test.cljs:125-154` | An installed-schema map legitimately keyed by `:seon.error/message` is success data; an actual database failure must stop a subsequent pull. These tests are retained and migrated to the explicit contract. |

## Shortest falsifier

The smallest decisive example is a successful scalar query whose value is
exactly the closed database error map:

```clojure
{:seon.error/message "ordinary domain text"
 :seon.error/kind :user-input}
```

Today `query` returns that map bare, so every message-based consumer classifies
success as failure. Tightening the error predicate alone does not solve this:
the value can exactly satisfy the closed error schema. The required result is
therefore an outer success value such as:

```clojure
{:seon.result/ok? true
 :seon.result/value
 {:seon.error/message "ordinary domain text"
  :seon.error/kind :user-input}}
```

A real failure has the same closed outer keys, `ok? false`, and carries the
closed database error as `:seon.result/value`. The discriminator is therefore
outside arbitrary data. Tests that merely use a success map with an extra key
are too weak: a closed error predicate would reject it and conceal the exact
collision.

## One closed database error and one predicate

`src/seon/db.cljs:24-29` owns `::error`, but the map is open. Make it closed,
with exactly required `:seon.error/message` and `:seon.error/kind`, plus the
optional ordinary `:seon.error/data`. All database failure constructors must
return that shape. In particular, the two `catch` sites currently returning
the richer `seon.error/->map` value must project it once into the database
error vocabulary (message, defaulted kind, optional data); raw JS errors,
stack, cause, and other forensic fields remain owned by `seon.error/record!`
and do not leak through this public database contract.

Promote the current private `error-value?` at `src/seon/db.cljs:211-212` to one
public, schema'd `error?` predicate implemented from `::error`, not from keys:

```clojure
(schema/valid-candidate-value? ::error value)
```

Every fixed-result caller that must branch on a database failure uses this one
predicate. Delete the duplicate message-string predicates in consumers as
their owner group migrates. Do not introduce a second predicate in
`seon.result`, `seon.error`, or a helper namespace.

This predicate is appropriate only where the success schema is disjoint. It
must never inspect an arbitrary query or pull payload; those callers inspect
the explicit outer `:seon.result/ok?` value instead.

## Closed outer result contract

Add `:seon.result/value :any` in `seon.result`. In `seon.db`, register one
closed union whose variants are:

- success: exactly `{:seon.result/ok? true :seon.result/value <arbitrary>}`;
- failure: exactly `{:seon.result/ok? false :seon.result/value <::error>}`.

The `:any` is justified at this single genuine Datahike boundary; it is not a
license for untyped domain schemas. Nesting the database error under the one
payload key lets the failure variant reference `::error` directly instead of
copying its fields or weakening its closedness. Helper constructors and
accessors, if named, live in `seon.db`; they are pure transformations over this
registered data, not another facade.

Historical eval/result EDN is not rewritten. This changes newly returned
public read values and their direct consumers only.

## Complete public-operation inventory

The inventory below covers every public function in `src/seon/db.cljs`, not
only the obvious query functions.

| Public operation | Current success shape | Classification and required action |
|---|---|---|
| `db` | closed `:seon.db/db` | **Fixed/disjoint.** Remains bare. Its schema becomes `[:or :seon.db/db ::error]`; callers use `db/error?`. |
| `as-of`, `since`, `history` | closed `:seon.db/db` and cannot fail | **Fixed/disjoint.** No envelope. |
| `cas-assert` | four-element vector | **Type-disjoint.** No envelope. Add/retain its concrete schema when this owner is touched. |
| `transact!` | closed `::transaction-report` | **Fixed/disjoint.** Keep the existing bare report-or-error union after closing `::error`; callers use `db/error?`. Do not add a second `ok?` around writes. |
| `query` | arbitrary Datahike scalar, tuple, collection, relation, or map | **Collision-capable.** Return the explicit outer result union. This includes nil, booleans, numbers, and exact error-shaped maps. |
| `query-with-evidence` | fixed outer evidence map with arbitrary result nested at `:datahike.query/result` | **Fixed/disjoint outer map.** Register a closed facade response and leave it bare; callers use `db/error?` only on the outer value. Do not wrap the nested result again. |
| `pull` | nil or arbitrary pulled map/value | **Collision-capable.** Return the explicit outer result union. |
| `pull-many` | ordered vector of arbitrary pulled values | **Fixed/disjoint outer vector.** Leave bare. Exact error-shaped entities remain ordinary elements and cannot collide with the outer map error. |
| `entity` | alias of `pull '[*]` | **Collision-capable.** It returns exactly the `pull` outer union; do not unwrap/re-wrap it. |
| `installed-schema` | map keyed by numeric ids and installed attributes, with schema maps as values | **Fixed/disjoint once described honestly.** Strengthen `::protocol/schema` / facade `::installed-schema` from generic `:map` to its concrete map-of shape and leave it bare. The value at the `:seon.error/message` key is a schema map, never the error schema's required string. Use `db/error?`; the prior live failure is the acceptance example. |
| `execute-many` | fixed `{::db/db database ::db/results wire-results}` | **Fixed/disjoint outer map.** Register this exact closed facade response and leave it bare. Each member response already carries wire `::protocol/success?`; do not strip or re-envelope members. |
| `index-page` | fixed map of datoms, completeness, optional cursor | **Fixed/disjoint.** Register/use its closed facade schema and leave bare. |
| `knn-search!` | vector of hit maps | **Type-disjoint from database error map.** Leave bare; its registered vector result and `db/error?` are sufficient. |
| `resolve-transaction-branch-head!` | closed branch-head | **Fixed/disjoint.** Leave bare and use `db/error?`. |
| `listen!` | fixed listener handle/ack contract | **Fixed/disjoint.** Leave bare. Wire events retain their own closed event discriminator. |
| `unlisten!` | boolean | **Type-disjoint.** Leave bare. |
| `cancel!` | fixed cancel response | **Fixed/disjoint.** Leave bare. |
| `release` / session lifecycle functions | fixed booleans, nil, or lifecycle maps according to their registered schemas | **Fixed/disjoint.** Leave bare; normalize failure through `::error` where applicable. |
| `with-read-evidence` | closed `{::value any ::read-evidence ...}` | **Fixed/disjoint outer map.** Leave bare. Its arbitrary computation value is nested and must not be classified by content. |
| pure schema/codec helpers (`malli->datahike-schema`, `tx-meta-datahike-schema`, `decode-edn-value`, `decode-edn-values`) | deterministic local values | **Not database failure unions.** No change. |

Only `query`, `pull`, and `entity` require the generic outer union. `pull-many`
and installed schema are deliberately not wrapped: their outer success shapes
are provably disjoint once registered precisely. This is the acceptance guard
against turning the ruling into a blanket facade rewrite.

## Domain responses at the boundary

An explicit database result is consumed and translated at the first domain
owner. It must not escape into higher layers for repeated unwrapping.

- `my.data/rows`, `my.kb/recall`, and `my.ns/functions` already own explicit
  `:seon.result/ok?` domain responses. They unwrap each database result once,
  preserve an exact error-shaped query/pull value as success data, and converge
  their failure payload to `:seon.error/message` in the same Stage 5 batch.
- `my.canvas/state` (`src/my/canvas.cljs:172-197`) directly exposes arbitrary
  pulled attributes as a map and therefore needs a closed explicit domain
  response. Its success payload must be nested outside the discriminator;
  returning the attribute map merged beside `ok?` would still permit key
  collision. `pinned` is a fixed `{::content?}` projection and may remain bare
  after its callers use `db/error?` / explicit database result handling.
- `seon.agent.message/message!` has a fixed id+hops success schema, and recent
  reads return vectors. These are provably disjoint from `::error`; they do not
  gain `ok?` merely for stylistic uniformity. Their internal database reads do
  migrate to explicit unwrapping.
- Fixed domain projections throughout `my.plan`, agent lifecycle, derived
  context, routes, and rendering keep their existing registered response
  shapes. They replace message sniffing with either `db/error?` on a fixed
  database result or one explicit unwrap at an arbitrary-read call site. They
  do not acquire generic result envelopes at their public surface unless they
  themselves return arbitrary database data.

This distinction is the reconciled ruling: explicit union by collision risk,
not by namespace and not by a blanket mandate to wrap every operation.

## Atomic owner groups and dependency order

The facade cut changes hundreds of direct call sites. A partially migrated
checkout is not buildable, so the implementation is a coordinated source
freeze with one owner at a time, followed by one integrated commit. The work
can be prepared as review groups, but no intermediate commit may expose the
new `query`/pull return contract to old callers.

1. **Contract owner — `seon.result`, `seon.db`, database tests.** Close and
   normalize `::error`; add `db/error?`; add the one outer result union; switch
   the three collision-capable facade functions; register missing fixed facade
   response schemas. Add exact-collision, nil, scalar, pull, pull-many, entity,
   and installed-schema regressions in `test/seon/db_remote_contract_test.cljs`.
2. **Core runtime consumers.** Migrate `src/seon/agent/**`, `eval.cljs`,
   `execution*.cljs`, `client.cljs`, `runtime/**`, `derive.cljs`, `state.cljs`,
   `warn.cljs`, `ai/**`, and `repl/**`. At each call, unwrap once immediately;
   pass the original payload onward. Preserve the existing immutable database
   value threading. This group includes the debug regression: a failed entity
   lookup must not call pull.
3. **Web/render consumers.** Migrate `src/seon/web/**` and `src/seon/render/**`
   without changing route, reactive, or render authority. This group must wait
   for the active Stage 4 route/host owner to release overlapping paths.
4. **Toolkit/domain consumers.** Migrate `src/my/**`, then close the capability
   failure-payload drift in the same owner-local schemas. `my.canvas/state` gets
   its explicit arbitrary-value domain union; fixed projections remain fixed.
5. **Tests and agent-facing documentation.** Update mocks to return the new
   facade union, retain fixed-operation mocks unchanged, update `my.kb` and
   toolkit examples, then run the callable-index/schema corpus checks. Do not
   bulk-rewrite historical stored EDN fixtures whose subject is prior eval
   serialization rather than a current facade call.

The top-level orchestrator must resolve current U4 ownership before opening
groups 1-3. This report deliberately edits none of the active database, host,
turn, AI, web, shared roadmap, or issue paths.

## Mechanical migration rule

At every changed call site:

1. await the database operation once;
2. for an explicit result, branch only on `:seon.result/ok?` and extract
   `:seon.result/value` once;
3. for a fixed result, branch only with `db/error?`;
4. never test `contains?`, truthiness, or string-ness of
   `:seon.error/message` outside `db/error?`;
5. never run `db/error?` against extracted arbitrary success data; and
6. preserve the complete database error map when translating to an existing
   domain error response instead of converting it to prose and discarding kind
   or data.

After migration, the source falsifier is:

```bash
rg -n "\(string\? \(:seon\.error/message|\(:seon\.error/message" \
  src/seon src/my --glob '*.cljs' --glob '*.cljc'
```

Remaining matches must be constructors, display of an already-classified
error, or non-database error domains; none may discriminate a database result.

## Acceptance and proof matrix

| Proof | Required observation |
|---|---|
| Closed schema | `schema/valid-candidate-value? :seon.db/error` accepts the exact three-key/optional-data contract and rejects missing kind, non-string message, and every extra key. |
| Exact query collision | A query whose successful scalar is exactly a valid `:seon.db/error` map returns `ok? true`; a transport/database failure returns `ok? false`. The two values remain byte-distinct only at the outer discriminator. |
| Native query shapes | Relation, scalar, collection, tuple, map, nil, boolean, and number all appear unchanged under `:seon.result/value`; the source-argument-order test remains green. |
| Pull family | `pull` and `entity` use the explicit outer contract; missing entity is successful nil, not failure. `pull-many` retains its bare outer vector, whose elements may include exact error-shaped entities as success data. |
| Installed schema | A schema map containing the installed `:seon.error/message` attribute remains a bare fixed-shape success. A real schema-read failure remains the closed error value. The home-requires regression passes through `db/error?` without local string sniffing. |
| Fixed results | Database value, transaction report, query evidence, execute-many, index page, KNN hits, branch head, listener/cancel/release results retain their existing external shapes. Focused tests assert no accidental blanket envelope. |
| Debug short-circuit | A failed query in `seon.agent.debug/turn` produces its existing domain failure and performs zero pull calls. |
| Domain collision | `my.canvas/state` and each explicit collection/domain response can carry an exact error-shaped map as success data. |
| Static sweep | No database-result discriminator based on message/key presence remains; duplicate local `error-value?` helpers disappear wherever their only input is a database result. |
| Integrated gates | Focused database/home/debug/canvas/toolkit selectors pass, then full `bin/test-cljs`, `bin/test-writer`, and `bin/seon test operator` pass on one frozen source digest. |
| Live gate | On a ready frozen default cluster, run one agent query returning the exact collision map, one missing-entity pull, and one installed-schema/home setup; the agent survives, values are correctly tagged, and no core fault datom is recorded. |

## Exit measure

The issue closes only when the exact-collision tests, full caller migration,
three frozen suites, and live agent proof all land with commit hashes in the
source-cleanup ledger. A closed error schema alone, a grep with no matches, or
an output-size-only smoke test is not sufficient proof.
