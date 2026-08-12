---
type: issue
status: resolved
severity: friction
tags: [issue, agent, runtime, testing]
---

# Make the generative loop fixture commit the run facts it asserts

## Problem

The generative loop fixture reaches its assertions with no frozen forms,
receipts, assignments, routed states, or planner completion message. The
settlement projection therefore reports true over an absent plan, producing a
large cascade that does not test the intended routing semantics.

## Evidence

The bare 2026-08-05 gate failed three vars:

- `seon.gen.loop-test/a-goal-is-a-message-and-the-attempt-routes-its-own-failures`
  expected seven form rows and seven receipt rows but received zero; later
  assignment/state queries were empty and one nil result caused an NPE;
- `seon.gen.loop-test/a-result-built-on-a-failed-form-is-red-and-routes`
  found neither routed ordinal and reported the absent plan settled;
- `seon.gen.loop-test/a-silent-owner-leaves-the-plan-unsettled-forever`
  found no completion message or routed state and reported settled.

The same three vars failed with the same zero-row/empty-state shape at
pre-rename commit `401fd300e`. This is distinct from the agent lifecycle
fixtures' explicit `starting-namespace-missing` log class: this output contains
no such refusal and does not identify why setup committed no durable facts.

## Owner

The `with-gen-cluster` setup in `test/seon/gen/loop_test.clj` and the run-loop
entry boundary it exercises.

## Acceptance

The fixture proves its run identity and frozen form/receipt census exist before
asserting routing. Setup failure yields one explicit refusal rather than
settled-by-absence cascades. All three vars exercise their intended durable
facts.

## Resolution

Commit `ee4f1cd3e` replaced the deleted schema-wall prose discriminator
(`Agent <id> ...`) with the namespace in W1's first retained REPL prompt,
using the fixture's one declared agent/namespace relation for both creation and
provider selection. Before the change each planner reply was misclassified as
root and froze one form plus one receipt; after it,
`clojure -M:test` ran `seon.gen.loop-test` as 3 tests / 92 assertions / 0
failures / 0 errors. The routing and settlement assertions are unchanged.
