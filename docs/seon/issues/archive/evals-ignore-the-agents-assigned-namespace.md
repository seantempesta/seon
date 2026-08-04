---
type: issue
status: resolved
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

## Triage 2026-08-02

**Still real; destination: SCI eval-context owner design gate.** Current HEAD
still derives the reply reader namespace with
`sci.eval/agent-namespace` at `src/seon/cluster/loop.cljc:1018`, and the
evaluation fallback still uses the same ID-derived function later in the
settlement path. `src/seon/sci/eval.clj:230-234` still constructs
`my.agents.<id>` without reading `:seon.cluster.agent/namespace`. The parsed
contract, live-context, and stateless-resume waves preserve this namespace
choice; they do not reconcile it with the agent's database ref.

## Resolution 2026-08-04

Commit `3a6264724` deleted eval-time namespace construction. The surviving
`seon.sci.eval/agent-namespace` reads the agent's
`:seon.cluster.agent/namespace` ref and resolves its `:seon.ns/name` from the
database value. The normal run loop passes no requested starting namespace to
`seon.cluster.run/plan-tx`, so `plan-call` writes
`:seon.cluster.run/starting-ns` from that same committed assignment. Explicit
starting namespaces remain available to system-authored and curation runs.

`my.agents.<id>` remains only in `seon.eval.drive/creation-request`, where it
is the default assigned to a temporary agent at creation. Bootstrap grading,
reply freezing, resumed-form fallback, and direct evaluation now all pass a
database value when resolving an existing agent's namespace.

## Verification 2026-08-04

The pre-fix falsifier on the isolated operator root
`tmp/eval-assigned-namespace-root` created agent `namespace-falsifier` with
assignment `my.tools.demo`; `seon.cluster.agent/owner-of` returned that agent,
while `seon.sci.eval/agent-namespace` returned the incorrect
`my.agents.namespace-falsifier`.

After publishing current source commit
`6a727b42-c967-580d-8225-e3204cea91e8`, the fresh scratch cluster
`assigned-ns-post` created `assigned-bootstrap-live` with assignment
`my.tools.demo`. Its system-authored run
`bootstrap:assigned-bootstrap-live` recorded starting namespace
`my.tools.demo`, closed normally, and committed 13 receipts. The set of
receipt evaluation namespaces was exactly `#{my.tools.demo}`; ordinal 0 also
recorded `my.tools.demo`.

The recurring regression `assigned-namespace-seeds-the-run-and-its-receipt`
creates a non-default assignment and proves the run starting namespace,
planned form namespace, admitted value, and settled receipt namespace. The
loop namespace passed 24 tests / 107 assertions. In the combined owning run,
all run and loop tests plus the new direct-eval regression passed; the SCI
namespace retained five unrelated dirty-tree assertions in the existing
`bare-dir-and-program-derived-doc-are-repl-native` and
`agent-contracts-apply-on-acquire-and-cold-recovery` tests.
