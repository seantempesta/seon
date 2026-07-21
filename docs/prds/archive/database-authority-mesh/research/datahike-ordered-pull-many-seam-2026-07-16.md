---
type: research
status: complete
tags: [research, database, flow]
---

# Datahike ordered `pull-many` seam — 2026-07-16

## Result

Strengthen Datahike's existing `pull-many`; do not implement it as repeated
Seon `pull` calls and do not add another protocol operation.

The maintained dependency is already organized around the right performance
seam. `pull-many` parses one selector, creates one resource budget, drives one
pull frame machine across the complete input, and certifies one eager result.
The defect is narrower: strict entity resolution throws for one absent lookup
ref, while an absent numeric entity is silently omitted from the result. The
output therefore does not correspond position-for-position with the input.

The smallest complete correction is dependency-native:

- resolve pull entity refs with a pull-specific non-throwing resolver that
  preserves errors for malformed refs and non-unique lookup attributes;
- make only the root `pull-many` frame append `nil` when an input does not
  resolve or produces no pull map;
- skip selection work for a resolved `nil`;
- retain the current single parse, frame machine, budget, and result
  certification; and
- make the public Datahike specification say that `pull-many` accepts a
  sequence of entity refs and returns an input-aligned vector of maps or nils.

This also gives `pull` the same missing-ref behavior through the shared
`pull-spec` owner. That matches Seon's current public contract and lets the Bun
cut delete Seon's local `resolve-existing-eid` wrapper rather than reproduce it
on the JVM authority.

No Datahike database value, parsed pull record, lazy sequence, Datom, entity,
or host object crosses the wire. The result remains a vector containing only
maps and nils.

## Dependency ledger

| Owner | Exact source | Relevant fact |
|---|---|---|
| Datahike | `reference-code/datahike` at `1296cfc4cb8c9b4868dde8bb6c3f4d4dc523d043` | Maintained fork with bounded pull resources, ordered missing-value pull, and the protocol capability catalog. |
| Pull engine | `src/datahike/pull_api.cljc:18-29, 290-359` | One root frame owns ordered entity traversal; `pull-many` parses once and calls `pull-spec` once. |
| Entity resolution | `src/datahike/db/utils.cljc:109-148` | `entid` returns nil only for a well-formed missing ref and still throws syntax/unique-schema errors; `entid-strict` converts nil to `:entity-id/missing`. |
| Native index access | `src/datahike/db/interface.cljc:87-103`; `src/datahike/db.cljc:246-254` | Pull uses exact EAVT/AVET prefix slices through the database wrapper's search context. There is no multi-key index cursor primitive. |
| Resource bounds | `src/datahike/resource.cljc:13-93, 157-168` | One dynamic budget bounds work, result nodes, and retained weight; final certification is eager and non-serializing. |
| Public API | `src/datahike/api/types.cljc:58-67, 102-110, 200-224`; `src/datahike/api/specification.cljc:521-551` | `SEId` includes numeric IDs, lookup refs, and idents. The current `pull-many` positional schema incorrectly names one `SEId`, and its return excludes nil. |
| CLJ/CLJS generation | `src/datahike/api.cljc:1-88`; `src/datahike/js.cljs:68-97` | Pure functions remain synchronous on both hosts; the JS conversion already preserves vector positions and converts nil to JavaScript null. |
| Datahike proof | `test/datahike/test/pull_api_test.cljc:119-128`; `test/datahike/test/attribute_refs/pull_api_test.cljc:52-62`; `test/datahike/test/api_test.cljc:171-182` | Existing cross-platform tests cover only all-present inputs. |
| Current Seon semantics | `src/seon/db.cljs` at `5fbd753c`, lines 1184-1196 and 1335-1375; `test/seon/db_test.cljs:1040-1051` | Seon already returns nil for a missing lookup ref and probes numeric IDs for existence before pull. |
| Authority interpreter | `src/seon/db/writer.clj:641-678`; `test/seon/db/writer_integration_test.clj:270-330, 398-455` | The JVM already calls one `d/pull-many` over a pinned immutable value and returns its eager result directly; execute-many uses the same member. |
| Wire contract | `src/seon/db/protocol.cljc` at `e9a9a793`, lines 114-170, 497-511, 558-566, 789-796 | Requests require an ordered vector; vectors, maps, and nil are ordinary wire data; no new response shape is needed. |

## Executable falsifiers

All probes ran against unmodified Datahike `a5315858` through the existing
`:test` alias. They did not start or alter Seon lifecycle state.

