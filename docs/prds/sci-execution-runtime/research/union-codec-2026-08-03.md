---
type: research
status: complete
tags: [research, database, schema, datahike]
---

# Union codec read normalization — 2026-08-03

## Read and dependency ledger

I read `AGENTS.md`, `plan/overnight-2026-08-03.md`, and the then-open
`docs/seon/issues/mixed-union-datahike-declaration-lacks-fresh-edn-codec.md`
(now under `docs/seon/issues/archive/`) whole before designing or editing. I
also read the complete
`data-oriented-clojure`, `datahike`, and `clojure-testing` skill files before
the work. The overnight plan makes this a core `seon.db` seam, requires a
fail-first qualified-symbol round trip, and forbids a render-specific decoder
(`plan/overnight-2026-08-03.md:153-170`). Its standing conditions require an
isolated operator root, path-limited commits, no push, and exact evidence
(`plan/overnight-2026-08-03.md:75-82`).

The dependency ledger for this slice is:

| Dependency or owner | Selected revision or source | Boundary used |
|---|---|---|
| Datahike | `0e8601d7f2f68c01070e13a95483bc82be04cabc` | `q-with-evidence` returns the raw query result and dependency evidence (`reference-code/datahike/src/datahike/query.cljc:98-151`); pull and pull-many return raw result maps plus parsed dependency plans (`reference-code/datahike/src/datahike/pull_api.cljc:411-454`); `datoms` exposes the stored datom values unchanged (`reference-code/datahike/src/datahike/core.cljc:157-164`). |
| Datalog parser | the revision vendored with the pinned Datahike checkout | `parse-pull` returns the parsed attribute map, including aliases and subpatterns (`reference-code/datalog-parser/src/datalog/parser/pull.cljc:200-234`). |
| Seon schema bridge | `src/seon/schema/datahike.clj` | Mixed top-level unions derive `:db.type/string` (`src/seon/schema/datahike.clj:135-150`), the transaction encoder writes canonical EDN (`src/seon/schema/datahike.clj:322-430`), and the existing decoder reads, canonicality-checks, and validates it (`src/seon/schema/datahike.clj:432-468`). |
| Seon database reads | `src/seon/db.clj` | The general application boundary is `q`, `pull`, `pull-many`, `entity`, and `datoms`; all now normalize before returning (`src/seon/db.clj:177-378,445-487,563-612`). |

Datahike's query engine applies predicates to its relation tuples before it
returns the result (`reference-code/datahike/src/datahike/query.cljc:1164-1188`).
That fact bounds what a result decoder can repair; it is recorded under
Remaining open rather than silently generalized.

## Encode and decode sites

The write and read chain is now one paired boundary:

1. `form->datahike-value-type-in` assigns heterogeneous unions the string
   fallback (`src/seon/schema/datahike.clj:135-150`).
2. `edn-encoded-attr-in?` derives whether the stored form uses that fallback;
   it now unwraps the same `:and` storage wrapper used by declaration
   derivation (`src/seon/schema/datahike.clj:268-298`).
3. `encode-transaction` validates the logical arm and writes canonical EDN at
   the Datahike transaction seam (`src/seon/schema/datahike.clj:322-430`).
4. `decode-attribute-value` reads one stored EDN string, rejects a non-string,
   malformed or non-canonical EDN, and validates the resulting logical value
   (`src/seon/schema/datahike.clj:432-449`).
5. `seon.db` derives result positions from the parsed Datalog query, pull
   selector, or returned datom attribute and applies that same decoder before
   returning application data (`src/seon/db.clj:177-378,445-487,585-612`).

There is no render-specific codec path.

## Blast radius

I loaded all 667 forms from `resources/seon/schema.edn`, independently derived
the fallback from each form's stored child, and separately asked the repaired
recognizer. The 2026-08-03 source probe returned:

```clojure
{:canonical-fallback-attributes []
 :recognizer-misses
 [:seon.render/surfaces
  :seon.render.walk/branch
  :seon.render.walk/path
  :seon.store/transaction-data]}
```

Therefore the complete current production attribute blast radius is **no
canonical database attributes**. No existing stored attribute changes logical
semantics when decoding starts, so the owner-decision stop condition did not
fire. The render premise in the original issue is stale: both
`:seon.render/ai` and `:seon.render/html` currently alias the symbol-only
`:seon.render/projection` schema and store natively as `:db.type/symbol`
(`resources/seon/schema.edn:360-368,2305-2321,2363-2369`). Literal prose and
Hiccup are explicitly runtime-only (`resources/seon/schema.edn:2323-2338`).

The bridge nevertheless supports runtime/future database attributes, so the
regression installs two synthetic attributes that exercise the affected class:

