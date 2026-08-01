---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, sci, architecture]
---

# Attribute evals to the agent's assigned namespace

## Problem

The database assigns each agent one namespace, but the evaluation path still
derives `my.agents.<id>` from the agent ID. An agent assigned to an existing
namespace such as `seon.flow` therefore evaluates in a different namespace and
cannot exercise namespace-owner workflows under its durable assignment.

This does not block the context MVP, whose temporary agents are deliberately
assigned `my.agents.<id>`. It blocks the broader owner-agent model in which a
real agent may own any namespace in the program graph.

## Evidence

- `src/seon/sci/eval.clj:192-199` defines `agent-namespace` as an unconditional
  `my.agents.<id>` derivation.
- `src/seon/cluster/loop.cljc:796-799` uses that derivation as the namespace for
  parsing and freezing the model's reply.
- `src/seon/cluster/loop.cljc:999-1003` uses the same derivation as the final
  evaluation-namespace fallback.
- `src/seon/cluster/agent.clj:93-110` records the independently assigned
  `:seon.cluster.agent/namespace` ref and already provides the inverse
  namespace-to-owner query. For an agent assigned `seon.flow`, this database
  fact and the ID-derived eval namespace disagree.
- `src/seon/cluster/run.cljc:399-405` demonstrates the existing database query
  that resolves a run's agent to its assigned namespace before freezing forms;
  the loop does not consistently use that fact.

## Owner

The SCI evaluation-context owner must make one gated design decision covering
namespace attribution, callable core-function binding, and current-database
injection. These are one agent-eval mechanism, not independent seams to open
ad hoc. The destination is the **SCI eval-context owner design gate**; no
binding, injection, or fallback change should land before that ruling records
the guarantees and the capability boundary.

## Acceptance

- A fresh agent assigned `my.agents.<id>` still parses, freezes, and evaluates
  its forms in that namespace.
- An owner agent assigned `seon.flow` parses, freezes, and evaluates ordinary
  forms in `seon.flow`; durable form namespace refs and runtime evaluation
  agree with the database assignment.
- Explicit namespace movement within a reply remains attributed by the reader
  rather than being overwritten by the agent assignment.
- The owner ruling settles namespace attribution together with callable
  core-function binding and current-database injection, without a second
  binding path or an eval-specific database side channel.
- Recurring run-loop and direct-evaluation proofs cover both default-namespace and
  assigned-namespace agents.