The current implementation loses a numeric missing position and fails the
whole call on a well-formed missing lookup ref:

```clojure
(d/pull-many test-db '[:name] [1 999 5])
;; => [{:name "Petr"} {:name "Elizabeth"}]

(d/pull-many unique-db '[:person/name]
             [[:person/id "a"] [:person/id "missing"]])
;; throws with
;; {:error :entity-id/missing,
;;  :entity-id [:person/id "missing"]}

```

A lookup ref using a non-unique attribute fails differently and must continue
to fail rather than become a nil result:

```clojure
(d/pull-many unique-db '[:person/name] [[:person/name "A"]])
;; throws with
;; {:error :lookup-ref/unique,
;;  :entity-id [:person/name "A"]}

```

The existing fast seam is real. Redefining only the parser counter around an
ordered duplicate input produced one parse and retained input order:

```clojure
{:result [{:name "Elizabeth"}
          {:name "Petr"}
          {:name "Elizabeth"}],
 :parse-calls 1}

```

One adjacent inconsistency is relevant to numeric refs. A nonexistent numeric
ID with wildcard currently fabricates an ID-only map, whereas explicit
`:db/id` silently drops the position:

```clojure
(d/pull test-db '[*] 999)                    ;=> {:db/id 999}
(d/pull test-db '[:db/id] 999)               ;=> nil
(d/pull-many test-db '[:db/id] [1 999 5])   ;=> [{:db/id 1} {:db/id 5}]

```

The pull-specific resolver must therefore preserve Seon's existing bounded
numeric existence check instead of treating every positive integer as a live
entity. That closes wildcard/default-selector false positives as well as
position loss.

## Why the present engine is the right owner

`pull-many` already avoids N independent pull invocations:

1. `dpp/parse-pull` runs once in `pull-many` (`pull_api.cljc:350-359`).
2. `pull-spec` resolves the input vector and creates one budget
   (`pull_api.cljc:328-337`).
3. `pull-pattern-frame` consumes entity IDs in order in the same root frame
   (`pull_api.cljc:290-308`).
4. `pull-pattern` returns one persistent vector
   (`pull_api.cljc:310-326`).
5. One `certify-result!` checks the final retained shape.

It deliberately does not reduce all work to one broad physical index scan.
Explicit forward attributes use exact EAVT prefixes `[eid attr]`; reverse
attributes use exact AVET prefixes `[attr eid]`; wildcard uses one EAVT entity
prefix and groups that entity's datoms (`pull_api.cljc:128-215, 264-282`). For
arbitrary sparse IDs, a single range from the lowest to highest ID would walk
unrelated entities and lose the storage working-set advantage. The database
interface has no multi-key cursor that could avoid both that scan and the
necessary point lookups.

Therefore the acceptance phrase “not N pulls” should mean one parse, setup,
budget, traversal owner, and final result—not one physical seek regardless of
input cardinality. Each raw numeric ID needs one bounded EAVT existence probe
to preserve the existing Seon contract. A lookup ref already performs one AVET
resolution that proves presence. Selection then uses exact point access.

This is the smallest truthful performance cut. A new multi-key index primitive
would have to be implemented for raw, filtered, historical, as-of, and since
database wrappers and benchmarked across sparse and dense IDs before it could
replace these point reads. It is not justified by the current falsifier.

## Exact Datahike contract

Keep the public name and both existing arities:

```clojure
(pull-many db {:selector selector
               :eids entity-refs
               :max-work optional-positive-int
               :max-results optional-positive-int
               :max-result-weight optional-positive-int})

(pull-many db selector entity-refs)

```

The semantic schema is:

```clojure
[:=>
 [:cat :datahike/SDB
  [:map {:closed true}
   [:selector [:vector :any]]
   [:eids [:sequential :datahike/SEId]]
   [:max-work {:optional true} pos-int?]
   [:max-results {:optional true} pos-int?]
   [:max-result-weight {:optional true} pos-int?]]]
 [:vector [:maybe :map]]]

[:=>
 [:cat :datahike/SDB
  [:vector :any]
  [:sequential :datahike/SEId]]
 [:vector [:maybe :map]]]

```

Define and register a `SPullManyOptions` shape rather than leaving
`SPullOptions` with optional `:eid` and `:eids`. Make `SPullOptions` require
`:eid`; make `SPullManyOptions` require `:eids`. Add the corresponding Java and
TypeScript type mappings if the named schema participates in generated
bindings. The native and Python pull-many generators already accept an encoded
ID collection and need no semantic adapter.

