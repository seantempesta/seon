---
type: issue
status: superseded
severity: blocker
tags: [issue, schema, testing, runtime]
---

# Make Step-1 relational properties executable

## Problem

The Step-1 effect contract claims two generative relational properties, but
both exist only as comments above stub-era example assertions. The suite can be
green without invoking one effect twice, generating a run identity or op-id,
or observing a committed fact.

A property in prose cannot constrain an implementation lane. Malli's two-child
`:=>` schemas validate input and output shapes but do not infer either
multi-invocation relation.

## Evidence

- `test/my/effect_contract_test.clj:51-61` marks message identity pending,
  describes generation in a comment, and asserts only that the former stub
  returns `:seon.effect/not-implemented`.
- `test/my/effect_contract_test.clj:63-71` does the same for transaction replay.
- The implementation landed in commit `2c5a416d8`, but neither test was
  activated during the subsequent source-freeze handoff.
- The test namespace has no `clojure.test.check` dependency, generator, repeated
  invocation, database fixture, or committed-fact query.
- Malli validates output and an optional authored guard at
  `reference-code/malli/src/malli/generator.cljc:526-556`; the Step-1 function
  schemas have no guards and cannot express a two-call database transition.

The ruled distinction and proposed activation diff are recorded in
[[../../prds/sci-execution-runtime/research/spec-authorship-relational-properties-2026-07-26]].

## Owner

`test/my/effect_contract_test.clj` owns the sealed Step-1 acceptance surface.
Its fixture must reuse the recurring writer/run-loop mechanism rather than
create a second replay implementation.

## Acceptance

- Pending metadata and stub assertions are removed.
- Generated `(run, ordinal, epoch)` scenarios execute the same sending form
  twice and query exactly one committed message identity/delivery.
- Generated op-id/transaction scenarios transact twice, query exactly one
  committed write, compare the two returned bases, and require
  `:seon.capability/replayed? true` on the second result.
- Failures print the seed and shrunk counterexample.
- The recurring writer suite claims both tests.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
