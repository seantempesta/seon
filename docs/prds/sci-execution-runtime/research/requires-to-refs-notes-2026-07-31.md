---
type: research
status: active
tags: [research, program-graph, datahike, render]
---

# Requires-to-refs implementation notes — 2026-07-31

## State

Owner ruling 12 in `plan/README.md` supersedes the derived requires edge in
`agent-entity-graph-audit-2026-07-31.md` §5. `:seon.ns/requires` is now a
cardinality-many Datahike ref. Complete indexing mints one identity-upserted,
name-only `:seon.ns` row for each required namespace with no first-party source
row. `seon.render.walk` follows the resulting ordinary refs and retains only
the trigger-message and asked-for-run computed edges.

The implementation landed in path-limited commits:

- `762b2482c` — index rows, external namespace identities, ordered ref
  transactions, analyzer reconstruction, and `requires-resolve-totally`;
- `b4b3f0f5a` — schema conversion, requires-derived-edge deletion, d2 walk and
  name-only family-lens regression, plus the corrected Datahike ordering
  comment.

## Dependency ledger and shortest falsifier

Datahike is pinned at `9b3be9d59cb0`.
Identity upsert reads the evolving transaction database in
`reference-code/datahike/src/datahike/db/transaction.cljc:640-714`; transaction
maps are reduced in order at `:1233-1274`; ref values resolve through strict
entity-id resolution at `:785-794` and
`reference-code/datahike/src/datahike/db/utils.cljc:110-148`.

An isolated in-memory probe falsified forward lookup resolution. A relation
map before its target identity refused atomically with
`:entity-id/missing` and left zero rows. The same relation after all namespace
base rows committed. Two sources requiring the same external name produced
one external eid and both edges. The index transaction therefore orders:

1. every source and external namespace base row with `/requires` removed;
2. relation-only maps carrying `:seon.ns/name` plus `/requires` lookup refs.

This is one transaction, so every lookup ref resolves against an identity
already asserted in its current mid-transaction database value.

## Implementation

`resources/seon/schema/program.edn` declares
`:seon.ns/requires [:set :seon.db/ref]`. Static namespace rows encode each
target as `[:seon.ns/name target-symbol]`. Complete indexing derives the set of
required names, subtracts namespaces with first-party source rows, and adds one
sorted name-only row per remainder. `:seon.ns/name` is a unique identity, so
repeat requires upsert rather than duplicate.

`src/seon/render/walk.clj` no longer contains `required-namespace-edges` and
its derived-edge vector has two members. The generic forward-ref walk sees
`:seon.ns/requires` through the mechanically derived family ref attributes.

Aliases and refers were deliberately not converted. Their component rows
still carry symbolic `:seon.ns.alias/target-ns` and
`:seon.ns.refer/target-ns`, so the namespace reaches each binding row but the
binding row cannot traverse to its target namespace. That inconsistency is a
follow-up modeling unit. `:seon.ns.import/target-class` names a Java class, not
a namespace row, and is not the same inconsistency.

## Recurring and scratch proof

Focused recurring evidence on Java 26.0.1:

- `seon.fn-test`: 15 tests / 96 assertions / 0 failures / 0 errors;
- `seon.schema.program-test`: 2 tests / 29 assertions / 0 failures / 0 errors;
- the replaced requires walk regression directly: 1 test / 6 assertions /
  0 failures / 0 errors.

The full walk namespace was terminated by shared JVM pressure before emitting
its summary, so no complete walk-suite claim is made. The new regression itself
ran in a fresh test JVM and proves both the agent → namespace → required
namespace d2 path and honest family-lens output for a name-only external row.

A frozen `bin/seon init` published `current-src` commit
`6a6ce9f0-5a0d-5c01-9841-1ed44420a055` with digest
`3e8d0b87cf82e43ceb4f73f8899fa98a35c8f1c3bf439626adb8eb330fd719c5`.
A dedicated `requires-refs-proof` fork returned:

```clojure
{:require-datoms 1029
 :resolved-edges 1029
 :dangling-edges 0
 :distinct-targets 106
 :namespace-rows 185
 :source-rows 130
 :external-target-rows 54
 :external-target-names 54
 :physical-values-all-eids? true}
```

The extra non-source namespace beyond the 54 external targets is the scratch
cluster's created agent namespace. The live walk returned an ordinary
`{:seon.render.walk/attribute :seon.ns/requires, :target 4334}` connection and
the corresponding d2 node at distance zero. Reloading `seon.render.walk`
reported exactly two computed-edge functions. The proof cluster was stopped
through `bin/seon stop requires-refs-proof`.

## Protected consumer seams

The owner sequenced these paths to other lanes; this unit did not edit them.

- `src/seon/render/ns.clj:20-24` bare-pulls `/requires`, so Datahike returns
  `{:db/id ...}` maps. `require-specs` at `:100-122` unions those maps with
  symbolic alias targets and emits invalid require forms. The owning repair is
  a nested `{:seon.ns/requires [:seon.ns/name]}` pull followed by projection of
  `:seon.ns/name`. Its test helper has the same bare selector at
  `test/seon/render/ns_test.clj:18-34`.
- `src/seon/cluster/loop.cljc:999-1003` likewise bare-pulls the runtime lint
  namespace row. `src/seon/fn/analyzer.clj` can normalize persisted lookup-ref
  vectors, but an eid-only pull carries no name to reconstruct.
- `src/seon/sci/eval.clj:463-509` writes SCI's symbolic require set directly
  and reads persisted requires as if they were still symbols. Its namespace
  pulls at `:546-550` and `:647-650` also need nested target names. The terminal
  persistence owner at `src/seon/cluster/run.cljc:626-729` otherwise receives
  the now-invalid symbolic durable row.

These are landing-order seams, not alternate representations: symbolic require
state may remain inside the reader/SCI resolver, but the persistence boundary
must convert symbols to namespace lookup refs and database reads must project
target names explicitly.
