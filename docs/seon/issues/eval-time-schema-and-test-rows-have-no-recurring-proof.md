---
type: issue
status: open
severity: blocker
tags: [issue, sci, program-graph, testing]
---

# Eval-time schema and test rows have no recurring proof

## Problem

The living half of the program graph — an agent evaluating code and the
corresponding row appearing in the terminal transaction — is proven for
FUNCTIONS only. `seon.sci.eval/program-row` publishes three shapes
(`:seon.fn/sym`, `:seon.schema/key`, `:seon.test/sym`) and
`install-program-row!` branches on all three, but no recurring test drives an
agent evaluating a `seon.schema/register!` or a `deftest` through the turn path
and asserts the resulting row. A proof that exists only in a lane transcript
counts as not covered.

Six-generation git archaeology established that this is not merely a missing
test. `seon.fn/durable-row`, `seon.sci.eval/program-row`, and
`seon.cluster.run/program-row-tx` independently restate the declaration-row
contract. The resulting drift is live: runtime schemas persist unevaluated
syntax, tests are committed but never materialized or acquired, `ns-unmap`
deletes only function identities, and one database's schema projection replaces
process-global state used by every cluster. The evidence and historical
precedents are recorded in
`docs/prds/sci-execution-runtime/research/registration-archaeology-2026-07-29.md`.

## Evidence

`test/seon/cluster/turn_test.clj` covers the function shape thoroughly:
`mixed-plan-publishes-only-the-contracted-function` (contracted admitted,
scratch `def` refused), `contracted-redefinition-exactly-replaces-the-program-row`
(upsert), `ns-unmap-retracts-the-owned-function-after-the-terminal-commit`,
`a-refused-contract-commits-a-receipt-and-no-program-row`, and
`evaluation-follows-the-readers-parse-time-namespace`.

No test under `test/seon/cluster/` other than `boot_test.clj` mentions
`:seon.schema/key` or `:seon.test/sym`, and `boot_test.clj` exercises the boot
population rather than an evaluation. So `install-program-row!`'s
`:seon.schema/key` arm (which calls `activate-program-schemas!`) and its
`:seon.test/sym` arm (which deliberately installs nothing) are unproven from
the agent side.

A related, smaller divergence in the same class: the build indexer's
`seon.fn/durable-row` admits any `:seon.fn/spec` or `:seon.schema/form` string,
while the eval path's `seon.sci.eval/program-row` additionally requires
`schema/malli-form?` and throws `::contract-refused`/`::schema-refused`
otherwise. The eval path is the stricter one; the two admission gates for one
row shape should agree explicitly rather than by coincidence.

That divergence already blocks a second silent drop, verified in the REPL.
`seon.sci.reader/resolved-operation` resolves an operator only through the
reading namespace's ALIASES, so an unqualified `register!` inside `seon.schema`
itself is not recognized and `(register! ::compiled-validator 'fn?)` at
`src/seon/schema.cljc:1956` produces no `:seon.schema` row. Extending the
resolution to refers and the current namespace is a three-line change and was
implemented and reverted, because the recognized row's static form is
`(quote fn?)` — the reader stores the unevaluated form while `register!`
receives the evaluated symbol. `seon.sci.eval/activate-program-schemas!` then
refuses the whole projection and `seon.gen.loop-test` errors on the next agent
acquisition. The two real dependencies at the surviving owners are: drop the
quote at `src/seon/schema.cljc:1956` (a protected file), and give the build
indexer the same `malli-form?` admission gate the eval path has, so a
non-Malli schema form is refused loudly at index time instead of poisoning
acquisition later.

## Why the test does not simply get written: the schema arm demolishes the registry

Writing the missing test was ATTEMPTED and reverted, and the attempt is the
finding. `eval-time-schema-and-test-declarations-become-rows` was added to
`test/seon/cluster/turn_test.clj` — an agent evaluating
`(seon.schema/register! :my.agents.agent-a/amount [:int {:min 0}])` and
`(clojure.test/deftest a-check)` through `with-cluster` and `drive!`. Baseline
for that namespace in isolation is 27 tests, 0 failures. With the new test:

```text
ERROR in (eval-time-schema-and-test-declarations-become-rows)
  :malli.core/invalid-schema {:schema :my.run/value}
ERROR in (a-refused-contract-commits-a-receipt-and-no-program-row)
  Bad entity attribute :seon.cluster.agent/id, not defined in current schema
ERROR in (concurrent-streams-share-one-conn-test)   ← same cascade
```

Four unrelated tests in the same JVM then fail. The mechanism is visible in the
source: `seon.sci.eval/activate-program-schemas!` (`src/seon/sci/eval.clj:411-439`)
rebuilds the WHOLE projection from the `:seon.schema` rows found in ONE database
and calls `schema/activate-projection!`, which is process-global. `with-cluster`
transacts the derived Datahike attributes but no canonical `:seon.schema` rows,
so the projection built from a database holding exactly one agent-authored row
REPLACES the process registry with that single schema. Everything else — including
`:seon.cluster.agent/id` and `:my.run/value` — disappears for the rest of the JVM.

Two distinct problems follow, both at the eval-path owner:

1. A database that lacks the canonical schema rows should make
   `activate-program-schemas!` REFUSE loudly, not silently narrow the registry
   to whatever it happened to find. Narrowing on absence is the same
   absence-read-as-health failure this class keeps producing.
2. `activate-projection!` is process-global while clusters are supposed to share
   no mutable state. One agent in one cluster registering a schema currently
   rewrites the registry every cluster in that JVM validates against. Whether
   the projection should be per-cluster is a design question, but the current
   coupling is undeclared.

## Owner

`seon.sci.eval` / `seon.cluster.run` — the eval-path publication owner. Both
files were protected during the indexer fix that produced this note.

## Acceptance

One shared pure owner defines canonical identities, owned attributes, row
construction, exact replacement, and typed deletion for functions, schemas,
and tests. Build and runtime call it after their explicit producer admission:
build indexes every function; runtime publishes only fully contracted
functions. No transaction owner restates that contract.

The recurring proof matrix then establishes:

- build/runtime canonical row parity over their shared admitted domain;
- evaluated, canonical schema values rather than reader syntax, with failed
  registration leaving neither a row nor staged state;
- exact function/schema/test redefinition and stale source reconciliation;
- `ns-unmap` removing both function and test identities;
- installation from the successful terminal transaction report's exact
  `db-after`, never from receipts or a pre-commit runtime mutation;
- current function, schema, and test materialization after cluster reopen; and
- two incompatible cluster projections alternating in one JVM without global
  registry bleed.