| Exercised attribute | Logical arms | Storage | Production read proof |
|---|---|---|---|
| `:seon.db-test/ai-declaration` | string or qualified symbol, under an `:and` wrapper | canonical EDN string | scalar, collection, constant-attribute and input-bound-attribute query projections; pull; entity; datoms (`test/seon/db_test.clj:9-96`) |
| `:seon.db-test/html-declaration` | vector literal or qualified symbol | canonical EDN string | pull plus a mixed-population query that distinguishes both arms (`test/seon/db_test.clj:12,39-96`) |

For completeness, these are **all registered schema forms** whose stored child
derives the fallback, including non-attribute value and function-contract
schemas:

```clojure
[:my.message/value
 :my.run/value
 :seon.ai/backup
 :seon.ai/completion
 :seon.ai/primary
 :seon.ai/request
 :seon.ai/request-body
 :seon.ai/target
 :seon.cluster.message/inbound
 :seon.cluster.registry/from
 :seon.db/entity-id
 :seon.db/lookup-ref-value
 :seon.db/query
 :seon.db/ref
 :seon.db/time-point
 :seon.flow/callback-result
 :seon.flow/submission-id
 :seon.flow/work-call
 :seon.flow/work-result
 :seon.render/literal
 :seon.render/surface
 :seon.render/surfaces
 :seon.render.walk/branch
 :seon.render.walk/path
 :seon.schema/projection-rows
 :seon.store/transaction
 :seon.store/transaction-data
 :seon.store/transaction-operation]
```

None is a canonical database attribute. The four `recognizer-misses` are
collection schemas whose **child** is a heterogeneous union. If one becomes a
database attribute, cardinality-many storage needs an explicit element-wise
codec decision; treating the entire collection as one encoded scalar would
change Datahike cardinality semantics. This slice does not invent that
decision, and no current stored value is affected.

## Falsifier and regression evidence

The first production-read regression stored `example.render/ai` as a qualified
symbol through `db/transact!`, queried it through `db/q`, and expected a
symbol. Before the fix, the focused run failed exactly as required:

```text
expected: example.render/ai
  actual: "example.render/ai"
Ran 11 tests containing 50 assertions.
1 failures, 0 errors.
```

The completed regression then proves the qualified-symbol arm, both literal
arms, same-spelling string-versus-symbol distinction, both mixed populations,
constant and input-bound query attributes, pull, entity, and datom projection
through production reads (`test/seon/db_test.clj:39-96`). A second regression
inserts malformed and schema-invalid storage directly through Datahike and
proves that `seon.db` returns a flat `:seon.db/invalid-read` with the decoder's
specific rule (`test/seon/db_test.clj:98-116`).

Final source gates, each using `bin/test`'s isolated operator root:

```text
bin/test seon.db-test
Ran 12 tests containing 60 assertions.
0 failures, 0 errors.

bin/test seon.schema.datahike-test seon.db-test seon.cluster.store-transact-test
Ran 24 tests containing 97 assertions.
0 failures, 0 errors.
```

Both successful roots were removed by the runner.

## Live proof

An isolated root at `tmp/union-codec-live-2026-08-03` published current source
at commit ID `6a6ff6b9-061e-5c1d-b955-342e90db1216`, started cluster
`union-codec` in PID 97717, and installed one runtime heterogeneous-union
attribute. Raw Datahike returned the two storage strings:

```clojure
["example.render/ai" "\"example.render/ai\""]
```

The first live `seon.db/q` probe used an attribute supplied through `:in` and
exposed a missing query-binding case. After adding the general parsed-input
mapping and its recurring regression, I hot-reloaded `seon.db` in that same
isolated JVM and the real read path returned:

```clojure
{:logical [example.render/ai "example.render/ai"]
 :types [clojure.lang.Symbol java.lang.String]
 :distinguishable? true}
```

This live observation exercised a hot-reloaded Var in the fresh isolated
cluster; it did not refork the cluster from a newly published commit after the
last source edit. The source-level focused and affected gates above exercised
the final files. `bin/seon --root tmp/union-codec-live-2026-08-03 down` stopped
PID 97717 and released the root. The default operator root and default cluster
were never touched.

## Remaining open

- Datahike query predicates and functions still receive storage strings before
  Seon's result normalization, because evaluation precedes result return
  (`reference-code/datahike/src/datahike/query.cljc:1164-1225`). No canonical
  database attribute uses the fallback today, so this changes no current
  behavior; a future fallback-backed attribute that needs logical-value query
  predicates requires an owner decision at the query/storage boundary.
- The four collection schemas in the independent census expose the
  cardinality-many decision described above. They are not database attributes
  today.
- The duplicate ambient and explicit-projection transaction codecs remain the
  separately filed deletion issue
  `docs/seon/issues/schema-datahike-keeps-a-readerless-second-codec.md:8-36`.

The application-read failure class in this lane is closed: every current
`seon.db` read surface with enough attribute information now returns the
logical value or one flat read error, and no render-specific exception exists.