Successful output has exactly `(count entity-refs)` positions:

- a present entity produces its eager pull map;
- a well-formed missing numeric ID, lookup ref, or ident produces nil;
- duplicate refs produce duplicate positions in the same order;
- two different lookup refs that resolve to the same entity produce the same
  value in their respective positions; and
- an empty input produces `[]`.

Malformed lookup-ref syntax, a lookup attribute without `:db/unique`, an
invalid entity-ref type, selector parse failure, and resource-budget exhaustion
remain operation failures. They do not masquerade as a missing entity.

## Smallest implementation change

Keep `pull`, `pull-many`, `pull-spec`, and the frame machine as the one
mechanism.

1. Add a private pull resolver beside `pull-spec`. It calls `dbu/entid`, which
   already distinguishes missing from invalid. For a raw numeric ref only,
   retain the current Seon check `(first (dbi/datoms db :eavt [eid]))`; lookup
   refs and idents already proved existence during AVET resolution.
2. Use that resolver for the root input vector instead of `entid-strict`.
   This makes missing inputs nil while preserving the structured throws from
   `dbu/entid`.
3. Mark only the root frame created for `pull-many` as `:pull-many? true`.
   Reuse the existing API name rather than introducing a second public term.
4. In `pull-pattern-frame`, when the next root input is nil, append one nil and
   reset directly to the next input without attribute or wildcard work.
5. When a non-nil root input completes with no key/value pairs, append nil
   rather than conditionally dropping it. Nested cardinality-many and recursion
   frames retain their current omission rules because they are not root
   `pull-many` frames.
6. Charge the retained nil position consistently under the existing result
   budget and keep final shallow-weight certification over the complete vector.
7. Update the Malli specification and docstring; do not add an option, another
   operation, or a Seon result-zip pass.

This changes all-present calls only in schema precision, not runtime values or
index access. Missing input behavior becomes deterministic and matches Seon.

## Seon boundary after the dependency change

Datahike owns entity-ref resolution, selector execution, input order, missing
positions, and resource bounds. Seon retains only policy Datahike cannot know:
whether a selector attribute is registered in Seon's schema but not yet
installed on this exact database value.

The JVM authority should prepare the selector once against the pinned database:

- installed attributes pass through;
- registered-but-uninstalled attributes are removed once from the selector;
- a genuinely unknown attribute returns the existing user-input/protocol error;
  and
- the resulting selector and ordered refs go to one `d/pull-many` call.

Lookup-ref syntax and uniqueness failures come from Datahike and should be
classified as invalid request data by Seon. A missing well-formed ref is a nil
success position. Resource exhaustion remains a bounded database-operation
failure. The authority should not catch all three and flatten them into the
same “not found” result.

The current protocol requires no version change: `::entity-ids` is already an
ordered vector, `::result` already admits ordinary polymorphic data, and the
recursive wire predicate admits nil. The writer's `materialize-result` returns
the persistent vector without copying it. Transit performs the one required
host-to-Bun encoding.

After consumer migration, delete Seon's local `resolve-existing-eid` and do
not recreate it in Bun. `entity` remains one `pull '[*]`, and multiple entities
use `pull-many` or one coordinate-pinned `execute-many` member.

## Options and tradeoffs

### Selected: correct the existing default

Make ordinary `pull`/`pull-many` return nil for a well-formed missing ref and
make `pull-many` input-aligned. All-present behavior is unchanged, the fork
matches Seon's established contract, and no compatibility option survives the
cut.

Cost: raw numeric refs pay one exact EAVT existence probe. That is already paid
by Seon's current local facade. Lookup refs pay no added existence probe beyond
their AVET resolution.

### Rejected: N Seon pulls plus `mapv`

This reparses the selector N times, creates N budgets and frame machines,
repeats JVM dispatch, and either creates N authority jobs or hides sequential
work inside one job. It adds application code while discarding Datahike's
existing batched owner.

### Rejected: a `:missing :nil` compatibility option

It preserves two behaviors indefinitely, adds a new term to every binding and
protocol adapter, and leaves the ordinary default unsafe for remote callers.
The fork and Seon own the contract; missing data is not exceptional.

### Deferred: deduplicate repeated resolved IDs inside one call

It could avoid repeated pull work for inputs such as `[a a a]`, but it changes
resource accounting because duplicate output positions still consume retained
and encoded bytes. It also needs a result map and a second ordered expansion.
Measure duplicate-heavy real requests before adding that complexity. The first
cut preserves exact order by direct traversal.

