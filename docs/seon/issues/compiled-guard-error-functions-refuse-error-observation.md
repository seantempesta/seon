---
type: issue
status: open
severity: friction
tags: [issue, schema, instrumentation]
---

# A compiled guard's error function prevents recording its refusal

Verified 2026-09-23 in the armed canonical fixture. Both the loaded and
projected `seon.db/q` contracts carry the declared query guard. Calling it
with mismatched query inputs reaches error observation, but returns this
deepest exception data instead of the promised contract error:

```clojure
{:seon.schema/error :seon.schema/noncanonical-definition
 :seon.schema/noncanonical-definition :seon.schema/callable-property
 :seon.schema/property :error/fn
 :seon.schema/value #object[seon.db$query_guard_message ...]
 :seon.error/kind :core-bug}
```

`seon.instrument/bind-contract-predicates` resolves the declared error
function for Malli. `boundary-refusal` fingerprints the compiled guard
through `seon.fn.schema-shape/normalized-form`. Its call to
`seon.schema/canonical-definition` rejects every callable property except
`:gen/gen`, including the compiled named `:error/fn`. The original declaration
still names `seon.db/query-guard-message`; this is not a missing guard or
an unnamed function. Recover that declared identity at the schema owner;
do not erase the property or weaken the contract-error assertion.

Evidence: `tmp/error-query-assertions-fast.log`; command
`bin/test-fast --paths test/seon/run6_db_test.clj -- seon.run6-db-test`.
The read-arity regression passes after distinguishing `arity-error` from
`contract-error`. The query regression has 13 failed schema assertions and
24 secondary nil-message errors. Total: 2 tests / 60 assertions / 13 failures
/ 24 errors, 162.88 s. Result recording was separately refused by the stale
blob contract described in
[the fixture issue](canonical-fixture-retains-old-function-contracts-after-adoption.md).

The schema owner is outside this bounded error assignment. Acceptance:
the existing query regression receives a complete contract error with its
guard message, and named error functions retain their canonical identities.
