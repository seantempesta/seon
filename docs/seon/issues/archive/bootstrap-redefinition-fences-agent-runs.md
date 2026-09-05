---
type: issue
status: resolved
severity: blocker
tags: [issue, bootstrap, agent, sci, repl]
---

# Let the bootstrap redefine a function without fencing the agent run

## Problem

The shipped bootstrap defines `largest` twice. On a newly published isolated
cluster, both definitions evaluate and return the new Var face, but terminal
installation faults because the committed declaration source no longer
matches the source associated with the earlier install request. Every newly
created agent stops after receipt 9 of 13, before the bootstrap's call and
completion forms. Agent-authored runs cannot start behind that held bootstrap.

## Evidence

The dogfood pass published `current-src` commit ID
`6a726510-8594-5e31-b0fa-f07aefd81285`, booted cluster `repldogfood0804` in
isolated operator root `tmp/repl-dogfood-0804-root`, and created agents
`dogfood-a` and `dogfood-b` through `seon.cluster.agent/ensure-entity!`.

For both agents, bootstrap ordinal 7 evaluated the open-map `largest`
definition and ordinal 8 evaluated its closed-map redefinition. Both receipts
carry the expected new face:

```clojure
#:seon.print{:face :seon.print/var,
             :name "my.agents.dogfood-a/largest"}
```

The run then records this durable core fault and no receipt for ordinal 9:

```clojure
{:seon.error/kind :seon.sci.eval/install-source-mismatch
 :seon.error/message
 "Committed declaration source does not match install request."}
```

Each bootstrap run had 9 receipts for 13 ordered forms and no
`:seon.cluster.run/closed-at`. The same boundary reproduced independently for
both agents, so it is not agent-specific state.

### N-way independent-symbol reproduction

The concurrency-independence stress lane reproduced the same durable core
fault without redefining a symbol. Its focused recurring test booted isolated
cluster `concurrency-independence`, created five agents on one branch, and
gave each agent a distinct assigned namespace and a distinct contracted
function symbol. All five definitions evaluated and their runs settled six
receipts, but concurrent terminal installation emitted:

```text
SEON CORE FAULT (dev panic): Committed declaration source does not match
install request. [signature
0708a7b745ed9db91eb8ef9813a245b59f8b69ffbdd99c9657a421d5d0a51e93]
```

The reproducer is
`test/seon/concurrency_independence_test.clj`; the retained failed operator
root is `tmp/test-runs/run.tXsWG5`. The five symbols were
`my.agents.concurrency.s0-n5.a0/stress-f-s0-n5-0` through
`my.agents.concurrency.s0-n5.a4/stress-f-s0-n5-4`. This disproves the narrower
hypothesis that only same-symbol redefinition reaches the mismatch: distinct
agents installing distinct symbols concurrently can cross the same boundary.

## Owner

The single bootstrap definition/update sequence in `resources/seon/bootstrap.edn`
and the declaration installation boundary in `seon.sci.eval`.

## Acceptance

- A freshly published isolated cluster creates two agents whose bootstrap runs
  each settle all 13 ordered forms.
- The second definition of one symbol is installed as an update without an
  `install-source-mismatch` fault, and its exact committed source is the source
  subsequently resolved and called.
- A new agent-authored run can then define, redefine, call, test, wait, and
  complete through the real run loop.
- Five agents on one branch can concurrently install five distinct contracted
  definitions without an `install-source-mismatch` fault; each committed
  `:seon.fn/source` matches the source installed for that exact symbol.
- The proof queries receipts and durable errors; it does not recover or rearm a
  fenced bootstrap session.

## Closed 2026-08-04

The install fence was correct. The D1 declared-content comparison passed the
canonical row and database to `declared-map` in reverse order, falsely
classifying changed declarations as identical and suppressing the replacement
transaction. `seon.cluster.run/declared-content` now calls the comparison with
the correct argument order; the SCI install-source guard remains unchanged.

The recurring boot test now waits for `bootstrap:root` to close and
asserts all 13 receipts. The complete `seon.cluster.boot-test` gate passed 28
tests and 137 assertions; the declared-content unit gate passed 13 tests and
61 assertions, including identical/no-op and changed/replacement cases.