### Deferred: a multi-key Datahike index primitive

It may help dense batches, but every database wrapper and storage backend must
preserve the same semantics, and sparse IDs can make a broad scan worse than
point access. Require a benchmark showing lower work, allocations, and storage
reads before extending `IIndexAccess`.

## Proof and acceptance

### Datahike CLJ and CLJS

- `[present, missing lookup ref, present]` returns `[map nil map]`.
- `[present, missing numeric, present, duplicate present]` retains four exact
  positions for explicit, wildcard, and selector-default patterns.
- Missing ident returns nil; malformed lookup ref, non-unique attribute,
  invalid ref type, and invalid selector retain their exact structured errors.
- Attribute-ref mode has the same order and nil semantics.
- Empty and all-missing inputs return `[]` and the correct all-nil vector.
- One parser-counter fixture proves exactly one selector parse for 1, 32, and
  1,000 refs.
- One frame/budget fixture proves one operation budget, bounded work, correct
  nil-position accounting, and recovery after exhaustion.
- Recursive, reverse, wildcard, cardinality-many, duplicate, and mixed-present
  existing pull-many tests remain green.
- The API specification validates the map and positional forms, rejects one
  scalar where an ID collection is required, and validates nil result slots.
- The maintained Node gate proves JS `pull_many` returns JavaScript null in the
  missing positions without Promise or host-value leakage.

### Seon integration

- A direct pull-many request and an execute-many pull-many member return the
  same exact ordered vector at one coordinate.
- Transit round-trip preserves nil positions and duplicate maps; the response
  satisfies `ordinary-wire-value?` and `valid-response?`.
- Registered-but-uninstalled selector attributes are removed once per request;
  unknown attributes and invalid lookup-ref schema return a legible protocol
  failure rather than nil.
- A mixed 1,000-ref request creates one fair read job, one dependency call, one
  encode, and a bounded response; it does not create 1,000 executor jobs.
- Cancellation and session close release the one pinned database value and
  retain no result, request, or frame state.

### Performance falsifier

Compare one `pull-many` against `mapv pull` for 1, 32, 256, and 1,000 sparse
lookup refs with one, four, and wildcard selectors. Record selector parses,
EAVT/AVET calls, work-budget evidence, elapsed time, allocations, and retained
result weight. The selected cut must keep one parse/setup/certification and
must not increase index calls for lookup refs over the current native
`pull-many`. Numeric existence probes are expected and must remain exact-prefix
access, never a database scan.

### Implemented dependency proof

Datahike commit `1296cfc4cb8c9b4868dde8bb6c3f4d4dc523d043`
implements the selected seam on `codex/database-authority-mesh` and is pushed to
the maintained fork. It keeps one parser invocation, one root frame machine,
one resource budget, and one eager result certification. Raw numeric refs use
one exact EAVT presence probe; lookup refs and idents retain Datahike's native
AVET resolution. Only the root pull-many frame preserves nil positions.

The focused cross-index/specification gate passes 120 tests and 393 assertions.
It covers persistent-set, hitchhiker-tree, attribute-ref mode, exact option and
return schemas, missing numeric/lookup/ident positions, malformed and
non-unique failures, wildcard absence, duplicate/order behavior, parser counts
at 1/32/1,000 inputs, and shared budget exhaustion/recovery. The canonical Node
CLJS gate now includes pull and API specification suites and passes 135 tests
and 934 assertions, including JavaScript null positions with no Promise or host
value leakage.

The repository-wide `bb test` remains an unresolved pre-existing aggregate-gate
failure: after the focused pull suites pass, a later cleanup aborts with
`Cannot delete a database with active connections. Release them first.` The
failure is outside the pull seam; no pull test creates a connection, and both
focused CLJ/CLJS gates release cleanly. This evidence does not graduate the
later Seon writer/protocol integration proof.

## Graduation decision

This belongs in Datahike. The dependency already owns the only efficient pull
state machine and the semantic distinction between a missing entity and an
invalid lookup ref. Implementing alignment in Seon would either repeat pulls or
reconstruct dependency semantics from partial results.

Seon owns only exact-coordinate selection, registered-schema policy, fair job
admission, error classification, and wire delivery. Once the dependency proof
passes, the authority can use the stronger operation directly and the Bun
consumer can expose one asynchronous `seon.db/pull-many` without a replica,
adapter, or compatibility path.
