---
type: issue
status: open
severity: blocker
tags: [issue, sci, agent, context, architecture]
---

# Derive or explain every special SCI base binding

## Problem

The SCI base context manually installs special callable namespaces beyond the
three explicitly explained session injections (`help`, `dir`, and `doc`). The
agent's rendered situation neither names nor explains schema lifecycle,
`clojure.test`, background operations, or message operations. `my.run` is only
partially explained through its terminal disposition rule.

Universal callability is not the problem: program-graph installation already
derives callable first-party Vars later in acquisition. The defect is a second,
hand-maintained base-binding roster whose extra semantics and visibility cannot
be answered from the generated situation.

## Evidence

`src/seon/sci/eval.clj:209-241` manually installs:

- `seon.schema/register!` and `unregister!`;
- every host `clojure.test` public;
- `my.run/wait` and `complete`;
- `my.background/background`, `poll`, and `await`; and
- `my.message/send` and `decline`.

The live situation render at `src/seon/cluster/agent.clj:140-156` explicitly
names only `help`, `dir`, `doc`, `my.run/complete`, and `my.run/wait`. Although
the underlying situation value carries `:seon.cluster.agent/protocol-namespaces`
from `src/seon/bootstrap.clj:166-177`, the selected render does not display that
member and does not explain the copied operations.

Later acquisition already has the derived mechanism:
`src/seon/sci/eval.clj:901-968` installs core-provenanced namespaces from
program rows, and `:1355-1408` installs declared aliases, refers, functions,
tests, and classes in dependency order.

## Owner

`seon.sci.eval/build-base-ctx` owns the minimal interpreter bootstrap;
program-graph acquisition owns universal callable membership. The agent
situation's declared render owns any genuinely special session injection that
must remain.

## Acceptance

- The base context manually injects only operations whose interpreter semantics
  cannot be obtained through program-graph acquisition.
- Each surviving special callable is explicitly named and explained by the
  declared situation render, including when and why it differs from an ordinary
  program function.
- Schema lifecycle, test, run, background, and message callables are derived
  from program facts wherever their ordinary Vars suffice; no parallel member
  roster remains.
- A query/proof compares special base bindings with the situation's declared
  injection data and refuses an unexplained member.
