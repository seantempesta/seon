---
type: issue
status: open
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
- The proof queries receipts and durable errors; it does not recover or rearm a
  fenced bootstrap session.
